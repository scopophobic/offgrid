package com.offgrid.shared.ai

data class GenerationConfig(
    val maxNewTokens: Int = 96,
    val eosTokenId: Int = -1
)

interface Tokenizer {
    fun encode(text: String): List<Int>
    fun decode(tokenIds: List<Int>): String
}

/**
 * Placeholder tokenizer so the generation pipeline can be wired now.
 * Replace with real Qwen tokenizer (BPE/SentencePiece) in the next step.
 */
class WhitespaceTokenizer : Tokenizer {
    override fun encode(text: String): List<Int> {
        if (text.isBlank()) return emptyList()
        return text.split(Regex("\\s+")).map { token -> token.hashCode() and Int.MAX_VALUE }
    }

    override fun decode(tokenIds: List<Int>): String {
        if (tokenIds.isEmpty()) return ""
        return tokenIds.joinToString(separator = " ") { "[tok:$it]" }
    }
}
