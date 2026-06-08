package com.playground.docs.api.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playground.docs.application.dto.DocumentBodyDto;
import com.playground.docs.application.dto.DocumentManifestEntry;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.domain.exception.DocumentNotFoundException;
import com.playground.docs.domain.model.id.DocumentId;
import com.playground.shared.error.SharedExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifies the internal body-fetch route ({@code GET /internal/docs/{id}/body})
 * per ADR-12 §2. M3 rag-ingestion will be the production caller.
 */
class InternalDocumentControllerTest {

    private final DocumentAppService docService = mock(DocumentAppService.class);
    private final InternalDocumentController controller = new InternalDocumentController(docService);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SharedExceptionHandler())
                .build();
    }

    @Test
    void get_body_returns_doc_with_checksum() throws Exception {
        UUID docId = UUID.randomUUID();
        Instant updatedAt = Instant.parse("2026-05-18T00:00:00Z");
        when(docService.getBody(docId)).thenReturn(new DocumentBodyDto(
                docId, "hello world", "deadbeefchecksum", updatedAt));

        mockMvc.perform(get("/internal/docs/{id}/body", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(docId.toString())))
                .andExpect(jsonPath("$.body", is("hello world")))
                .andExpect(jsonPath("$.bodyChecksum", is("deadbeefchecksum")));
    }

    @Test
    void get_body_with_malformed_uuid_returns_404() throws Exception {
        mockMvc.perform(get("/internal/docs/{id}/body", "not-a-uuid"))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_body_when_doc_missing_returns_404() throws Exception {
        UUID docId = UUID.randomUUID();
        when(docService.getBody(docId)).thenThrow(new DocumentNotFoundException(DocumentId.of(docId)));

        mockMvc.perform(get("/internal/docs/{id}/body", docId))
                .andExpect(status().isNotFound());
    }

    // --- SP3a manifest route (GET /internal/docs/manifest) ---

    @Test
    void manifest_returnsIdTitleList() throws Exception {
        UUID user = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        when(docService.manifestForUser(eq(user), eq(30)))
                .thenReturn(List.of(new DocumentManifestEntry(docId, "My Brief")));

        mockMvc.perform(get("/internal/docs/manifest").param("userId", user.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].id", is(docId.toString())))
                .andExpect(jsonPath("$.documents[0].title", is("My Brief")));
    }

    @Test
    void manifest_emptyIsOk() throws Exception {
        UUID user = UUID.randomUUID();
        when(docService.manifestForUser(eq(user), eq(30))).thenReturn(List.of());

        mockMvc.perform(get("/internal/docs/manifest").param("userId", user.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").isArray())
                .andExpect(jsonPath("$.documents").isEmpty());
    }

    @Test
    void manifest_clampsLimit() throws Exception {
        UUID user = UUID.randomUUID();
        when(docService.manifestForUser(eq(user), eq(100))).thenReturn(List.of());

        mockMvc.perform(get("/internal/docs/manifest")
                        .param("userId", user.toString())
                        .param("limit", "9999"))
                .andExpect(status().isOk());
    }

    @Test
    void manifest_missingUserId_is400() throws Exception {
        mockMvc.perform(get("/internal/docs/manifest"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void manifest_badUserId_is400() throws Exception {
        mockMvc.perform(get("/internal/docs/manifest").param("userId", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
