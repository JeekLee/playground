package com.playground.ragingestion.infrastructure.redis;

import com.playground.ragingestion.application.port.DistributedLockPort;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Redisson-backed implementation of {@link DistributedLockPort} per ADR-08
 * Exception 2 + ADR-13 §5. The lock-key namespace
 * ({@code rag-ingestion:lock:}) is prepended here so the unqualified key the
 * caller passes (e.g., {@code "document:<uuid>"}) doesn't collide with any
 * other BC's Redis keys.
 *
 * <p>Failure modes:
 * <ul>
 *   <li>{@code tryLock} returns {@code false} after {@code waitTime} — surfaces
 *       as an {@link IllegalStateException}; the Kafka error handler routes
 *       the original event to the DLQ.</li>
 *   <li>Interrupt during wait — restores the interrupt flag and rethrows as
 *       {@link IllegalStateException}.</li>
 *   <li>Runtime exception inside the supplier — the lock is always released
 *       in a {@code finally} block, then the exception propagates.</li>
 * </ul>
 */
@Component
public class RedissonDistributedLockAdapter implements DistributedLockPort {

    private static final Logger log = LoggerFactory.getLogger(RedissonDistributedLockAdapter.class);

    /** Per-BC lock-key namespace pinned by ADR-08 Exception 2. */
    private static final String NAMESPACE = "rag-ingestion:lock:";

    private final RedissonClient redisson;

    public RedissonDistributedLockAdapter(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public <T> T runWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> work) {
        String namespacedKey = NAMESPACE + key;
        RLock lock = redisson.getLock(namespacedKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for lock " + namespacedKey, e);
        }
        if (!acquired) {
            throw new IllegalStateException(
                    "Failed to acquire lock " + namespacedKey + " within " + waitTime);
        }
        try {
            return work.get();
        } finally {
            // The lease will auto-release after `leaseTime` anyway, but
            // releasing eagerly lets the next contender proceed immediately.
            // Guard isHeldByCurrentThread because the lease may have expired
            // mid-work (rare but possible at the 60 s lease + 47 s worst-
            // case retry budget edge).
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (RuntimeException e) {
                    log.warn("Failed to release lock {} cleanly", namespacedKey, e);
                }
            } else {
                log.warn(
                        "Lock {} lease expired mid-work — lock auto-released, no manual unlock issued",
                        namespacedKey);
            }
        }
    }
}
