package com.playground.docs.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.playground.docs.search.application.service.SearchDocumentsService;
import com.playground.docs.search.application.service.SearchDocumentsService.SearchOutcome;
import com.playground.shared.chat.SourceRef;
import com.playground.shared.error.SharedExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MockMvc test for {@link SearchToolController} (agentic-search spec D1, D5).
 * Verifies the X-User-Id requirement and the NDJSON terminal wire: success is
 * one {@code result} event line; a service failure is one {@code error} event
 * line — HTTP stays 200 either way (stream contract: post-handshake failures
 * are terminal error events).
 */
class SearchToolControllerTest {

    private final SearchDocumentsService service = mock(SearchDocumentsService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SearchToolController controller = new SearchToolController(service, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SharedExceptionHandler())
                .build();
    }

    @Test
    void requiresUserIdHeader() throws Exception {
        mockMvc.perform(post("/internal/tools/search-documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"hello\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNdjsonTerminalResult() throws Exception {
        UUID caller = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(service.search(eq(caller), eq("how to build"), any(), eq(null)))
                .thenReturn(new SearchOutcome(
                        List.of(new SourceRef(
                                "document",
                                "Guide",
                                "excerpt body",
                                "https://playground.jeeklee.com/docs/" + docId)),
                        1,
                        "how to build — 1건"));

        MvcResult result = mockMvc.perform(post("/internal/tools/search-documents")
                        .header("X-User-Id", caller.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"how to build\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        Assertions.assertThat(body).endsWith("\n");
        Assertions.assertThat(body.strip()).doesNotContain("\n"); // single line
        Assertions.assertThat(body).contains("\"event\":\"result\"");
        Assertions.assertThat(body).contains("\"summary\":\"how to build — 1건\"");
        Assertions.assertThat(body).contains("\"totalFound\":1");

        // SourceRef field shape — corpus-agnostic citation (SP3b spec D2).
        Assertions.assertThat(body).contains("\"sourceType\":\"document\"");
        Assertions.assertThat(body).contains("\"title\":\"Guide\"");
        Assertions.assertThat(body).contains("\"content\":\"excerpt body\"");
        Assertions.assertThat(body)
                .contains("\"uri\":\"https://playground.jeeklee.com/docs/" + docId + "\"");
        // The retired doc-citation fields must NOT appear on the wire.
        Assertions.assertThat(body).doesNotContain("documentId");
        Assertions.assertThat(body).doesNotContain("chunkIndex");
        Assertions.assertThat(body).doesNotContain("visibility");
    }

    @Test
    void embeddingFailureMapsToErrorEvent() throws Exception {
        UUID caller = UUID.randomUUID();
        when(service.search(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("gateway down"));

        MvcResult result = mockMvc.perform(post("/internal/tools/search-documents")
                        .header("X-User-Id", caller.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"how to build\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        Assertions.assertThat(body).contains("\"event\":\"error\"");
        Assertions.assertThat(body).contains("\"code\":\"SEARCH_EMBEDDING_FAILED\"");
        Assertions.assertThat(body).contains("\"status\":502");
    }

    @Test
    void requiresQuery() throws Exception {
        UUID caller = UUID.randomUUID();
        mockMvc.perform(post("/internal/tools/search-documents")
                        .header("X-User-Id", caller.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
