package com.offgrid.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.offgrid.shared.ai.ModelManager
import com.offgrid.shared.knowledge.HybridRetriever
import com.offgrid.shared.knowledge.KnowledgePack
import com.offgrid.shared.knowledge.KnowledgePackStore
import com.offgrid.shared.models.AppResult
import com.offgrid.shared.models.ChatMessage
import com.offgrid.shared.models.ChatTurn
import com.offgrid.shared.models.ChatUiState
import com.offgrid.shared.models.ModelBootstrapUiState
import com.offgrid.shared.models.ModelInfo
import com.offgrid.shared.rag.QueryAnswerCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Owns chat state, the active LLM lifecycle, and the knowledge-pack catalog.
 *
 * Model lifecycle:
 *   1. On init → look up `activeModelId` in prefs.
 *      - If present and files cached locally → load directly.
 *      - Else → fetch the model catalog and surface [ModelBootstrapUiState.NeedsSelection].
 *   2. User picks a model → [selectModel] downloads (or reuses) artifacts and
 *      rebuilds the [ModelManager] via [modelManagerFactory].
 *   3. User deletes a non-active model → files removed; active model untouched.
 */
class ChatViewModel(
    private val packStore: KnowledgePackStore,
    private val workerPackRepository: WorkerPackRepository,
    private val modelFilesRepository: ModelFilesRepository,
    private val modelCatalogRepository: ModelCatalogRepository,
    private val retriever: HybridRetriever,
    private val answerCache: QueryAnswerCache,
    private val modelManagerFactory: (modelFile: File, tokenizerFile: File) -> ModelManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _modelBootstrapUi = MutableStateFlow<ModelBootstrapUiState>(ModelBootstrapUiState.Checking)
    val modelBootstrapUi: StateFlow<ModelBootstrapUiState> = _modelBootstrapUi.asStateFlow()

    private val _availableModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _freeStorageBytes = MutableStateFlow(0L)
    val freeStorageBytes: StateFlow<Long> = _freeStorageBytes.asStateFlow()

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
    private val _deletingModelIds = MutableStateFlow<Set<String>>(emptySet())
    val deletingModelIds: StateFlow<Set<String>> = _deletingModelIds.asStateFlow()

    /** Latest fetched catalog entries (not the UI projection — that's [_availableModels]). */
    private var catalogEntries: List<CatalogModelEntry> = emptyList()

    private var modelManager: ModelManager? = null
    private var generationJob: Job? = null

    init {
        _activeModelId.value = modelFilesRepository.activeModelId()
        refreshFreeStorage()
        refreshPacks()
        refreshCatalog()
        runModelBootstrap()
    }

    fun retryModelBootstrap() {
        runModelBootstrap()
    }

    fun refreshAvailableModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                catalogEntries = modelCatalogRepository.listModels()
                _availableModels.value = projectAvailableModels(catalogEntries)
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(error = "Model catalog failed: ${t.message}")
                }
            }
            refreshFreeStorage()
        }
    }

    /**
     * Pick a model. Triggers download if missing. Does not clear other models'
     * files — those stay around so users can switch back without redownloading.
     */
    fun selectModel(modelId: String) {
        val entry = catalogEntries.firstOrNull { it.id == modelId } ?: run {
            _uiState.update {
                it.copy(error = "Model \"$modelId\" not in catalog (try refreshing).")
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Tear down any existing native model before swapping files/path.
            stopGenerationInternal()
            runCatching { modelManager?.unloadModel() }
            modelManager = null

            _modelBootstrapUi.value = ModelBootstrapUiState.Checking
            val ensure = modelFilesRepository.ensureActiveModel(entry) { phase, rec, tot ->
                _modelBootstrapUi.value =
                    ModelBootstrapUiState.Downloading(phase, rec, tot)
            }
            when (ensure) {
                is ModelFilesRepository.EnsureResult.Failed -> {
                    _modelBootstrapUi.value = ModelBootstrapUiState.Failed(ensure.message)
                    return@launch
                }
                ModelFilesRepository.EnsureResult.NeedsSelection,
                ModelFilesRepository.EnsureResult.Ready -> Unit
            }
            _activeModelId.value = modelFilesRepository.activeModelId()
            refreshFreeStorage()
            _availableModels.value = projectAvailableModels(catalogEntries)
            loadActiveModel()
        }
    }

    fun deleteModel(modelId: String) {
        if (modelId == _activeModelId.value) {
            _uiState.update {
                it.copy(error = "Switch to another model before deleting the active one.")
            }
            return
        }
        if (_deletingModelIds.value.contains(modelId)) return
        viewModelScope.launch(Dispatchers.IO) {
            _deletingModelIds.update { it + modelId }
            try {
                modelFilesRepository.deleteModel(modelId)
            } catch (t: Throwable) {
                _uiState.update { it.copy(error = "Delete model failed: ${t.message}") }
            } finally {
                _deletingModelIds.update { it - modelId }
                refreshFreeStorage()
                _availableModels.value = projectAvailableModels(catalogEntries)
            }
        }
    }

    private fun runModelBootstrap() {
        viewModelScope.launch(Dispatchers.IO) {
            _modelBootstrapUi.value = ModelBootstrapUiState.Checking
            try {
                // 1. Best-effort catalog fetch (gives picker something to show).
                catalogEntries = runCatching { modelCatalogRepository.listModels() }
                    .getOrElse { emptyList() }
                _availableModels.value = projectAvailableModels(catalogEntries)

                // 2. Try to migrate legacy single-model layout if catalog has a
                //    default entry — saves users from redownloading after upgrade.
                val defaultEntry = catalogEntries.firstOrNull { it.isDefault }
                    ?: catalogEntries.firstOrNull()
                if (modelFilesRepository.activeModelId() == null && defaultEntry != null) {
                    modelFilesRepository.migrateLegacyToActive(defaultEntry.id)
                    if (modelFilesRepository.isModelDownloaded(defaultEntry.id)) {
                        modelFilesRepository.setActiveModelId(defaultEntry.id)
                    }
                }

                val activeId = modelFilesRepository.activeModelId()
                _activeModelId.value = activeId
                _availableModels.value = projectAvailableModels(catalogEntries)

                if (activeId == null) {
                    if (catalogEntries.isEmpty()) {
                        _modelBootstrapUi.value = ModelBootstrapUiState.Failed(
                            "No models in catalog. Configure KV `model:catalog` on the Worker."
                        )
                    } else {
                        _modelBootstrapUi.value = ModelBootstrapUiState.NeedsSelection(
                            available = projectAvailableModels(catalogEntries)
                        )
                    }
                    return@launch
                }

                // Active model selected. If files missing → re-trigger download.
                if (!modelFilesRepository.isModelDownloaded(activeId)) {
                    val entry = catalogEntries.firstOrNull { it.id == activeId }
                    if (entry == null) {
                        _modelBootstrapUi.value = ModelBootstrapUiState.Failed(
                            "Active model \"$activeId\" not in catalog. Pick another."
                        )
                        return@launch
                    }
                    val ensure = modelFilesRepository.ensureActiveModel(entry) { phase, rec, tot ->
                        _modelBootstrapUi.value =
                            ModelBootstrapUiState.Downloading(phase, rec, tot)
                    }
                    if (ensure is ModelFilesRepository.EnsureResult.Failed) {
                        _modelBootstrapUi.value = ModelBootstrapUiState.Failed(ensure.message)
                        return@launch
                    }
                }

                loadActiveModel()
            } catch (t: Throwable) {
                _modelBootstrapUi.value =
                    ModelBootstrapUiState.Failed(t.message ?: "Model bootstrap failed")
            } finally {
                refreshFreeStorage()
                _availableModels.value = projectAvailableModels(catalogEntries)
            }
        }
    }

    private suspend fun loadActiveModel() {
        val (mf, tf) = modelFilesRepository.activeModelPaths() ?: run {
            _modelBootstrapUi.value = ModelBootstrapUiState.Failed("No active model files found")
            return
        }
        val mgr = modelManagerFactory(mf, tf)
        modelManager = mgr
        when (val loaded = mgr.loadModel()) {
            is AppResult.Success ->
                _modelBootstrapUi.value = ModelBootstrapUiState.Ready
            is AppResult.Error ->
                _modelBootstrapUi.value = ModelBootstrapUiState.Failed(loaded.message)
        }
    }

    private fun projectAvailableModels(entries: List<CatalogModelEntry>): List<ModelInfo> {
        val activeId = modelFilesRepository.activeModelId()
        return entries.map { e ->
            ModelInfo(
                id = e.id,
                displayName = e.displayName,
                description = e.description,
                sizeBytes = e.sizeBytes,
                tags = e.tags,
                recommendedRamMb = e.recommendedRamMb,
                isActive = e.id == activeId,
                isDownloaded = modelFilesRepository.isModelDownloaded(e.id),
                onDeviceBytes = modelFilesRepository.onDeviceBytes(e.id)
            )
        }
    }

    private fun refreshFreeStorage() {
        _freeStorageBytes.value = modelFilesRepository.freeStorageBytes()
    }

    fun sendMessage(text: String) {
        if (_modelBootstrapUi.value !is ModelBootstrapUiState.Ready) return
        if (text.isBlank() || _uiState.value.isLoading) return
        val mgr = modelManager ?: return

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
                // Sliding window of recent turns (excludes the user msg + pending
                // assistant we just appended). Char budget enforced inside
                // ExecutorchModelManager so this is a soft cap.
                val priorTurns = _uiState.value.messages
                    .dropLast(2)
                    .takeLast(MAX_HISTORY_TURNS * 2)
                    .map { ChatTurn(fromUser = it.fromUser, text = it.text) }

                val cacheKey = if (priorTurns.isEmpty()) text else {
                    text + priorTurns.joinToString("|") { it.text.take(20) }
                }

                val cached = answerCache.get(cacheKey)
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

                mgr.streamResponse(promptForModel, priorTurns).collect { token ->
                    _uiState.update { state ->
                        val updated = state.messages.map { msg ->
                            if (msg.id == responseId) msg.copy(text = msg.text + token) else msg
                        }
                        state.copy(messages = updated)
                    }
                }
                val finalAnswer =
                    _uiState.value.messages.firstOrNull { it.id == responseId }?.text.orEmpty()
                if (finalAnswer.isBlank()) {
                    val hint =
                        "No text came back from the model. Check Logcat for ExecuTorch / native errors."
                    _uiState.update { state ->
                        val updated = state.messages.map { msg ->
                            if (msg.id == responseId) msg.copy(text = hint) else msg
                        }
                        state.copy(
                            isLoading = false,
                            isRetrieving = false,
                            messages = updated,
                            error = hint
                        )
                    }
                } else {
                    answerCache.put(cacheKey, finalAnswer)
                    _uiState.update { it.copy(isLoading = false, isRetrieving = false) }
                }
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
        stopGenerationInternal()
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

    private fun stopGenerationInternal() {
        runCatching { modelManager?.stopGeneration() }
        generationJob?.cancel()
        generationJob = null
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
        val pack = _availablePacks.value.firstOrNull { it.id == packId }
        if (pack == null) {
            _uiState.update {
                it.copy(error = "Pack \"$packId\" not in catalog. Open Knowledge and tap Catalog to refresh.")
            }
            return
        }
        if (_installingPackIds.value.contains(packId)) return
        viewModelScope.launch(Dispatchers.IO) {
            _installingPackIds.update { it + packId }
            try {
                workerPackRepository.installPack(pack)
                packStore.refresh()
                if (!packStore.hasInstalledPack(pack.id)) {
                    _uiState.update {
                        it.copy(
                            error = "Saved ${pack.id}.zip but on-device import failed. Run: adb logcat -s PackImporter PackStore"
                        )
                    }
                }
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
        stopGenerationInternal()
        viewModelScope.launch {
            modelManager?.unloadModel()
            modelManager = null
        }
        super.onCleared()
    }

    private companion object {
        // Last 4 user/assistant pairs shown in chat are fed back to model as
        // multi-turn context. Higher = better continuity but more tokens used.
        const val MAX_HISTORY_TURNS = 4
    }
}
