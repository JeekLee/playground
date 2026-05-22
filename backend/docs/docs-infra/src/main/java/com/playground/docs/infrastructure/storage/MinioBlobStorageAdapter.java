package com.playground.docs.infrastructure.storage;

import com.playground.docs.application.port.BlobStoragePort;
import com.playground.docs.domain.exception.DocsErrorCode;
import com.playground.shared.error.ExceptionCreator;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
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
 * MinIO-backed {@link BlobStoragePort} per ADR-12 §A12.4.
 *
 * <p>Streaming contract: both {@code putObject} (multipart upload → MinIO)
 * and {@code getObject} (MinIO → HTTP response) flow byte-for-byte via
 * {@code InputStream}; this class never reads the blob into a {@code byte[]}
 * on the heap. The MinIO SDK's {@code PutObjectArgs} chunked-upload mode is
 * used when {@code sizeBytes < 0}; when the size is known we pass it through
 * so the SDK can pick the optimal part size.
 *
 * <p>Bucket bootstrap: {@link #ensureBucketExists()} runs at
 * {@code @PostConstruct} so the first upload doesn't race the operator's
 * {@code mc mb} pass.
 */
@Component
public class MinioBlobStorageAdapter implements BlobStoragePort {

    private static final Logger log = LoggerFactory.getLogger(MinioBlobStorageAdapter.class);

    /**
     * Part size for chunked uploads (unknown content length). 5 MiB is the
     * MinIO minimum; larger values waste memory.
     */
    private static final long DEFAULT_PART_SIZE = 5L * 1024 * 1024;

    private final MinioClient client;
    private final String bucket;

    public MinioBlobStorageAdapter(
            MinioClient client,
            @Value("${playground.docs.minio.bucket:playground-docs-originals}") String bucket) {
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
            // Fail-soft at boot: MinIO might still be coming up on the
            // compose-internal network. The first PUT/GET will retry and
            // surface BLOB_STORAGE_UNAVAILABLE if MinIO is truly down.
            log.warn("MinIO bucket bootstrap failed (will retry on first request): {}", e.toString());
        }
    }

    @Override
    public void putObject(String objectKey, InputStream input, long sizeBytes, String contentType) {
        try {
            PutObjectArgs.Builder args = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType(contentType == null ? "application/octet-stream" : contentType);
            if (sizeBytes >= 0) {
                args.stream(input, sizeBytes, -1);
            } else {
                args.stream(input, -1, DEFAULT_PART_SIZE);
            }
            client.putObject(args.build());
        } catch (Exception e) {
            log.warn("MinIO put failed for key {}: {}", objectKey, e.toString());
            throw ExceptionCreator.of(DocsErrorCode.BLOB_STORAGE_UNAVAILABLE).build();
        }
    }

    @Override
    public Optional<BlobHandle> getObject(String objectKey) {
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
            throw ExceptionCreator.of(DocsErrorCode.BLOB_STORAGE_UNAVAILABLE).build();
        } catch (Exception e) {
            log.warn("MinIO get failed for key {}: {}", objectKey, e.toString());
            throw ExceptionCreator.of(DocsErrorCode.BLOB_STORAGE_UNAVAILABLE).build();
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            // Log but do not throw — delete is best-effort; the nightly
            // orphan-cleanup pass (future work) reconciles any drift.
            log.warn("MinIO delete failed for key {} (will leave orphan): {}", objectKey, e.toString());
        }
    }

    /** Streaming handle backed by a {@link GetObjectResponse}. */
    static final class MinioBlobHandle implements BlobHandle {
        private final GetObjectResponse response;
        private final long sizeBytes;
        private final String contentType;

        MinioBlobHandle(GetObjectResponse response) {
            this.response = response;
            // Content length header is the authoritative source of truth.
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
