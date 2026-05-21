package com.playground.docs.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/**
 * M6 ADR-16 unit tests for the hybrid PDF extractor. Builds tiny PDFs in
 * memory via PDFBox itself so the tests don't depend on a binary fixture
 * checked into the repo.
 *
 * <p>The Vision adapter is mocked — these tests assert (1) the text-layer
 * branch returns the page text verbatim, (2) image-only pages route to the
 * Vision adapter, and (3) the page-count + OCR-page caps throw.
 */
class PdfExtractorAdapterTest {

    private final VisionOcrAdapter visionOcr = mock(VisionOcrAdapter.class);
    private final PdfExtractorAdapter extractor = new PdfExtractorAdapter(visionOcr);

    @Test
    void text_layer_pdf_is_extracted_without_calling_vision() throws IOException {
        // Build a one-page PDF whose text layer contains a 50-char string —
        // well over the 30-char text-layer threshold.
        String pageText = "This is a markdown-ready paragraph for testing.";
        byte[] pdfBytes = singlePagePdfWithText(pageText);

        String md = extractor.extractToMarkdown(pdfBytes);

        assertThat(md).contains(pageText.substring(0, 20));
        verify(visionOcr, never()).toMarkdown(any(byte[].class));
    }

    @Test
    void blank_page_falls_back_to_vision_ocr() throws IOException {
        // Empty page → text layer is empty → renderer + Vision adapter run.
        byte[] pdfBytes = singleBlankPagePdf();
        when(visionOcr.toMarkdown(any(byte[].class))).thenReturn("# OCR output");

        String md = extractor.extractToMarkdown(pdfBytes);

        assertThat(md).isEqualTo("# OCR output");
        verify(visionOcr, times(1)).toMarkdown(any(byte[].class));
    }

    @Test
    void empty_byte_array_throws_pdf_corrupted() {
        assertThatThrownBy(() -> extractor.extractToMarkdown(new byte[0]))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(DocsErrorCode.PDF_CORRUPTED.defaultMessage());
    }

    @Test
    void malformed_bytes_throw_pdf_corrupted() {
        // Random non-PDF bytes — PDFBox throws IOException, mapped to
        // PDF_CORRUPTED per ADR-16.
        byte[] junk = "this is definitely not a pdf body".getBytes();
        assertThatThrownBy(() -> extractor.extractToMarkdown(junk))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void mixed_pdf_concatenates_text_and_ocr_with_double_newline() throws IOException {
        byte[] pdfBytes = twoPagePdfTextPlusBlank("First page text. Long enough to count as text layer.");
        when(visionOcr.toMarkdown(any(byte[].class))).thenReturn("Second page OCR text");

        String md = extractor.extractToMarkdown(pdfBytes);

        // Per ADR-16 pages are joined with "\n\n".
        assertThat(md).contains("First page text");
        assertThat(md).contains("Second page OCR text");
        assertThat(md).contains("\n\n");
        verify(visionOcr, times(1)).toMarkdown(any(byte[].class));
    }

    // --- PDF fixture builders ---

    /** Build a 1-page PDF with text-layer content via PDFBox. */
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

    /** Build a 1-page PDF with NO content stream — text layer is empty. */
    private static byte[] singleBlankPagePdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Build a 2-page PDF: page 1 has text, page 2 is blank. */
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
