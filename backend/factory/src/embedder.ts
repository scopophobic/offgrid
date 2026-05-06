export const EMBED_MODEL_ID = "Xenova/bge-small-en-v1.5";
export const EMBED_DIMENSION = 384;

type FeatureExtractor = (
  texts: string | string[],
  opts: { pooling: "mean"; normalize: boolean }
) => Promise<{ data: Float32Array; dims: number[] }>;

let pipelinePromise: Promise<FeatureExtractor> | null = null;

async function getPipeline(): Promise<FeatureExtractor> {
  if (!pipelinePromise) {
    pipelinePromise = (async () => {
      // Dynamic import keeps @xenova/transformers (and its sharp dep) out of
      // module-load for callers that never embed (eg the --dry-run path).
      const { env, pipeline } = await import("@xenova/transformers");
      env.allowLocalModels = false;
      env.useBrowserCache = false;
      return (await pipeline("feature-extraction", EMBED_MODEL_ID)) as FeatureExtractor;
    })();
  }
  return pipelinePromise;
}

export interface EmbedderResult {
  vectors: Float32Array;
  embedDim: number;
  modelId: string;
}

export async function embedTexts(texts: string[]): Promise<EmbedderResult> {
  if (texts.length === 0) {
    return {
      vectors: new Float32Array(0),
      embedDim: EMBED_DIMENSION,
      modelId: EMBED_MODEL_ID
    };
  }
  const fe = await getPipeline();
  const out = new Float32Array(texts.length * EMBED_DIMENSION);
  const batchSize = 32;
  for (let i = 0; i < texts.length; i += batchSize) {
    const batch = texts.slice(i, i + batchSize);
    const result = await fe(batch, { pooling: "mean", normalize: true });
    if (result.data.length !== batch.length * EMBED_DIMENSION) {
      throw new Error(
        `embedder returned ${result.data.length} floats for batch of ${batch.length}; expected ${batch.length * EMBED_DIMENSION}`
      );
    }
    out.set(result.data, i * EMBED_DIMENSION);
  }
  return { vectors: out, embedDim: EMBED_DIMENSION, modelId: EMBED_MODEL_ID };
}
