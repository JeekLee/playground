package com.playground.massinggen.application.service;

import java.text.Normalizer;
import java.util.Objects;

/**
 * Slug derivation for the {@code Content-Disposition} filename per
 * ADR-18 §21 — {@code "massing-<briefSlug>-<timestamp>.3dm"}.
 *
 * <p>Rules:
 * <ul>
 *   <li>Lowercase; collapse runs of whitespace and non-alphanumeric chars
 *       to a single hyphen.</li>
 *   <li>Strip leading / trailing hyphens.</li>
 *   <li>Cap at 40 chars.</li>
 *   <li>Preserve Hangul (Korean) — Hangul code points pass through
 *       intact since they are alphabetic per Unicode.</li>
 *   <li>If the result is empty, return {@code "brief"} as a placeholder.</li>
 * </ul>
 */
public final class BriefSlug {

    private static final int MAX_LEN = 40;

    private BriefSlug() {}

    public static String of(String title) {
        Objects.requireNonNull(title, "title must not be null");
        // Normalize to NFC so the Korean composed forms are stable.
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(normalized.length());
        boolean lastWasHyphen = false;
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                int lower = Character.toLowerCase(codePoint);
                sb.appendCodePoint(lower);
                lastWasHyphen = false;
            } else {
                if (!lastWasHyphen && sb.length() > 0) {
                    sb.append('-');
                    lastWasHyphen = true;
                }
            }
        }
        // Trim trailing hyphens.
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.deleteCharAt(sb.length() - 1);
        }
        // Cap length by char count — code-point boundary safe enough at 40.
        if (sb.length() > MAX_LEN) {
            sb.setLength(MAX_LEN);
            while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
                sb.deleteCharAt(sb.length() - 1);
            }
        }
        if (sb.length() == 0) {
            return "brief";
        }
        return sb.toString();
    }
}
