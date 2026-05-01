package com.offgrid.shared.knowledge

/**
 * Metadata for a Knowledge Pack installed on (or available to) the device.
 *
 * Mirrors the manifest.json shipped inside the pack ZIP produced by the
 * Knowledge Factory (backend/factory/).
 */
data class KnowledgePack(
    val id: String,
    val title: String,
    val description: String,
    val version: String,
    val generatedAt: String,
    val numChunks: Int,
    val numSources: Int,
    val sizeBytes: Long,
    val sha256: String,
    val tags: List<String>,
    val embedModel: String,
    val embedDim: Int
)

/**
 * A retrieval hit ready for prompt injection.
 *
 * sourceLabel is a human-readable attribution like "Wikipedia: Italian Game",
 * suitable for an inline [Source: ...] tag.
 */
data class RetrievedChunk(
    val text: String,
    val sourceLabel: String,
    val sectionPath: String,
    val packId: String
)
