package com.playground.docs.api.response;

import com.playground.docs.application.dto.FolderListItemDto;

/**
 * Response item for {@code GET /api/docs/folders} per M2 spec §6.4
 * {@code FolderListItem}. Mirrors {@link FolderListItemDto} on the wire.
 */
public record FolderListItemResponse(String path, long count) {

    public static FolderListItemResponse from(FolderListItemDto dto) {
        return new FolderListItemResponse(dto.path(), dto.count());
    }
}
