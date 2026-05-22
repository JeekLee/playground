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
import java.util.concurrent.Semaphore;
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
            // Downsample embedded high-DPI images during render. Architectural
            // / scan-heavy PDFs commonly embed 300-600 DPI JPEGs; PDFBox's
            // DCTFilter decodes them to a `ByteInterleavedRaster` at native
            // resolution, which can OOM the 1 GB docs-api heap even on a
            // single page. Subsampling lets the renderer decode at the
            // target output resolution (150 DPI here), trading negligible
            // OCR quality for a 10-100× memory reduction per page.
            renderer.setSubsamplingAllowed(true);

            // Render pages SERIALLY on the calling thread, then dispatch
            // OCR in parallel. PDFBox's PDDocument + PDFRenderer are NOT
            // thread-safe — sharing a renderer across executor threads
            // corrupts the internal page-parser / font-cache state and
            // silently produces blank or garbled PNGs. The Vision LLM,
            // given a near-blank image, hallucinates generic training
            // content (Docker tutorials, weather diary entries) — the
            // failure mode that triggered the 2026-05-22 incident.
            //
            // Memory bound: the in-flight semaphore caps the queue at
            // `parallelism` page-byte[] arrays at any moment (~3 MB / page
            // at 150 DPI for A4). Render thread blocks before each new
            // submit until an in-flight OCR completes, so peak memory
            // stays bounded at ≈ 5 × PNG size regardless of pageCount.
            Semaphore inFlight = new Semaphore(Math.max(1, extractionExecutor.getMaximumPoolSize()));
            List<Future<String>> futures = new ArrayList<>(pageCount);
            for (int i = 0; i < pageCount; i++) {
                inFlight.acquire();
                byte[] pngBytes = renderPage(renderer, i);
                final int pageIndex = i;
                final byte[] page = pngBytes;
                Callable<String> task = () -> {
                    try {
                        return page == null ? "" : ocrWithRetry(page, pageIndex);
                    } finally {
                        inFlight.release();
                    }
                };
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
        } catch (InterruptedException e) {
            // Dispatcher loop was interrupted while waiting on the in-flight
            // semaphore. Propagate the interrupt and abort the extraction.
            Thread.currentThread().interrupt();
            log.warn("PDF extraction interrupted while waiting for OCR slot");
            ExceptionCreator.of(DocsErrorCode.PDF_CORRUPTED).throwIt();
            return null; // unreachable
        }
    }

    /**
     * Render page i to a PNG byte[]. Called serially from the dispatcher
     * loop (NOT from executor threads — see the comment in {@link #extract}).
     * Returns {@code null} on render failure; the caller short-circuits to
     * an empty page in that case.
     */
    private byte[] renderPage(PDFRenderer renderer, int pageIndex) {
        try {
            BufferedImage img = renderer.renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", out);
            return out.toByteArray();
        } catch (IOException e) {
            log.warn("PDF page {} render failed, returning empty: {}", pageIndex, e.toString());
            return null;
        }
    }

    /**
     * Call Vision with retry classification. Runs on an executor thread.
     * Retryable: 5xx + IO errors. Non-retryable: 4xx. On exhaustion:
     * returns empty markdown for the page (graceful per-page degradation
     * per ADR-16 + ADR-12 §A12.10).
     */
    private String ocrWithRetry(byte[] pngBytes, int pageIndex) {
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
