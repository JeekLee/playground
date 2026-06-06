package com.playground.chat.infrastructure.storage;

import com.playground.chat.application.port.BlobStoragePort;
import com.playground.chat.domain.exception.ChatErrorCode;
import com.playground.shared.error.ExceptionCreator;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MinIO-backed {@link BlobStoragePort} for chat attachment downloads per ADR-20 §D3 revised.
 *
 * <p>Per ADR-20 §D3 revised, agent-tools owns the write path — it uploads the
 * .3dm before returning its response and includes the {@code storageKey} in the
 * artifact envelope. chat only needs {@link #get} to serve downloads; the
 * {@code put} path is removed.
 *
 * <p>Bucket bootstrap: {@link #ensureBucketExists()} runs at
 * {@code @PostConstruct} so the first download doesn't race the operator's
 * {@code mc mb} pass.
 */
@Component
public class MinioBlobStorageAdapter implements BlobStoragePort {

    private static final Logger log = LoggerFactory.getLogger(MinioBlobStorageAdapter.class);

    private final MinioClient client;
    private final String bucket;

    public MinioBlobStorageAdapter(
            MinioClient client,
            @Value("${PLAYGROUND_CHAT_MINIO_BUCKET:chat-attachments}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @PostConstruct
    void ensureBucketExists() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket {} at startup", bucket);
            }
        } catch (Exception e) {
            log.warn("MinIO bucket bootstrap failed (will retry on first request): {}", e.toString());
        }
    }

    @Override
    public Optional<BlobHandle> get(String objectKey) {
        try {
            GetObjectResponse response = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return Optional.of(new MinioBlobHandle(response));
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchKey".equalsIgnoreCase(code) || "NoSuchObject".equalsIgnoreCase(code)) {
                return Optional.empty();
            }
            log.warn("MinIO get failed for key {}: {}", objectKey, e.toString());
            throw ExceptionCreator.of(ChatErrorCode.BLOB_STORAGE_UNAVAILABLE).build();
        } catch (Exception e) {
            log.warn("MinIO get failed for key {}: {}", objectKey, e.toString());
            throw ExceptionCreator.of(ChatErrorCode.BLOB_STORAGE_UNAVAILABLE).build();
        }
    }

    /** Streaming handle backed by a {@link GetObjectResponse}. */
    static final class MinioBlobHandle implements BlobHandle {
        private final GetObjectResponse response;
        private final long sizeBytes;
        private final String contentType;

        MinioBlobHandle(GetObjectResponse response) {
            this.response = response;
            String contentLength = response.headers().get("Content-Length");
            this.sizeBytes = contentLength == null ? -1L : safeParseLong(contentLength);
            String ct = response.headers().get("Content-Type");
            this.contentType = ct == null ? "application/octet-stream" : ct;
        }

        @Override
        public InputStream stream() {
            return response;
        }

        @Override
        public long sizeBytes() {
            return sizeBytes;
        }

        @Override
        public String contentType() {
            return contentType;
        }

        @Override
        public void close() {
            try {
                response.close();
            } catch (IOException ignored) {
                // Already closed or upstream cut — nothing actionable.
            }
        }

        private static long safeParseLong(String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return -1L;
            }
        }
    }
}
