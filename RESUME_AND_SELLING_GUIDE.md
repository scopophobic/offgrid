# Offgrid: Resume Guide & Selling Strategy

This document provides exact resume bullet points, interview talking points, engineering highlights, and actionable plans to make **Offgrid** stand out to engineering hiring managers.

---

## 1. Resume Bullet Points

Choose the framing that best fits the roles you are targeting (Android, Mobile AI / Edge ML, or Full-Stack / Systems).

### Option A: Edge AI & Mobile Systems Focus (Strongest / Recommended)
> **Offgrid – Offline-First On-Device AI Assistant** | *Kotlin, Jetpack Compose, KMP, PyTorch ExecuTorch, SQLite FTS5*
> - Engineered a 100% offline, privacy-first Android assistant executing quantized LLMs (Qwen 2.5 / SmolLM2) locally via **PyTorch ExecuTorch**, eliminating cloud latency and API costs.
> - Implemented an on-device Retrieval-Augmented Generation (RAG) pipeline leveraging **SQLite FTS5 BM25** search to ground model outputs in topic packs and custom user documents.
> - Built a robust document processing engine using **PDFBox-Android** with memory-bounded chunking (<12 MB / 500k chars) to ingest text PDFs and Markdown into localized collections.
> - Architected token-by-token reactive UI streaming with **Kotlin Coroutines and StateFlow**, handling native interrupt callbacks and citation auditing in Jetpack Compose.
> - Hardened network boundaries with a strict SSRF-defended HTTP client (blocking internal IP ranges/redirect exploits) for optional, ephemeral web queries.

### Option B: Core Android / Kotlin Multiplatform Focus
> **Offgrid – Privacy-Centric On-Device Knowledge & AI App** | *Android, Kotlin Multiplatform, Compose, Coroutines*
> - Designed clean multi-module architecture separating shared retrieval and model orchestration (`shared`) from Jetpack Compose UI (`androidApp`).
> - Integrated Android native `SpeechRecognizer` (on-device engine) and local Text-to-Speech (TTS) for completely offline voice interactions.
> - Developed deterministic edge utility tools (safe arithmetic parser, unit converter, date math) to guarantee zero-hallucination answers for calculation queries.
> - Optimized app lifecycle handling, persistent SQLite storage, and background model file downloads with SHA-256 verification and resume support.

---

## 2. Technical Decisions & Interview Stories (The "Why")

When interviewers ask *"Tell me about a challenging project,"* use these structured STAR-method stories:

### Story 1: Running LLMs within mobile resource constraints
- **Problem**: Mobile devices have strict RAM limits, thermal throttling, and background app kills. Large models crash or lag the UI thread.
- **Solution**: Decoupled ExecuTorch JNI inference from the main UI thread onto background dispatchers with reactive `callbackFlow`. Pinned context windows with strict token and character budgeting (keeping context under ~3,000 characters). Implemented early model unload hooks and native cancellation tokens.

### Story 2: Offline RAG without heavyweight vector databases
- **Problem**: Full vector stores (Chroma, Milvus, or large vector search libraries) and mobile embedding models add huge binary bloat and battery drain on low-end devices.
- **Solution**: Engineered a hybrid BM25 lexical retrieval engine using SQLite FTS5 directly on device. Grouped information into pre-compiled topic packs (`chunks.db` with indexed section headers), retrieving top-K passages with precise citation chips.

### Story 3: Privacy and SSRF Defense
- **Problem**: Web-enabled LLM assistants are vulnerable to data leakage and SSRF attacks if fetching URLs.
- **Solution**: Enforced a zero-leakage sandbox where web keys (Brave Search) are session-only and never written to disk or logs. Configured a custom OkHttp DNS resolver checking against loopback, link-local, multicast, and private CIDR ranges to prevent attacks against internal networks.

---

## 3. How to Make This Project Even More Sellable

To take this project from "impressive hobby project" to "top 1% candidate portfolio piece", execute these 4 initiatives:

### Step 1: Record 3 Short High-Quality Visual Artifacts (Crucial)
Hiring managers spend ~30 seconds on a GitHub repo. Photos and videos immediately prove it actually works on a real device.
- **GIF 1: Airplane Mode Proof**: Open Android quick settings, turn ON Airplane mode, ask a question, and show tokens streaming instantly.
- **GIF 2: PDF Import & RAG Citation**: Import a technical PDF manual, ask a query about it, and show the app answering with the exact page chip `[Page 4]`.
- **GIF 3: Deterministic Tools / Offline Voice**: Dictate a math or unit conversion problem offline and get an instant computed answer.

### Step 2: Add Real Benchmark Numbers
Add a small "Benchmarks" table in your README showing actual device metrics:
| Device | Model | Token Speed (tok/s) | Peak RAM | First-Token Latency |
| :--- | :--- | :--- | :--- | :--- |
| Pixel 7 / Galaxy S23 | Qwen 2.5 1.5B (Q4) | ~8.2 tok/s | ~1.4 GB | 620 ms |
| Mid-range (Snapdragon 7xx) | SmolLM2 135M (Q4) | ~24 tok/s | ~380 MB | 180 ms |

*(Even rough measurements from Android Studio Profiler or log timestamps add immense credibility.)*

### Step 3: Write an Architecture / Engineering Blog Post
Write a 5-minute technical writeup on Medium, Dev.to, or your personal blog titled:
> *"Building a Zero-Cloud LLM Assistant on Android with ExecuTorch and SQLite RAG"*
Link this blog post at the top of your GitHub README. Recruiters love developers who can communicate technical design decisions clearly.

### Step 4: Sideload / Release an APK
Publish a signed release APK or GitHub Release with one sample model (or instructions for the in-app picker). Having a downloadable APK link on the repo turns a code dump into a shipped product.
