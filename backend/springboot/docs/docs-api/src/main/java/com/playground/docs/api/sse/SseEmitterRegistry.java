package com.playground.docs.api.sse;

import com.playground.docs.application.dto.ExtractionStatusUpdate;
import com.playground.docs.domain.enums.ExtractionStatus;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * M6.1 ADR-12 §A12.5 — in-process registry of SSE emitters keyed by document
 * id. The extraction worker publishes {@link ExtractionStatusUpdate} via
 * Spring's {@link org.springframework.context.ApplicationEventPublisher}; the
 * {@link #onUpdate(ExtractionStatusUpdate)} listener fans out to every
 * emitter subscribed for the document.
 *
 * <p>Multiple concurrent SSE clients per document are supported via
 * {@link CopyOnWriteArrayList}. The 30s keepalive ping
 * ({@link #keepalive()}) defeats Cloudflare's 100s and Tomcat's 60s idle
 * timeouts.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emittersByDocId = new ConcurrentHashMap<>();

    /**
     * Register an emitter for the given document id. The emitter is removed
     * on completion / timeout / error so callers (controllers) need not
     * cleanup explicitly.
     */
    public SseEmitter register(UUID documentId) {
        SseEmitter emitter = new SseEmitter(0L); // no Spring-side timeout
        emittersByDocId.computeIfAbsent(documentId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(documentId, emitter));
        emitter.onTimeout(() -> remove(documentId, emitter));
        emitter.onError(t -> remove(documentId, emitter));
        return emitter;
    }

    /**
     * Send a snapshot of the current extraction state to a single emitter —
     * called by the controller immediately after registration so a
     * late-subscribing client sees the latest state without waiting for the
     * next transition.
     */
    public void sendSnapshot(SseEmitter emitter, ExtractionStatus status, String reason) {
        sendInternal(emitter, status, reason, null, null);
        if (status == ExtractionStatus.EXTRACTED || status == ExtractionStatus.FAILED) {
            emitter.complete();
        }
    }

    @EventListener
    public void onUpdate(ExtractionStatusUpdate update) {
        broadcast(update);
    }

    private void broadcast(ExtractionStatusUpdate update) {
        UUID docId = update.documentId().value();
        CopyOnWriteArrayList<SseEmitter> list = emittersByDocId.get(docId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            sendInternal(emitter, update.status(), update.reason(), update.pageDone(), update.pageTotal());
            if (update.status() == ExtractionStatus.EXTRACTED || update.status() == ExtractionStatus.FAILED) {
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // already completed
                }
            }
        }
    }

    private void sendInternal(SseEmitter emitter, ExtractionStatus status, String reason, Integer done, Integer total) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("status", status.wireValue());
            if (reason != null) {
                data.put("reason", reason);
            }
            if (done != null) {
                data.put("pageDone", done);
            }
            if (total != null) {
                data.put("pageTotal", total);
            }
            emitter.send(SseEmitter.event()
                    .name(status.wireValue())
                    .data(data)
                    .build());
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping SSE emitter (send failed): {}", e.toString());
            // Best-effort: subsequent onError/onCompletion callbacks will
            // remove the emitter from the registry.
        }
    }

    private void remove(UUID docId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emittersByDocId.get(docId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emittersByDocId.remove(docId, list);
            }
        }
    }

    /** 30-second keepalive ping per ADR-12 §A12.14. */
    @Scheduled(fixedRate = 30_000L, initialDelay = 30_000L)
    public void keepalive() {
        emittersByDocId.forEach((docId, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping").build());
                } catch (IOException | IllegalStateException e) {
                    log.debug("Keepalive failed; dropping emitter for doc {}: {}", docId, e.toString());
                    remove(docId, emitter);
                }
            }
        });
    }
}
