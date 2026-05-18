package com.playground.docs.infrastructure.external;

import com.playground.docs.application.port.OwnerResolverPort;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

/**
 * Resolves the playground owner's user id at boot per ADR-12 §8 + M2 spec §6.3.
 *
 * <p>Reads {@code PLAYGROUND_OWNER_GOOGLE_SUB}, calls
 * {@code GET /internal/users/by-google-sub/{sub}} on identity-api with a
 * short timeout + 3 retries, caches the resolved UUID in a JVM-lifetime
 * {@link AtomicReference}.
 *
 * <p>Fail-closed: when the env var is unset / blank, when identity-api is
 * unreachable, or when the lookup 404s, the cache stays empty —
 * {@link #ownerUserId()} returns {@link Optional#empty()} and the
 * {@code GET /api/docs/owner} endpoint surfaces
 * {@code { "ownerUserId": null }}. The home page's "Latest documents"
 * tile then renders empty (per spec).
 *
 * <p>The boot lookup runs asynchronously so a slow identity-api does not
 * block the docs-api Spring context from finishing startup. The cache is
 * empty until the resolution settles; subsequent {@code GET /api/docs/owner}
 * calls re-attempt resolution lazily on cache miss.
 */
@Component
public class OwnerResolverAdapter implements OwnerResolverPort {

    private static final Logger log = LoggerFactory.getLogger(OwnerResolverAdapter.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_RETRY = 3;

    private final WebClient identityWebClient;
    private final String ownerGoogleSub;
    private final AtomicReference<Optional<UUID>> cached = new AtomicReference<>(Optional.empty());

    public OwnerResolverAdapter(
            @Qualifier("identityWebClient") WebClient identityWebClient,
            @Value("${PLAYGROUND_OWNER_GOOGLE_SUB:}") String ownerGoogleSub) {
        this.identityWebClient = identityWebClient;
        this.ownerGoogleSub = ownerGoogleSub;
    }

    @PostConstruct
    void bootstrapOwner() {
        if (ownerGoogleSub == null || ownerGoogleSub.isBlank()) {
            log.warn("PLAYGROUND_OWNER_GOOGLE_SUB is unset — owner-resolution returns empty; "
                    + "the home's owner-feed tile will render empty until set");
            return;
        }
        // Subscribe async so a slow identity-api does not block boot.
        identityWebClient.get()
                .uri("/internal/users/by-google-sub/{sub}", ownerGoogleSub)
                .retrieve()
                .bodyToMono(IdentityLookupAdapter.UserDto.class)
                .timeout(TIMEOUT)
                .retryWhen(Retry.backoff(MAX_RETRY, Duration.ofMillis(200)).jitter(0.5))
                .doOnError(e -> log.warn(
                        "owner resolution failed for sub={} — owner feed will be empty until next request retries",
                        ownerGoogleSub, e))
                .doOnSuccess(u -> {
                    if (u != null) {
                        cached.set(Optional.of(u.id()));
                        log.info("owner resolved at boot — sub={}, userId={}", ownerGoogleSub, u.id());
                    } else {
                        log.warn("owner sub={} not found in identity-api — owner feed will render empty",
                                ownerGoogleSub);
                    }
                })
                .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                .subscribe();
    }

    @Override
    public Optional<UUID> ownerUserId() {
        return cached.get();
    }
}
