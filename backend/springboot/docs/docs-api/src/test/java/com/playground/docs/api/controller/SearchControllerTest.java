package com.playground.docs.api.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playground.docs.application.dto.AuthorDto;
import com.playground.docs.application.dto.CursorPage;
import com.playground.docs.application.dto.SearchHitDto;
import com.playground.docs.application.service.DocumentSearchService;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.SharedExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SearchControllerTest {

    private final DocumentSearchService searchService = mock(DocumentSearchService.class);
    private final SearchController controller = new SearchController(searchService);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SharedExceptionHandler())
                .build();
    }

    @Test
    void public_scope_does_not_require_auth_header() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        when(searchService.searchPublic(eq("hello"), any()))
                .thenReturn(CursorPage.of(List.of(new SearchHitDto(
                        docId, "Hello", "public", "/",
                        new AuthorDto(authorId, "Alice", null),
                        "<mark>hello</mark> world",
                        Instant.parse("2026-05-18T00:00:00Z"),
                        Instant.parse("2026-05-18T00:00:00Z"))), null));

        mockMvc.perform(get("/search").param("q", "hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].documentId", is(docId.toString())))
                .andExpect(jsonPath("$.items[0].author.id", is(authorId.toString())))
                .andExpect(jsonPath("$.items[0].author.displayName", is("Alice")));
    }

    @Test
    void mine_scope_requires_auth_header() throws Exception {
        mockMvc.perform(get("/search").param("q", "hello").param("scope", "mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.USER_HEADER_MISSING.code())));
    }

    @Test
    void invalid_scope_returns_400() throws Exception {
        mockMvc.perform(get("/search").param("q", "x").param("scope", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.SEARCH_SCOPE_INVALID.code())));
    }

    @Test
    void blank_query_returns_400_via_app_service() throws Exception {
        // The app service throws on blank q; the controller relays it. We simulate
        // that by stubbing the call to throw a BadRequestException with the right code.
        when(searchService.searchPublic(eq(""), any()))
                .thenThrow(com.playground.shared.error.ExceptionCreator
                        .of(DocsErrorCode.SEARCH_QUERY_BLANK).build());

        mockMvc.perform(get("/search").param("q", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.SEARCH_QUERY_BLANK.code())));
    }
}
