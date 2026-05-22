package com.playground.docs.application.dto;

import java.util.List;

/**
 * Cursor-paginated result for the community feed / per-author feed / search
 * routes per M2 spec §6.1 (every list endpoint returns
 * {@code { items: [...], nextCursor: string? }}).
 *
 * <p>{@code nextCursor} is null when the current page is the last.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public static <T> CursorPage<T> of(List<T> items, String nextCursor) {
        return new CursorPage<>(items, nextCursor);
    }

    public static <T> CursorPage<T> empty() {
        return new CursorPage<>(List.of(), null);
    }
}
