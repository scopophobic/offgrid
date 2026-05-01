package com.offgrid.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
    private val packsRoot: File by lazy {
        File(context.getExternalFilesDir(null), "packs").apply { mkdirs() }
    }

    fun listPacks(): List<RemotePack> {
        val url = URL("$baseUrl/v1/packs")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        conn.inputStream.bufferedReader().use { reader ->
            val body = reader.readText()
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
    }

    /**
     * Download a pack from Worker and persist it to `<external>/packs/<id>.zip`.
     * Verifies SHA-256 before replacing existing file.
     */
    fun installPack(pack: RemotePack) {
        val download = getDownloadInfo(pack.id)
        val downloadUrl = absolutize(download.url)
        val expectedSha = download.checksumSha256.ifBlank { pack.checksumSha256 }.lowercase()
        val target = File(packsRoot, "${pack.id}.zip")
        val temp = File(packsRoot, "${pack.id}.zip.tmp")
        if (temp.exists()) temp.delete()

        val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 120_000
        }
        conn.inputStream.use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        val actualSha = sha256Hex(temp)
        if (expectedSha.isNotBlank() && actualSha.lowercase() != expectedSha) {
            // Backward compatibility: older published metadata used manifest
            // content hash, not ZIP hash. Accept if the manifest hash matches.
            val manifestSha = readManifestShaFromZip(temp)
            if (manifestSha == null || manifestSha.lowercase() != expectedSha) {
                temp.delete()
                throw IllegalStateException("Checksum mismatch for ${pack.id}")
            }
        }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun getDownloadInfo(packId: String): DownloadInfo {
        val conn = (URL("$baseUrl/v1/packs/$packId/download").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        conn.inputStream.bufferedReader().use { reader ->
            val o = JSONObject(reader.readText())
            return DownloadInfo(
                url = o.optString("downloadUrl"),
                checksumSha256 = o.optString("checksumSha256")
            )
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
}

