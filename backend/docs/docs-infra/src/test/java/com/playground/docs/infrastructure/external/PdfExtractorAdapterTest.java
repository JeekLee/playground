package com.playground.docs.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.docs.application.port.PdfExtractorPort;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M6.1 ADR-12 §A12.9 / §A12.10 — unit tests for the all-pages-Vision
 * extractor. PDFBox text-layer fast-path is retired; every page goes
 * through the mocked {@link VisionOcrAdapter}.
 */
class PdfExtractorAdapterTest {

    private final VisionOcrAdapter visionOcr = mock(VisionOcrAdapter.class);
    private ThreadPoolExecutor extractionExecutor;
    private PdfExtractorAdapter extractor;

    @BeforeEach
    void setUp() {
        // Inline executor for deterministic test ordering.
        extractionExecutor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(64));
        extractor = new PdfExtractorAdapter(visionOcr, extractionExecutor, 100, 60, 2);
    }

    @AfterEach
    void tearDown() {
        extractionExecutor.shutdownNow();
    }

    @Test
    void single_page_pdf_routes_through_vision_ocr() throws IOException {
        byte[] pdfBytes = singlePagePdfWithText("Some text on page one.");
        when(visionOcr.toMarkdown(any(byte[].class))).thenReturn("# Page 1");

        PdfExtractorPort.Result result = extractor.extract(pdfBytes);

        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.failedPages()).isEqualTo(0);
        assertThat(result.markdown()).isEqualTo("# Page 1");
        verify(visionOcr, atLeast(1)).toMarkdown(any(byte[].class));
    }

    @Test
    void multi_page_pdf_concatenates_with_double_newline_in_page_order() throws IOException {
        byte[] pdfBytes = twoPagePdfTextPlusBlank("First page content.");
        // Both pages go through Vision. Stub returns deterministic strings.
        when(visionOcr.toMarkdown(any(byte[].class)))
                .thenReturn("PAGE-A")
                .thenReturn("PAGE-B");

        PdfExtractorPort.Result result = extractor.extract(pdfBytes);

        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.markdown()).contains("\n\n");
        // Order preservation matters — both stubbed strings must appear.
        assertThat(result.markdown()).contains("PAGE-A").contains("PAGE-B");
    }

    @Test
    void empty_byte_array_throws_pdf_corrupted() {
        assertThatThrownBy(() -> extractor.extract(new byte[0]))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(DocsErrorCode.PDF_CORRUPTED.defaultMessage());
    }

    @Test
    void malformed_bytes_throw_pdf_corrupted() {
        byte[] junk = "this is definitely not a pdf body".getBytes();
        assertThatThrownBy(() -> extractor.extract(junk))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void empty_vision_response_counts_as_failed_page() throws IOException {
        byte[] pdfBytes = singlePagePdfWithText("X");
        when(visionOcr.toMarkdown(any(byte[].class))).thenReturn("");

        PdfExtractorPort.Result result = extractor.extract(pdfBytes);

        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.failedPages()).isEqualTo(1);
        assertThat(result.failureRate()).isEqualTo(1.0d);
    }

    // --- PDF fixture builders ---

    private static byte[] singlePagePdfWithText(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] twoPagePdfTextPlusBlank(String firstPageText) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(firstPageText);
                cs.endText();
            }
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
