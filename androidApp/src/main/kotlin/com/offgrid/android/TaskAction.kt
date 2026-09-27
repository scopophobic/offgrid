package com.offgrid.android

/** User-selected tasks. The small model receives a precise, bounded output shape. */
enum class TaskAction(val label: String, val instruction: String) {
    ASK("Ask", "Answer the question directly. If source passages are present, use them and cite the passage numbers for factual claims. Say when the evidence is insufficient."),
    SUMMARIZE("Summarize", "Summarize the supplied text or selected source. Start with one sentence, then 3-5 useful points. Include important travel logistics when present. Cite source passage numbers. Do not invent details beyond the excerpts."),
    EXPLAIN("Explain", "Explain the supplied text in plain language. Define unfamiliar terms, use one concrete example if helpful, and cite source passage numbers for source-based facts."),
    REWRITE("Rewrite", "Rewrite the supplied text clearly while preserving its meaning. Return only the rewritten text. Do not add facts."),
    PROOFREAD("Proofread", "Correct spelling, grammar, and punctuation while preserving meaning and tone. Return the corrected text, then list the most important changes briefly."),
    COMPARE("Compare", "Compare the supplied options using a short table or parallel bullets. State criteria and uncertainties. Cite source passage numbers for factual differences."),
    ACTIONS("Action items", "Extract actionable items only. Use a checklist with an owner or date only if explicitly supplied. Do not invent commitments."),
    CHECKLIST("Checklist", "Turn the supplied text into a practical checklist. Group related steps and preserve important constraints. Do not invent requirements."),
    SHORTEN("Shorter", "Make the supplied answer shorter while retaining essential facts and source citations."),
    SIMPLIFY("Simpler", "Explain the supplied answer in simpler language while retaining essential facts and source citations.");

    companion object { fun fromId(id: String?): TaskAction = entries.firstOrNull { it.name == id } ?: ASK }
}
