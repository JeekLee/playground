package com.playground.docs.application.port;

import java.io.InputStream;
import java.util.Optional;

/**
 * Outbound port for the MinIO source-blob store per ADR-12 §A12.4.
 *
 * <p>Implementations stream both ways — neither {@code putObject} nor
 * {@code getObject} should materialize the full blob into a {@code byte[]} on
 * the heap. The adapter ({@code MinioBlobStorageAdapter} in docs-infra) wraps
 * the MinIO Java SDK calls with bucket-existence bootstrapping at startup.
 *
 * <p>Object key convention: {@code {documentId}/source.{ext}} where
 * {@code ext} is {@code pdf} or {@code md}.
 *
 * <p>Failure surfaces as {@link com.playground.docs.domain.exception.DocsErrorCode#BLOB_STORAGE_UNAVAILABLE}
 * (503) on transient errors or missing buckets — the caller may translate to
 * {@code SOURCE_BLOB_NOT_FOUND} (404) for the get path when the key is gone.
 */
public interface BlobStoragePort {

    /**
     * Stream the supplied {@code InputStream} to the bucket under the
     * supplied object key. Caller provides the size if known
     * ({@code MultipartFile.getSize()}); pass {@code -1} when unknown
     * (chunked uploads — the adapter falls back to a 5 MiB part size).
     *
     * @param objectKey  bucket-relative object key
     * @param input      source stream (NOT closed by the port; caller may
     *                   continue reading downstream)
     * @param sizeBytes  total length in bytes; {@code -1} for unknown
     * @param contentType the wire content type (e.g. {@code application/pdf})
     */
    void putObject(String objectKey, InputStream input, long sizeBytes, String contentType);

    /**
     * Stream the object identified by {@code objectKey}. Returns an empty
     * {@link Optional} when the key is missing (404 from MinIO);
     * other failures throw {@code BLOB_STORAGE_UNAVAILABLE} (503).
     *
     * <p>The returned handle owns an open HTTP connection — close it after
     * streaming to the response body.
     */
    Optional<BlobHandle> getObject(String objectKey);

    /** Idempotent delete — never throws when the object is already missing. */
    void deleteObject(String objectKey);

    /** Streamable read handle returned by {@link #getObject}. */
    interface BlobHandle extends AutoCloseable {
        InputStream stream();

        long sizeBytes();

        String contentType();

        @Override
        void close();
    }
}
