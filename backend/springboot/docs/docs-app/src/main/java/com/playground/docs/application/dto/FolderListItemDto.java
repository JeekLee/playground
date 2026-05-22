package com.playground.docs.application.dto;

/**
 * Use-case I/O DTO for {@code GET /api/docs/folders} list items per M2 spec
 * §6.4 {@code FolderListItem}.
 *
 * @param path  per-user folder path (always starts and ends with {@code /});
 *              e.g. {@code "/"}, {@code "/agents/"}, {@code "/agents/build-log/"}
 * @param count number of caller's documents whose {@code path} equals this
 *              exact value (folders are implicit per spec §4.1 — no folders
 *              table)
 */
public record FolderListItemDto(String path, long count) {
}
