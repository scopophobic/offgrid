# Offgrid — a pocket for your curiosity

28 September 2026 · Revised after feedback that V1 felt generic and unfriendly.

This is the current visual direction. It supersedes the palette, typography, surface styling, and tone in UI_PLAN.md. The original document remains useful for feature coverage, state behavior, privacy wording, accessibility, and the Compose implementation sequence.

[Editable Figma V2 board](https://www.figma.com/design/wIXsEBGs2VDya44Vg5bFUZ?node-id=10-3)

## What needed to change

V1 treated almost everything as a muted row or pill. The green palette communicated restraint, but the screens had weak visual hierarchy, interchangeable actions, and too much empty space between the task and composer. The repeated status copy made the app feel like a utility console. The visual metaphor never extended beyond the phrase “field notebook.”

V2 gives the product recognizable objects and a coherent interaction: choose a folder, attach its contents to a question, inspect the matching source, and keep an answer. The personality lives around starting and saving. Reading remains calm.

## Directions considered

| Direction | Distinctive idea | Decision |
| --- | --- | --- |
| Pocket companion | Tactile folders, layered prompt tickets, round ink navigation, direct friendly language | Develop. It connects personality to the existing save-and-ask workflow. |
| Illustrated notebook | Small hand-drawn scenes, marginal notes, quiet storybook character | Potential welcome-screen treatment, but full-time illustration would compete with long answers. |
| Creative workspace | Strong typographic posters, asymmetric compositions, bold task controls | Borrow the clear hierarchy; avoid making everyday reading visually demanding. |

These are authored explorations, not claims that a design language has never appeared elsewhere. The goal is a distinctive, useful Offgrid experience.

## The signature interaction

1. A personal pack appears as a **folder** with a content preview, type, and item count. Users recognize their saved material before opening it.
2. **Ask this notebook** attaches it to the composer. A narrow ribbon names the selected context and provides a remove action.
3. The question uses a lilac surface. The answer reads directly on the warm background.
4. **See what I used** carries the folder's color into an evidence preview. It shows the actual passage, origin, and saved date.
5. **Keep this** confirms where the answer went, and the saved item appears in Library.

Color reinforces explicit titles and labels; it is never the only way to identify content. Library selection must never silently change the chat's scope. In production, context survives the appropriate conversation lifecycle and can always be removed.

## Visual language

| Role | Light | Dark |
| --- | --- | --- |
| Warm background | #F7F3EB | #211E28 |
| Reading / input surface | #FFFDF8 | #302B38 |
| Main text | #302238 | #F7EEE7 |
| Secondary text | #746974 | #C3B5C6 |
| Divider | #DFD5D9 | #504458 |
| Question / lilac | #E1D6F2 | #514367 |
| Personal pack / apricot | #FFCFAE | #6B4233 |
| Invitation / yellow | #EDE9A9 | #545331 |
| Main action / navigation | #38273F | #E4D8EE |
| Text on main action | #FFF9EF | #302238 |

Use DM Sans for its open forms and friendly rhythm. Bundle it locally for Android after checking its distribution license. Android body text starts at 16sp; the web concept's compact supporting labels are not a reason to shrink production body copy. Screen headings are 34–40sp on compact screens and can wrap naturally. Medium weight carries most emphasis. The wordmark may use bold.

Use 20–24dp page gutters, 48dp interactive targets, and 12–24dp internal spacing. Rounded forms vary by function: folder corners 18–22dp, question bubble 20dp with one quieter corner, composer 24dp, navigation capsule 28dp. A folder tab and inset paper preview create identity without heavy texture or image downloads.

At most one expressive heading treatment per screen. Use the lilac highlight on an introductory phrase, not throughout answers. Small paper tilts are decorative; hit targets and text layout remain stable. Drop shadows are subtle and limited to layered objects and the composer.

The preview includes an alternative Sky & butter palette for comparison. This is an exploration control, not a requirement to ship a theme marketplace.

## Screen direction

| Area | V2 treatment |
| --- | --- |
| Ask home | “Room for a little curiosity.” One prominent “Make this make sense” ticket, two concise starters, one recent conversation, persistent composer. No invented activity stats or daily pressure. |
| New user | Replace the recent-conversation area with an honest empty state. Explain one model download and show actual storage requirements. No preloaded personal content. |
| Attach tray | “Start with something.” Choose existing content or import/paste; the selected item visibly joins the composer. |
| Conversation | Lilac user question, plain readable assistant answer, compact source entry, Keep / Shorter / More. Streaming uses a small state label and Stop, not a looping mascot. |
| Evidence | “Here’s what I used.” A paper-like passage with the folder accent, source title, timestamp, page if present, and open-original controls where applicable. |
| Library | “Your little library.” My things / Discover packs. Personal folders have previews; curated packs remain distinctly labeled. Search and list view must remain available when the collection grows. |
| Pack detail | Large recognizable title, offline state, Ask this notebook, readable content rows. A short list replaces decorative previews when browsing inside a pack. |
| Tools | “Little helpers. Less hassle.” A useful local tool can be immediately interactive; other tools have clear labels. The percentage demo is a direction, not a mandate to always feature percentages. |
| Settings | “Make it your own.” Calm grouped settings, appearance, assistant/model, preferences, storage. The user should find controls without decoding playful labels. |
| Downloads | Real file name, size, progress and retry. Friendly copy must not hide technical failure details needed for recovery. |
| Errors | Short plain explanation plus one relevant recovery action. Avoid jokes around lost work, privacy, missing sources or failed downloads. |

Bottom navigation uses **Ask / Library / Tools**. Settings and history remain in the top bar. “Ask” names the user action; saved conversation history remains explicit and accessible.

## Behavior that makes it feel friendly

- Keep drafts when switching destinations or recovering from an error.
- Show useful next steps after saving: where it went and how to open it.
- Make context selection visible at the point of sending, not only in a settings panel.
- Use short transformations to explain movement: a folder becomes an attached ribbon; saving briefly acknowledges the destination.
- Never require dragging, swiping, hovering, or animation to access core actions. Each action has a normal button equivalent.
- Honor reduced motion and large text. At larger font settings, folder previews simplify and chips wrap into rows.
- Do not anthropomorphize confidence. A friendly tone is compatible with “No matching passage found” and “I couldn’t read this page.”

## Implementation boundaries

Keep the native Jetpack Compose architecture. Reuse the existing models, persistence, retrieval, tool and permission behavior. Build the theme and shared visual primitives first, then the Ask → Library → source → save journey. Validate that journey before expanding styling to setup, downloads and settings.

Use Material semantics and accessibility behavior under the custom visuals. The unusual parts are the visual forms and transitions, not hidden navigation or unconventional input handling. Add screenshot coverage for the main light/dark states only when implementing, then test a real phone for keyboard insets, long responses, streaming, memory use and accessibility.

## What is ready to review

- An interactive concept with Ask, Library, context attachment, sample conversation, source inspection, save confirmation, tools and appearance routes.
- Three editable Figma V2 core screens in a separate page. V1 remains available for comparison.
- Light and dark preview styling, plus a color alternative.
- Browser checks: 360px library layout; 320px tools layout; sending a sample question; keeping an answer; opening its evidence; updating the percentage tool (20% of 240 produces 48).

The preview's answers and library content are examples. Save state is temporary in the preview. No model, file import, persistence, web lookup or download runs there. The Figma board is a visual exploration with editable layers, not a complete component library or wired prototype.
