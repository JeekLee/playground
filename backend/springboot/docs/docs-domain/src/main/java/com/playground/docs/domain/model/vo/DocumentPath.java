package com.playground.docs.domain.model.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Per-user directory path per M2 spec §4.1. Must satisfy the regex
 * <pre>^(/|(/[a-z0-9][a-z0-9-]*)+/)$</pre>
 * which is also the DB CHECK constraint. Root is the literal {@code "/"}.
 * Nested paths look like {@code "/agents/build-log/"} — always lowercase
 * ASCII with hyphens, always starts and ends with {@code "/"}.
 *
 * <p>S1 ships the column and validation but the directory tree UI / folder
 * picker / move action are M2 S2 / S3 — every S1 document defaults to the
 * root path {@link #ROOT}.
 */
public record DocumentPath(String value) {

    private static final Pattern PATTERN =
            Pattern.compile("^(/|(/[a-z0-9][a-z0-9-]*)+/)$");

    public static final DocumentPath ROOT = new DocumentPath("/");

    public DocumentPath {
        Objects.requireNonNull(value, "DocumentPath.value must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "DocumentPath does not match the required pattern: " + value);
        }
    }

    public static DocumentPath of(String value) {
        return new DocumentPath(value == null || value.isEmpty() ? "/" : value);
    }

    public static DocumentPath root() {
        return ROOT;
    }

    @Override
    public String toString() {
        return value;
    }
}
