import type { FetchedSource } from "../types.js";

const USER_AGENT =
  "OffgridKnowledgeFactory/0.1 (https://example.org/offgrid; offline-ai-assistant)";

interface WikipediaParseResponse {
  parse?: {
    title: string;
    pageid: number;
    text: string;
    sections?: Array<{ line: string; level: string; anchor: string }>;
  };
  error?: { code: string; info: string };
}

export interface FetchOptions {
  followLinks?: {
    maxPerArticle: number;
    keywordFilters: string[];
  };
}

export async function fetchWikipediaArticles(
  titles: string[],
  options: FetchOptions = {}
): Promise<FetchedSource[]> {
  const seen = new Set<string>();
  const results: FetchedSource[] = [];

  // Stage 1: breadth.
  for (const title of titles) {
    const fetched = await fetchOne(title);
    if (fetched && !seen.has(fetched.sourceId)) {
      seen.add(fetched.sourceId);
      results.push(fetched);
    }
  }

  // Stage 2: depth (one-hop link follow over Stage-1 results only).
  if (options.followLinks && options.followLinks.maxPerArticle > 0) {
    const followCount = options.followLinks.maxPerArticle;
    const filters = options.followLinks.keywordFilters.map((s) => s.toLowerCase());
    const newTitles: string[] = [];
    for (const fetched of results.slice(0, titles.length)) {
      const linkTitles = extractInternalLinks(fetched.html, followCount, filters);
      for (const linkTitle of linkTitles) {
        const id = `wikipedia/${linkTitle.replace(/ /g, "_")}`;
        if (!seen.has(id) && !newTitles.includes(linkTitle)) {
          newTitles.push(linkTitle);
        }
      }
    }
    for (const title of newTitles) {
      const fetched = await fetchOne(title);
      if (fetched && !seen.has(fetched.sourceId)) {
        seen.add(fetched.sourceId);
        results.push(fetched);
      }
    }
  }

  return results;
}

async function fetchOne(title: string): Promise<FetchedSource | null> {
  const normalized = title.replace(/ /g, "_");
  const url = new URL("https://en.wikipedia.org/w/api.php");
  url.searchParams.set("action", "parse");
  url.searchParams.set("page", normalized);
  url.searchParams.set("format", "json");
  url.searchParams.set("prop", "text|sections");
  url.searchParams.set("redirects", "1");
  url.searchParams.set("formatversion", "2");

  try {
    const res = await fetch(url, { headers: { "User-Agent": USER_AGENT } });
    if (!res.ok) {
      console.warn(`[wikipedia] ${title}: HTTP ${res.status}`);
      return null;
    }
    const data = (await res.json()) as WikipediaParseResponse;
    if (data.error) {
      console.warn(`[wikipedia] ${title}: ${data.error.code} ${data.error.info}`);
      return null;
    }
    if (!data.parse?.text) {
      console.warn(`[wikipedia] ${title}: no parse.text in response`);
      return null;
    }
    const articleTitle = data.parse.title;
    const slug = articleTitle.replace(/ /g, "_");
    return {
      sourceId: `wikipedia/${slug}`,
      url: `https://en.wikipedia.org/wiki/${slug}`,
      title: `Wikipedia: ${articleTitle}`,
      license: "CC BY-SA 4.0",
      fetchedAt: new Date().toISOString(),
      html: data.parse.text
    };
  } catch (err) {
    console.warn(`[wikipedia] ${title}: ${(err as Error).message}`);
    return null;
  }
}

function extractInternalLinks(
  html: string,
  maxCount: number,
  keywordFilters: string[]
): string[] {
  const linkRegex = /<a\s+href="\/wiki\/([^"#:?]+)"[^>]*>([^<]+)<\/a>/g;
  const titles: string[] = [];
  const seen = new Set<string>();
  let m: RegExpExecArray | null;
  while ((m = linkRegex.exec(html)) !== null && titles.length < maxCount) {
    const slug = decodeURIComponent(m[1]);
    if (
      slug.startsWith("File:") ||
      slug.startsWith("Special:") ||
      slug.startsWith("Help:") ||
      slug.startsWith("Wikipedia:") ||
      slug.startsWith("Category:") ||
      slug.startsWith("Template:")
    ) {
      continue;
    }
    const normalized = slug.replace(/_/g, " ");
    if (seen.has(normalized)) continue;
    seen.add(normalized);
    if (keywordFilters.length > 0) {
      const linkText = m[2].toLowerCase();
      const slugLower = normalized.toLowerCase();
      const match = keywordFilters.some(
        (kw) => linkText.includes(kw) || slugLower.includes(kw)
      );
      if (!match) continue;
    }
    titles.push(normalized);
  }
  return titles;
}
