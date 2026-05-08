# Offgrid — Project Rules for Cursor

## What this app is
Offgrid is a mobile app that lets users download knowledge topic packs (guitar, chess, travel destinations, languages) on demand when connected to the internet, then use a fully on-device AI to query that knowledge completely offline. It combines a small quantized LLM, a local RAG pipeline, offline maps, and a smart knowledge fetching backend.

## Core principles
- Everything sensitive runs on-device. No user data, queries, or knowledge ever leaves the phone.
- The LLM model is fixed and bundled. Only the knowledge database (SQLite) grows.
- Optimize ruthlessly for mobile constraints: RAM, storage, battery, and cold start time.
- Offline-first. Every feature must work with zero network. Network is only used for downloading.
- Never suggest cloud inference, third-party AI APIs, or Firebase for core functionality.

## Platform targets
- Android first (Kotlin + Jetpack Compose)
- iOS second (Swift + SwiftUI), sharing the KMP core
- Minimum Android API: 26 (Android 8.0)
- Minimum iOS: 16

## Tech stack — never suggest alternatives unless asked

### Mobile
- Language: Kotlin (shared logic via KMP), Swift (iOS UI only)
- Android UI: Jetpack Compose
- iOS UI: SwiftUI (added in a later phase)
- Shared module: Kotlin Multiplatform (KMP)
- Build system: Gradle with KMP plugin

### On-device AI
- LLM runtime: ExecuTorch (Meta) — must use NPU/Neural Engine acceleration where available
- LLM model: Qwen3 1.7B Q4_K_M quantized (~1.2GB)
- Embedding model: all-MiniLM-L6-v2 INT8 (~23MB)
- Both models are bundled or downloaded on first launch — never fetched per-request

### Local storage
- Database: SQLite via SQLDelight (KMP-compatible)
- Vector search: sqlite-vec extension for similarity search
- One database file handles everything: chunks, embeddings, topic metadata, download state, map data
- Never suggest Room, Realm, or separate vector databases

### Offline maps
- Map rendering: MapLibre Native (Android + iOS)
- Map data: OpenStreetMap tiles, downloaded per destination
- POI and travel knowledge: Wikivoyage (CC BY-SA), Wikipedia summaries
- Never suggest Google Maps SDK or Mapbox (requires API key / internet)

### Networking (online features only)
- HTTP client: Ktor (KMP-compatible)
- Connectivity detection: kotlinx-coroutines + Android ConnectivityManager / iOS NWPathMonitor
- Topic pack downloads: chunked, resumable, WiFi-only by default

### Backend
- API: Hono framework on Cloudflare Workers (TypeScript)
- Storage: Cloudflare R2 (topic pack ZIPs, map tile bundles)
- Metadata/registry: Cloudflare KV
- Never suggest Express, AWS Lambda, or traditional servers
- Backend is thin — serves pre-built packs only, no live scraping per user

## Project structure
offgrid/
├── androidApp/          # Jetpack Compose Android UI
│   └── src/main/kotlin/com/offgrid/android/
├── iosApp/              # SwiftUI iOS UI (Phase 4+)
│   └── iosApp/
├── shared/              # KMP shared business logic
│   └── src/
│       ├── commonMain/kotlin/com/offgrid/shared/
│       │   ├── ai/          # LLM manager, embedding manager
│       │   ├── rag/         # Chunker, retriever, prompt builder
│       │   ├── db/          # SQLDelight schema, queries
│       │   ├── topics/      # Topic pack downloader, registry
│       │   ├── travel/      # Map data, destination packs
│       │   ├── network/     # Ktor client, connectivity manager
│       │   └── models/      # Data models, domain types
│       ├── androidMain/     # Android-specific implementations
│       └── iosMain/         # iOS-specific implementations
├── backend/             # Cloudflare Workers API (TypeScript/Hono)
│   ├── src/
│   │   ├── index.ts         # Hono app entry
│   │   ├── routes/          # Topic registry, pack serving
│   │   └── pipeline/        # Topic pack generation scripts
│   └── wrangler.toml
└── scripts/             # Local tooling, pack generation

## RAG pipeline — how it works
1. User requests a topic download (online)
2. App fetches pre-built topic pack ZIP from Cloudflare R2
3. ZIP contains: text chunks (JSON) + pre-computed embeddings (binary)
4. App stores chunks and embeddings in SQLite + sqlite-vec
5. User asks a question (online or offline)
6. Query is embedded using on-device MiniLM
7. sqlite-vec finds top-5 most similar chunks
8. Chunks injected into LLM prompt as context
9. Qwen3 1.7B generates a response
10. Response streamed token by token to UI

## Connectivity modes
- WiFi: full downloads permitted, street-level map tiles, rich content
- Mobile data: block large downloads, allow lightweight metadata sync, show queue UI
- Offline: pure local AI, zero network calls, all features work from local DB
- Detection must happen before any network call — check first, act second

## Battery optimisation rules
- Always route inference through NPU/Neural Engine — never CPU-only unless fallback
- Unload LLM from memory when app is backgrounded for >30 seconds
- Cache last 20 question-answer pairs in memory to avoid repeat inference
- Embedding model stays loaded — it's tiny (23MB) and needed for retrieval
- Never run downloads in the background without explicit user permission

## Content and licensing rules
- Only use open-licensed sources: Wikipedia (CC BY-SA), Wikivoyage (CC BY-SA), OpenStreetMap (ODbL), OpenStax, musictheory.net (verify per use)
- Store license metadata per chunk in the DB
- Attribution must be shown somewhere in the UI per pack
- Never scrape sites without verifying their terms first

## Model licensing
- Qwen3: Apache 2.0 — commercial use permitted
- all-MiniLM-L6-v2: Apache 2.0 — commercial use permitted
- MapLibre: BSD 2-Clause — permitted
- SQLite: public domain — permitted

## Code style
- Kotlin: follow official Kotlin coding conventions
- No Hungarian notation
- Coroutines for all async — never callbacks or RxJava
- Use Flow for reactive streams
- Dependency injection: Koin (KMP-compatible, lightweight)
- Error handling: sealed Result types, never raw exceptions to UI
- All DB operations on IO dispatcher, never Main
- UI state: StateFlow in ViewModels

## What to never suggest
- Firebase (requires internet, sends data to Google)
- Google Maps SDK (requires API key, internet)
- Retrofit (not KMP-compatible — use Ktor)
- Room (Android-only — use SQLDelight)
- Any cloud AI API for inference (OpenAI, Anthropic, Gemini)
- RxJava (use coroutines/Flow)
- AsyncTask (deprecated)
- Any solution that requires persistent internet for core features

Part 2 — Phase 0 Task Prompt
Drop this into Cursor chat when you're ready to start building:
I'm building Offgrid — a mobile app that runs a small LLM fully on-device
for offline AI. Read the project rules file before doing anything.

We are in Phase 0. The only goal of this phase is:
Get ExecuTorch running inside an Android app built with Kotlin + Jetpack
Compose, load the Qwen3 1.7B Q4 model, and display a working chat screen
where I can type a message and get a streamed response from the model.

Nothing else. No RAG, no downloads, no maps, no backend. Just inference working.

Do this in order:

1. Scaffold the KMP project with the folder structure from the rules file.
   Android app only for now. shared/ module with commonMain and androidMain.
   Use Gradle with the KMP plugin. Target Android API 26+.

2. Set up the Android app with Jetpack Compose. Basic theme, no design system
   yet. Single activity, single screen.

3. Add ExecuTorch as a dependency. Use the official Maven artifact if available,
   otherwise set up the .aar integration. Show me exactly how to do this — do
   not guess, check the latest ExecuTorch Android integration docs approach.

4. Create a ModelManager class in shared/androidMain that:
   - Loads a .pte model file from the app's assets or local storage
   - Runs inference with a string input
   - Returns tokens as a Flow<String> for streaming
   - Handles load/unload lifecycle correctly
   - Routes to NPU/NNAPI where available, falls back to CPU

5. Create a simple ChatViewModel in the Android app that:
   - Exposes a StateFlow<ChatUiState> with message list and loading state
   - Calls ModelManager and collects the streaming Flow
   - Handles errors with a sealed Result type

6. Build a minimal ChatScreen composable with:
   - Scrollable message list
   - Input field at bottom
   - Send button
   - Streaming response renders token by token as it arrives
   - Loading indicator while model is processing

7. For now, use a tiny placeholder .pte model or mock the ModelManager
   with a fake streaming response so I can verify the UI and ViewModel
   work correctly before the real model is integrated.

Tell me what you need from me at each step (SDK versions, gradle versions,
any manual downloads). Do not skip steps or combine them. One step at a time.

Part 3 — Phase 1 Prompt (save for later)
When Phase 0 is done and inference is working, drop this in:
Phase 0 is complete. Inference is working. Now we build Phase 1: the RAG pipeline.

Goal: the app can answer questions using locally stored knowledge chunks,
not just the model's built-in knowledge.

Steps:

1. Add SQLDelight to the shared KMP module. Set up the database schema with
   these tables:
   - topics (id, name, description, source, license, downloaded_at, chunk_count)
   - chunks (id, topic_id, content TEXT, token_count INT)
   - embeddings (chunk_id, vector BLOB) — this will be queried via sqlite-vec
   - cache (query_hash, answer, created_at) — for answer caching

2. Integrate sqlite-vec as a SQLite extension. Show me how to load it at
   runtime on Android. The vector column should store float32 embeddings
   of dimension 384 (MiniLM output size).

3. Create an EmbeddingManager in shared/androidMain that:
   - Loads all-MiniLM-L6-v2 INT8 ONNX model via ONNX Runtime Android
   - Takes a string input, returns FloatArray of 384 dimensions
   - Stays loaded in memory (never unload — it's only 23MB)

4. Create a RAGPipeline class in shared/commonMain that:
   - Takes a user query string
   - Embeds the query via EmbeddingManager
   - Queries sqlite-vec for top 5 most similar chunks for the active topic
   - Builds a prompt: system context + retrieved chunks + user question
   - Returns the assembled prompt string to be passed to ModelManager

5. Update ChatViewModel to:
   - Run the RAG pipeline before calling ModelManager
   - Show a subtle "searching knowledge..." state while retrieving
   - Pass the RAG-assembled prompt to inference, not the raw user message

6. Write a simple DataSeeder that I can trigger from a debug button:
   - Takes a hardcoded list of 10 guitar theory text chunks
   - Embeds each one using EmbeddingManager
   - Stores chunks + embeddings in SQLite
   - So I can test retrieval without building the download system yet

7. Add an answer cache: before running RAG + inference, hash the query,
   check the cache table, return cached answer if found (TTL: 24 hours).

Test this by asking "what is a G chord" and verifying the retrieved chunks
contain guitar knowledge and the model uses them in its answer.