package com.offgrid.shared.rag

import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Tiny in-memory cache of recent answers keyed by normalized query text.
 *
 * Survives only for the lifetime of the process. The TTL is conservative
 * (default 24h) since model + retrieval changes can shift answers.
 */
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
        values[hash(query)] = Entry(
            answer = answer,
            createdAtMs = Clock.System.now().toEpochMilliseconds()
        )
    }

    private fun hash(value: String): String = value.lowercase().trim()
}
