package com.offgrid.android

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads ExecuTorch [model.pte] + [tokenizer.json] after install:
 * GET /v1/model/manifest → stream to .tmp → SHA-256 → rename into app external files dir
 * (same paths [ExecutorchModelManager] resolves).
 *
 * KV key `model:manifest` on Worker; 404 = skip download (bundled assets / adb only).
 */
class ModelFilesRepository(
    private val context: Context,
    private val baseUrl: String
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val destDir: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir
    @Volatile
    private var ensureInProgress: Boolean = false

    /** Same layout as adb push / ExecuTorchModelManager external resolution. */
    fun modelDest(): File = File(destDir, MODEL_NAME)
    fun tokenizerDest(): File = File(destDir, TOKENIZER_NAME)

    sealed class EnsureResult {
        /** Manifest missing — caller may still load from APK assets. */
        data object NoManifest : EnsureResult()

        /** Files present or downloaded and verified. */
        data object Ready : EnsureResult()

        data class Failed(val message: String) : EnsureResult()
    }

    /**
     * Blocking (call from [Dispatchers.IO]). [onProgress] receives phase label and byte counts
     * (total may be -1 if unknown).
     */
    fun ensureModelArtifacts(
        onProgress: (phase: String, bytesReceived: Long, bytesTotal: Long) -> Unit
    ): EnsureResult {
        synchronized(this) {
            if (ensureInProgress) {
                return EnsureResult.Failed("Model download already in progress")
            }
            ensureInProgress = true
        }
        try {
        val manifestUrl = "${baseUrl.trimEnd('/')}/v1/model/manifest"
        val manifestBody = try {
            getJsonIfPresent(manifestUrl) ?: return EnsureResult.NoManifest
        } catch (e: Exception) {
            return EnsureResult.Failed("Model manifest request failed: ${e.message}")
        }

        return try {
            val root = JSONObject(manifestBody)
            val version = root.getString("version")
            val model = root.getJSONObject("model")
            val tokenizer = root.getJSONObject("tokenizer")
            val modelUrl = absolutize(model.getString("downloadUrl"))
            val modelSha = model.getString("checksumSha256").lowercase()
            val tokUrl = absolutize(tokenizer.getString("downloadUrl"))
            val tokSha = tokenizer.getString("checksumSha256").lowercase()

            val storedVer = prefs.getString(KEY_MANIFEST_VERSION, null)
            val mf = modelDest()
            val tf = tokenizerDest()
            val filesPresent = mf.exists() && tf.exists() && mf.length() > 0L && tf.length() > 0L
            if (storedVer == version && filesPresent) {
                onProgress("Using cached model ($version)", 1L, 1L)
                return EnsureResult.Ready
            }
            // Cost guard: if files already exist and match current manifest checksums,
            // reuse them even when prefs/version marker is missing or stale.
            if (filesPresent &&
                sha256Hex(mf).equals(modelSha, ignoreCase = true) &&
                sha256Hex(tf).equals(tokSha, ignoreCase = true)
            ) {
                prefs.edit().putString(KEY_MANIFEST_VERSION, version).apply()
                onProgress("Using verified cached model ($version)", 1L, 1L)
                return EnsureResult.Ready
            }

            val tmpModel = File(destDir, "$MODEL_NAME.tmp")
            val tmpTok = File(destDir, "$TOKENIZER_NAME.tmp")
            tmpModel.delete()
            tmpTok.delete()

            onProgress("Downloading model…", 0L, -1L)
            downloadVerifyRename(
                url = modelUrl,
                temp = tmpModel,
                dest = mf,
                expectedSha = modelSha,
                onProgress = { r, t -> onProgress("Downloading model…", r, t) }
            )

            onProgress("Downloading tokenizer…", 0L, -1L)
            downloadVerifyRename(
                url = tokUrl,
                temp = tmpTok,
                dest = tf,
                expectedSha = tokSha,
                onProgress = { r, t -> onProgress("Downloading tokenizer…", r, t) }
            )

            prefs.edit().putString(KEY_MANIFEST_VERSION, version).apply()
            EnsureResult.Ready
        } catch (e: Exception) {
            EnsureResult.Failed(e.message ?: e::class.simpleName ?: "model download failed")
        }
        } finally {
            synchronized(this) {
                ensureInProgress = false
            }
        }
    }

    private fun getJsonIfPresent(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} $text")
            }
            if (text.isBlank()) throw IllegalStateException("Empty manifest body")
            return text
        }
    }

    private fun downloadVerifyRename(
        url: String,
        temp: File,
        dest: File,
        expectedSha: String,
        onProgress: (Long, Long) -> Unit
    ) {
        // Resume support: keep one fixed .tmp file and request remaining bytes.
        val already = if (temp.exists()) temp.length() else 0L
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
        if (already > 0L) {
            reqBuilder.header("Range", "bytes=$already-")
        }
        val req = reqBuilder.get().build()

        try {
            http.newCall(req).execute().use { resp ->
                if (!(resp.isSuccessful || resp.code == 206)) {
                    val err = resp.body?.string().orEmpty()
                    throw IllegalStateException("GET failed HTTP ${resp.code} url=$url $err")
                }
                val body = resp.body ?: throw IllegalStateException("No body for $url")

                val append = already > 0L && resp.code == 206
                if (already > 0L && !append) {
                    // Server did not honor Range; restart from zero.
                    temp.delete()
                }
                val startAt = if (append) already else 0L
                val totalFromServer = body.contentLength().takeIf { it >= 0 } ?: -1L
                val total = if (append && totalFromServer > 0L) startAt + totalFromServer else totalFromServer

                body.byteStream().use { input ->
                    FileOutputStream(temp, append).use { output ->
                        val buf = ByteArray(8192)
                        var received = startAt
                        onProgress(received, total)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            received += n
                            onProgress(received, total)
                        }
                    }
                }
            }

            val actual = sha256Hex(temp)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                temp.delete()
                throw IllegalStateException(
                    "SHA-256 mismatch for $url (got $actual expected $expectedSha)"
                )
            }
            if (dest.exists()) dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
        } catch (t: Throwable) {
            // Keep temp for future resume on transient network failures.
            throw t
        }
    }

    private fun absolutize(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return "${baseUrl.trimEnd('/')}/${url.trimStart('/')}"
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private companion object {
        const val PREFS = "offgrid_model"
        const val KEY_MANIFEST_VERSION = "manifest_version"
        const val MODEL_NAME = "model.pte"
        const val TOKENIZER_NAME = "tokenizer.json"
        const val USER_AGENT = "Offgrid/1.0 (Android; model-download)"
    }
}
