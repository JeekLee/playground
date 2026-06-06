package com.playground.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.playground.chat.application.port.BlobStoragePort;
import com.playground.chat.application.repository.AttachmentRepository;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.id.AttachmentId;
import com.playground.chat.domain.model.id.MessageId;
import com.playground.chat.domain.model.id.UserId;
import com.playground.shared.error.AbstractException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Owner-only attachment download tests per ADR-20 §D4. Verifies the tenant
 * isolation rule: the owning message's user must equal the caller, else the
 * attachment reads as not-found (404). Uses a fake {@link AttachmentRepository}
 * (which enforces the owner filter, as the JDBC join does) + a fake
 * {@link BlobStoragePort}.
 */
class AttachmentDownloadServiceTest {

    private final UserId owner = UserId.of(UUID.randomUUID());
    private final UserId stranger = UserId.of(UUID.randomUUID());

    /** Fake repo: findOwned only returns the attachment when caller == its owner. */
    private static final class FakeAttachmentRepository implements AttachmentRepository {
        private final Map<AttachmentId, Attachment> rows = new ConcurrentHashMap<>();
        private final Map<AttachmentId, UserId> owners = new ConcurrentHashMap<>();

        void put(Attachment a, UserId ownerId) {
            rows.put(a.id(), a);
            owners.put(a.id(), ownerId);
        }

        @Override
        public Attachment save(Attachment attachment) {
            rows.put(attachment.id(), attachment);
            return attachment;
        }

        @Override
        public void saveAll(List<Attachment> attachments) {
            attachments.forEach(this::save);
        }

        @Override
        public Optional<Attachment> findOwned(AttachmentId id, UserId caller) {
            Attachment a = rows.get(id);
            if (a == null || !caller.equals(owners.get(id))) {
                return Optional.empty();
            }
            return Optional.of(a);
        }

        @Override
        public List<Attachment> findByMessages(List<MessageId> messageIds) {
            return rows.values().stream().filter(a -> messageIds.contains(a.messageId())).toList();
        }
    }

    private static final class FakeBlobStorage implements BlobStoragePort {
        private final Map<String, byte[]> store = new ConcurrentHashMap<>();

        void put(String key, byte[] bytes) {
            store.put(key, bytes);
        }

        @Override
        public Optional<BlobHandle> get(String objectKey) {
            byte[] bytes = store.get(objectKey);
            if (bytes == null) {
                return Optional.empty();
            }
            return Optional.of(new BlobHandle() {
                @Override
                public InputStream stream() {
                    return new ByteArrayInputStream(bytes);
                }

                @Override
                public long sizeBytes() {
                    return bytes.length;
                }

                @Override
                public String contentType() {
                    return "application/octet-stream";
                }

                @Override
                public void close() {
                }
            });
        }
    }

    private Attachment attachment(AttachmentId id, String storageKey) {
        return Attachment.toolArtifact(
                id, MessageId.generate(), "massing-한글-1.3dm", "application/octet-stream",
                5L, storageKey, "generate_massing", null, Instant.parse("2026-06-04T12:00:00Z"));
    }

    @Test
    void owner_getsTheBytes_200() {
        FakeAttachmentRepository repo = new FakeAttachmentRepository();
        FakeBlobStorage blobs = new FakeBlobStorage();
        AttachmentId id = AttachmentId.generate();
        String key = "chat/s/m/" + id.value() + "-massing-한글-1.3dm";
        byte[] bytes = "fake-3dm".getBytes(StandardCharsets.UTF_8);
        repo.put(attachment(id, key), owner);
        blobs.put(key, bytes);

        AttachmentDownloadService svc = new AttachmentDownloadService(repo, blobs);
        AttachmentDownloadService.Download dl = svc.open(id, owner);

        assertThat(dl.attachment().id()).isEqualTo(id);
        try (var handle = dl.handle(); InputStream in = handle.stream()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void nonOwner_gets404_notFound() {
        FakeAttachmentRepository repo = new FakeAttachmentRepository();
        FakeBlobStorage blobs = new FakeBlobStorage();
        AttachmentId id = AttachmentId.generate();
        String key = "chat/s/m/" + id.value() + "-f.3dm";
        repo.put(attachment(id, key), owner);
        blobs.put(key, "x".getBytes(StandardCharsets.UTF_8));

        AttachmentDownloadService svc = new AttachmentDownloadService(repo, blobs);

        // The stranger is not the owner → resolves to ATTACHMENT_NOT_FOUND (404),
        // indistinguishable from a missing attachment (tenant isolation).
        assertThatThrownBy(() -> svc.open(id, stranger))
                .isInstanceOf(AbstractException.class)
                .satisfies(e -> assertThat(((AbstractException) e).errorCode().code())
                        .isEqualTo("CHAT-NOT-FOUND-002"));
    }

    @Test
    void missingAttachment_gets404() {
        AttachmentDownloadService svc = new AttachmentDownloadService(
                new FakeAttachmentRepository(), new FakeBlobStorage());
        assertThatThrownBy(() -> svc.open(AttachmentId.generate(), owner))
                .isInstanceOf(AbstractException.class);
    }

    @Test
    void blobPurgedButRowSurvives_gets404() {
        FakeAttachmentRepository repo = new FakeAttachmentRepository();
        AttachmentId id = AttachmentId.generate();
        repo.put(attachment(id, "chat/s/m/" + id.value() + "-f.3dm"), owner);
        // Blob store is empty — the row resolves but the bytes are gone.
        AttachmentDownloadService svc = new AttachmentDownloadService(repo, new FakeBlobStorage());
        assertThatThrownBy(() -> svc.open(id, owner))
                .isInstanceOf(AbstractException.class)
                .satisfies(e -> assertThat(((AbstractException) e).errorCode().code())
                        .isEqualTo("CHAT-NOT-FOUND-002"));
    }

    // --- openPreview (design spec 2026-06-05-massing-glb-preview) ---

    @Test
    void preview_ownerGetsGlbBytes() {
        FakeAttachmentRepository repo = new FakeAttachmentRepository();
        FakeBlobStorage blobs = new FakeBlobStorage();
        AttachmentId id = AttachmentId.generate();
        String key3dm = "architecture/massing/20260605/u/massing-brief-1.3dm";
        byte[] glb = "fake-glb".getBytes(StandardCharsets.UTF_8);
        repo.put(attachment(id, key3dm), owner);
        // The .glb sibling sits at the same prefix, extension swapped.
        blobs.put("architecture/massing/20260605/u/massing-brief-1.glb", glb);

        AttachmentDownloadService svc = new AttachmentDownloadService(repo, blobs);
        AttachmentDownloadService.Download preview = svc.openPreview(id, owner);

        try (var handle = preview.handle(); InputStream in = handle.stream()) {
            assertThat(in.readAllBytes()).isEqualTo(glb);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void preview_glbMissing_gets404() {
        // Legacy row or failed store_glb — the .3dm row exists, the .glb doesn't.
        FakeAttachmentRepository repo = new FakeAttachmentRepository();
        AttachmentId id = AttachmentId.generate();
        repo.put(attachment(id, "architecture/massing/20260605/u/m.3dm"), owner);

        AttachmentDownloadService svc = new AttachmentDownloadService(repo, new FakeBlobStorage());
        assertThatThrownBy(() -> svc.openPreview(id, owner))
                .isInstanceOf(AbstractException.class)
                .satisfies(e -> assertThat(((AbstractException) e).errorCode().code())
                        .isEqualTo("CHAT-NOT-FOUND-002"));
    }

    @Test
    void preview_non3dmAttachment_gets415() {
        FakeAttachmentRepository repo = new FakeAttachmentRepository();
        AttachmentId id = AttachmentId.generate();
        repo.put(attachment(id, "architecture/massing/20260605/u/report.pdf"), owner);

        AttachmentDownloadService svc = new AttachmentDownloadService(repo, new FakeBlobStorage());
        assertThatThrownBy(() -> svc.openPreview(id, owner))
                .isInstanceOf(AbstractException.class)
                .satisfies(e -> assertThat(((AbstractException) e).errorCode().code())
                        .isEqualTo("CHAT-PREVIEW-001"));
    }

    @Test
    void preview_nonOwner_gets404() {
        FakeAttachmentRepository repo = new FakeAttachmentRepository();
        FakeBlobStorage blobs = new FakeBlobStorage();
        AttachmentId id = AttachmentId.generate();
        repo.put(attachment(id, "architecture/massing/20260605/u/m.3dm"), owner);
        blobs.put("architecture/massing/20260605/u/m.glb", "g".getBytes(StandardCharsets.UTF_8));

        AttachmentDownloadService svc = new AttachmentDownloadService(repo, blobs);
        // Tenant isolation — reads as not-found, same as the download path.
        assertThatThrownBy(() -> svc.openPreview(id, stranger))
                .isInstanceOf(AbstractException.class)
                .satisfies(e -> assertThat(((AbstractException) e).errorCode().code())
                        .isEqualTo("CHAT-NOT-FOUND-002"));
    }

    @Test
    void preview_nonOwnerWithNonPreviewableType_gets404_not415() {
        // Pins the check ordering: ownership resolves BEFORE the type check,
        // so a stranger never learns the attachment exists (404, not 415).
        FakeAttachmentRepository repo = new FakeAttachmentRepository();
        AttachmentId id = AttachmentId.generate();
        repo.put(attachment(id, "architecture/massing/20260605/u/report.pdf"), owner);

        AttachmentDownloadService svc = new AttachmentDownloadService(repo, new FakeBlobStorage());
        assertThatThrownBy(() -> svc.openPreview(id, stranger))
                .isInstanceOf(AbstractException.class)
                .satisfies(e -> assertThat(((AbstractException) e).errorCode().code())
                        .isEqualTo("CHAT-NOT-FOUND-002"));
    }
}
