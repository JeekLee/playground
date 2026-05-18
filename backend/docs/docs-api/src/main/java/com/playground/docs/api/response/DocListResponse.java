package com.playground.docs.api.response;

import com.playground.docs.application.dto.CursorPage;
import com.playground.docs.application.dto.DocListItemDto;
import java.util.List;

/**
 * Cursor-paginated response for {@code GET /api/docs} (community feed) and
 * {@code GET /api/docs?author=...} (per-author feed) per M2 spec §6.1.
 */
public record DocListResponse(List<DocListItemResponse> items, String nextCursor) {

    public static DocListResponse from(CursorPage<DocListItemDto> page) {
        return new DocListResponse(
                page.items().stream().map(DocListItemResponse::from).toList(),
                page.nextCursor());
    }
}
