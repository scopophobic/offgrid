# Offgrid UI and frontend design plan

> **Current visual direction:** [UI_DIRECTION_V2.md](UI_DIRECTION_V2.md) supersedes this document's V1 palette, typography, surface styling, and tone following the 28 September feedback. Keep the feature coverage and behavior requirements below as the implementation checklist. [Figma V2](https://www.figma.com/design/wIXsEBGs2VDya44Vg5bFUZ?node-id=10-3).

Proposed design • 27 September 2026 • Native Android / Jetpack Compose

## Product and design direction

Your AI, on your phone. A quiet personal field notebook for thinking, saving useful information, and using it anywhere. Optimize for the repeat journey: open → ask using saved material → inspect evidence → keep an answer. Secondary journey: find information while connected → save → use offline.

The visual direction is **Field Notes**: warm paper, deep forest, soft sage, restrained terracotta, generous reading space. Use clean sans-serif UI typography and an occasional serif welcome headline. Avoid decorative chat bubbles around assistant responses, noisy dashboards, oversized branding, and repeated status banners. Content is the centerpiece.

This is a design proposal, not a claim of implemented behavior. FEATURES.md and current Kotlin source are the baseline; PROJECT_RESTART_BRIEF.md contains older limitations that no longer all apply. Preview content and counts are illustrative.

## Current frontend findings

- MainActivity.kt defines a light-only theme inline; ChatScreen.kt duplicates colors and owns navigation, chat, and knowledge UI. Extract theme and screen responsibilities before visual changes grow.
- Five bottom destinations split related content between Knowledge and Library. Combine discovery and saved content under one Library destination while retaining their distinct provenance.
- The repeated uppercase masthead and global banners consume space above the task. Use a small app bar and show actionable status near the affected control.
- AssistantFeatures.kt presents many answer actions in a strip. Prioritize Copy and Save; put Share, Listen, Shorter, and Simpler in an accessible overflow sheet.
- Existing functionality includes history, document imports, source inspection, personal packs, deterministic tools, and optional web access. Preserve these during migration.

## Information architecture

Bottom navigation: **Chat / Library / Tools**. Settings is an always-available top-bar action on root screens. History is in the Chat top bar. Back from a detail returns to the previous destination with its scroll position intact. Android Back dismisses a sheet first, then returns through the destination stack.

Library has **Saved / Discover** segments. Saved includes personal field packs, ungrouped items, and installed curated packs. Label personal content “Personal pack” and downloaded catalog content “Knowledge pack”; never imply they have the same author or update policy. Discover is the existing Knowledge catalog. Search stays within the selected segment.

Tools includes Calculate, Convert, Date difference, and Web. Web remains explicitly opt-in. Model management stays in Settings, with a setup shortcut only when chat cannot run.

## Visual tokens

| Role | Light | Dark |
| --- | --- | --- |
| Background | #F7F7F0 | #111B16 |
| Surface | #FFFFFF | #1A2720 |
| Raised / selected surface | #E8EEE3 | #293C30 |
| Main text | #192D23 | #EFF3E9 |
| Secondary text | #536357 | #B7C5B9 |
| Primary action | #24553D | #B8D7A8 |
| On primary | #FFFFFF | #14251B |
| Divider | #D5DDD1 | #3D5042 |
| Caution text | #874022 | #F1B28C |
| Error text | #B3261E | #FFB4AB |

Verify contrast for each actual pairing during implementation. Target 4.5:1 for body text and 3:1 for large text and meaningful UI boundaries. Status always includes a label or icon, not color alone.

Typography: bundled Roboto or platform sans for body and controls; optional bundled serif for welcome headlines only. Body 16sp/24sp; supporting text 14sp/20sp; labels 12sp/16sp; screen title 28sp/34sp; welcome 32sp/38sp. Medium emphasis rather than heavy all-caps. No network fonts in the Android app.

Spacing: 4, 8, 12, 16, 24, 32dp. Screen gutters 20dp compact, 24–32dp expanded. Minimum touch target 48dp. Shapes: inputs 16dp, cards 20dp, sheets 28dp at top. Reserve pills for status and small context selectors. Use tonal surfaces and thin dividers; elevation only for sheets and floating controls. Use one coherent family of Material vector icons in Compose.

## Screen specifications

| Screen | Layout and primary action | Secondary behavior |
| --- | --- | --- |
| Welcome | One short value statement, local generation explanation, Set up assistant | Explain that model downloads and optional web tools use internet; no account wall |
| Model setup | Recommended compatible model with download size, available storage and capability caveats; Download | Other models collapsed; show real catalog values, never invented compatibility claims |
| Setup download | Named stages: Downloading, Verifying, Preparing; determinate progress only with known totals | Expose only supported cancel/resume behavior; retry errors without losing valid progress |
| Chat empty | Compact app bar, device/web status, welcoming headline, Summarize / Explain / Ask library task starters, recent conversation | Composer available immediately when model is ready; avoid large empty illustration |
| Chat active | User message on sage surface; assistant response directly on paper; readable Markdown; source references beneath answer | Copy and Save inline; other actions in More; New chat and History in app bar |
| Context picker | Sheet with All saved content / pack / document, search and current selection | Visible removable context chip above composer; selection must persist for the intended chat scope |
| History | Search, Today / Earlier groups, title and small excerpt | Rename and delete in item menu; confirm permanent deletion when recovery is unavailable |
| Source detail | Title, origin, saved/read date, page number if present, retrieved passage | Save offline and open original when a URL exists; explicitly say evidence is not independent verification |
| Library Saved | Search, featured personal pack, recent items, Import action | Create pack and paste note in Add menu; item count and offline state in each row |
| Personal pack | Name, item count, item list; Ask this pack | Add items, rename, and clearly scoped deletion; distinguish removing a collection from deleting its contents |
| Document reader | Title, type, saved date, readable extracted text; Ask about this | Summarize, move to pack, share, delete; long summary says it uses selected excerpts |
| Library Discover | Search and topic filters; restrained catalog cards showing size and install status | Cached catalog still browsable offline when available; downloads unavailable offline |
| Knowledge pack detail | Description, content origin, size, installed/version information when available; Download / Ask this pack | Progress, retry, remove local copy; do not imply automatic updates |
| Tools | Simple list or 2-column compact tiles for four tool families | Mark arithmetic/conversions/dates as computed locally; web status remains separate |
| Calculator / units / dates | Focused labeled form, clear result and units, copy | Inline validation, keyboard appropriate to input; no model required |
| Web permission | Explain query provider and contacted sites; explicit Allow web | Keep local generation status separate; permission is not proof of connectivity |
| Web search and reader | Query, provider, results, page preview; Read then Save offline | No-result and failed-extraction states; selected page can be summarized locally |
| Android share intake | Shared text or URL preview and task choices | Text becomes a draft; URLs require explicit web permission/read; imported file errors return to intake |
| Settings | Assistant, Appearance, Voice, Storage, Privacy groups | Preferences editable with Forget all; model catalog details nested under Assistant |
| Model management | Active model, other installed models, catalog and storage | Switching/loading/errors explicit; prevent unsupported deletion of active model |
| Storage | Model, curated pack and personal content usage from measured values | Review individual content before deletion; explain chats/preferences are separately managed |

## Key interaction flows

1. First run → choose model → download and verify → ready → first question. Users may access deterministic tools or already-saved content while generation is unavailable where supported.
2. Library → import text PDF → readable content preview → select pack → Ask about this → inspect cited passage → Save answer.
3. Tools → Web → allow access → search → read page → save offline → later ask that saved item with web disabled.
4. Chat → context picker → personal pack → question → streamed answer → source detail. Retrieval miss says “No matching saved passages found”; do not silently present general model recall as document evidence.
5. Share into app → preview → choose task → edit draft → send. Retain the draft across recoverable errors and rotation.

## State and trust contract

| State | What the user sees | Recovery / action |
| --- | --- | --- |
| Model unavailable | Chat setup prompt; composer explains why sending is unavailable | Open model setup; keep library and utilities usable |
| Model loading | “Preparing assistant” near composer | Avoid fabricated percentage; preserve draft |
| Generating | “Writing on your device” and Stop replacing Send | Stop retains partial response and labels it interrupted |
| Retrieval in progress | “Looking through saved content” | Distinguish retrieval from generation; cancellation if supported |
| No library items | One helpful sentence and Import file / Add note | No fake demo content in production |
| Import failed | Specific unsupported type, size limit, or no readable text message | Choose another file; explain scanned PDF OCR is unavailable |
| Network unavailable | “No connection. Saved content is available.” in network workflows | Retry; never block offline chat startup on catalog fetch |
| Web disabled | “Web off” status; generation remains local | Permission sheet before first external request |
| Download failed | File name, failed stage, concise cause if known | Retry supported operation; avoid promising resume universally |
| Low storage | Required and available bytes when known | Manage storage; preserve user content |
| Source mismatch | Unmatched citation warning beneath answer | Inspect actual retrieved sources; never show verified checkmark |
| Speech unavailable | Explain missing on-device recognizer or offline voice | Keep keyboard/text workflow; no silent network fallback |
| Destructive action | Exact object and consequences | Confirm when no undo exists; offer undo only with actual restoration |

Model state, network reachability, and permission to use web are three independent states. Do not compress them into a misleading “private/online” toggle. Search sends the query to the chosen provider; opening a page contacts its site; generation remains local. API keys are session-only in the existing implementation and must be described that way.

## Components and frontend structure

Keep Kotlin + Jetpack Compose + Material 3. This planning work does not call for a web framework or an application rewrite.

Proposed packages:

```text
ui/theme/       OffgridTheme, Color, Typography, Spacing
ui/components/  AppBar, ConnectionStatus, ActionTile, ContentRow,
                SourceChip, EmptyState, DownloadStatus, Composer
ui/navigation/  AppDestination, OffgridScaffold
ui/chat/        ChatScreen, MessageContent, HistorySheet, ContextSheet
ui/library/     LibraryScreen, PackDetail, DocumentDetail, ImportSheet
ui/tools/       ToolsScreen, CalculatorScreen, WebScreen
ui/settings/    SettingsScreen, ModelScreen, StorageScreen
ui/setup/       WelcomeScreen, ModelSetupScreen
```

Extract stateless composables with immutable UI state and explicit callbacks. Retain existing ViewModel/repository behavior initially; map existing state to presentation models. Avoid a simultaneous persistence/inference rewrite. Keep message rendering stable during streaming using stable message IDs; scroll only when already near the bottom, otherwise show Jump to latest. Restore drafts, context selection, destination, and list position as appropriate. Gate duplicate actions while operations are running.

Use Scaffold with safe drawing/navigation/IME insets applied once. Composer moves above the keyboard; bottom navigation may hide during text entry to preserve reading room. Preserve input focus. Markdown supports paragraphs, lists, headings, code and links; horizontal scrolling belongs only within wide code/table content, not the whole conversation.

Compact phones: single pane. Expanded windows: navigation rail with a constrained reading column; Library can use list/detail when space allows. Base adaptation on available width, not device name or orientation. Large font sizes switch grids to stacked rows; do not truncate essential labels.

## Motion, accessibility, and assets

Use short 160–220ms state transitions, respect reduced motion, and avoid animating every streamed token. Speak a completion state to accessibility services rather than every token. Label all icon actions, preserve logical focus order, announce validation errors, and expose selected navigation/context states. Verify TalkBack, switch access, keyboard use, 200% font scaling, and edge-to-edge insets.

Bundle any typefaces and vector assets. Illustrations are optional and limited to welcome/empty states; the product should stay useful and polished without remote images. Keep warm light and forest dark palettes equally complete. System theme is the default; manual Light / Dark / System belongs in Appearance.

## Delivery sequence and acceptance criteria

1. **Foundation:** extract tokens and theme; build app bar, rows, chips, empty/error components; light/dark previews and large-text checks. No duplicated screen color constants.
2. **Navigation and chat:** migrate to three destinations; add Settings entry, context sheet, calm answer layout and action overflow. Existing history, stop, save, share and citations must still work.
3. **Library:** bring Saved and Discover together; polish imports, pack detail and document reading. Distinguish personal vs curated content and preserve existing data.
4. **Tools and setup:** focused local tools, web permission/search/read/save flow, model setup and download states. No external request before its required permission.
5. **Release polish:** dark mode, tablets/landscape, accessibility, keyboard/inset behavior and performance on a real Android device.

Validate with existing unit/instrumentation suites plus targeted interaction coverage where behavior changes. Manually verify airplane-mode launch, interrupted generation, process recreation, missing model, full storage, failed download, unsupported/scanned PDF, unavailable voice, and disabled web. Screenshot checks should cover compact light/dark and large font sizes. Physical-device validation is required before claiming inference performance or voice reliability.

Success criteria: a new user understands how to get ready; a returning user can immediately ask a question; source scope is visible before sending; one-tap source inspection works; saving for offline is discoverable; every blocking state offers an honest next action.

## Design tooling

The editable [Figma concept](https://www.figma.com/design/wIXsEBGs2VDya44Vg5bFUZ) contains seven Android screens: welcome/setup, chat home, sourced answer, saved library, pack discovery, tools, and settings. It is a direction for review; the detailed states and remaining views are specified above for implementation. The in-conversation interactive concept demonstrates navigation, not real model, storage, import, or download behavior. No additional skill or runtime package is required for this proposal.
