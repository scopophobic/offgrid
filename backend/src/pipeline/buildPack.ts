/**
 * Offline pack builder scaffold.
 * Real pipeline stages:
 * 1) fetch approved source content
 * 2) normalize and chunk text
 * 3) create embeddings
 * 4) package chunks+embeddings into ZIP
 * 5) upload ZIP to R2
 * 6) publish metadata to KV (pack:<id>)
 */

type BuildPackInput = {
  id: string;
  title: string;
  description: string;
  tags: string[];
  sources: string[];
};

async function buildPack(input: BuildPackInput): Promise<void> {
  // Placeholder: no scraping yet. Keep deterministic and explicit.
  console.log(`[pack] build started: ${input.id}`);
  console.log(`[pack] title: ${input.title}`);
  console.log(`[pack] sources: ${input.sources.length}`);
  console.log("[pack] TODO: implement fetch/chunk/embed/zip/upload/publish");
}

const sample: BuildPackInput = {
  id: "guitar-basics",
  title: "Guitar Basics",
  description: "Beginner chords and practice guidance",
  tags: ["guitar", "music", "beginner"],
  sources: ["https://example.org/approved-source"]
};

buildPack(sample).catch((err) => {
  console.error(err);
  process.exit(1);
});
