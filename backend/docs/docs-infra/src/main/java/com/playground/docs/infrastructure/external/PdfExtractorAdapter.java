package com.playground.docs.infrastructure.external;

import com.playground.docs.application.port.PdfExtractorPort;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hybrid PDF → Markdown extractor per M6 ADR-16. PDFBox text-layer extraction
 * is attempted first; pages with no extractable text fall back to Vision OCR
 * via {@link VisionOcrAdapter}.
 *
 * <p>Caps per ADR-16:
 * <ul>
 *   <li>Total page count: 200 (throws {@link DocsErrorCode#PDF_TOO_MANY_PAGES}).</li>
 *   <li>OCR pages: 30 (throws {@link DocsErrorCode#PDF_TOO_MANY_OCR_PAGES}).</li>
 *   <li>Text-layer threshold: page text length ≥ 30 characters after
 *       form-feed normalization → treated as a text-layer hit.</li>
 *   <li>Render DPI: 150 — balances OCR accuracy with payload size for the
 *       Vision call.</li>
 * </ul>
 *
 * <p>Per-page Markdown is concatenated with {@code "\n\n"} so the result is
 * a valid GFM document M3 rag-ingestion can chunk verbatim.
 */
@Component
public class PdfExtractorAdapter implements PdfExtractorPort {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractorAdapter.class);

    /** Maximum PDF page count (ADR-16). */
    static final int MAX_PAGES = 200;
    /** Maximum pages that may fall back to Vision OCR (ADR-16). */
    static final int MAX_OCR_PAGES = 30;
    /** Minimum text-layer length (chars) below which a page is OCR'd (ADR-16). */
    static final int TEXT_LAYER_MIN_CHARS = 30;
    /** PDF render DPI for the Vision OCR fallback (ADR-16). */
    static final float RENDER_DPI = 150f;

    private final VisionOcrAdapter visionOcr;

    public PdfExtractorAdapter(VisionOcrAdapter visionOcr) {
        this.visionOcr = visionOcr;
    }

    @Override
    public String extractToMarkdown(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            ExceptionCreator.of(DocsErrorCode.PDF_CORRUPTED).throwIt();
        }
        // Try-with-resources — PDDocument holds native file handles.
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount > MAX_PAGES) {
                ExceptionCreator.of(DocsErrorCode.PDF_TOO_MANY_PAGES).throwIt();
            }

            List<String> pageMarkdowns = new ArrayList<>(pageCount);
            int ocrPageCount = 0;
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            // Renderer is lazily constructed — when every page has text layer,
            // we never instantiate it (avoids the heavyweight cost on pure
            // text PDFs).
            PDFRenderer renderer = null;

            for (int i = 0; i < pageCount; i++) {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String pageText = stripper.getText(doc).replace("\f", "\n\n").trim();
                if (pageText.length() >= TEXT_LAYER_MIN_CHARS) {
                    pageMarkdowns.add(pageText);
                } else {
                    ocrPageCount++;
                    if (ocrPageCount > MAX_OCR_PAGES) {
                        ExceptionCreator.of(DocsErrorCode.PDF_TOO_MANY_OCR_PAGES).throwIt();
                    }
                    if (renderer == null) {
                        renderer = new PDFRenderer(doc);
                    }
                    String ocrMd = renderPageThenOcr(renderer, i);
                    pageMarkdowns.add(ocrMd);
                }
            }

            return String.join("\n\n", pageMarkdowns);
        } catch (InvalidPasswordException e) {
            ExceptionCreator.of(DocsErrorCode.PDF_ENCRYPTED).throwIt();
            return null; // unreachable
        } catch (IOException e) {
            // PDFBox throws IOException on truncated / malformed input.
            log.warn("PDFBox parse failed: {}", e.toString());
            ExceptionCreator.of(DocsErrorCode.PDF_CORRUPTED).throwIt();
            return null; // unreachable
        }
    }

    /**
     * Render the i-th page to a PNG and hand it to the Vision adapter.
     * Per ADR-16 a failure here returns an empty string for the page (the
     * Vision adapter itself catches its own gateway errors); only an
     * unrecoverable I/O error (out of memory rendering, etc.) escapes here
     * — surfaced as PDF_CORRUPTED to avoid leaking implementation details
     * to the API client.
     */
    private String renderPageThenOcr(PDFRenderer renderer, int pageIndex) {
        try {
            BufferedImage img = renderer.renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            return visionOcr.toMarkdown(out.toByteArray());
        } catch (IOException e) {
            log.warn("PDF page {} render failed, returning empty: {}", pageIndex, e.toString());
            return "";
        }
    }
}
