package com.playground.docs.api.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.playground.docs.application.port.BlobStoragePort;
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
 * M6.1 ADR-12 §A12.5 — controller-layer tests for the multipart PDF upload
 * path. PDFs stream straight to MinIO; the controller returns 201 with
 * {@code extraction_status='pending_extraction'} and the async worker
 * materializes the body via Kafka. The PdfExtractorPort is NOT called on
 * the request thread anymore.
 */
class DocumentPdfUploadTest {

    private final DocumentAppService docService = mock(DocumentAppService.class);
    private final DocumentFeedService feedService = mock(DocumentFeedService.class);
    private final BlobStoragePort blobStorage = mock(BlobStoragePort.class);
    private final DocumentController controller =
            new DocumentController(docService, feedService, blobStorage);
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
    void pdf_upload_streams_to_minio_and_returns_pending_extraction() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", PDF_MAGIC);
        when(docService.createWithId(any(UUID.class), any()))
                .thenReturn(stubDetail("report", "", "application/pdf", "pending_extraction"));

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title", is("report")))
                .andExpect(jsonPath("$.mimeType", is("application/pdf")))
                .andExpect(jsonPath("$.extractionStatus", is("pending_extraction")));

        verify(blobStorage, times(1)).putObject(anyString(), any(), anyLong(), anyString());
        verify(docService, times(1)).createWithId(any(UUID.class), argThat(cmd ->
                cmd.authorId().equals(author)
                        && cmd.title().equals("report")
                        && cmd.isAsyncExtraction()
                        && cmd.mimeType().wireValue().equals("application/pdf")));
    }

    @Test
    void pdf_upload_with_octet_stream_content_type_is_accepted() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.pdf", "application/octet-stream", PDF_MAGIC);
        when(docService.createWithId(any(UUID.class), any()))
                .thenReturn(stubDetail("scan", "", "application/pdf", "pending_extraction"));

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isCreated());
    }

    @Test
    void pdf_with_wrong_content_type_is_rejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "image/png", PDF_MAGIC);

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.INVALID_FILE_TYPE.code())));

        verify(blobStorage, never()).putObject(anyString(), any(), anyLong(), anyString());
        verify(docService, never()).create(any());
        verify(docService, never()).createWithId(any(UUID.class), any());
    }

    @Test
    void pdf_without_magic_bytes_is_rejected() throws Exception {
        byte[] fakeBytes = "this is not a pdf".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", fakeBytes);

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.INVALID_FILE_TYPE.code())));

        verify(blobStorage, never()).putObject(anyString(), any(), anyLong(), anyString());
        verify(docService, never()).createWithId(any(UUID.class), any());
    }

    @Test
    void file_with_unsupported_extension_is_rejected() throws Exception {
        byte[] bytes = "anything".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is(DocsErrorCode.INVALID_FILE_TYPE.code())));
    }

    @Test
    void uppercase_pdf_suffix_is_accepted() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "REPORT.PDF", "application/pdf", PDF_MAGIC);
        when(docService.createWithId(any(UUID.class), any()))
                .thenReturn(stubDetail("REPORT", "", "application/pdf", "pending_extraction"));

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isCreated());
    }

    @Test
    void markdown_upload_streams_to_minio_too() throws Exception {
        String content = "# heading";
        MockMultipartFile file = new MockMultipartFile(
                "file", "intro.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));
        when(docService.createWithId(any(UUID.class), any()))
                .thenReturn(stubDetail("intro", "", "text/markdown", "pending_extraction"));

        mockMvc.perform(multipart("/").file(file).header("X-User-Id", author.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mimeType", is("text/markdown")));

        verify(blobStorage, times(1)).putObject(anyString(), any(), anyLong(), anyString());
        verify(docService).createWithId(any(UUID.class), argThat(cmd ->
                cmd.mimeType() != null && cmd.mimeType().wireValue().equals("text/markdown")));
    }

    private DocumentDetailDto stubDetail(String title, String body, String mimeType, String extractionStatus) {
        Instant now = Instant.parse("2026-05-22T00:00:00Z");
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
                extractionStatus,
                null,
                false,
                null,
                now,
                now);
    }
}
