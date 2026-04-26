package com.offgrid.shared.ai

import android.content.Context
import com.offgrid.shared.models.AppResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ExecutorchModelManager(
    private val appContext: Context,
    private val modelAssetPath: String = "models/smollm2/model.pte",
    private val tokenizerAssetPath: String = "models/smollm2/tokenizer.json",
    private val generationConfig: GenerationConfig = GenerationConfig(maxNewTokens = 128),
    private val temperature: Float = 0.7f
) : ModelManager {
    private var module: LlmModule? = null

    override suspend fun loadModel(): AppResult<Unit> {
        return try {
            val modelFile = ensureModelFile(modelAssetPath)
            val tokenizerFile = ensureModelFile(tokenizerAssetPath)
            module = LlmModule(
                modelFile.absolutePath,
                tokenizerFile.absolutePath,
                temperature
            )
            // Force native load now so we fail early.
            module?.load()
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Error(
                message = "ExecuTorch model load failed. Falling back to mock stream.",
                throwable = t
            )
        }
    }

    override fun streamResponse(prompt: String): Flow<String> = callbackFlow {
        val activeModule = module
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

        val finished = AtomicBoolean(false)
        val callback = object : LlmCallback {
            override fun onResult(result: String) {
                if (finished.get()) return
                trySend(result)
            }

            override fun onStats(stats: String) {
                // Keep stats available for future debug UI; ignored for now.
            }
        }

        try {
            // echo=false so only generated text is returned.
            activeModule.generate(
                prompt,
                generationConfig.maxNewTokens.coerceAtLeast(64),
                callback,
                false
            )
        } catch (t: Throwable) {
            trySend("Generation failed: ${t.message ?: "unknown error"}")
        } finally {
            finished.set(true)
            close()
        }
    }

    override suspend fun unloadModel() {
        runCatching { module?.resetContext() }
        runCatching { module?.resetNative() }
        module = null
    }

    private fun ensureModelFile(assetPath: String): File {
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
}
