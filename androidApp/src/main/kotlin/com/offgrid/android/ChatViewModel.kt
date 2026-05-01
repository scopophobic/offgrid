package com.offgrid.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offgrid.shared.ai.ModelManager
import com.offgrid.shared.knowledge.HybridRetriever
import com.offgrid.shared.knowledge.KnowledgePack
import com.offgrid.shared.knowledge.KnowledgePackStore
import com.offgrid.shared.models.AppResult
import com.offgrid.shared.models.ChatMessage
import com.offgrid.shared.models.ChatUiState
import com.offgrid.shared.rag.QueryAnswerCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val modelManager: ModelManager,
    private val packStore: KnowledgePackStore,
    private val workerPackRepository: WorkerPackRepository,
    private val retriever: HybridRetriever,
    private val answerCache: QueryAnswerCache
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val installedPacks: StateFlow<List<KnowledgePack>> = packStore.installed()

    private val _isRefreshingPacks = MutableStateFlow(false)
    val isRefreshingPacks: StateFlow<Boolean> = _isRefreshingPacks.asStateFlow()
    private val _availablePacks = MutableStateFlow<List<RemotePack>>(emptyList())
    val availablePacks: StateFlow<List<RemotePack>> = _availablePacks.asStateFlow()
    private val _isRefreshingCatalog = MutableStateFlow(false)
    val isRefreshingCatalog: StateFlow<Boolean> = _isRefreshingCatalog.asStateFlow()
    private val _installingPackIds = MutableStateFlow<Set<String>>(emptySet())
    val installingPackIds: StateFlow<Set<String>> = _installingPackIds.asStateFlow()
    private val _deletingPackIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingPackIds: StateFlow<Set<String>> = _deletingPackIds.asStateFlow()

    private var generationJob: Job? = null

    init {
        viewModelScope.launch {
            when (val result = modelManager.loadModel()) {
                is AppResult.Success -> Unit
                is AppResult.Error -> _uiState.update { it.copy(error = result.message) }
            }
        }
        refreshPacks()
        refreshCatalog()
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isLoading) return

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
                isRetrieving = true,
                error = null,
                messages = it.messages + userMessage + pendingAssistant
            )
        }

        generationJob?.cancel()
        generationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = answerCache.get(text)
                if (cached != null) {
                    _uiState.update { state ->
                        val updated = state.messages.map { msg ->
                            if (msg.id == responseId) msg.copy(text = cached) else msg
                        }
                        state.copy(isLoading = false, isRetrieving = false, messages = updated)
                    }
                    return@launch
                }

                val promptForModel = retriever.buildPrompt(text)
                _uiState.update { it.copy(isRetrieving = false) }

                modelManager.streamResponse(promptForModel).collect { token ->
                    _uiState.update { state ->
                        val updated = state.messages.map { msg ->
                            if (msg.id == responseId) msg.copy(text = msg.text + token) else msg
                        }
                        state.copy(messages = updated)
                    }
                }
                val finalAnswer =
                    _uiState.value.messages.firstOrNull { it.id == responseId }?.text.orEmpty()
                if (finalAnswer.isNotBlank()) {
                    answerCache.put(text, finalAnswer)
                }
                _uiState.update { it.copy(isLoading = false, isRetrieving = false) }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRetrieving = false,
                        error = t.message ?: "Unknown error"
                    )
                }
            } finally {
                generationJob = null
            }
        }
    }

    fun stopGeneration() {
        modelManager.stopGeneration()
        generationJob?.cancel()
        generationJob = null
        _uiState.update { state ->
            val updatedMessages = state.messages.toMutableList().also { messages ->
                val lastIndex = messages.indexOfLast { !it.fromUser }
                if (lastIndex >= 0 && messages[lastIndex].text.isBlank()) {
                    messages[lastIndex] = messages[lastIndex].copy(text = "[stopped]")
                }
            }
            state.copy(
                messages = updatedMessages,
                isLoading = false,
                isRetrieving = false,
                error = null
            )
        }
    }

    fun refreshPacks() {
        if (_isRefreshingPacks.value) return
        viewModelScope.launch {
            _isRefreshingPacks.value = true
            try {
                packStore.refresh()
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = "Pack refresh failed: ${t.message}") }
            } finally {
                _isRefreshingPacks.value = false
            }
        }
    }

    fun refreshCatalog() {
        if (_isRefreshingCatalog.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshingCatalog.value = true
            try {
                _availablePacks.value = workerPackRepository.listPacks()
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = "Catalog refresh failed: ${t.message}") }
            } finally {
                _isRefreshingCatalog.value = false
            }
        }
    }

    fun installPack(packId: String) {
        val pack = _availablePacks.value.firstOrNull { it.id == packId } ?: return
        if (_installingPackIds.value.contains(packId)) return
        viewModelScope.launch(Dispatchers.IO) {
            _installingPackIds.update { it + packId }
            try {
                workerPackRepository.installPack(pack)
                packStore.refresh()
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = "Pack install failed: ${t.message}") }
            } finally {
                _installingPackIds.update { it - packId }
            }
        }
    }

    fun deletePack(packId: String) {
        if (packId.isBlank()) return
        if (_deletingPackIds.value.contains(packId)) return
        viewModelScope.launch {
            _deletingPackIds.update { it + packId }
            try {
                val deleted = packStore.delete(packId)
                if (!deleted) {
                    _uiState.update { it.copy(error = "Pack not found: $packId") }
                }
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = "Pack delete failed: ${t.message}") }
            } finally {
                _deletingPackIds.update { it - packId }
            }
        }
    }

    override fun onCleared() {
        stopGeneration()
        viewModelScope.launch {
            modelManager.unloadModel()
        }
        super.onCleared()
    }
}
