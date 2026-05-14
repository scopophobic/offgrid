package com.offgrid.android

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Catalog entry as served by the Worker's `GET /v1/models` endpoint.
 *
 * Pure data; mirrors the backend `ModelCatalogEntrySchema` plus the resolved
 * download URLs. Used by the Settings UI and first-run model picker.
 */
data class CatalogModelEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val version: String,
    val sizeBytes: Long,
    val tags: List<String>,
    val recommendedRamMb: Int?,
    val isDefault: Boolean,
    val modelUrl: String,
    val modelSha256: String,
    val tokenizerUrl: String,
    val tokenizerSha256: String
)

/**
 * Fetches the LLM catalog from the Worker.
 *
 * Falls back to an empty list when the endpoint or KV catalog is missing —
 * caller should then surface the legacy single-model flow.
 */
class ModelCatalogRepository(
    private val baseUrl: String
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun listModels(): List<CatalogModelEntry> {
        val url = "${baseUrl.trimEnd('/')}/v1/models"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("GET /v1/models failed HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()
            val root = JSONObject(body)
            val arr = root.optJSONArray("models") ?: JSONArray()
            return (0 until arr.length()).mapNotNull { i ->
                runCatching { parseEntry(arr.getJSONObject(i)) }.getOrNull()
            }
        }
    }

    private fun parseEntry(o: JSONObject): CatalogModelEntry {
        val model = o.getJSONObject("model")
        val tokenizer = o.getJSONObject("tokenizer")
        val tagsArr = o.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until tagsArr.length()).map { tagsArr.optString(it, "") }
            .filter { it.isNotBlank() }
        return CatalogModelEntry(
            id = o.getString("id"),
            displayName = o.optString("displayName", o.getString("id")),
            description = o.optString("description", ""),
            version = o.optString("version", ""),
            sizeBytes = o.optLong("sizeBytes", 0L),
            tags = tags,
            recommendedRamMb = if (o.has("recommendedRamMb")) o.optInt("recommendedRamMb") else null,
            isDefault = o.optBoolean("isDefault", false),
            modelUrl = model.getString("downloadUrl"),
            modelSha256 = model.getString("checksumSha256").lowercase(),
            tokenizerUrl = tokenizer.getString("downloadUrl"),
            tokenizerSha256 = tokenizer.getString("checksumSha256").lowercase()
        )
    }

    private companion object {
        const val USER_AGENT = "Offgrid/1.0 (Android; model-catalog)"
    }
}
