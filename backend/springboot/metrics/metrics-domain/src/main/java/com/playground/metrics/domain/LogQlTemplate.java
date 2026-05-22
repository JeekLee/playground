package com.playground.metrics.domain;

/**
 * LogQL template constants per ADR-15 §11 + spec §6. The single template
 * is {@code {container="<service>"} |~ "<search>" | json}; {@code <service>}
 * is validated against {@link ServiceAllowlist} before substitution, and
 * {@code <search>} is escaped into a quoted-string by the adapter.
 *
 * <p>Loki's label set per ADR-15 §11 is {@code container}, {@code service},
 * {@code source} — {@code level} is queried inside the JSON pipeline as
 * {@code | json | level=~"WARN|ERROR"} rather than as a label.
 */
public final class LogQlTemplate {

    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_LIMIT = 200;

    private LogQlTemplate() {
        // static
    }

    /**
     * Build the LogQL query string for the {@code /api/metrics/logs}
     * endpoint. Caller-supplied {@code service} must have passed
     * {@link ServiceAllowlist#contains(String)} before this method is invoked.
     */
    public static String forService(String service, String search) {
        if (!ServiceAllowlist.contains(service)) {
            throw new IllegalArgumentException("Unknown service: " + service);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{container=\"").append(service).append("\"}");
        if (search != null && !search.isBlank()) {
            sb.append(" |~ \"").append(escape(search)).append("\"");
        }
        sb.append(" | json");
        return sb.toString();
    }

    /**
     * Minimal escape for the LogQL line-filter regex literal — escapes the
     * double-quote that would terminate the regex string. The {@code |~}
     * operator interprets the body as a regex; tilde-class metacharacters
     * are accepted by Loki by design (operator-supplied search literals).
     */
    static String escape(String search) {
        StringBuilder sb = new StringBuilder(search.length() + 8);
        for (int i = 0; i < search.length(); i++) {
            char c = search.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Clamp the operator-supplied {@code limit} to {@link #MAX_LIMIT}. */
    public static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
