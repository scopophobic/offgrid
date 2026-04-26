package com.offgrid.shared.ai

import android.content.Context
import org.json.JSONObject

/**
 * Lightweight tokenizer backed by tokenizer.json vocab lookup.
 * This is not a full BPE implementation yet, but it provides readable decode
 * and a better encode fallback than hash-based placeholder tokenization.
 */
class JsonVocabTokenizer(
    private val tokenToId: Map<String, Int>,
    private val idToToken: Map<Int, String>
) : Tokenizer {

    override fun encode(text: String): List<Int> {
        if (text.isBlank()) return emptyList()

        val encoded = mutableListOf<Int>()
        val words = text.trim().split(Regex("\\s+"))
        words.forEachIndexed { index, word ->
            val direct = tokenToId[word]
            val withLeadingSpace = tokenToId[" $word"] ?: tokenToId["Ġ$word"]
            when {
                direct != null && index == 0 -> encoded += direct
                withLeadingSpace != null && index > 0 -> encoded += withLeadingSpace
                direct != null -> encoded += direct
                else -> encodeByCharacters(word, encoded)
            }
        }
        return encoded.ifEmpty { listOf(0) }
    }

    override fun decode(tokenIds: List<Int>): String {
        if (tokenIds.isEmpty()) return ""

        val raw = buildString {
            tokenIds.forEach { id ->
                val token = idToToken[id] ?: return@forEach
                if (token.startsWith("<|") && token.endsWith("|>")) return@forEach
                append(token)
            }
        }
        return raw
            .replace("Ġ", " ")
            .replace("Ċ", "\n")
            .trim()
    }

    private fun encodeByCharacters(word: String, out: MutableList<Int>) {
        word.forEach { ch ->
            val charToken = ch.toString()
            val id = tokenToId[charToken]
            if (id != null) out += id else out += 0
        }
    }

    companion object {
        fun fromAsset(context: Context, assetPath: String): JsonVocabTokenizer {
            val jsonText = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonText)
            val vocabObj = root.getJSONObject("model").getJSONObject("vocab")

            val tokenToId = mutableMapOf<String, Int>()
            val idToToken = mutableMapOf<Int, String>()
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                val id = vocabObj.getInt(token)
                tokenToId[token] = id
                idToToken[id] = token
            }
            return JsonVocabTokenizer(
                tokenToId = tokenToId,
                idToToken = idToToken
            )
        }
    }
}
