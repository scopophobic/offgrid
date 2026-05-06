import { spawn } from "node:child_process";
import { stat } from "node:fs/promises";
import { resolve } from "node:path";
import JSZip from "jszip";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import type { PackManifest } from "./types.js";

/**
 * Publish a built pack ZIP to Cloudflare:
 *
 *   - R2 object at `<bucket>/packs/<id>/<version>.zip`
 *   - KV value at `pack:<id>` containing the runtime [TopicPackMeta] the
 *     Worker (`backend/src/index.ts`) expects.
 *
 * Shells out to `wrangler` (already installed in `backend/`). The user must
 * have run `wrangler login` and provisioned the R2 bucket + KV namespace
 * named in `backend/wrangler.toml`.
 *
 * Usage from CLI: see [src/index.ts] `publish` subcommand.
 */

interface UploadOptions {
  /** Path to the built pack ZIP (e.g. backend/factory/out/<id>.zip). */
  zipPath: string;
  /** Path to the Worker's wrangler.toml (so we don't depend on cwd). */
  wranglerConfigPath: string;
  /** R2 bucket name from wrangler.toml. */
  bucketName: string;
  /** KV binding name from wrangler.toml. */
  kvBinding: string;
}

interface TopicPackMeta {
  id: string;
  title: string;
  description: string;
  version: string;
  sizeBytes: number;
  r2Key: string;
  checksumSha256: string;
  updatedAt: string;
  tags: string[];
}

export async function publishPack(opts: UploadOptions): Promise<TopicPackMeta> {
  const zipPath = resolve(opts.zipPath);
  const fileStat = await stat(zipPath);
  if (!fileStat.isFile()) {
    throw new Error(`pack zip not found: ${zipPath}`);
  }

  const zipSha256 = await sha256File(zipPath);
  const manifest = await readManifestFromZip(zipPath);
  const r2Key = `packs/${manifest.id}/${manifest.version}.zip`;

  console.log(`[upload] pack=${manifest.id}@${manifest.version} -> r2://${opts.bucketName}/${r2Key}`);
  await runWrangler([
    "r2",
    "object",
    "put",
    `${opts.bucketName}/${r2Key}`,
    `--file=${zipPath}`,
    `--config=${opts.wranglerConfigPath}`
  ]);

  const meta: TopicPackMeta = {
    id: manifest.id,
    title: manifest.title,
    description: manifest.description,
    version: manifest.version,
    sizeBytes: manifest.sizeBytes,
    r2Key,
    // Runtime downloader verifies the ZIP bytes it received from R2.
    checksumSha256: zipSha256,
    updatedAt: manifest.generatedAt,
    tags: manifest.tags
  };

  console.log(`[upload] kv pack:${manifest.id}`);
  await runWrangler([
    "kv:key",
    "put",
    "--binding",
    opts.kvBinding,
    `pack:${manifest.id}`,
    JSON.stringify(meta),
    `--config=${opts.wranglerConfigPath}`
  ]);

  console.log(`[upload] OK ${manifest.id}@${manifest.version}`);
  return meta;
}

async function readManifestFromZip(zipPath: string): Promise<PackManifest> {
  const buf = await readFile(zipPath);
  const zip = await JSZip.loadAsync(buf);
  const manifestFile = zip.file("manifest.json");
  if (!manifestFile) {
    throw new Error(`manifest.json missing inside ${zipPath}`);
  }
  const text = await manifestFile.async("string");
  return JSON.parse(text) as PackManifest;
}

async function sha256File(path: string): Promise<string> {
  const buf = await readFile(path);
  return createHash("sha256").update(buf).digest("hex");
}

function runWrangler(args: string[]): Promise<void> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn("npx", ["--yes", "wrangler", ...args], {
      stdio: ["ignore", "pipe", "pipe"]
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (d) => {
      stdout += d.toString();
    });
    child.stderr.on("data", (d) => {
      stderr += d.toString();
    });
    child.on("close", (code) => {
      if (code === 0) {
        if (stdout.trim()) console.log(stdout.trim());
        resolvePromise();
      } else {
        reject(
          new Error(
            `wrangler ${args.join(" ")} exited with code ${code}\n` +
              `stdout: ${stdout}\nstderr: ${stderr}`
          )
        );
      }
    });
    child.on("error", reject);
  });
}
