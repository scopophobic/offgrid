package com.offgrid.shared.ai

import com.offgrid.shared.models.AppResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MockModelManager : ModelManager {
    private var isLoaded = false

    override suspend fun loadModel(): AppResult<Unit> {
        isLoaded = true
        return AppResult.Success(Unit)
    }

    override fun streamResponse(prompt: String): Flow<String> = flow {
        if (!isLoaded) {
            emit("Model not loaded. ")
            return@flow
        }
        val text = "Mock response for: $prompt. ExecuTorch wiring will be added next."
        text.split(" ").forEach { token ->
            emit("$token ")
            delay(80)
        }
    }

    override suspend fun unloadModel() {
        isLoaded = false
    }
}
