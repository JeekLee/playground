package com.playground.ragchat.application.port;

import java.io.InputStream;
import java.util.Optional;

/**
 * Outbound port for reading rag-chat attachment blobs from MinIO per ADR-20 §D3 revised.
 *
 * <p>Per ADR-20 §D3 revised, agent-tools owns the MinIO write path: it uploads
 * the .3dm at generation time and returns the {@code storageKey} in the
 * {@code artifact} envelope. rag-chat only needs the read path to serve
 * attachment downloads.
 *
 * <p>Object-key convention (set by agent-tools):
 * {@code architecture/massing/{date}/{uuid}/{filename}}.
 *
 * <p>The download path streams MinIO → HTTP response via a {@link BlobHandle}
 * so the controller never buffers the blob on the heap.
 */
public interface BlobStoragePort {

    /**
     * Stream the object identified by {@code objectKey}. Returns an empty
     * {@link Optional} when the key is missing; other failures throw a 503.
     *
     * <p>The returned handle owns an open HTTP connection — close it after
     * streaming to the response body.
     */
    Optional<BlobHandle> get(String objectKey);

    /** Streamable read handle returned by {@link #get}. */
    interface BlobHandle extends AutoCloseable {
        InputStream stream();

        long sizeBytes();

        String contentType();

        @Override
        void close();
    }
}
