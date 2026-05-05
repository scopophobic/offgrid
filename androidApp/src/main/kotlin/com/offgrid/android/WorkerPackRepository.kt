package com.offgrid.android

import android.content.Context
import com.offgrid.shared.knowledge.androidPacksRoot
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class RemotePack(
    val id: String,
    val title: String,
    val description: String,
    val version: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val tags: List<String>
)

class WorkerPackRepository(
    private val context: Context,
    private val baseUrl: String
) {
    private val packsRoot: File by lazy { androidPacksRoot(context) }

    /** Short reads (catalog + download JSON). */
    private val jsonClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Large binary ZIP from R2. */
    private val zipClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun listPacks(): List<RemotePack> {
        val url = "$baseUrl/v1/packs"
        val body = getJsonOrThrow(jsonClient, url, "GET /v1/packs")
        val root = JSONObject(body)
        val arr = root.optJSONArray("packs") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            RemotePack(
                id = o.optString("id"),
                title = o.optString("title"),
                description = o.optString("description"),
                version = o.optString("version"),
                sizeBytes = o.optLong("sizeBytes"),
                checksumSha256 = o.optString("checksumSha256"),
                tags = parseTags(o.optJSONArray("tags"))
            )
        }
    }

    /**
     * Download a pack from Worker and persist it to `<packs>/<id>.zip`.
     * Verifies SHA-256 before replacing existing file.
     */
    fun installPack(pack: RemotePack) {
        val download = getDownloadInfo(pack.id)
        val downloadUrl = absolutize(download.url)
        if (downloadUrl.isBlank()) {
            throw IllegalStateException("Empty downloadUrl from server for ${pack.id}")
        }
        val expectedSha = download.checksumSha256.ifBlank { pack.checksumSha256 }.lowercase()
        val target = File(packsRoot, "${pack.id}.zip")
        val temp = File(packsRoot, "${pack.id}.zip.tmp")
        if (temp.exists()) temp.delete()

        val req = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .get()
            .build()

        zipClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw IllegalStateException(
                    "GET pack zip failed: HTTP ${resp.code} ${resp.message} url=$downloadUrl err=$err".trim()
                )
            }
            val stream = resp.body?.byteStream()
                ?: throw IllegalStateException("No response body for zip: $downloadUrl")
            stream.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
        }

        if (!looksLikeZip(temp)) {
            val hint = sniffFailureHint(temp)
            temp.delete()
            throw IllegalStateException(
                "Download is not a ZIP at $downloadUrl ($hint). Worker may be OK; check URL and device network."
            )
        }
        val actualSha = sha256Hex(temp)
        if (expectedSha.isNotBlank() && actualSha.lowercase() != expectedSha) {
            val manifestSha = readManifestShaFromZip(temp)
            if (manifestSha == null || manifestSha.lowercase() != expectedSha) {
                temp.delete()
                throw IllegalStateException(
                    "Checksum mismatch for ${pack.id}: file=$actualSha expectedKV=$expectedSha"
                )
            }
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun getDownloadInfo(packId: String): DownloadInfo {
        val url = "$baseUrl/v1/packs/$packId/download"
        val body = getJsonOrThrow(jsonClient, url, "GET /v1/packs/$packId/download")
        val o = JSONObject(body)
        return DownloadInfo(
            url = o.optString("downloadUrl"),
            checksumSha256 = o.optString("checksumSha256")
        )
    }

    private fun getJsonOrThrow(client: OkHttpClient, url: String, label: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("$label failed: HTTP ${resp.code} ${resp.message} $text".trim())
            }
            if (text.isBlank()) {
                throw IllegalStateException("$label: empty body")
            }
            return text
        }
    }

    private fun looksLikeZip(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        file.inputStream().use { input ->
            val sig = ByteArray(4)
            val n = input.read(sig)
            if (n < 4) return false
            return sig[0] == 'P'.code.toByte() && sig[1] == 'K'.code.toByte()
        }
    }

    private fun sniffFailureHint(file: File): String {
        if (!file.exists() || file.length() == 0L) return "empty file"
        val nBytes = minOf(256L, file.length()).toInt()
        val buf = ByteArray(nBytes)
        val read = file.inputStream().use { it.read(buf) }
        val prefix = if (read <= 0) {
            ""
        } else {
            buf.decodeToString(0, read, throwOnInvalidSequence = false)
        }
        val trimmed = prefix.trimStart()
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> "body looks like JSON/HTML, not binary zip"
            trimmed.startsWith("<") -> "body looks like HTML/XML"
            else -> "len=${file.length()} head=${prefix.take(80).replace('\n', ' ')}"
        }
    }

    private fun absolutize(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return "${baseUrl.trimEnd('/')}/${url.trimStart('/')}"
    }

    private fun parseTags(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, null) }
    }

    private fun readManifestShaFromZip(zipFile: File): String? {
        ZipInputStream(zipFile.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (!entry.isDirectory && entry.name == "manifest.json") {
                    val text = zin.readBytes().decodeToString()
                    val obj = JSONObject(text)
                    return if (obj.has("sha256")) obj.getString("sha256") else null
                }
            }
        }
        return null
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

    private data class DownloadInfo(
        val url: String,
        val checksumSha256: String
    )

    private companion object {
        private const val USER_AGENT = "Offgrid/1.0 (Android; okhttp)"
    }
}
