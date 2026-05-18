package com.playground.docs.api.response;

import com.playground.docs.application.dto.CursorPage;
import com.playground.docs.application.dto.SearchHitDto;
import java.util.List;

/**
 * Cursor-paginated response for {@code GET /api/docs/search} per M2 spec §6.1.
 */
public record SearchResponse(List<SearchHitResponse> items, String nextCursor) {

    public static SearchResponse from(CursorPage<SearchHitDto> page) {
        return new SearchResponse(
                page.items().stream().map(SearchHitResponse::from).toList(),
                page.nextCursor());
    }
}
