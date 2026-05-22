package com.playground.massinggen.domain.summary;

import java.util.Locale;

/**
 * Korean-fixed summary formatter per ADR-18 §5.
 *
 * <p>Format: {@code "%d실 · %d층 · 총 %.0f m²"} — locale-independent,
 * ASCII-safe middot {@code ·}, single-byte digits, room count + floor count
 * + total area (square meters, no decimals). Example output for a 12-room,
 * 3-floor, 480 m² massing: {@code "12실 · 3층 · 총 480 m²"}.
 *
 * <p>Locale stability — the formatter pins {@link Locale#ROOT} so the same
 * input string lands regardless of the JVM's default locale (no thousands
 * separator drift, no Arabic / Indic digit substitution). Verified by
 * {@code MassingSummaryTest} comparing US and KOREA locale outputs.
 *
 * <p>This class is Spring-free (lives in {@code massing-gen-domain}) per the
 * ADR-02 v2 invariant; the use case calls {@link #format(int, int, double)}
 * inline at the end of the orchestrator.
 */
public final class MassingSummary {

    /** ADR-18 §5 — fixed Korean format string. */
    private static final String FORMAT = "%d실 · %d층 · 총 %.0f m²";

    private MassingSummary() {
        // utility class — instantiation disallowed
    }

    /**
     * Format the user-facing summary string.
     *
     * @param rooms       number of rooms in the extracted program
     * @param floors      computed floor count
     * @param totalAreaM2 sum of room areas (square meters)
     * @return Korean summary, e.g. {@code "12실 · 3층 · 총 480 m²"}
     */
    public static String format(int rooms, int floors, double totalAreaM2) {
        return String.format(Locale.ROOT, FORMAT, rooms, floors, totalAreaM2);
    }
}
