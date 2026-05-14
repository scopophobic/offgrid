package com.offgrid.shared.ai

import com.offgrid.shared.models.AppResult
import com.offgrid.shared.models.ChatTurn
import kotlinx.coroutines.flow.Flow

interface ModelManager {
    suspend fun loadModel(): AppResult<Unit>
    fun streamResponse(prompt: String): Flow<String>

    /**
     * Generate with prior conversation turns rendered into the chat template so
     * the model sees its own previous answers. Default delegates to the
     * single-prompt overload for backwards compatibility with [MockModelManager].
     */
    fun streamResponse(prompt: String, history: List<ChatTurn>): Flow<String> =
        streamResponse(prompt)

    fun stopGeneration()
    suspend fun unloadModel()
}
