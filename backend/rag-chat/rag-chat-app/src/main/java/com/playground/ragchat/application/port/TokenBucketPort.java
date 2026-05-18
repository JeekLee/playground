package com.playground.ragchat.application.port;

import com.playground.ragchat.domain.model.id.UserId;

/**
 * Per-user token bucket per ADR-14 §5. Two-tier: hourly + daily. The infra
 * adapter ({@code RedissonTokenBucketAdapter}) keys on
 * {@code rag-chat:bucket:user:&lt;userId&gt;:hourly|daily}.
 *
 * <p>{@link #tryAcquire(UserId)} debits one permit if both buckets have
 * capacity; returns a {@link Decision} carrying the result and (on deny) the
 * smaller refill ETA in seconds for {@code Retry-After}. The bucket is NOT
 * refunded on stream abort per ADR-14 §14 — the permit is consumed at the
 * start of the turn and stays consumed regardless of outcome to match the
 * "best-effort, hourly window self-heals" framing.
 */
public interface TokenBucketPort {

    Decision tryAcquire(UserId user);

    /** Outcome of a per-turn acquire attempt. */
    record Decision(boolean allowed, long retryAfterSeconds) {
        public static Decision allow() {
            return new Decision(true, 0L);
        }

        public static Decision deny(long retryAfterSeconds) {
            return new Decision(false, Math.max(0L, retryAfterSeconds));
        }
    }
}
