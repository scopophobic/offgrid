# Knowledge Factory

Local Node script that turns YAML topic configs into Offgrid pack ZIPs. Runs entirely on your dev machine — never executed inside a Cloudflare Worker.

## Pipeline

```
YAML topic config
   ↓ fetch (Wikipedia REST: action=parse + one-hop link follow)
   ↓ sanitize (cheerio prune + Mozilla Readability + turndown markdown)
   ↓ chunk (semantic, H2/H3-aware, ~512 tokens, 12% overlap)
   ↓ embed (BGE-small-en-v1.5 via @xenova/transformers, local CPU)
   ↓ pack (manifest.json + chunks.jsonl + embeddings.f32 + sources.json + README.md → ZIP)
```

## Setup

```bash
cd backend/factory
npm install
```

First successful build downloads the BGE-small ONNX weights (~30 MB) into `node_modules/@xenova/transformers/.cache/`. Cached for subsequent runs.

## Build a pack

```bash
npm run build chess-openings-beginner
```

Output goes to `backend/factory/out/<topic-id>.zip`. Typical first build (one topic, ~5–8 source articles, ~200–600 chunks) takes 1–3 minutes including the model warm-up.

## Dry run (no embedding, no zip)

Useful for iterating on a topic config without paying the embedding cost — fetches, sanitizes, and chunks, then prints a summary plus a sample chunk:

```bash
npx tsx src/index.ts build chess-openings-beginner --dry-run
```

## List configured topics

```bash
npm run list
```

## Authoring a new topic

Drop a new YAML in `topics/<id>.yaml`. Filename and `id` must match.

```yaml
id: guitar-basics
title: "Guitar Basics"
description: "Beginner chords, progressions, and practice guidance."
tags: [guitar, music, beginner]
sources:
  - kind: wikipedia
    titles:
      - "Chord (music)"
      - "Strumming"
      - "Open chord"
    follow_links:
      max_per_article: 2
      keyword_filters: [chord, finger, beginner]
maxChunks: 600
maxBytes: 4194304
```

Then run `npm run build guitar-basics`.

## Pack ZIP layout

```
pack-<id>-<version>.zip
├── manifest.json     (id, title, version, embedModel, embedDim, numChunks, sha256, …)
├── chunks.jsonl      (one chunk per line: id, text, sourceId, sectionPath, position)
├── embeddings.f32    (raw little-endian Float32, exactly numChunks × 384 floats)
├── sources.json      (sourceId, url, title, license, fetchedAt)
└── README.md         (human-readable attribution + license summary)
```

The phone unzips this once, streams `chunks.jsonl` into local SQLite + FTS5, and keeps `embeddings.f32` on disk for future on-device retrieval.

## Guardrails

- Per-pack hard caps (`maxChunks`, `maxBytes`) enforced. Exceeds → factory aborts; split the topic into narrower sub-topics.
- Domain whitelist (Wikipedia today; pluggable). New `kind` values plug into `src/sources/registry.ts`.
- Wikipedia content is CC BY-SA 4.0; attribution is preserved in `sources.json` and the rendered `README.md`.

## Publish to Cloudflare

Once you've built a pack with `npm run build <id>`, ship it:

```bash
npm run publish <id>
```

This shells out to `wrangler` (already installed in `backend/`) and:

- Uploads `out/<id>.zip` to R2 at `offgrid-packs/packs/<id>/<version>.zip`.
- Writes the runtime metadata to KV at `pack:<id>` so the Worker's `/v1/packs` and `/v1/packs/:id/download` endpoints surface it.

Prereqs (one-time, in the parent `backend/` dir):

```bash
cd ../
npx wrangler login
npx wrangler r2 bucket create offgrid-packs
npx wrangler kv:namespace create PACKS_REGISTRY
# paste the printed namespace id into wrangler.toml
npx wrangler deploy
```

After that the chain is:

```
factory build <id>      # produces out/<id>.zip (manifest, chunks, embeddings)
factory publish <id>    # R2 upload + KV register
GET https://<worker>/v1/packs                  # catalog
GET https://<worker>/v1/packs/<id>/download    # { downloadUrl, sha256, sizeBytes }
GET https://<worker>/r2/packs/<id>/<version>.zip   # streams the ZIP
```

The Android app's planned in-app installer will hit the catalog endpoint, fetch the download URL, stream the ZIP via Ktor, verify the sha256 against the manifest, then hand the local ZIP to `AndroidPackImporter` (the same code path used today by sideloading via `adb push`).
