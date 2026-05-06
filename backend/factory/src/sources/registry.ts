import type { FetchedSource, SourceConfig } from "../types.js";
import { fetchWikipediaArticles } from "./wikipedia.js";

export async function fetchAllSources(
  sources: SourceConfig[]
): Promise<FetchedSource[]> {
  const seen = new Set<string>();
  const all: FetchedSource[] = [];

  for (const config of sources) {
    if (config.kind === "wikipedia") {
      const fetched = await fetchWikipediaArticles(
        config.titles,
        config.follow_links
          ? {
              followLinks: {
                maxPerArticle: config.follow_links.max_per_article,
                keywordFilters: config.follow_links.keyword_filters
              }
            }
          : {}
      );
      for (const f of fetched) {
        if (!seen.has(f.sourceId)) {
          seen.add(f.sourceId);
          all.push(f);
        }
      }
    }
    // Future: other source kinds plug in here.
  }

  return all;
}
