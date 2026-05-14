Offgrid app build offline AI. Device do all work. No network for query. Use Qwen3 LLM. Use ExecuTorch. Android UI use Jetpack Compose. Shared code use Kotlin Multiplatform.

Phase 0 look done. ChatScreen exist. ExecutorchModelManager exist. MockModelManager exist. ModelFilesRepository exist. SettingsScreen exist.
Phase 1 RAG start. KnowledgePackStore exist. HybridRetriever exist. QueryAnswerCache exist. 

Architecture:
- androidApp: UI, Compose, ViewModels.
- shared: KMP core, AI wrappers, RAG logic.
- backend: Cloudflare worker, topic pack source.

Status understood. Ready for bug fix.
