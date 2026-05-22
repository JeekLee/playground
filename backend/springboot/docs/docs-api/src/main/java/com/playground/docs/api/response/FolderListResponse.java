package com.playground.docs.api.response;

import com.playground.docs.application.dto.FolderListItemDto;
import java.util.List;

/**
 * Response envelope for {@code GET /api/docs/folders} per M2 spec §6.1 row +
 * §6.4 ({@code FolderListItem[]}). Single {@code items} field keeps the
 * shape consistent with {@code DocListResponse} / {@code MyDocumentListResponse}.
 */
public record FolderListResponse(List<FolderListItemResponse> items) {

    public static FolderListResponse from(List<FolderListItemDto> items) {
        return new FolderListResponse(
                items.stream().map(FolderListItemResponse::from).toList());
    }
}
