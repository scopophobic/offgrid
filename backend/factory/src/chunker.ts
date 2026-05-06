import { createHash } from "node:crypto";
import type { Chunk, CleanedSource } from "./types.js";

const TARGET_CHARS = 2000; // ~512 tokens for English prose
const MIN_CHARS = 200;
const MAX_CHARS = 3200; // ~800 tokens
const OVERLAP_CHARS = 240; // ~12% overlap

interface Section {
  path: string;
  paragraphs: string[];
}

export function chunkSource(source: CleanedSource): Chunk[] {
  const sections = parseMarkdownSections(source.markdown);
  const chunks: Chunk[] = [];
  let position = 0;

  for (const section of sections) {
    for (const text of chunkSection(section)) {
      const id = createHash("sha256")
        .update(`${source.sourceId}:${position}`)
        .digest("hex")
        .slice(0, 16);
      chunks.push({
        id,
        text,
        sourceId: source.sourceId,
        sectionPath: section.path,
        position,
        charCount: text.length,
        tokenCountEst: estimateTokens(text)
      });
      position += 1;
    }
  }
  return chunks;
}

function parseMarkdownSections(markdown: string): Section[] {
  const lines = markdown.split("\n");
  const sections: Section[] = [];
  const headingStack: string[] = [];
  let buffer: string[] = [];
  let currentPath = "Introduction";

  function flush() {
    if (buffer.length === 0) return;
    const paragraphs = buffer
      .join("\n")
      .split(/\n{2,}/)
      .map((p) => p.trim())
      .filter(Boolean);
    if (paragraphs.length > 0) {
      sections.push({ path: currentPath, paragraphs });
    }
    buffer = [];
  }

  for (const line of lines) {
    const headingMatch = /^(#{1,4})\s+(.*?)\s*$/.exec(line);
    if (headingMatch) {
      flush();
      const level = headingMatch[1].length;
      const text = headingMatch[2].trim();
      headingStack.length = level - 1;
      headingStack[level - 1] = text;
      currentPath = headingStack.filter(Boolean).join(" > ") || text;
      continue;
    }
    buffer.push(line);
  }
  flush();
  return sections;
}

function chunkSection(section: Section): string[] {
  const merged: string[] = [];
  let acc = "";

  for (const p of section.paragraphs) {
    const candidate = acc ? `${acc}\n\n${p}` : p;
    if (candidate.length <= TARGET_CHARS) {
      acc = candidate;
      continue;
    }
    if (acc.length === 0) {
      // A single paragraph already exceeds TARGET_CHARS. Split mid-paragraph
      // at the latest sentence boundary that keeps each piece under MAX_CHARS.
      merged.push(...splitLongParagraph(p));
      continue;
    }
    if (acc.length >= MIN_CHARS) {
      merged.push(acc.trim());
      acc = p;
    } else {
      // acc is too small to stand alone; force-grow it past MIN even if we
      // overshoot TARGET, then split if necessary.
      acc = `${acc}\n\n${p}`;
      if (acc.length > MAX_CHARS) {
        merged.push(...splitLongParagraph(acc));
        acc = "";
      }
    }
  }
  if (acc.trim().length > 0) {
    if (acc.length < MIN_CHARS && merged.length > 0) {
      merged[merged.length - 1] = `${merged[merged.length - 1]}\n\n${acc.trim()}`;
    } else {
      merged.push(acc.trim());
    }
  }

  return applyOverlap(merged);
}

function splitLongParagraph(p: string): string[] {
  if (p.length <= MAX_CHARS) return [p];
  const sentences = p.split(/(?<=[.!?])\s+/);
  const out: string[] = [];
  let acc = "";
  for (const s of sentences) {
    const candidate = acc ? `${acc} ${s}` : s;
    if (candidate.length > TARGET_CHARS && acc.length >= MIN_CHARS) {
      out.push(acc.trim());
      acc = s;
    } else {
      acc = candidate;
    }
  }
  if (acc.trim().length > 0) {
    out.push(acc.trim());
  }
  return out;
}

function applyOverlap(chunks: string[]): string[] {
  if (chunks.length <= 1) return chunks;
  const out: string[] = [chunks[0]];
  for (let i = 1; i < chunks.length; i++) {
    const prev = out[out.length - 1];
    const tailStart = Math.max(0, prev.length - OVERLAP_CHARS);
    const tail = prev.slice(tailStart);
    const overlapTail = tail.split(/(?<=[.!?])\s+/).slice(-2).join(" ").trim();
    if (overlapTail.length > 0) {
      out.push(`${overlapTail}\n\n${chunks[i]}`);
    } else {
      out.push(chunks[i]);
    }
  }
  return out;
}

function estimateTokens(text: string): number {
  return Math.ceil(text.length / 4);
}
