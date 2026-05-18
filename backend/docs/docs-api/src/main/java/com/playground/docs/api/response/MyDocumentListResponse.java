package com.playground.docs.api.response;

import com.playground.docs.application.dto.MyDocumentListItemDto;
import java.util.List;

/**
 * Wraps the {@code GET /api/docs/mine} response in {@code { items: [...] }} so
 * adding cursor pagination in M2 S2 (per spec §6.1) is a non-breaking addition
 * of a {@code nextCursor} field at the top level.
 */
public record MyDocumentListResponse(List<MyDocumentListItemResponse> items) {

    public static MyDocumentListResponse from(List<MyDocumentListItemDto> dtos) {
        return new MyDocumentListResponse(dtos.stream().map(MyDocumentListItemResponse::from).toList());
    }
}
