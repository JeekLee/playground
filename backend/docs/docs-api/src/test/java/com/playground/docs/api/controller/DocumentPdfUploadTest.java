package com.playground.docs.api.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.playground.docs.application.dto.DocumentDetailDto;
import com.playground.docs.application.port.PdfExtractorPort;
import com.playground.docs.application.service.DocumentAppService;
import com.playground.docs.application.service.DocumentFeedService;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.SharedExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * M6 ADR-16 — controller-layer tests for the PDF upload path. Mirrors
 * {@link DocumentMultipartUploadTest} for the Markdown path. Uses standalone
 * MockMvc + a mocked {@link PdfExtractorPort} so the test runs without
 * PDFBox / Spring AI on the classpath (the real extractor lives in
 * docs-infra).
 */
class DocumentPdfUploadTest {

    private final DocumentAppService docService = mock(DocumentAppService.class);
    private final DocumentFeedService feedService = mock(DocumentFeedService.class);
    private final PdfExtractorPort pdfExtractor = mock(PdfExtractorPort.class);
    private final DocumentController controller =
            new DocumentController(docService, feedService, pdfExtractor);
    private final SharedExceptionHandler exceptionHandler = new SharedExceptionHandler();

    private MockMvc mockMvc;

    private final UUID author = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    /** Minimal PDF magic bytes — controller only sniffs offset 0. */
    private static final byte[] PDF_MAGIC = "%PDF-1.4\n".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    @Test
    void pdf_upload_creates_document_with_extracted_markdown() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", PDF_MAGIC);
        String extracted = "# Report\n\nExtracted body";
        when(pdfExtractor.extractToMarkdown(any(byte[].class))).thenReturn(extracted);
        when(docService.create(any())).thenReturn(stubDetail("report", extracted, "application/pdf"));

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("report")))
                .andExpect(jsonPath("$.body", is(extracted)))
                .andExpect(jsonPath("$.mimeType", is("application/pdf")));

        // The command must propagate the PDF mime type so the app service
        // stamps documents.mime_type='application/pdf' on the new row.
        verify(docService, times(1)).create(argThat(cmd ->
                cmd.authorId().equals(author)
                        && cmd.title().equals("report")
                        && cmd.body().equals(extracted)
                        && cmd.mimeType() != null
                        && cmd.mimeType().wireValue().equals("application/pdf")));
    }

    @Test
    void pdf_upload_with_octet_stream_content_type_is_accepted() throws Exception {
        // Browsers occasionally tag PDFs as application/octet-stream when no
        // local handler is registered. The 3-step gate accepts it because
        // the magic-byte check is the real authority.
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.pdf", "application/octet-stream", PDF_MAGIC);
        when(pdfExtractor.extractToMarkdown(any(byte[].class))).thenReturn("body");
        when(docService.create(any())).thenReturn(stubDetail("scan", "body", "application/pdf"));

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isCreated());
    }

    @Test
    void pdf_with_wrong_content_type_is_rejected() throws Exception {
        // image/png with a .pdf suffix — the step-2 gate fails.
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "image/png", PDF_MAGIC);

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.INVALID_FILE_TYPE.code())));

        verify(pdfExtractor, never()).extractToMarkdown(any(byte[].class));
        verify(docService, never()).create(any());
    }

    @Test
    void pdf_without_magic_bytes_is_rejected() throws Exception {
        // .pdf suffix + application/pdf content type but the body bytes
        // are NOT a real PDF — step-3 magic byte check fails.
        byte[] fakeBytes = "this is not a pdf".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", fakeBytes);

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.INVALID_FILE_TYPE.code())));

        verify(pdfExtractor, never()).extractToMarkdown(any(byte[].class));
    }

    @Test
    void file_with_unsupported_extension_is_rejected() throws Exception {
        // .docx — neither .md/.markdown nor .pdf. Step-1 suffix gate fails.
        byte[] bytes = "anything".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.INVALID_FILE_TYPE.code())));
    }

    @Test
    void uppercase_pdf_suffix_is_accepted() throws Exception {
        // Suffix check is case-insensitive (ADR-16 verbatim).
        MockMultipartFile file = new MockMultipartFile(
                "file", "REPORT.PDF", "application/pdf", PDF_MAGIC);
        when(pdfExtractor.extractToMarkdown(any(byte[].class))).thenReturn("body");
        when(docService.create(any())).thenReturn(stubDetail("REPORT", "body", "application/pdf"));

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isCreated());
    }

    @Test
    void markdown_upload_still_works_after_pdf_branch_added() throws Exception {
        // Sanity — the Markdown path stays unchanged.
        String content = "# heading";
        MockMultipartFile file = new MockMultipartFile(
                "file", "intro.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));
        when(docService.create(any())).thenReturn(stubDetail("intro", content, "text/markdown"));

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType", is("text/markdown")));

        verify(pdfExtractor, never()).extractToMarkdown(any(byte[].class));
        verify(docService).create(argThat(cmd ->
                cmd.mimeType() != null && cmd.mimeType().wireValue().equals("text/markdown")));
    }

    private DocumentDetailDto stubDetail(String title, String body, String mimeType) {
        Instant now = Instant.parse("2026-05-21T00:00:00Z");
        return new DocumentDetailDto(
                documentId.toString(),
                author.toString(),
                null,
                title,
                body,
                "",
                "private",
                "/",
                0L,
                0L,
                null,
                mimeType,
                null,
                now,
                now);
    }
}
