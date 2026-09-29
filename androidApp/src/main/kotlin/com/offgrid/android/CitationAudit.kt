package com.offgrid.android

/** Checks reference numbers only; it cannot establish that the cited passage proves a claim. */
object CitationAudit {
    fun invalidReferences(answer: String, sourceCount: Int): Set<Int> =
        Regex("\\[(\\d+)]").findAll(answer).mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it < 1 || it > sourceCount }.toSet()
}
