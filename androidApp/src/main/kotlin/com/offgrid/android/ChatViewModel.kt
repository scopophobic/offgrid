package com.offgrid.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offgrid.shared.ai.ModelManager
import com.offgrid.shared.models.AppResult
import com.offgrid.shared.models.ChatMessage
import com.offgrid.shared.models.ChatUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val modelManager: ModelManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            when (val result = modelManager.loadModel()) {
                is AppResult.Success -> Unit
                is AppResult.Error -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            fromUser = true
        )
        val responseId = UUID.randomUUID().toString()
        val pendingAssistant = ChatMessage(id = responseId, text = "", fromUser = false)

        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                messages = it.messages + userMessage + pendingAssistant
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                modelManager.streamResponse(text).collect { token ->
                    _uiState.update { state ->
                        val updated = state.messages.map { msg ->
                            if (msg.id == responseId) msg.copy(text = msg.text + token) else msg
                        }
                        state.copy(messages = updated)
                    }
                }
                _uiState.update { it.copy(isLoading = false) }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = t.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        viewModelScope.launch {
            modelManager.unloadModel()
        }
        super.onCleared()
    }
}
