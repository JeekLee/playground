package com.playground.docs.application.port;

/**
 * Use-case port for converting an uploaded PDF byte stream into Markdown text
 * suitable for storage in {@link com.playground.docs.domain.model.vo.DocumentBody}.
 *
 * <p>Per M6 (ADR-16) the docs BC extracts the text layer using PDFBox and
 * falls back to Vision OCR (Spring AI multimodal {@code ChatClient} against
 * spark-inference-gateway per ADR-04) for pages whose text layer is empty
 * (typically scanned documents). The adapter that implements this port lives
 * in docs-infra ({@code PdfExtractorAdapter}); the application service treats
 * the conversion as a black box and only sees the resulting Markdown.
 *
 * <p>Implementation contract:
 * <ul>
 *   <li>Throws a {@link com.playground.shared.error.BadRequestException}
 *       carrying a {@link com.playground.docs.domain.exception.DocsErrorCode}
 *       when the PDF cannot be parsed (corrupted, encrypted, or over the
 *       page-count / OCR-page-count caps).</li>
 *   <li>Returns an empty body on Vision OCR failure for a page rather than
 *       failing the whole upload — see ADR-16 ("둘 다 실패하면 빈 문자열
 *       반환 — 전체 업로드 실패시키지 않음").</li>
 *   <li>Concatenates per-page Markdown with {@code "\n\n"} so the result is
 *       a valid GFM document.</li>
 * </ul>
 */
public interface PdfExtractorPort {

    /**
     * Convert the supplied PDF bytes into Markdown.
     *
     * @param pdfBytes raw PDF body (validated as {@code application/pdf} by
     *                 the controller's 3-step check before this port is invoked)
     * @return the Markdown body (concatenated per-page text + OCR output)
     */
    String extractToMarkdown(byte[] pdfBytes);
}
