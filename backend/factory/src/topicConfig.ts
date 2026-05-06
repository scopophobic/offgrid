import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { parse as parseYaml } from "yaml";
import { z } from "zod";

const followLinksSchema = z
  .object({
    max_per_article: z.number().int().min(0).max(20).default(0),
    keyword_filters: z.array(z.string()).default([])
  })
  .strict();

const wikipediaSourceSchema = z
  .object({
    kind: z.literal("wikipedia"),
    titles: z.array(z.string().min(1)).min(1),
    follow_links: followLinksSchema.optional()
  })
  .strict();

const sourceSchema = z.discriminatedUnion("kind", [wikipediaSourceSchema]);

export const topicConfigSchema = z
  .object({
    id: z.string().regex(/^[a-z0-9][a-z0-9-]*$/),
    title: z.string().min(1),
    description: z.string().min(1),
    tags: z.array(z.string()).default([]),
    sources: z.array(sourceSchema).min(1),
    maxChunks: z.number().int().positive().default(1500),
    maxBytes: z
      .number()
      .int()
      .positive()
      .default(8 * 1024 * 1024)
  })
  .strict();

export type ValidatedTopicConfig = z.infer<typeof topicConfigSchema>;

export async function loadTopicConfig(
  topicId: string,
  topicsDir: string
): Promise<ValidatedTopicConfig> {
  const filePath = join(topicsDir, `${topicId}.yaml`);
  const raw = await readFile(filePath, "utf8");
  const parsed = parseYaml(raw);
  const result = topicConfigSchema.safeParse(parsed);
  if (!result.success) {
    const issues = result.error.issues
      .map((i) => `${i.path.join(".") || "<root>"}: ${i.message}`)
      .join("; ");
    throw new Error(`Invalid topic config '${topicId}': ${issues}`);
  }
  if (result.data.id !== topicId) {
    throw new Error(
      `Topic config id '${result.data.id}' does not match filename '${topicId}'`
    );
  }
  return result.data;
}
