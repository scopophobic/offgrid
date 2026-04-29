package com.offgrid.shared.rag

import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

data class KnowledgeChunk(
    val id: String,
    val topicId: String,
    val content: String
)

class QueryAnswerCache(
    private val ttlMs: Long = 24.hours.inWholeMilliseconds
) {
    private data class Entry(val answer: String, val createdAtMs: Long)
    private val values = mutableMapOf<String, Entry>()

    fun get(query: String): String? {
        val key = hash(query)
        val entry = values[key] ?: return null
        val now = Clock.System.now().toEpochMilliseconds()
        return if (now - entry.createdAtMs <= ttlMs) entry.answer else null
    }

    fun put(query: String, answer: String) {
        values[hash(query)] = Entry(answer = answer, createdAtMs = Clock.System.now().toEpochMilliseconds())
    }

    private fun hash(value: String): String = value.lowercase().trim()
}

class InMemoryKnowledgeStore {
    private val chunks = mutableListOf<KnowledgeChunk>()

    fun seedGuitarTheory() {
        if (chunks.isNotEmpty()) return
        chunks += listOf(
            KnowledgeChunk("g1", "guitar", "A chord is a group of notes played together."),
            KnowledgeChunk("g2", "guitar", "A G major chord uses the notes G, B, and D."),
            KnowledgeChunk("g3", "guitar", "Common beginner progression: G, C, D, Em."),
            KnowledgeChunk("g4", "guitar", "A major chord has a root, major third, and perfect fifth."),
            KnowledgeChunk("g5", "guitar", "G7 includes an added minor seventh note: F."),
            KnowledgeChunk("g6", "guitar", "Practice chord changes slowly with a metronome."),
            KnowledgeChunk("g7", "guitar", "Use clean finger placement and arch fingertips for clear strings."),
            KnowledgeChunk("g8", "guitar", "Strum from bass strings to treble with even timing."),
            KnowledgeChunk("g9", "guitar", "Muted or buzzing strings often mean finger pressure or angle issues."),
            KnowledgeChunk("g10", "guitar", "Short daily practice sessions are better than occasional long sessions.")
        )
    }

    fun all(): List<KnowledgeChunk> = chunks.toList()
}

class SimpleRagPipeline(
    private val knowledgeStore: InMemoryKnowledgeStore
) {
    fun buildPrompt(userQuery: String): String {
        val context = retrieve(userQuery).joinToString("\n") { "- ${it.content}" }
        return buildString {
            append("Use the provided local knowledge context when relevant.\n")
            append("Context:\n")
            append(context.ifBlank { "- No local knowledge found." })
            append("\n\nUser question:\n")
            append(userQuery.trim())
        }
    }

    private fun retrieve(userQuery: String): List<KnowledgeChunk> {
        val queryTokens = tokenize(userQuery)
        return knowledgeStore.all()
            .map { chunk -> chunk to overlapScore(queryTokens, tokenize(chunk.content)) }
            .sortedByDescending { it.second }
            .take(5)
            .filter { it.second > 0 }
            .map { it.first }
    }

    private fun overlapScore(queryTokens: Set<String>, chunkTokens: Set<String>): Int {
        return queryTokens.intersect(chunkTokens).size
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 }
            .toSet()
    }
}
