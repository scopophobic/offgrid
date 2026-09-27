# Offgrid 0.2 feature guide

Offgrid generates replies with an installed model on the Android device. The **Knowledge** tab installs curated packs while connected; installed packs work offline. Travel content can also be saved in **Library** for later questions.

## Chat and saved information

- **Chat → History** reopens and searches saved conversations. Rename and delete are available there. New chat starts a separate conversation. Interrupted replies are labelled.
- Share text or a URL to Offgrid from another Android app. Choose a task from the share sheet; for a URL, explicitly enable web access and read the page before saving it offline. Shared text becomes a draft; shared files import into Library. You can also use **Library → Import file** or paste a note.
- Library supports text, Markdown, and text PDFs. Scanned PDFs have no readable text and need OCR, which is outside this release. Individual imports are limited to 12 MB and 500,000 extracted characters.
- **Library → Create a pack** makes a named personal field pack. Add notes, imported documents, and saved pages to that pack by choosing its name as their collection. Pack cards show item counts; select a pack in chat to limit retrieval to its content. These packs are private to the device and distinct from curated packs in Knowledge.
- Save answers, notes, and read web pages in named collections. Select a collection or one item before asking a question. Tap a source chip under an answer to inspect its passage and save it offline. PDF excerpts retain page labels where available. Summarizing a selected long document samples its beginning, middle, and end; it does not claim full coverage. Source chips show retrieved evidence, not independent fact checking. Invalid citation numbers are flagged, but citation support is not semantically verified.
- Response actions include copy, Android share, save, speech playback, and drafting a shorter or simpler follow-up. Task chips send structured instructions to the model without adding those instructions to the visible user text.
- Optional preferences in **Settings → Assistant preferences** are stored on the device. Delete them with **Forget all**. Saved chats and library items have their own delete controls. App backup is disabled.

## Tools and connection

- **Tools** has arithmetic, percentages, common travel unit conversions, and date differences. These are computed by code without the model or internet.
- **Tools → Web** begins disabled. Turn on **Web allowed** to search Wikipedia without a key, or set a Brave Search API key in Settings for general web search. The key lasts only for the app session. Search sends the typed query to the provider. Reading a page contacts that site. The model still generates answers locally.
- Read pages can be inspected, saved offline, or selected for a local summary. Only readable public HTTPS pages are supported; some login or JavaScript pages cannot be extracted. A summary of a long item uses selected excerpts and may not cover the entire document.
- **Dictate offline** uses Android's on-device speech recognizer where the device provides one; it never falls back to a network recognizer. **Listen** requires an installed offline text-to-speech voice. Availability varies by device and language.

## Build and verify

Open the project in Android Studio. The app uses Gradle 8.10.2, Android Gradle Plugin 8.6.1, API 35, and JDK 17 or a compatible newer JDK. Run `gradlew.bat :androidApp:assembleDebug` for an APK, `:androidApp:testDebugUnitTest` for utility and streaming tests, and `:androidApp:connectedDebugAndroidTest` for emulator storage, document, and navigation tests.

Model downloads and live web services need an internet connection. Actual ExecuTorch inference, native speech availability, and battery behavior require validation on a physical device.
