import { Hono } from "hono";
import { cors } from "hono/cors";
import { z } from "zod";

type Env = {
  PACKS_R2: R2Bucket;
  PACKS_REGISTRY: KVNamespace;
};

type TopicPackMeta = {
  id: string;
  title: string;
  description: string;
  version: string;
  sizeBytes: number;
  r2Key: string;
  checksumSha256: string;
  updatedAt: string;
  tags: string[];
};

const app = new Hono<{ Bindings: Env }>();
app.use("*", cors());

const TopicPackSchema = z.object({
  id: z.string(),
  title: z.string(),
  description: z.string(),
  version: z.string(),
  sizeBytes: z.number().int().nonnegative(),
  r2Key: z.string(),
  checksumSha256: z.string(),
  updatedAt: z.string(),
  tags: z.array(z.string())
});

const ModelArtifactSchema = z.object({
  downloadUrl: z.string(),
  checksumSha256: z.string(),
  sizeBytes: z.number().int().nonnegative().optional()
});

const ModelManifestSchema = z.object({
  version: z.string(),
  model: ModelArtifactSchema,
  tokenizer: ModelArtifactSchema
});

app.get("/", (c) => c.json({ service: "offgrid-api", ok: true }));

/**
 * GET /v1/model/manifest
 *
 * KV key `model:manifest` JSON must match ModelManifestSchema.
 * Upload `model.pte` / `tokenizer.json` to R2 (e.g. models/qwen3/model.pte) then:
 *   npx wrangler kv key put --binding=PACKS_REGISTRY model:manifest '<json>'
 *
 * Android downloads to app external files dir, verifies SHA-256, then loads ExecuTorch.
 */
app.get("/v1/model/manifest", async (c) => {
  const raw = await c.env.PACKS_REGISTRY.get("model:manifest", "json");
  if (!raw) {
    return c.json(
      {
        error: "model manifest not configured",
        hint: "PUT KV key model:manifest with version, model{downloadUrl,checksumSha256}, tokenizer{...}"
      },
      404
    );
  }
  const parsed = ModelManifestSchema.safeParse(raw);
  if (!parsed.success) {
    return c.json({ error: "invalid model:manifest in KV", issues: parsed.error.flatten() }, 500);
  }
  const m = parsed.data;
  return c.json({
    version: m.version,
    model: {
      ...m.model,
      downloadUrl: absolutizeModelUrl(c, m.model.downloadUrl)
    },
    tokenizer: {
      ...m.tokenizer,
      downloadUrl: absolutizeModelUrl(c, m.tokenizer.downloadUrl)
    }
  });
});

function absolutizeModelUrl(c: { req: { url: string } }, url: string): string {
  if (url.startsWith("http://") || url.startsWith("https://")) return url;
  const origin = new URL(c.req.url).origin;
  return `${origin}${url.startsWith("/") ? "" : "/"}${url}`;
}

app.get("/v1/health", async (c) => {
  // Lightweight smoke checks so deploy issues are obvious immediately.
  const kvList = await c.env.PACKS_REGISTRY.list({ prefix: "pack:", limit: 1 });
  return c.json({
    ok: true,
    service: "offgrid-api",
    packCountHint: kvList.keys.length
  });
});

app.get("/v1/packs", async (c) => {
  const q = c.req.query("q")?.trim().toLowerCase() ?? "";
  const limitRaw = c.req.query("limit");
  const limit = Math.min(Math.max(Number(limitRaw ?? "50") || 50, 1), 200);

  const list = await c.env.PACKS_REGISTRY.list({ prefix: "pack:" });
  const metas: TopicPackMeta[] = [];
  for (const key of list.keys) {
    const raw = await c.env.PACKS_REGISTRY.get(key.name, "json");
    const parsed = TopicPackSchema.safeParse(raw);
    if (parsed.success) metas.push(parsed.data);
  }
  const filtered = q
    ? metas.filter((m) => {
        const hay = `${m.id} ${m.title} ${m.description} ${m.tags.join(" ")}`.toLowerCase();
        return hay.includes(q);
      })
    : metas;
  filtered.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  return c.json({
    packs: filtered.slice(0, limit),
    total: filtered.length,
    query: q || null
  });
});

app.get("/v1/packs/:id", async (c) => {
  const id = c.req.param("id");
  const raw = await c.env.PACKS_REGISTRY.get(`pack:${id}`, "json");
  const parsed = TopicPackSchema.safeParse(raw);
  if (!parsed.success) return c.json({ error: "pack not found" }, 404);
  return c.json(parsed.data);
});

app.get("/v1/packs/:id/download", async (c) => {
  const id = c.req.param("id");
  const raw = await c.env.PACKS_REGISTRY.get(`pack:${id}`, "json");
  const parsed = TopicPackSchema.safeParse(raw);
  if (!parsed.success) return c.json({ error: "pack not found" }, 404);

  // Same-origin path so the app doesn't need a separate R2 host. The Worker
  // streams the bytes from R2 below; swap to signed URLs if you make the
  // bucket private.
  const path = `/r2/${parsed.data.r2Key}`;
  return c.json({
    id,
    downloadUrl: path,
    checksumSha256: parsed.data.checksumSha256,
    sizeBytes: parsed.data.sizeBytes
  });
});

// Streams pack ZIPs directly from R2. Same-origin keeps the Android client
// trivial: one Ktor GET, no separate R2 host or CORS dance.
app.get("/r2/*", async (c) => {
  const url = new URL(c.req.url);
  const key = url.pathname.replace(/^\/r2\//, "");
  if (!key) return c.json({ error: "missing key" }, 400);
  const obj = await c.env.PACKS_R2.get(key);
  if (!obj) return c.json({ error: "not found" }, 404);
  const headers = new Headers();
  obj.writeHttpMetadata(headers);
  headers.set("etag", obj.httpEtag);
  // Pack contents are immutable per (id, version), so allow long caching.
  headers.set("Cache-Control", "public, max-age=31536000, immutable");
  if (!headers.has("content-type")) {
    const ct =
      key.endsWith(".json") || key.endsWith(".jsonl")
        ? "application/json"
        : key.endsWith(".pte") || key.endsWith(".bin")
          ? "application/octet-stream"
          : "application/zip";
    headers.set("content-type", ct);
  }
  return new Response(obj.body, { headers });
});

export default app;
