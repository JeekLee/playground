package com.playground.docs.api.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playground.docs.application.dto.DocumentDetailDto;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.SharedExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-layer unit test for the multipart {@code POST /api/docs} variant
 * + the new {@code ?scope=mine} guard on {@code GET /api/docs} per M2 spec
 * §6.1/§6.2.
 *
 * <p>Uses standalone MockMvc (no full Spring context) so it runs without the
 * JPA / DataSource auto-configuration that the docs-api's
 * {@code runtimeOnly(":docs:docs-infra")} dependency would otherwise drag in.
 * The full JPA stack is exercised by {@code DocumentCrudIT} in docs-infra.
 */
class DocumentMultipartUploadTest {

    private final DocumentAppService docService = mock(DocumentAppService.class);
    private final DocumentController controller = new DocumentController(docService);
    private final SharedExceptionHandler exceptionHandler = new SharedExceptionHandler();

    private MockMvc mockMvc;

    private final UUID author = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Wire the shared exception handler so DocsErrorCode → 400 mapping is
        // exercised by the assertions below.
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @Test
    void multipart_upload_creates_document_with_file_contents_as_body() throws Exception {
        String content = "# Hello\n\nThis is markdown.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "intro.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));

        when(docService.create(any())).thenReturn(stubDetail("intro", content));

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(documentId.toString())))
                .andExpect(jsonPath("$.title", is("intro")))
                .andExpect(jsonPath("$.body", is(content)));

        verify(docService, times(1)).create(argThat(cmd ->
                cmd.authorId().equals(author)
                        && cmd.title().equals("intro")
                        && cmd.body().equals(content)
                        && cmd.path() == null));
    }

    @Test
    void multipart_upload_uses_explicit_title_when_supplied() throws Exception {
        String content = "body bytes";
        MockMultipartFile file = new MockMultipartFile(
                "file", "ignored-filename.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));

        when(docService.create(any())).thenReturn(stubDetail("Explicit Title", content));

        mockMvc.perform(multipart("/")
                        .file(file)
                        .param("title", "Explicit Title")
                        .param("path", "/notes/")
                        .header("X-User-Id", author.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("Explicit Title")));

        verify(docService).create(argThat(cmd ->
                cmd.title().equals("Explicit Title")
                        && cmd.body().equals(content)
                        && "/notes/".equals(cmd.path())));
    }

    @Test
    void multipart_upload_strips_markdown_extension_for_title() throws Exception {
        String content = "body";
        MockMultipartFile file = new MockMultipartFile(
                "file", "design-notes.markdown", "text/markdown", content.getBytes(StandardCharsets.UTF_8));

        when(docService.create(any())).thenReturn(stubDetail("design-notes", content));

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isCreated());

        verify(docService).create(argThat(cmd -> cmd.title().equals("design-notes")));
    }

    @Test
    void multipart_upload_without_file_returns_400() throws Exception {
        mockMvc.perform(multipart("/").header("X-User-Id", author.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.UPLOAD_FILE_MISSING.code())));

        verify(docService, times(0)).create(any());
    }

    @Test
    void json_post_still_works_after_multipart_added() throws Exception {
        // Sanity check — the JSON content-type variant is selected over the
        // multipart variant via the controller's `consumes` declaration.
        when(docService.create(any())).thenReturn(stubDetail("JSON Title", "json body"));

        mockMvc.perform(post("/")
                        .header("X-User-Id", author.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"JSON Title\",\"body\":\"json body\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("JSON Title")));
    }

    @Test
    void list_mine_requires_scope_mine_query_param() throws Exception {
        // Per spec §6.2 GET /api/docs/mine is deleted; the bare GET /api/docs
        // (which is what we see post-StripPrefix=2 → "/") with no scope must 400.
        mockMvc.perform(get("/").header("X-User-Id", author.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.SCOPE_REQUIRED.code())));
    }

    @Test
    void list_mine_rejects_unsupported_scope() throws Exception {
        mockMvc.perform(get("/")
                        .param("scope", "community")
                        .header("X-User-Id", author.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.SCOPE_REQUIRED.code())));
    }

    @Test
    void list_mine_rejects_combined_filters_in_s1() throws Exception {
        // Path / author filters land in S2; the bare ?scope=mine is the only
        // S1-supported shape.
        mockMvc.perform(get("/")
                        .param("scope", "mine")
                        .param("path", "/agents/")
                        .header("X-User-Id", author.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.SCOPE_FILTER_UNSUPPORTED.code())));
    }

    private DocumentDetailDto stubDetail(String title, String body) {
        Instant now = Instant.parse("2026-05-18T00:00:00Z");
        return new DocumentDetailDto(
                documentId.toString(),
                author.toString(),
                title,
                body,
                "private",
                "/",
                null,
                now,
                now);
    }
}
