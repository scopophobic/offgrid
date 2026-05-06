import { Readability } from "@mozilla/readability";
import { load } from "cheerio";
import { parseHTML } from "linkedom";
import TurndownService from "turndown";
import type { CleanedSource, FetchedSource } from "./types.js";

const NOISY_SELECTORS = [
  "script",
  "style",
  "nav",
  "aside",
  "footer",
  "table.infobox",
  ".reference",
  ".mw-editsection",
  ".thumb",
  ".navbox",
  "sup.reference",
  ".reflist",
  ".toc",
  "#toc",
  ".hatnote",
  ".mw-empty-elt",
  ".metadata",
  ".shortdescription",
  ".sister-project",
  ".mw-parser-output > .mbox-small"
];

const turndown = new TurndownService({
  headingStyle: "atx",
  bulletListMarker: "-",
  codeBlockStyle: "fenced",
  hr: "---"
});

turndown.addRule("strip-figures", {
  filter: ["figure", "figcaption", "img"],
  replacement: () => ""
});

// Flatten markdown links to plain text. URLs are useless offline and add
// noise to embeddings: "[chess](/wiki/Chess \"Chess\")" -> "chess".
// Handles backslash-escaped parens inside the URL/title block (which turndown
// emits for Wikipedia URLs like "/wiki/Variation_(game_tree)").
const MD_LINK_REGEX = /\[([^\]]+)\]\((?:\\.|[^)])*\)/g;

function unescapeParens(text: string): string {
  return text.replace(/\\([()])/g, "$1");
}

const WIKI_CRUFT: Array<[RegExp, string]> = [
  [/\[edit\]/g, ""],
  [/\[citation needed\]/gi, ""],
  [/\[note\s*\d+\]/gi, ""],
  [/\(disambiguation\)/gi, ""],
  // Drop bare reference markers like [1], [12], [a].
  [/\[\d+\]/g, ""],
  [/\[[a-z]\]/g, ""],
  // Collapse leftover empty parentheses and repeated horizontal whitespace
  // (preserve newlines so paragraphs and headings stay separated).
  [/\(\s*\)/g, ""],
  [/[ \t]{2,}/g, " "],
  [/\n\s*\n\s*\n+/g, "\n\n"]
];

export function sanitizeSource(fetched: FetchedSource): CleanedSource {
  const $ = load(fetched.html);
  for (const sel of NOISY_SELECTORS) {
    $(sel).remove();
  }
  const pruned = $.html();

  let mainHtml = pruned;
  try {
    const { document } = parseHTML(
      `<!doctype html><html><body>${pruned}</body></html>`
    );
    const reader = new Readability(document as unknown as Document);
    const article = reader.parse();
    if (article?.content && article.content.length > 200) {
      mainHtml = article.content;
    }
  } catch {
    // Readability is best-effort. Fall back to cheerio output.
  }

  let markdown = turndown.turndown(mainHtml);
  // Strip markdown links (function form: also unescape any \(  \) inside link text).
  markdown = markdown.replace(MD_LINK_REGEX, (_, text: string) =>
    unescapeParens(text)
  );
  // Catch any stray escaped parens that survived link-stripping.
  markdown = unescapeParens(markdown);
  for (const [pattern, replacement] of WIKI_CRUFT) {
    markdown = markdown.replace(pattern, replacement);
  }

  return {
    sourceId: fetched.sourceId,
    url: fetched.url,
    title: fetched.title,
    license: fetched.license,
    fetchedAt: fetched.fetchedAt,
    markdown: markdown.trim()
  };
}
