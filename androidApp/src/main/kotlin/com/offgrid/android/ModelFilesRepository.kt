package com.offgrid.android

import android.content.Context
import android.os.StatFs
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Storage layout for downloaded LLMs:
 *
 *   <externalFilesDir>/models/<modelId>/model.pte
 *   <externalFilesDir>/models/<modelId>/tokenizer.json
 *   <externalFilesDir>/models/<modelId>/.version          (manifest version)
 *
 * Active model id stored in SharedPreferences. The active model's files are
 * what [ExecutorchModelManager] loads via overrides (see [activeModelPaths]).
 *
 * Legacy single-model installs that wrote to <externalFilesDir>/model.pte
 * are migrated to the per-id directory once when [migrateLegacyToActive]
 * detects them — saves users a 1.2 GB redownload.
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

    private val rootDir: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    private val modelsRoot: File
        get() = File(rootDir, "models").apply { mkdirs() }

    @Volatile
    private var ensureInProgress: Boolean = false

    fun activeModelId(): String? = prefs.getString(KEY_ACTIVE_MODEL_ID, null)

    fun setActiveModelId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_MODEL_ID, id).apply()
    }

    fun modelDir(modelId: String): File =
        File(modelsRoot, modelId).apply { mkdirs() }

    fun modelFile(modelId: String): File = File(modelDir(modelId), MODEL_NAME)
    fun tokenizerFile(modelId: String): File = File(modelDir(modelId), TOKENIZER_NAME)
    private fun versionFile(modelId: String): File = File(modelDir(modelId), VERSION_NAME)

    /** Files for the currently active model, or null if none selected. */
    fun activeModelPaths(): Pair<File, File>? {
        val id = activeModelId() ?: return null
        val mf = modelFile(id)
        val tf = tokenizerFile(id)
        return if (mf.exists() && tf.exists()) mf to tf else null
    }

    /**
     * Returns true if [modelId]'s files exist on disk and look complete (non-empty).
     * Does NOT verify SHA-256 (cheap check; the cost guard in [ensureModelArtifacts]
     * does the full hash check before any redownload).
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val mf = modelFile(modelId)
        val tf = tokenizerFile(modelId)
        return mf.exists() && tf.exists() && mf.length() > 0L && tf.length() > 0L
    }

    /** Total bytes for [modelId]'s files (model + tokenizer + meta). */
    fun onDeviceBytes(modelId: String): Long {
        val dir = modelDir(modelId)
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /** Free space on the volume that holds the app's external files dir. */
    fun freeStorageBytes(): Long {
        val path = rootDir.absolutePath
        return try {
            val stat = StatFs(path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Removes a downloaded model. Skips the active one to avoid "deleted while
     * generating" surprises — caller should switch active first.
     */
    fun deleteModel(modelId: String): Boolean {
        if (modelId == activeModelId()) return false
        val dir = modelDir(modelId)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    /**
     * One-time migration from the legacy single-model layout.
     *
     * If `<externalFilesDir>/model.pte` and `tokenizer.json` exist and the
     * given [defaultModelId] doesn't yet have a per-id copy, move the legacy
     * files into `models/<defaultModelId>/`. Saves users a re-download after
     * upgrading the app.
     */
    fun migrateLegacyToActive(defaultModelId: String) {
        val legacyModel = File(rootDir, MODEL_NAME)
        val legacyTok = File(rootDir, TOKENIZER_NAME)
        val targetModel = modelFile(defaultModelId)
        val targetTok = tokenizerFile(defaultModelId)
        if (!legacyModel.exists() || !legacyTok.exists()) return
        if (targetModel.exists() && targetTok.exists()) return

        modelDir(defaultModelId).mkdirs()
        runCatching {
            if (!targetModel.exists()) {
                if (!legacyModel.renameTo(targetModel)) {
                    legacyModel.copyTo(targetModel, overwrite = true)
                    legacyModel.delete()
                }
            }
            if (!targetTok.exists()) {
                if (!legacyTok.renameTo(targetTok)) {
                    legacyTok.copyTo(targetTok, overwrite = true)
                    legacyTok.delete()
                }
            }
            Log.i(TAG, "migrated legacy model files to models/$defaultModelId/")
        }.onFailure { t -> Log.w(TAG, "legacy migration failed: ${t.message}") }
    }

    sealed class EnsureResult {
        /** No active model selected — caller must show the model picker. */
        data object NeedsSelection : EnsureResult()

        /** Files present or downloaded and verified. */
        data object Ready : EnsureResult()

        data class Failed(val message: String) : EnsureResult()
    }

    /**
     * Make sure the active model's files exist on disk (download if missing
     * or stale). Resume-aware via [downloadVerifyRename].
     *
     * The catalog entry is supplied by the caller (we don't fetch the manifest
     * here so callers can prompt for selection first).
     */
    fun ensureActiveModel(
        entry: CatalogModelEntry,
        onProgress: (phase: String, bytesReceived: Long, bytesTotal: Long) -> Unit
    ): EnsureResult {
        synchronized(this) {
            if (ensureInProgress) {
                return EnsureResult.Failed("Model download already in progress")
            }
            ensureInProgress = true
        }
        return try {
            ensureLocked(entry, onProgress)
        } catch (t: Throwable) {
            EnsureResult.Failed(t.message ?: "model download failed")
        } finally {
            synchronized(this) { ensureInProgress = false }
        }
    }

    private fun ensureLocked(
        entry: CatalogModelEntry,
        onProgress: (phase: String, bytesReceived: Long, bytesTotal: Long) -> Unit
    ): EnsureResult {
        val mf = modelFile(entry.id)
        val tf = tokenizerFile(entry.id)
        val vf = versionFile(entry.id)
        val filesPresent = mf.exists() && tf.exists() && mf.length() > 0L && tf.length() > 0L

        if (filesPresent && vf.exists() && vf.readText().trim() == entry.version) {
            onProgress("Using cached ${entry.displayName} (${entry.version})", 1L, 1L)
            setActiveModelId(entry.id)
            return EnsureResult.Ready
        }
        // Cost guard: even if version marker missing, reuse files when checksums match.
        if (filesPresent &&
            sha256Hex(mf).equals(entry.modelSha256, ignoreCase = true) &&
            sha256Hex(tf).equals(entry.tokenizerSha256, ignoreCase = true)
        ) {
            vf.writeText(entry.version)
            setActiveModelId(entry.id)
            onProgress("Verified cached ${entry.displayName}", 1L, 1L)
            return EnsureResult.Ready
        }

        val tmpModel = File(modelDir(entry.id), "$MODEL_NAME.tmp")
        val tmpTok = File(modelDir(entry.id), "$TOKENIZER_NAME.tmp")

        onProgress("Downloading ${entry.displayName} model…", 0L, -1L)
        downloadVerifyRename(
            url = absolutize(entry.modelUrl),
            temp = tmpModel,
            dest = mf,
            expectedSha = entry.modelSha256,
            onProgress = { r, t -> onProgress("Downloading model…", r, t) }
        )

        onProgress("Downloading tokenizer…", 0L, -1L)
        downloadVerifyRename(
            url = absolutize(entry.tokenizerUrl),
            temp = tmpTok,
            dest = tf,
            expectedSha = entry.tokenizerSha256,
            onProgress = { r, t -> onProgress("Downloading tokenizer…", r, t) }
        )

        vf.writeText(entry.version)
        setActiveModelId(entry.id)
        return EnsureResult.Ready
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

        http.newCall(req).execute().use { resp ->
            if (!(resp.isSuccessful || resp.code == 206)) {
                val err = resp.body?.string().orEmpty()
                throw IllegalStateException("GET failed HTTP ${resp.code} url=$url $err")
            }
            val body = resp.body ?: throw IllegalStateException("No body for $url")

            val append = already > 0L && resp.code == 206
            if (already > 0L && !append) {
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
        const val KEY_ACTIVE_MODEL_ID = "active_model_id"
        const val MODEL_NAME = "model.pte"
        const val TOKENIZER_NAME = "tokenizer.json"
        const val VERSION_NAME = ".version"
        const val USER_AGENT = "Offgrid/1.0 (Android; model-download)"
        const val TAG = "ModelFilesRepo"
    }
}
