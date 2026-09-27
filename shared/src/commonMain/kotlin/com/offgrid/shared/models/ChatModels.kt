package com.offgrid.shared.models

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(val message: String, val throwable: Throwable? = null) : AppResult<Nothing>
}

data class ChatMessage(
    val id: String,
    val text: String,
    val fromUser: Boolean,
    val sources: List<AnswerSource> = emptyList(),
    val interrupted: Boolean = false,
    val taskId: String? = null
)

data class AnswerSource(
    val title: String,
    val passage: String,
    val location: String = "",
    val savedAt: Long = 0L
)

data class ChatTurn(val fromUser: Boolean, val text: String)

data class ChatUiState(
    val conversationId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isRetrieving: Boolean = false,
    val error: String? = null
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeBytes: Long,
    val tags: List<String>,
    val recommendedRamMb: Int?,
    val isActive: Boolean,
    val isDownloaded: Boolean,
    val onDeviceBytes: Long
)

sealed interface ModelBootstrapUiState {
    data object Checking : ModelBootstrapUiState
    data object Ready : ModelBootstrapUiState
    data class NeedsSelection(val available: List<ModelInfo>) : ModelBootstrapUiState
    data class Downloading(
        val label: String,
        val bytesReceived: Long,
        val bytesTotal: Long
    ) : ModelBootstrapUiState
    data class Failed(val message: String) : ModelBootstrapUiState
}
