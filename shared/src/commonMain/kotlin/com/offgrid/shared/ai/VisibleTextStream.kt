package com.offgrid.shared.ai

/** Incrementally removes protocol markers and reasoning blocks without rewriting emitted text. */
class VisibleTextStream {
    private var pending = ""
    private var thinking = false
    var stopped = false
        private set
    private val stops = listOf("<|im_end|>", "<|endoftext|>", "<|eot_id|>", "<|end_of_text|>")
    private val tags = stops + listOf("<think>", "</think>", "<|im_start|>assistant", "<|start_header_id|>assistant<|end_header_id|>")
    fun append(chunk: String): String {
        if(stopped) return ""
        pending += chunk
        val visible = StringBuilder()
        while(pending.isNotEmpty()) {
            val tag = tags.firstOrNull { pending.startsWith(it) }
            if(tag != null) {
                pending = pending.drop(tag.length)
                when(tag) {
                    "<think>" -> thinking = true
                    "</think>" -> thinking = false
                    in stops -> { stopped = true; pending = ""; break }
                }
            } else if(tags.any { it.startsWith(pending) }) break
            else {
                if(!thinking) visible.append(pending[0])
                pending = pending.drop(1)
            }
        }
        return visible.toString()
    }
    fun finish(): String {
        val tail = if(thinking || stopped || tags.any { it.startsWith(pending) }) "" else pending
        pending = ""
        return tail
    }
}
