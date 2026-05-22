package com.playground.docs.application.port;

/**
 * Use-case port for converting an uploaded PDF byte stream into Markdown text
 * suitable for storage in {@link com.playground.docs.domain.model.vo.DocumentBody}.
 *
 * <p>M6.1 — every page goes through Vision OCR (PDFBox text-layer fast-path
 * retired per ADR-12 §A12.10). The adapter parallelizes per-page work on a
 * shared {@link java.util.concurrent.ExecutorService} (N=5 default).
 *
 * <p>The adapter throws a {@link com.playground.shared.error.BadRequestException}
 * (carrying a {@link com.playground.docs.domain.exception.DocsErrorCode}) on
 * page-cap / encrypted / corrupted PDFs. Per-page Vision failures degrade
 * gracefully — the page contributes empty markdown and the workflow caller
 * decides whether the failure rate is acceptable.
 */
public interface PdfExtractorPort {

    /**
     * Legacy entry point — convert the supplied PDF bytes into Markdown.
     * Used by the sync test paths; the async {@link ExtractionWorkflow}
     * prefers {@link #extract(byte[])} so it can read the failure stats.
     */
    String extractToMarkdown(byte[] pdfBytes);

    /**
     * M6.1 — convert PDF bytes into Markdown and surface per-page failure
     * counts. The workflow uses {@link Result#failureRate()} to decide
     * between "extracted (degraded)" and "failed".
     */
    Result extract(byte[] pdfBytes);

    record Result(String markdown, int totalPages, int failedPages) {
        public double failureRate() {
            return totalPages == 0 ? 0d : (double) failedPages / totalPages;
        }
    }
}
