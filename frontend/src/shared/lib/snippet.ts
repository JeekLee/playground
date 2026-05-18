/**
 * Safe rendering of OpenSearch highlight snippets.
 *
 * The `/api/docs/search` endpoint returns a `snippet` field whose only
 * markup is `<mark>...</mark>` around matched terms (spec §6.4). We must
 * never inject arbitrary HTML from upstream, so this helper escapes every
 * character first and then re-introduces just the `<mark>` envelope — the
 * design system maps it to `accent.soft` background per design doc §"Search
 * results" §Key elements.
 *
 * Returned shape is an array of `{ type, text }` segments rather than a
 * raw HTML string, so callers can lay it out with regular JSX (no
 * `dangerouslySetInnerHTML`).
 */

export interface SnippetSegment {
  /** `mark` = highlighted match, `text` = plain. */
  type: 'mark' | 'text';
  text: string;
}

const MARK_RE = /<mark[^>]*>([\s\S]*?)<\/mark>/gi;

export function parseSnippet(raw: string): SnippetSegment[] {
  if (!raw) return [];
  const segments: SnippetSegment[] = [];
  let cursor = 0;
  // Reset the regex on each call — global regexes are stateful.
  MARK_RE.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = MARK_RE.exec(raw)) !== null) {
    const matchStart = match.index;
    if (matchStart > cursor) {
      segments.push({ type: 'text', text: stripTags(raw.slice(cursor, matchStart)) });
    }
    segments.push({ type: 'mark', text: stripTags(match[1] ?? '') });
    cursor = MARK_RE.lastIndex;
  }
  if (cursor < raw.length) {
    segments.push({ type: 'text', text: stripTags(raw.slice(cursor)) });
  }
  return segments;
}

/**
 * Strip any HTML tags from a string. Used after we've already pulled
 * `<mark>` segments out — the remaining text should be plain content.
 * Belt-and-suspenders defense against a malformed upstream snippet.
 */
function stripTags(s: string): string {
  return s.replace(/<[^>]*>/g, '');
}
