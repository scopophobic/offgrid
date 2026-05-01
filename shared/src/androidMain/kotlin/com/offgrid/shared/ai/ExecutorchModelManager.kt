package com.offgrid.shared.ai

import android.content.Context
import com.offgrid.shared.models.AppResult
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
    private val generationConfig: GenerationConfig = GenerationConfig(maxNewTokens = 384),
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

    override fun streamResponse(prompt: String): Flow<String> = callbackFlow {
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

        buildHeuristicReply(prompt.trim())?.let { heuristic ->
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
            val llmConfig =
                LlmGenerationConfig.create()
                    .maxNewTokens(generationConfig.maxNewTokens)
                    // seqLen is maximum total tokens (prompt + generation).
                    // 1024 leaves comfortable room for retrieved RAG context.
                    .seqLen(1024)
                    .echo(false)
                    .temperature(temperature)
                    .build()

            activeModule.generate(formatAsChatPrompt(prompt), llmConfig, callback)
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

        val overrideFile = overridePath?.let { File(it) }
        if (overrideFile != null) {
            if (overrideFile.exists() && overrideFile.canRead()) {
                return overrideFile
            }
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

    private fun formatAsChatPrompt(userPrompt: String): String {
        return buildString {
            append("<|endoftext|>")
            append("<|im_start|>system\n")
            append(SYSTEM_PROMPT)
            append("\n<|im_end|>\n")
            append("<|im_start|>user\n")
            append(userPrompt.trim())
            append("\n<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
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

        val compactWhitespace = withoutRoleMarkers
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        val withoutMonologueParagraphs = dropMonologueParagraphs(compactWhitespace)
        val withoutLineNarration = removeReasoningNarration(withoutMonologueParagraphs)
        val trimmedLeadingJunk = trimLeadingJunk(withoutLineNarration)
        return trimAfterFirstQuestionBlock(trimmedLeadingJunk)
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
        if (MONOLOGUE_SIGNALS.any { lower.contains(it) }) return true
        val firstChar = paragraph.firstOrNull() ?: return false
        if (firstChar.isLowerCase() && paragraph.length < 240) {
            // A short fragment that doesn't even start with a capital is almost
            // always residual narration when followed by a real reply.
            return MONOLOGUE_SIGNALS_LOOSE.any { lower.contains(it) }
        }
        return false
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
            "(?i)\\blet me\\s+(think|see|check|consider)[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\bfrom the context[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\bi need to[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\bi should[^.?!\\n]*[.?!]?\\s*",
            "(?i)\\bbased on the (context|provided)[^.?!\\n]*[.?!]?\\s*"
        )
        patterns.forEach { pattern ->
            cleaned = cleaned.replace(Regex(pattern), "")
        }
        return cleaned.trim()
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are Offgrid, a practical offline assistant on the user's phone. " +
                "Answer concisely in plain language, using lists or steps when useful. " +
                "Ground answers in any context provided. " +
                "If you don't know something, say so."

        val STOP_SEQUENCES = listOf("<|im_end|>", "<|endoftext|>", "<|im_start|>")
        val GREETING_INPUTS = setOf("hello", "hi", "hey", "hello!", "hi!", "hey!")

        val THINK_BLOCK_REGEX = Regex(
            pattern = "<think>[\\s\\S]*?</think>",
            options = setOf(RegexOption.IGNORE_CASE)
        )

        // Phrases that indicate the model is monologuing about *how* it should
        // respond (echoing the system prompt, narrating its own plan, etc).
        // Keep lowercase, no anchors. A first paragraph containing any of these
        // is dropped wholesale; the actual answer that follows is preserved.
        val MONOLOGUE_SIGNALS = listOf(
            "no local knowledge",
            "local knowledge",
            "keep it simple",
            "leep it simple",
            "no tags",
            "no repeats",
            "no repetition",
            "friendly greeting",
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
            "based on the context",
            "based on the provided",
            "from the context",
            "first, ",
            "alright, ",
            "alright. ",
            "okay, ",
            "okay. "
        )

        // Looser signals: only used for short, lowercase-starting paragraphs
        // (typical of mid-sentence reasoning the model leaks before recovering).
        val MONOLOGUE_SIGNALS_LOOSE = listOf(
            "user",
            "should",
            "need to",
            "let me",
            "okay"
        )
    }
}
