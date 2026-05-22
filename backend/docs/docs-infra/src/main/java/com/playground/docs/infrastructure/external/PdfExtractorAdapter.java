package com.playground.docs.infrastructure.external;

import com.playground.docs.application.port.PdfExtractorPort;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * M6.1 ADR-12 §A12.9 + §A12.10 — PDF → Markdown extractor. The M6 hybrid
 * PDFBox-text + Vision-OCR algorithm is retired; every page goes to Vision
 * OCR. Page render is CPU-bound, the Vision LLM call is network-bound; both
 * happen on the same {@code extractionExecutor} (N=5 parallel by default —
 * see {@link com.playground.docs.infrastructure.extraction.ExtractionExecutorConfig}).
 *
 * <p>Caps:
 * <ul>
 *   <li>Total page count: configurable (default 100). Over-cap throws
 *       {@link DocsErrorCode#PDF_TOO_MANY_PAGES}.</li>
 *   <li>Per-page timeout: configurable (default 60s). On per-page timeout
 *       (or any Vision-side error) we record an empty page rather than
 *       failing the whole extraction. The aggregate failure tally is
 *       tracked by the workflow caller (degraded extraction tolerated up
 *       to 20% by default).</li>
 *   <li>Render DPI: 150 — preserved from M6 ADR-16.</li>
 * </ul>
 *
 * <p>Per-page Markdown is concatenated in original order with {@code "\n\n"}
 * so the result is a valid GFM document.
 */
@Component
public class PdfExtractorAdapter implements PdfExtractorPort {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractorAdapter.class);

    /** PDF render DPI for the Vision OCR call (ADR-16, preserved). */
    static final float RENDER_DPI = 150f;

    private final VisionOcrAdapter visionOcr;
    private final ThreadPoolExecutor extractionExecutor;
    private final int maxPages;
    private final long perPageTimeoutSeconds;
    private final int maxRetryAttempts;

    public PdfExtractorAdapter(
            VisionOcrAdapter visionOcr,
            @Qualifier("extractionExecutor") ThreadPoolExecutor extractionExecutor,
            @Value("${playground.docs.extraction.pdf.max-pages:100}") int maxPages,
            @Value("${playground.docs.extraction.vision.per-page-timeout-seconds:60}") long perPageTimeoutSeconds,
            @Value("${playground.docs.extraction.vision.retry.max-attempts:2}") int maxRetryAttempts) {
        this.visionOcr = visionOcr;
        this.extractionExecutor = extractionExecutor;
        this.maxPages = maxPages;
        this.perPageTimeoutSeconds = perPageTimeoutSeconds;
        this.maxRetryAttempts = maxRetryAttempts;
    }

    /**
     * Convenience overload mirroring the historical port — buffers the
     * {@code byte[]} and delegates to {@link #extract(byte[])}.
     */
    @Override
    public String extractToMarkdown(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            ExceptionCreator.of(DocsErrorCode.PDF_CORRUPTED).throwIt();
        }
        Result result = extract(pdfBytes);
        return result.markdown();
    }

    /**
     * M6.1 entry point — drives the parallel per-page extraction. Returns
     * the assembled markdown plus per-page failure stats so the workflow
     * caller can decide between "extracted (degraded)" and "failed".
     */
    @Override
    public Result extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            ExceptionCreator.of(DocsErrorCode.PDF_CORRUPTED).throwIt();
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount > maxPages) {
                ExceptionCreator.of(DocsErrorCode.PDF_TOO_MANY_PAGES).throwIt();
            }

            PDFRenderer renderer = new PDFRenderer(doc);

            // Per-page tasks dispatched to the shared extraction executor.
            // Order preserved by index — assembly walks 0..N-1.
            List<Future<String>> futures = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                final int pageIndex = i;
                Callable<String> task = () -> renderPageThenOcr(renderer, pageIndex);
                futures.add(extractionExecutor.submit(task));
            }

            String[] pageMarkdowns = new String[pageCount];
            int failedPages = 0;
            for (int i = 0; i < pageCount; i++) {
                try {
                    String md = futures.get(i).get(perPageTimeoutSeconds, TimeUnit.SECONDS);
                    pageMarkdowns[i] = md == null ? "" : md;
                    if (pageMarkdowns[i].isEmpty()) {
                        failedPages++;
                    }
                } catch (TimeoutException te) {
                    log.warn("PDF page {} OCR timed out after {}s", i, perPageTimeoutSeconds);
                    futures.get(i).cancel(true);
                    pageMarkdowns[i] = "";
                    failedPages++;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    futures.get(i).cancel(true);
                    pageMarkdowns[i] = "";
                    failedPages++;
                } catch (ExecutionException ee) {
                    log.warn("PDF page {} OCR errored: {}", i, ee.getCause() == null ? ee : ee.getCause().toString());
                    pageMarkdowns[i] = "";
                    failedPages++;
                }
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pageCount; i++) {
                if (i > 0) {
                    sb.append("\n\n");
                }
                sb.append(pageMarkdowns[i]);
            }
            return new Result(sb.toString(), pageCount, failedPages);
        } catch (InvalidPasswordException e) {
            ExceptionCreator.of(DocsErrorCode.PDF_ENCRYPTED).throwIt();
            return null; // unreachable
        } catch (IOException e) {
            log.warn("PDFBox parse failed: {}", e.toString());
            ExceptionCreator.of(DocsErrorCode.PDF_CORRUPTED).throwIt();
            return null; // unreachable
        }
    }

    /**
     * Render the i-th page to PNG, then call Vision with retry classification.
     * Retryable: 5xx + IO errors. Non-retryable: 4xx. On exhaustion or page
     * render failure: returns empty markdown for the page (graceful per-page
     * degradation per ADR-16 + ADR-12 §A12.10).
     */
    private String renderPageThenOcr(PDFRenderer renderer, int pageIndex) {
        byte[] pngBytes;
        try {
            BufferedImage img = renderer.renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            pngBytes = out.toByteArray();
        } catch (IOException e) {
            log.warn("PDF page {} render failed, returning empty: {}", pageIndex, e.toString());
            return "";
        }

        // Up to (1 + maxRetryAttempts) calls — the Vision adapter swallows
        // its own runtime errors and returns "" on transient failure, so the
        // retry loop here exists to give one more chance when the adapter
        // explicitly classifies a 5xx as retryable. The VisionOcrAdapter
        // currently treats every failure as terminal-empty; treating an
        // empty return as a retry attempt would re-call the LLM on every
        // truly-blank page (e.g. a divider), which is wasteful. We retry
        // only when the adapter throws an explicit retryable exception.
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return visionOcr.toMarkdown(pngBytes);
            } catch (VisionOcrAdapter.RetryableVisionException re) {
                if (attempts > maxRetryAttempts) {
                    log.warn("PDF page {} OCR exhausted {} retry attempts: {}", pageIndex, maxRetryAttempts, re.toString());
                    return "";
                }
                long backoffMs = 200L * (1L << (attempts - 1));
                try {
                    Thread.sleep(Math.min(backoffMs, 2_000L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "";
                }
            } catch (RuntimeException re) {
                // Non-retryable (4xx / programming error) — give up.
                log.warn("PDF page {} OCR non-retryable error: {}", pageIndex, re.toString());
                return "";
            }
        }
    }

    /** Streaming overload — preferred path from {@code ExtractionWorkflow}. */
    public Result extract(InputStream pdfStream) throws IOException {
        try (InputStream s = pdfStream) {
            return extract(s.readAllBytes());
        }
    }
}
