package com.offgrid.shared.ai

import android.content.Context
import android.util.Log
import com.offgrid.shared.models.AppResult
import com.offgrid.shared.models.ChatTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ExecutorchModelManager(
    private val appContext: Context,
    private val modelAssetPath: String = "models/qwen3_1_7b/model.pte",
    private val tokenizerAssetPath: String = "models/qwen3_1_7b/tokenizer.json",
    private val modelFilePathOverride: String? = null,
    private val tokenizerFilePathOverride: String? = null,
    private val modelExternalFileName: String = "model.pte",
    private val tokenizerExternalFileName: String = "tokenizer.json",
    private val temperature: Float = 0.1f
) : ModelManager {
    private var module: LlmModule? = null
    private var isGenerationStopped = AtomicBoolean(false)

    override suspend fun loadModel(): AppResult<Unit> {
        return try {
            val modelFile = resolveFile(modelFilePathOverride, modelAssetPath)
            val tokenizerFile = resolveFile(tokenizerFilePathOverride, tokenizerAssetPath)
            module = LlmModule(
                modelFile.absolutePath,
                tokenizerFile.absolutePath,
                temperature
            )
            // Force native load now so we fail early.
            module?.load()
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            val detail = t.message?.takeIf { it.isNotBlank() } ?: "no native error message"
            AppResult.Error(
                message =
                    "ExecuTorch model load failed: ${t::class.simpleName}: $detail. " +
                        "Falling back to mock stream.",
                throwable = t
            )
        }
    }

    override fun streamResponse(prompt: String): Flow<String> =
        streamResponse(prompt, emptyList())

    override fun streamResponse(prompt: String, history: List<ChatTurn>): Flow<String> = callbackFlow {
        val activeModule = module
        isGenerationStopped.set(false)
        if (activeModule == null) {
            trySend("Model not loaded.")
            close()
            return@callbackFlow
        }

        if (prompt.isBlank()) {
            trySend("Prompt is empty.")
            close()
            return@callbackFlow
        }

        // RAG wraps as "Context: ...\n\nQuestion: <user>"; heuristics must use <user> only.
        val queryForHeuristic = userFacingQueryFromPrompt(prompt)
        buildHeuristicReply(queryForHeuristic)?.let { heuristic ->
            trySend(heuristic)
            close()
            return@callbackFlow
        }

        val finished = AtomicBoolean(false)
        val generatedText = StringBuilder()
        var emittedChars = 0
        val callback = object : LlmCallback {
            override fun onResult(result: String) {
                if (finished.get() || isGenerationStopped.get()) return
                generatedText.append(result)
                val normalizedText = normalizeGeneratedText(generatedText.toString())
                if (normalizedText.length > emittedChars) {
                    val delta = normalizedText.substring(emittedChars)
                    emittedChars = normalizedText.length
                    trySend(delta)
                }

                if (
                    containsStopSequence(generatedText.toString()) ||
                    isRepetitiveLoop(normalizedText)
                ) {
                    finished.set(true)
                    isGenerationStopped.set(true)
                    runCatching { activeModule.resetContext() }
                    runCatching { activeModule.stop() }
                }
            }

            override fun onStats(stats: String) {
                // Keep stats available for future debug UI; ignored for now.
            }
        }

        try {
            // No explicit token cap: rely on stop tokens, EOS, repetition guard, and seqLen.
            // seqLen is total budget for prompt + generation.
            val llmConfig =
                LlmGenerationConfig.create()
                    .seqLen(SEQ_LEN)
                    .echo(false)
                    .temperature(temperature)
                    .build()

            activeModule.generate(formatAsChatPrompt(prompt, history), llmConfig, callback)
        } catch (t: Throwable) {
            trySend("Generation failed: ${t.message ?: "unknown error"}")
        } finally {
            finished.set(true)
            isGenerationStopped.set(true)
            close()
        }
    }

    override fun stopGeneration() {
        isGenerationStopped.set(true)
        runCatching { module?.stop() }
        runCatching { module?.resetContext() }
    }

    override suspend fun unloadModel() {
        stopGeneration()
        runCatching { module?.resetContext() }
        runCatching { module?.resetNative() }
        module = null
    }

    private fun resolveFile(overridePath: String?, assetPath: String): File {
        val overrideFile = overridePath?.let { File(it) }
        if (overrideFile != null && overrideFile.exists() && overrideFile.canRead()) {
            return overrideFile
        }

        val externalFilesDir = appContext.getExternalFilesDir(null)
        if (externalFilesDir != null) {
            val externalFile = File(externalFilesDir, assetPath.substringAfterLast("/"))
            if (externalFile.exists() && externalFile.canRead()) {
                return externalFile
            }
            val externalByName = if (assetPath.contains("tokenizer")) {
                File(externalFilesDir, tokenizerExternalFileName)
            } else {
                File(externalFilesDir, modelExternalFileName)
            }
            if (externalByName.exists() && externalByName.canRead()) {
                return externalByName
            }
        }

        if (overrideFile != null) {
            throw IllegalStateException(
                "Override file is not readable: $overridePath. " +
                    "Push file to this exact path or remove override."
            )
        }
        return ensureModelFileFromAssets(assetPath)
    }

    private fun ensureModelFileFromAssets(assetPath: String): File {
        val outFile = File(appContext.filesDir, assetPath.substringAfterLast("/"))
        if (!outFile.exists()) {
            outFile.outputStream().use { output ->
                appContext.assets.open(assetPath).use { input ->
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }

    /**
     * Render conversation as ChatML so the model sees prior turns as proper
     * `<|im_start|>user/assistant` blocks. Caps the rendered history by char
     * budget so the prompt stays within [SEQ_LEN] minus generation headroom.
     */
    private fun formatAsChatPrompt(userPrompt: String, history: List<ChatTurn>): String {
        val sb = StringBuilder()
        sb.append("<|endoftext|>")
        sb.append("<|im_start|>system\n")
        sb.append(SYSTEM_PROMPT)
        sb.append("\n<|im_end|>\n")

        // Sliding window: keep most recent turns under HISTORY_CHAR_BUDGET.
        val historyBlock = StringBuilder()
        var used = 0
        for (turn in history.asReversed()) {
            var cleanText = turn.text.trim()
            if (cleanText.isEmpty()) continue
            val role = if (turn.fromUser) "user" else "assistant"
            
            // Truncate long assistant replies so they don't eat the entire budget
            if (!turn.fromUser && cleanText.length > 1500) {
                cleanText = cleanText.take(1500) + "...\n[Truncated]"
            }
            
            val rendered = "<|im_start|>$role\n$cleanText\n<|im_end|>\n"
            if (used + rendered.length > HISTORY_CHAR_BUDGET) break
            historyBlock.insert(0, rendered)
            used += rendered.length
        }
        sb.append(historyBlock)

        sb.append("<|im_start|>user\n")
        sb.append(userPrompt.trim())
        sb.append("\n<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /**
     * Last line after [HybridRetriever]'s `Question:` marker, or whole prompt if absent.
     */
    private fun userFacingQueryFromPrompt(prompt: String): String {
        val marker = "\n\nQuestion: "
        val idx = prompt.lastIndexOf(marker)
        return if (idx >= 0) prompt.substring(idx + marker.length).trim() else prompt.trim()
    }

    private fun buildHeuristicReply(userPrompt: String): String? {
        val normalized = userPrompt.lowercase().trim()
        if (normalized in GREETING_INPUTS) {
            return "Hello! How can I help offline today?"
        }
        if (normalized == "who are you") {
            return "I'm Offgrid, your offline assistant."
        }
        return null
    }

    private fun normalizeGeneratedText(rawText: String): String {
        val withoutThinkBlocks = rawText.replace(THINK_BLOCK_REGEX, "")
        val withoutThinkTags = withoutThinkBlocks
            .replace("<think>", "")
            .replace("</think>", "")
        val withoutStops = truncateAtStopSequence(withoutThinkTags)
        val withoutRoleMarkers = withoutStops
            .replace("<|endoftext|>", "")
            .replace("<|im_start|>assistant", "")
            .replace("<|im_start|>user", "")
            .replace("<|im_start|>system", "")
            .replace("<|im_end|>", "")

        // Reasoning models without explicit <think> tags often start with a
        // monologue paragraph (planning/repeating the question). If they do,
        // skip everything before the FIRST blank-line break that separates the
        // monologue from the actual answer.
        val deMonologued = stripUntaggedThinking(withoutRoleMarkers)

        val compactWhitespace = deMonologued
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        val withoutMonologueParagraphs = dropMonologueParagraphs(compactWhitespace)
        val withoutLineNarration = removeReasoningNarration(withoutMonologueParagraphs)
        val trimmedLeadingJunk = trimLeadingJunk(withoutLineNarration)
        val trimmed = trimAfterFirstQuestionBlock(trimmedLeadingJunk)
        if (trimmed.isBlank() && compactWhitespace.isNotBlank()) {
            Log.w(
                TAG,
                "normalize stripped all visible text; falling back to light cleanup"
            )
            return lightCleanupFallback(compactWhitespace)
        }
        return trimmed
    }

    /**
     * Heuristic for "thinking out loud" without `<think>` tags: when the first
     * paragraph contains classic monologue cues AND a real answer follows after
     * a blank line, drop everything up to that blank line.
     *
     * We only do this once, on the first paragraph, to avoid eating real
     * content from longer answers.
     */
    private fun stripUntaggedThinking(text: String): String {
        val firstBreak = text.indexOf("\n\n")
        if (firstBreak <= 0) return text
        val first = text.substring(0, firstBreak)
        val rest = text.substring(firstBreak + 2)
        if (rest.isBlank()) return text
        val lower = first.lowercase()
        val cuesHit = THINKING_OUT_LOUD_CUES.count { lower.contains(it) }
        // Require at least 1 cue AND the remainder to look like an answer
        // (capital letter / list / heading) to avoid eating short, valid replies.
        val nextChar = rest.trimStart().firstOrNull()
        val nextLooksReal = nextChar != null &&
            (nextChar.isUpperCase() || nextChar == '-' || nextChar == '#' || nextChar == '1')
        return if (cuesHit >= 1 && nextLooksReal) rest else text
    }

    /** When aggressive filters remove everything, keep role markers / stops stripped only. */
    private fun lightCleanupFallback(text: String): String {
        val noThink = text.replace(THINK_BLOCK_REGEX, "")
            .replace("<think>", "")
            .replace("</think>", "")
        return truncateAtStopSequence(noThink)
            .replace("<|endoftext|>", "")
            .replace("<|im_start|>assistant", "")
            .replace("<|im_start|>user", "")
            .replace("<|im_start|>system", "")
            .replace("<|im_end|>", "")
            .trim()
    }

    private fun dropMonologueParagraphs(text: String): String {
        if (text.isBlank()) return text
        val paragraphs = text.split(Regex("\\n{2,}"))
        val keep = mutableListOf<String>()
        var seenRealContent = false
        for (paragraph in paragraphs) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) continue
            val isMonologue = !seenRealContent && looksLikeMonologue(trimmed)
            if (isMonologue) continue
            keep += trimmed
            seenRealContent = true
        }
        return keep.joinToString("\n\n")
    }

    private fun looksLikeMonologue(paragraph: String): Boolean {
        val lower = paragraph.lowercase()
        // Avoid substring matches like "okay"/"user" on real answers (they nuked short replies).
        return MONOLOGUE_SIGNALS.any { lower.contains(it) }
    }

    private fun trimLeadingJunk(text: String): String {
        if (text.isEmpty()) return text
        var start = 0
        while (start < text.length) {
            val ch = text[start]
            if (ch.isLetterOrDigit() || ch == '"' || ch == '\'') break
            start++
        }
        if (start == 0) return text
        return text.substring(start)
    }

    private fun trimAfterFirstQuestionBlock(text: String): String {
        val lower = text.lowercase()
        val markers = listOf(
            "\nwhat is the name of",
            "\nwhat is the second",
            "\nwhat is the first",
            "\nquestion:",
            "\nuser:"
        )
        val cutIndex = markers
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return text
        return text.substring(0, cutIndex).trim()
    }

    private fun containsStopSequence(text: String): Boolean {
        return STOP_SEQUENCES.any { text.contains(it) }
    }

    private fun truncateAtStopSequence(text: String): String {
        val stopIndex = STOP_SEQUENCES
            .map { text.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return text
        return text.substring(0, stopIndex)
    }

    private fun isRepetitiveLoop(text: String): Boolean {
        if (text.length < 48) return false
        return repeatedTailCount(text, 8) >= 4 ||
            repeatedTailCount(text, 12) >= 3 ||
            repeatedTailCount(text, 16) >= 3
    }

    private fun repeatedTailCount(text: String, unit: Int): Int {
        if (text.length < unit * 2) return 1
        val tail = text.takeLast(unit)
        var count = 1
        var index = text.length - (2 * unit)
        while (index >= 0) {
            if (text.substring(index, index + unit) != tail) break
            count++
            index -= unit
        }
        return count
    }

    private fun removeReasoningNarration(text: String): String {
        var cleaned = text
        val patterns = listOf(
            "(?i)\\bokay,?\\s*the user[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\bokay,?[^.?!\\n]{0,120}?applications[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\blet me\\s+(think|see|check|consider|figure out|outline|structure)[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\bi need to figure out[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\bi should[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\bmake sure (the )?answer[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\bkeep (each point|it brief)[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\bavoid (any )?technical[^.?!\\n]*[.?!]?\\s*"
        )
        patterns.forEach { pattern ->
            cleaned = cleaned.replace(Regex(pattern), "")
        }
        return cleaned.trim()
    }

    private companion object {
        private const val TAG = "ExecuTorchModel"

        // Total prompt + generation budget. RAG context (~3k chars), 4 history
        // turns, system prompt and final answer all share this. Higher = more
        // memory used but no truncated answers.
        private const val SEQ_LEN = 4096
        private const val HISTORY_CHAR_BUDGET = 8000

        const val SYSTEM_PROMPT =
            "You are Offgrid, a practical offline assistant on the user's phone. " +
                "Answer concisely. Do NOT think out loud. Do NOT explain your reasoning, " +
                "your plan, or what you are going to do. Do NOT restate the question. " +
                "Do NOT write phrases like \"the user is asking\", \"let me see\", " +
                "\"okay, the user\", \"first I need to\", or \"based on the context\". " +
                "If a Context block is provided, ground your answer in it and cite sources " +
                "inline as `[Source: …]` when useful. " +
                "Output ONLY the final reply the user should read, nothing else."

        // Do not stop on "<|im_start|>" because some models emit "<|im_start|>assistant"
        // before actual text; truncating there drops the whole answer.
        val STOP_SEQUENCES = listOf("<|im_end|>", "<|endoftext|>")
        val GREETING_INPUTS = setOf("hello", "hi", "hey", "hello!", "hi!", "hey!")

        val THINK_BLOCK_REGEX = Regex(
            pattern = "<think>[\\s\\S]*?</think>",
            options = setOf(RegexOption.IGNORE_CASE)
        )

        // Cues that signal an opening "thinking out loud" paragraph when no
        // <think> tag is used (Qwen-class reasoning leakage).
        val THINKING_OUT_LOUD_CUES = listOf(
            "okay, the user",
            "okay, let",
            "the user is asking",
            "the user wants",
            "the user provided",
            "i need to figure out",
            "i need to think",
            "let me see",
            "let me figure",
            "let me think",
            "let me consider",
            "let me outline",
            "let me structure",
            "first, the context",
            "first, i ",
            "based on the context",
            "from the context"
        )

        // Phrases that indicate the model is monologuing about *how* it should
        // respond (echoing the system prompt, narrating its own plan, etc).
        // Keep lowercase, no anchors. A first paragraph containing any of these
        // is dropped wholesale; the actual answer that follows is preserved.
        val MONOLOGUE_SIGNALS = listOf(
            "no local knowledge",
            "local knowledge",
            "keep each point",
            "keep it brief",
            "keep it simple",
            "leep it simple",
            "no tags",
            "no repeats",
            "no repetition",
            "friendly greeting",
            "avoid any technical",
            "make sure the answer",
            "might be interested",
            "user might be",
            "user could be",
            "the user is",
            "the user wants",
            "the user said",
            "the user asked",
            "user just said",
            "let me think",
            "let me consider",
            "i should ",
            "i need to ",
            "as an ai",
            "as a language model",
            "my job is",
            "my task is",
            // Do not match normal answers that ground in RAG ("based on the sources…")
            // or polite openings ("Okay, here are…"); those were nuking whole paragraphs.
            "based on the provided context only",
            "based on the provided information only",
            "from the context above only",
            "from the provided context only",
            "let me outline how i'll answer",
            "let me structure",
            "step one:",
            "step 1:"
        )
    }
}
