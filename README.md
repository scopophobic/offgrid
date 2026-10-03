# Offgrid

A private, offline-first Android assistant powered by on-device LLMs (ExecuTorch), local RAG, deterministic travel tools, and optional ephemeral web capabilities.

Inference runs 100% locally on your phone. No tracking, no user data sent to remote LLM servers, and full functionality in airplane mode.

---

<!-- Replace these placeholders with your actual screenshots/GIFs -->
<!--
<p align="center">
  <img src="docs/screenshots/chat_stream.png" width="30%" alt="Streaming on-device chat" />
  <img src="docs/screenshots/knowledge_pack.png" width="30%" alt="Knowledge Packs & RAG" />
  <img src="docs/screenshots/tools_library.png" width="30%" alt="Document Reader & Tools" />
</p>
-->

## Key Capabilities

- **Zero-Cloud Inference**: Runs quantized open models (Qwen 2.5 / SmolLM2 / LLaMA 3.2) locally via PyTorch's **ExecuTorch** runtime with hardware delegation support.
- **Offline Field RAG**: SQLite FTS5 lexical retrieval against pre-compiled or custom topic packs (e.g. wilderness first aid, navigation, travel guidebooks) within strict context and token budgets.
- **Personal Document Library**: On-device parsing for text, Markdown, and text PDFs (up to 200 pages / 12 MB) using Android PDFBox. Organizable into custom offline collections.
- **Deterministic Tool Engine**: Built-in math parser, unit converter (distance, mass, volume, temperature), and date math executed purely in Kotlin code without LLM hallucination risk or network requests.
- **Strict Privacy & Web Toggle**: Web connectivity is opt-in, session-only, and separated from inference. When enabled, it searches Wikipedia or Brave Search and fetches public HTTPS pages for the local model to read and summarize. API keys and personal files never leave the device.
- **100% On-Device Voice**: Dictation using Android's native offline speech recognizer (`SpeechRecognizer.createOnDeviceSpeechRecognizer`), paired with local Android Text-to-Speech (TTS).

---

## Architecture & Data Flow

```text
[ User Prompt / Shared Doc ]
           │
           ▼
┌──────────────────────────────────────┐
│       ChatViewModel / Store          │
│   (StateFlow, SharedPreferences)     │
└──────┬────────────────────────┬──────┘
       │                        │
       ▼                        ▼
┌──────────────────┐    ┌───────────────────────────────┐
│ Local Tools      │    │ Local RAG (HybridRetriever)   │
│ - Math parser    │    │ - SQLite FTS5 (BM25 ranking)  │
│ - Unit converter │    │ - Curated knowledge packs     │
│ - Date delta     │    │ - Personal imported documents │
└──────────────────┘    └──────────────┬────────────────┘
                                       │
                                       ▼ (Grounding Context)
                        ┌───────────────────────────────┐
                        │ ExecuTorch Model Manager      │
                        │ - Quantized .pte weights      │
                        │ - Streaming token callback    │
                        │ - Citation reference check    │
                        └──────────────┬────────────────┘
                                       │
                                       ▼ (Token Stream)
                        ┌───────────────────────────────┐
                        │ UI (Jetpack Compose)          │
                        │ - Interactive citation chips  │
                        │ - Offline voice / TTS         │
                        └───────────────────────────────┘
```

---

## Technical Highlights

| Component | Stack | Details |
| :--- | :--- | :--- |
| **Model Runtime** | ExecuTorch 1.1.0 | Loads `.pte` models directly into memory; supports token-by-token streaming callbacks and native interrupt/cancellation. |
| **App Architecture**| Kotlin Multiplatform (KMP) + Compose | Architecture separating shared retrieval/model logic (`shared`) from Android UI (`androidApp`). |
| **On-Device Storage**| SQLite FTS5 / Room / Android Keystore | Offline full-text search with token cleanup and BM25 ranking. |
| **Document Parsing**| PDFBox-Android | Memory-bounded chunking and page-labeled extraction (up to 500,000 characters). |
| **Network Security**| OkHttpClient + Dns filter | Strict SSRF defense blocking private/loopback/multicast IPs; enforces HTTPS and limits response buffers to 2 MB. |

---

## Getting Started

### Prerequisites
- **Android Studio Ladybug | 2024.2+** or compatible.
- **JDK 17** or newer.
- Physical Android phone running **Android 8.0 (API 26)** or higher (Android 12+ required for native offline dictation). A physical device with at least 6 GB of RAM is strongly recommended for running 1.5B+ models smoothly.

### Build & Run
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/offgrid.git
   cd offgrid
   ```
2. Build debug APK:
   ```bash
   ./gradlew :androidApp:assembleDebug
   ```
3. Run unit tests:
   ```bash
   ./gradlew :androidApp:testDebugUnitTest
   ```

### Loading Models
Models can be downloaded in-app via the first-run picker or sideloaded directly onto your phone via adb:
```bash
adb push path/to/model.pte /sdcard/Android/data/com.offgrid.android/files/models/
```

---

## Roadmap

- [x] On-device ExecuTorch LLM integration & streaming tokens
- [x] FTS5-backed local knowledge pack indexing and RAG
- [x] Personal document ingestion (PDF, MD, TXT)
- [x] Offline voice dictation and TTS playback
- [x] Deterministic calculation and unit tools
- [ ] On-device embeddings & vector search (HNSW / ONNX)
- [ ] Hardware acceleration delegates (NPU / GPU via Vulkan / QNN)
- [ ] Multi-turn citation semantic verification
