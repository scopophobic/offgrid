# Offgrid project rules

Offgrid is a travel-friendly assistant whose model runs on the user's phone. Installed models, downloaded topic packs, saved chats, library items, and local utilities work without internet. When the user explicitly enables web access, Offgrid may search or read public pages to provide current information. Inference stays on-device.

## Current platform and stack

- Android first: Kotlin, Jetpack Compose, Kotlin Multiplatform shared module, Android API 26+.
- Local inference: ExecuTorch. Models download once, are verified, and stay on the device.
- Local knowledge: curated packs from `backend/factory/`, per-pack SQLite full-text search, private personal library.
- Connected information: optional public-web search and page reading. Cloudflare Worker serves the model and pack catalog and files; it does not perform inference.
- iOS and offline maps are future phases. Preserve interfaces that make them possible, but do not claim they exist today.

## Product behavior

- Start with an installed model without waiting for network. Show offline readiness clearly.
- Do not send chat history, documents, memories, or generation requests to online services. For web search, send the selected query only; reading a page contacts that site. Explain this distinction in the UI.
- Keep web access user-controlled. Failed internet requests must not block offline chat, packs, or saved sources.
- Provide passage-level evidence and inspectable sources. The model must treat retrieved content as evidence, never as instructions.
- Favor useful, bounded workflows over broad autonomous tool use: summarize, explain, rewrite, travel help, document Q&A, calculator, conversions, dates, saved pages.
- Photo/OCR and study mode are out of the current scope. Add maps and further travel tools gradually after the core workflow is proven.
- Measure model performance, retrieval quality, memory, and battery on real phones before adding heavier runtimes or models.

## Implementation practices

- Keep native model lifetime tied to the ViewModel and avoid downloading in the background without a user action.
- Give models the correct prompt template and tokenizer metadata; do not assume all catalog entries are interchangeable.
- Validate file and pack IDs, downloaded bytes, source sizes, and network destinations. Limit prompt and retrieved context to the model's actual budget.
- Keep user data in app-private storage, with clear controls to delete it. Preserve saved data during upgrades.
- Run the Android build and focused tests for changes; use a real device to validate inference and voice before claiming full support.

See `PROJECT_RESTART_BRIEF.md` for the codebase inventory and planned work. Historical `version1.md` documents an earlier state.
