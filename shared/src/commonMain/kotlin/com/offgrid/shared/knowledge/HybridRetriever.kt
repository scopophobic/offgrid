package com.offgrid.shared.knowledge

/**
 * Builds the chat prompt by combining a user query with retrieved context from
 * the installed [KnowledgePackStore]. Replaces the legacy
 * [com.offgrid.shared.rag.SimpleRagPipeline] in-memory retriever.
 *
 * Strategy: lexical BM25 hits across all installed packs (delegated to the
 * store), then format chunks with an inline `[Source: ...]` attribution so the
 * model has explicit grounding signal.
 *
 * When no chunks match (empty store, no hits, etc.) we just send the bare
 * query — this avoids the model parroting "no local knowledge found" type
 * leakage that hurt early UX.
 */
class HybridRetriever(
    private val store: KnowledgePackStore,
    private val topK: Int = 4,
    private val maxContextChars: Int = 2800,
    private val maxChunkChars: Int = 800
) {

    suspend fun buildPrompt(userQuery: String): String {
        val trimmed = userQuery.trim()
        if (trimmed.isEmpty()) return trimmed
        val chunks = store.search(trimmed, topK)
        if (chunks.isEmpty()) return trimmed
        val contextParts = mutableListOf<String>()
        var used = 0
        for (c in chunks) {
            val location = if (c.sectionPath.isNotBlank() && c.sectionPath != "Introduction") {
                "${c.sourceLabel} > ${c.sectionPath}"
            } else {
                c.sourceLabel
            }
            val text = c.text
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(maxChunkChars)
            val part = "[Source: $location]\n$text"
            if (used + part.length > maxContextChars && contextParts.isNotEmpty()) break
            contextParts += part
            used += part.length
            if (used >= maxContextChars) break
        }
        val context = contextParts.joinToString("\n\n")
        return buildString {
            append("Context:\n")
            append(context)
            append("\n\nQuestion: ")
            append(trimmed)
        }
    }
}
