package com.offgrid.shared.ai

import com.offgrid.shared.models.AppResult
import kotlinx.coroutines.flow.Flow

interface ModelManager {
    suspend fun loadModel(): AppResult<Unit>
    fun streamResponse(prompt: String): Flow<String>
    fun stopGeneration()
    suspend fun unloadModel()
}
