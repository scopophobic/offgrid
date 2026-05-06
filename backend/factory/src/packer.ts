import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import JSZip from "jszip";
import { EMBED_DIMENSION, EMBED_MODEL_ID } from "./embedder.js";
import type {
  Chunk,
  CleanedSource,
  PackManifest,
  SourceRecord
} from "./types.js";

export interface PackInput {
  topicId: string;
  title: string;
  description: string;
  tags: string[];
  chunks: Chunk[];
  cleanedSources: CleanedSource[];
  embeddings: Float32Array;
}

export async function buildPack(
  input: PackInput,
  outputPath: string
): Promise<{ manifest: PackManifest; zipPath: string }> {
  const expectedFloats = input.chunks.length * EMBED_DIMENSION;
  if (input.embeddings.length !== expectedFloats) {
    throw new Error(
      `embedding count mismatch: expected ${expectedFloats} floats (chunks=${input.chunks.length}, dim=${EMBED_DIMENSION}), got ${input.embeddings.length}`
    );
  }

  const sources: SourceRecord[] = input.cleanedSources.map((s) => ({
    sourceId: s.sourceId,
    url: s.url,
    title: s.title,
    license: s.license,
    fetchedAt: s.fetchedAt
  }));

  const chunksJsonl =
    input.chunks
      .map((c) =>
        JSON.stringify({
          id: c.id,
          text: c.text,
          sourceId: c.sourceId,
          sectionPath: c.sectionPath,
          position: c.position
        })
      )
      .join("\n") + (input.chunks.length > 0 ? "\n" : "");

  const embeddingsBuffer = Buffer.from(
    input.embeddings.buffer,
    input.embeddings.byteOffset,
    input.embeddings.byteLength
  );

  const sourcesJson = JSON.stringify(sources, null, 2);
  const readme = renderReadme({
    title: input.title,
    description: input.description,
    sources
  });

  const contentHash = createHash("sha256");
  contentHash.update(chunksJsonl);
  contentHash.update(embeddingsBuffer);
  contentHash.update(sourcesJson);
  const sha256 = contentHash.digest("hex");

  const version = makeVersion();

  const manifest: PackManifest = {
    schemaVersion: 1,
    id: input.topicId,
    title: input.title,
    description: input.description,
    version,
    generatedAt: new Date().toISOString(),
    embedModel: EMBED_MODEL_ID,
    embedDim: EMBED_DIMENSION,
    embedDtype: "f32",
    numChunks: input.chunks.length,
    numSources: sources.length,
    sizeBytes: 0,
    sha256,
    tags: input.tags
  };

  // First pass: zip without final sizeBytes so we can measure.
  let zip = new JSZip();
  zip.file("manifest.json", JSON.stringify(manifest, null, 2));
  zip.file("chunks.jsonl", chunksJsonl);
  zip.file("embeddings.f32", embeddingsBuffer);
  zip.file("sources.json", sourcesJson);
  zip.file("README.md", readme);

  const probeZip = await zip.generateAsync({
    type: "nodebuffer",
    compression: "DEFLATE",
    compressionOptions: { level: 6 }
  });
  manifest.sizeBytes = probeZip.length;

  // Second pass: re-emit with final sizeBytes in the manifest.
  zip = new JSZip();
  zip.file("manifest.json", JSON.stringify(manifest, null, 2));
  zip.file("chunks.jsonl", chunksJsonl);
  zip.file("embeddings.f32", embeddingsBuffer);
  zip.file("sources.json", sourcesJson);
  zip.file("README.md", readme);

  const finalZip = await zip.generateAsync({
    type: "nodebuffer",
    compression: "DEFLATE",
    compressionOptions: { level: 6 }
  });

  await mkdir(dirname(outputPath), { recursive: true });
  await writeFile(outputPath, finalZip);

  return { manifest, zipPath: outputPath };
}

function makeVersion(): string {
  const d = new Date();
  const yyyy = d.getUTCFullYear();
  const mm = String(d.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(d.getUTCDate()).padStart(2, "0");
  return `${yyyy}${mm}${dd}.1`;
}

function renderReadme(input: {
  title: string;
  description: string;
  sources: SourceRecord[];
}): string {
  const lines: string[] = [];
  lines.push(`# ${input.title}`);
  lines.push("");
  lines.push(input.description);
  lines.push("");
  lines.push("## Sources");
  lines.push("");
  for (const src of input.sources) {
    lines.push(`- **${src.title}** — ${src.url} _(${src.license})_`);
  }
  lines.push("");
  lines.push("## License attribution");
  lines.push("");
  lines.push(
    "This pack contains content adapted from the sources listed above."
  );
  lines.push(
    "Wikipedia content is available under the Creative Commons Attribution-ShareAlike 4.0 License."
  );
  lines.push("");
  return lines.join("\n");
}
