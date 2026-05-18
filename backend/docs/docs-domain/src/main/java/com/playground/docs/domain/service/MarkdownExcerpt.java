package com.playground.docs.domain.service;

import java.util.regex.Pattern;

/**
 * Pure utility implementing the canonical excerpt derivation per M2 spec §4.3:
 * <pre>
 *   excerpt(body) =
 *     strip_markdown(body, strong)
 *     |> normalize_whitespace
 *     |> take(160)
 *     |> append('…' if truncated)
 * </pre>
 *
 * <p>POJO per ADR-02 v2 (no Spring, no JPA, no Jackson). Lives in the domain
 * package because the algorithm is part of the bounded context's contract — the
 * spec calls it "canonical" and "byte-stable for a given body input".
 *
 * <p>This is intentionally a small ad-hoc stripper, not a full Markdown
 * parser — it covers the M2-supported set (headings, fenced + inline code,
 * links, images, bold/italic markers, blockquotes, list markers). HTML in MD
 * is sanitized out by the renderer (spec §9), so we do not bother with it
 * here.
 */
public final class MarkdownExcerpt {

    private MarkdownExcerpt() {}

    /** Maximum visible characters before ellipsis truncation per spec §4.3. */
    public static final int MAX_CHARS = 160;

    /** Ellipsis appended on truncation. Single Unicode codepoint. */
    public static final String ELLIPSIS = "…"; // "…"

    // Fenced code block: ``` ... ``` (greedy across newlines).
    private static final Pattern FENCED_CODE = Pattern.compile("```[\\s\\S]*?```");
    // Inline code: `...`
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]*)`");
    // Image: ![alt](url) — drop entirely (no alt text in excerpt; conservative).
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    // Link: [text](url) — keep text, drop URL.
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    // Heading marker at line start: any number of # + space.
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+");
    // Blockquote marker at line start.
    private static final Pattern BLOCKQUOTE = Pattern.compile("(?m)^>\\s?");
    // Unordered list marker at line start.
    private static final Pattern ULIST = Pattern.compile("(?m)^\\s*[-*+]\\s+");
    // Ordered list marker at line start.
    private static final Pattern OLIST = Pattern.compile("(?m)^\\s*\\d+\\.\\s+");
    // Bold/italic: **text** or __text__ → text.
    private static final Pattern STRONG_DOUBLE = Pattern.compile("(\\*\\*|__)(.+?)\\1");
    // Bold/italic: *text* or _text_ → text.
    private static final Pattern EM_SINGLE = Pattern.compile("(?<![*_])([*_])(?=\\S)(.+?)(?<=\\S)\\1(?![*_])");
    // Strikethrough: ~~text~~ → text.
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    // Whitespace collapse.
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Derive an excerpt from the supplied raw Markdown body. Null and empty
     * input both return the empty string — the spec calls excerpt "a String"
     * (non-null) on every DTO that carries document metadata.
     */
    public static String of(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String stripped = body;
        // Order matters: fenced code first (so a triple-backtick block doesn't
        // get partially mangled by inline-code stripping), then inline code,
        // then images, then links, etc.
        stripped = FENCED_CODE.matcher(stripped).replaceAll(" ");
        stripped = INLINE_CODE.matcher(stripped).replaceAll("$1");
        stripped = IMAGE.matcher(stripped).replaceAll(" ");
        stripped = LINK.matcher(stripped).replaceAll("$1");
        stripped = HEADING.matcher(stripped).replaceAll("");
        stripped = BLOCKQUOTE.matcher(stripped).replaceAll("");
        stripped = ULIST.matcher(stripped).replaceAll("");
        stripped = OLIST.matcher(stripped).replaceAll("");
        stripped = STRONG_DOUBLE.matcher(stripped).replaceAll("$2");
        stripped = EM_SINGLE.matcher(stripped).replaceAll("$2");
        stripped = STRIKE.matcher(stripped).replaceAll("$1");

        // Normalize whitespace: replace any run of \s with a single space; trim ends.
        String normalized = WHITESPACE.matcher(stripped).replaceAll(" ").trim();
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.length() <= MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_CHARS) + ELLIPSIS;
    }
}
