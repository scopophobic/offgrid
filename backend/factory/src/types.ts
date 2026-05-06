export type SourceKind = "wikipedia";

export interface WikipediaSourceConfig {
  kind: "wikipedia";
  titles: string[];
  follow_links?: {
    max_per_article: number;
    keyword_filters: string[];
  };
}

export type SourceConfig = WikipediaSourceConfig;

export interface TopicConfig {
  id: string;
  title: string;
  description: string;
  tags: string[];
  sources: SourceConfig[];
  maxChunks: number;
  maxBytes: number;
}

export interface FetchedSource {
  sourceId: string;
  url: string;
  title: string;
  license: string;
  fetchedAt: string;
  html: string;
}

export interface CleanedSource {
  sourceId: string;
  url: string;
  title: string;
  license: string;
  fetchedAt: string;
  markdown: string;
}

export interface Chunk {
  id: string;
  text: string;
  sourceId: string;
  sectionPath: string;
  position: number;
  charCount: number;
  tokenCountEst: number;
}

export interface PackManifest {
  schemaVersion: number;
  id: string;
  title: string;
  description: string;
  version: string;
  generatedAt: string;
  embedModel: string;
  embedDim: number;
  embedDtype: "f32";
  numChunks: number;
  numSources: number;
  sizeBytes: number;
  sha256: string;
  tags: string[];
}

export interface SourceRecord {
  sourceId: string;
  url: string;
  title: string;
  license: string;
  fetchedAt: string;
}
