package com.playground.docs.infrastructure.ingestion.reembed;

import com.playground.docs.ingestion.application.repository.ChunkRepository;
import com.playground.docs.ingestion.application.service.ReembedService;
import com.playground.docs.ingestion.domain.model.id.DocumentId;
import com.playground.docs.infrastructure.ingestion.config.ReembedProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * CLI entry point for the {@code reembed} Spring profile per ADR-13 §7
 * (M3.1 amendment). Iterates the document candidate set (scope-filtered)
 * and runs {@link ReembedService#reembedOne} per document, exiting with
 * code 0 on success and 2 if any document failed.
 *
 * <p>Activated by {@code --spring.profiles.active=reembed}; absent that
 * profile the bean is not created and normal Kafka consumer wiring runs
 * unchanged.
 *
 * <p>Lives in {@code -infra} (not {@code -app}) because it imports Spring
 * Boot's {@link ApplicationRunner} + {@link EnableConfigurationProperties}.
 * The actual per-document work lives in {@link ReembedService} (in
 * {@code -app}).
 */
@Configuration
@Profile("reembed")
@EnableConfigurationProperties(ReembedProperties.class)
@RequiredArgsConstructor
public class ReembedCommandLineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReembedCommandLineRunner.class);

    private final ReembedProperties properties;
    private final ReembedService service;
    private final ChunkRepository chunkRepository;
    private final ConfigurableApplicationContext ctx;
    private final MeterRegistry registry;

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = runAndReportExitCode(args);
        System.exit(SpringApplication.exit(ctx, () -> exitCode));
    }

    /**
     * Orchestration logic extracted for testability. Returns the exit code
     * (0 = all success/skipped, 2 = at least one failure) without calling
     * {@code System.exit}. Tests invoke this directly so that the test JVM
     * is not terminated.
     */
    int runAndReportExitCode(ApplicationArguments args) {
        List<UUID> ids = resolveCandidates();
        log.info("rag-ingestion: reembed start scope={} candidates={}", properties.getScope(), ids.size());

        Timer.Sample sample = Timer.start(registry);
        int success = 0;
        int skipped = 0;
        int failed = 0;
        long start = System.currentTimeMillis();
        for (UUID id : ids) {
            ReembedService.Outcome o = service.reembedOne(DocumentId.of(id));
            switch (o) {
                case SUCCESS -> success++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
            registry.counter("playground.rag_ingestion.reembed.documents",
                    "outcome", o.name().toLowerCase()).increment();
            log.info("rag-ingestion: reembed documentId={} outcome={}", id, o);
            if (properties.getInterDocumentDelayMillis() > 0) {
                try {
                    Thread.sleep(properties.getInterDocumentDelayMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("rag-ingestion: reembed done processed={} success={} skipped={} failed={} durationMs={}",
                ids.size(), success, skipped, failed, elapsed);

        int exit = failed > 0 ? 2 : 0;
        sample.stop(Timer.builder("playground.rag_ingestion.reembed.duration").register(registry));
        return exit;
    }

    private List<UUID> resolveCandidates() {
        return switch (properties.getScope().toLowerCase()) {
            case "document" -> {
                if (properties.getDocumentId() == null) {
                    throw new IllegalArgumentException(
                            "scope=document requires --playground.rag-ingestion.reembed.document-id");
                }
                yield List.of(properties.getDocumentId());
            }
            case "user" -> {
                if (properties.getUserId() == null) {
                    throw new IllegalArgumentException(
                            "scope=user requires --playground.rag-ingestion.reembed.user-id");
                }
                yield chunkRepository.findDistinctDocumentIdsByUser(properties.getUserId());
            }
            case "all" -> chunkRepository.findAllDistinctDocumentIds();
            default -> throw new IllegalArgumentException("Unknown reembed scope: " + properties.getScope());
        };
    }
}
