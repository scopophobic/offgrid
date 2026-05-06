import { readdir } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chunkSource } from "./chunker.js";
import { EMBED_DIMENSION, embedTexts } from "./embedder.js";
import { buildPack } from "./packer.js";
import { sanitizeSource } from "./sanitize.js";
import { fetchAllSources } from "./sources/registry.js";
import { loadTopicConfig } from "./topicConfig.js";
import { publishPack } from "./uploader.js";
import type { Chunk, CleanedSource } from "./types.js";

const __filename = fileURLToPath(import.meta.url);
const FACTORY_ROOT = resolve(__filename, "..", "..");
const TOPICS_DIR = resolve(FACTORY_ROOT, "topics");
const OUT_DIR = resolve(FACTORY_ROOT, "out");
// The Worker's wrangler.toml lives one level up from the factory.
const WORKER_WRANGLER = resolve(FACTORY_ROOT, "..", "wrangler.toml");
// Mirrors `[[r2_buckets]]` and `[[kv_namespaces]]` in backend/wrangler.toml.
const R2_BUCKET_NAME = "offgrid-packs";
const KV_BINDING = "PACKS_REGISTRY";

interface BuildOptions {
  dryRun: boolean;
}

async function buildOne(topicId: string, opts: BuildOptions) {
  const start = Date.now();
  console.log(`[factory] loading topic config: ${topicId}`);
  const config = await loadTopicConfig(topicId, TOPICS_DIR);

  console.log(`[factory] fetching ${config.sources.length} source group(s)…`);
  const fetched = await fetchAllSources(config.sources);
  if (fetched.length === 0) {
    throw new Error(`no sources fetched for topic '${topicId}'`);
  }
  console.log(`[factory] fetched ${fetched.length} document(s)`);

  console.log("[factory] sanitizing…");
  const cleaned: CleanedSource[] = fetched.map(sanitizeSource);

  console.log("[factory] chunking…");
  let chunks: Chunk[] = [];
  for (const source of cleaned) {
    const next = chunkSource(source);
    chunks.push(...next);
    if (chunks.length >= config.maxChunks) {
      chunks = chunks.slice(0, config.maxChunks);
      break;
    }
  }
  console.log(`[factory] produced ${chunks.length} chunk(s)`);

  if (opts.dryRun) {
    console.log(
      "[factory] dry-run: skipping embedding and pack assembly. Sample chunk:"
    );
    console.log(
      JSON.stringify(
        {
          sources: cleaned.length,
          chunks: chunks.length,
          firstChunk: chunks[0]
            ? {
                sectionPath: chunks[0].sectionPath,
                charCount: chunks[0].charCount,
                preview: chunks[0].text.slice(0, 200)
              }
            : null
        },
        null,
        2
      )
    );
    return;
  }

  console.log(
    `[factory] embedding ${chunks.length} chunks (first run downloads BGE-small ~30 MB)…`
  );
  const embedStart = Date.now();
  const { vectors } = await embedTexts(chunks.map((c) => c.text));
  const embedSeconds = ((Date.now() - embedStart) / 1000).toFixed(1);
  console.log(
    `[factory] generated ${vectors.length / EMBED_DIMENSION} embedding(s) in ${embedSeconds}s`
  );

  const outPath = join(OUT_DIR, `${topicId}.zip`);
  console.log(`[factory] assembling pack → ${outPath}`);
  const { manifest } = await buildPack(
    {
      topicId: config.id,
      title: config.title,
      description: config.description,
      tags: config.tags,
      chunks,
      cleanedSources: cleaned,
      embeddings: vectors
    },
    outPath
  );

  if (manifest.sizeBytes > config.maxBytes) {
    throw new Error(
      `pack size ${manifest.sizeBytes} exceeds maxBytes ${config.maxBytes}. Split topic or lower maxChunks.`
    );
  }

  const totalSeconds = ((Date.now() - start) / 1000).toFixed(1);
  console.log(
    `[factory] OK ${manifest.id}@${manifest.version}` +
      ` size=${(manifest.sizeBytes / 1024).toFixed(1)} KiB` +
      ` chunks=${manifest.numChunks}` +
      ` sources=${manifest.numSources}` +
      ` sha256=${manifest.sha256.slice(0, 12)}…` +
      ` elapsed=${totalSeconds}s`
  );
}

async function listTopics() {
  const entries = await readdir(TOPICS_DIR);
  const topics = entries
    .filter((f) => f.endsWith(".yaml"))
    .map((f) => f.replace(/\.yaml$/, ""))
    .sort();
  if (topics.length === 0) {
    console.log("(no topic configs found in topics/)");
    return;
  }
  console.log("Available topic configs:");
  for (const t of topics) console.log(`  - ${t}`);
}

async function publishOne(topicId: string) {
  const zipPath = join(OUT_DIR, `${topicId}.zip`);
  const meta = await publishPack({
    zipPath,
    wranglerConfigPath: WORKER_WRANGLER,
    bucketName: R2_BUCKET_NAME,
    kvBinding: KV_BINDING
  });
  console.log(JSON.stringify(meta, null, 2));
}

function printUsage() {
  console.error("usage:");
  console.error("  tsx src/index.ts build <topic-id> [--dry-run]");
  console.error("  tsx src/index.ts publish <topic-id>");
  console.error("  tsx src/index.ts list");
}

async function main() {
  const [, , cmd, ...rest] = process.argv;
  switch (cmd) {
    case "build": {
      const id = rest.find((a) => !a.startsWith("--"));
      const dryRun = rest.includes("--dry-run");
      if (!id) {
        printUsage();
        process.exit(2);
      }
      await buildOne(id, { dryRun });
      break;
    }
    case "publish": {
      const id = rest.find((a) => !a.startsWith("--"));
      if (!id) {
        printUsage();
        process.exit(2);
      }
      await publishOne(id);
      break;
    }
    case "list":
      await listTopics();
      break;
    default:
      printUsage();
      process.exit(2);
  }
}

main().catch((err) => {
  console.error("[factory] failed:", (err as Error).message);
  if (process.env.DEBUG) console.error((err as Error).stack);
  process.exit(1);
});
