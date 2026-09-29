# Offgrid: restart brief

Reviewed 26 September 2026. Based on the current source, not only the older development notes. “Implemented” below means code exists and is wired; it does not mean verified on a phone in this review. No application code was changed.

## Product direction

**Your AI, on your phone. Works offline. Connects to the web when you need it.**

Keep inference entirely on-device. Use internet access to acquire information: search results, readable pages, and downloadable knowledge. The local model interprets that information and generates the response.

The product opportunity is to make a small model useful through good context, reliable tools, and focused workflows. Model selection belongs in settings; everyday users should start with actions such as “Summarize,” “Explain,” “Ask my library,” and “Search the web.”

Privacy wording must distinguish local inference from network activity: web searches send a query to a provider, and reading pages contacts websites. Do not promise that nothing ever leaves the phone when web access is enabled. Send only the information needed for the selected tool, with visible controls.

## What is implemented

| Area | Current implementation | Limits |
| --- | --- | --- |
| Android app | Kotlin, Jetpack Compose, Chat / Knowledge / Settings tabs; Android API 26+ | KMP has only an Android target. No iOS, desktop, or browser app. |
| Local generation | ExecuTorch 1.1.0 integration, model loading, streamed responses, stop, repetition guards, output cleanup | Device behavior not revalidated. No explicit hardware-delegate selection or background memory policy found. |
| Conversation | Recent four user/assistant pairs passed to generation; 8,000-character history budget; configured total sequence length 4,096 | Messages live in memory. No saved conversations or durable memory. Character limits do not guarantee token limits. |
| Model management | First-run picker, catalog fetch, download progress, SHA-256 checks on downloads, Range-based resume attempt, switching, deletion of inactive models, storage display, legacy migration | Catalog lists Qwen3 1.7B, Llama 3.2 1B, SmolLM2 135M. Catalog presence is not proof of compatibility; one ChatML template is used for all. |
| Knowledge library | Catalog browsing/filtering, ZIP download, import, installed list, deletion, refresh, sideload support | Existing imported IDs are skipped regardless of newer ZIP versions. |
| Local retrieval | Per-pack SQLite, FTS5 BM25 with FTS4 fallback; top two chunks injected with source labels; roughly 3,000-character context budget | Despite the name `HybridRetriever`, this is lexical retrieval. Pack embeddings are stored but unused. Query normalization strips non-English letters. |
| Answer cache | In-memory cache with 24-hour TTL | No model/pack-version invalidation; history key uses only 20 characters per previous message. |
| Knowledge Factory | YAML → Wikipedia fetch → clean text → section-based chunks → BGE-small embeddings → ZIP; R2/KV publishing code | Five topic configs: chess, guitar, Japan travel, first aid, knots. Generated packs and publishing were not verified. |
| Backend | Hono Worker, model catalogs/manifests, pack listing/search/metadata/download routes, R2 byte serving, health route | Deployment and live catalog availability unverified. `backend/src/pipeline/buildPack.ts` is still a stub; use `backend/factory/` for the actual builder. |
| Response UI | Streaming text, basic bold/headings/bullets, retrieval status | Code fences are removed entirely. Source labels are text, not inspectable citation cards. |

Not implemented: live web search or browsing in chat, tool dispatch, personal document import, share-to-app flow, OCR, voice input/output, calculator tools, durable chat history, editable memory, offline maps, semantic/vector retrieval, or cross-device sync.

Current flow:

```text
Question → answer cache → local pack search → context + recent turns
         → ExecuTorch on phone → streamed answer

Internet today → model files and knowledge packs only
```

## Fix before expanding

1. **Restore missing shared types.** The source imports `com.offgrid.shared.models` types including `ChatMessage`, `ChatUiState`, `ChatTurn`, `AppResult`, and model bootstrap/UI types, but their definitions are absent. `.gitignore` line 45, `models/`, also ignores this Kotlin package; `git check-ignore` confirms it. Narrow the artifact ignore rule and recover or recreate the source. The checkout is incomplete for compilation.
2. **Fix lifecycle ownership and persist chats.** `MainActivity` constructs `ChatViewModel` directly rather than through a lifecycle ViewModel provider. Its cleanup is not properly tied to a ViewModel store, and activity destruction closes the pack store. Define ownership, cancellation, native unload, and rotation behavior before adding more jobs.
3. **Make model configuration explicit.** Store the chat template, tokenizer format, stop tokens, and supported context length per model. Confirm each artifact/runtime combination on a real device. `export_add.py` exports a tensor-addition demo under a Qwen-like filename; it is not an LLM exporter.
4. **Fix streaming and context handling.** Cleanup rewrites accumulated text while the UI only appends deltas; removed reasoning can already have been displayed. Use a streaming parser and tokenizer-aware budgeting, reserving space for the answer. Preserve useful formatted content.
5. **Harden installs and cache correctness.** Validate model/pack IDs before constructing filesystem paths; validate pack content rather than trusting the ZIP's embedded checksum string; make imports version-aware. Invalidate cached answers when model, sources, or conversation changes. The Worker's R2 route does not implement Range handling, although the downloader requests it.

No tracked automated tests were found. Java and adb were not available on PATH, `local.properties` was absent, and backend dependencies were not installed. No build, device benchmark, live service check, or publishing run was performed. These are source-review findings, not a reproduced build log.

## Highest-value feature ideas

| Priority | Experience | How it helps a small model |
| --- | --- | --- |
| 1 | **Share to Offgrid:** share text or a URL; summarize, explain, extract a checklist, or ask questions | Supplies a specific task and relevant source instead of relying on model recall. Start with text and readable public pages. |
| 1 | **Search and read:** explicit Web button; retrieve a few results, read selected pages, answer with source cards | Supplies current facts while generation remains local. Show titles, links, and retrieval times. |
| 1 | **Save for offline:** store a useful page in a personal library and ask about it later | Turns connected use into lasting offline value; extends the existing knowledge architecture. |
| 2 | **Reliable utilities:** calculator, percentages, units, dates | Deterministic code produces results; the model explains them. |
| 2 | **Task cards:** rewrite, summarize, compare, explain, extract action items | Short templates and structured outputs reduce ambiguity. |
| 2 | **Personal documents:** notes and text PDFs, with selectable collections | Retrieval focuses answers on the user's material. Add OCR after text import works. |
| 3 | **Study tools and editable memory:** flashcards from sources, saved preferences with forget controls | Reuses knowledge and gives continuity without stuffing every past message into context. |
| Later | Voice, camera input, travel maps, additional platforms | Useful extensions after the core workflow meets quality and device-performance targets. |

Recommended first audience hypothesis: people reading and learning on their phones who want to keep useful material. Validate it with a few users before committing to a broad assistant product.

## Architecture for the next version

Add a small orchestrator between `ChatViewModel` and `ModelManager`:

```text
User action → explicit task/tool selection → local tools or web fetch
            → cleaned, bounded evidence → local model → answer + source cards
                                        ↘ optional save to local library
```

Start with app-controlled workflows, then evaluate model-selected tools. Define typed tools such as `searchLocal`, `searchWeb`, `readPage`, `calculate`, and `saveSource`, with validated inputs, output limits, timeouts, and a maximum step count. Treat page content as untrusted evidence; it cannot authorize additional tools or access private data.

Keep the existing ExecuTorch boundary while measuring it. Reuse the pack factory's extraction/chunking concepts, but its Node pipeline cannot simply execute inside Android. A search proxy could extend the Worker to protect provider credentials; it would handle search requests, not inference. Choose the provider after checking quality, cost, and data handling.

Offer “Device only” and “Web allowed” controls. With an installed model, offline startup should proceed without waiting for catalog requests. When offline, disable live search clearly and allow saved sources; label their age. Keep source metadata separate from model-generated text so citations can be validated.

## Suggested build order

1. **Restore the baseline:** shared types, build setup, lifecycle, one verified model, saved chats, airplane-mode startup, and basic performance measurements.
2. **Ship one complete experience:** shared text/URL → local summary → follow-up questions → source card → save → answer from saved content offline.
3. **Add search and utilities:** explicit web search, bounded page reading, calculator, task cards, and visible connection controls.
4. **Tune from evidence:** measure answer correctness, citation support, first-token latency, RAM, and sustained-session heat on target phones. Add semantic retrieval or change models only when measured failures justify it.

Use a small evaluation set covering direct answers, follow-ups, retrieval misses, source conflicts, calculation, unavailable internet, cancellation, and long input. Compare plain-model answers against tool-assisted answers on the same tasks.

## Documentation and references

`version1.md` is historical: downloads, settings, history, and backend routes have advanced beyond its description. `.skills/rules/offgrid.md` also describes unimplemented architecture and says network is only for downloads; the new product direction supersedes that constraint. Update these rules when implementation resumes so future work follows the intended product.

For current platform context, Android documents on-device models as suited to focused tasks and custom model runtimes: [Android AI overview](https://developer.android.com/ai/overview). ExecuTorch's current [Android LLM guide](https://github.com/pytorch/executorch/blob/main/docs/source/llm/run-on-android.md) is a reference for revalidating runtime integration; it is not evidence that this repository's pinned version or artifacts work. The roadmap above is a proposal, not a claim that a framework supplies these features automatically.
