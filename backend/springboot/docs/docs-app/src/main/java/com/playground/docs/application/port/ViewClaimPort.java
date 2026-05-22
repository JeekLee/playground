package com.playground.docs.application.port;

import java.time.Duration;

/**
 * Outbound port for the per-viewer view-dedup claim per M2 spec §10
 * "View dedup correctness" + ADR-12 §10. Backed in {@code docs-infra} by a
 * Redis {@code SET ... NX EX 86400} on a key shaped:
 *
 * <pre>
 *   view:{documentId}:anon:{PLAYGROUND_ANON cookie value}    -- preferred
 *   view:{documentId}:ip:{X-Forwarded-For first hop}         -- fallback when cookie absent
 * </pre>
 *
 * <p>Same path is used whether the caller is authenticated or anonymous
 * (ADR-12 §10 "same-cookie path regardless of auth state"). The TTL is
 * fixed at 24h per ADR-12 §10.
 *
 * <p>The {@link #claim} call MUST be atomic — the {@code setIfAbsent} primitive
 * is the contract; a higher-level "check then set" two-step would race the
 * second viewer. Redis's native {@code SET key value NX EX seconds} satisfies
 * this; Spring's {@code ValueOperations.setIfAbsent(key, value, duration)}
 * compiles to the same RESP command.
 */
public interface ViewClaimPort {

    /**
     * Attempt to claim a view for the given dedup key. Returns {@code true}
     * on first claim within the TTL window (caller must increment the
     * counter), {@code false} when a prior claim is still live (caller
     * must NOT increment).
     *
     * @param key the fully-built dedup key (caller has already chosen the
     *            {@code anon:} vs {@code ip:} flavor)
     * @param ttl claim lifetime — 24h per ADR-12 §10
     */
    boolean claim(String key, Duration ttl);
}
