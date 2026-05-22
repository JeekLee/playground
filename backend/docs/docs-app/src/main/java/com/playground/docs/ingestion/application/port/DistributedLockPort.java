package com.playground.docs.ingestion.application.port;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Outbound port to the Redisson-backed distributed lock per ADR-08 Exception
 * 2 + ADR-13 §5. Lock keys live under the
 * {@code rag-ingestion:lock:document:{id}} namespace; TTL is capped at 5
 * minutes (ADR-08 §"Exception 2" hard upper bound).
 *
 * <p>The implementation ({@code RedissonDistributedLockAdapter} in
 * {@code rag-ingestion-infra}) wraps Redisson's {@code RLock.tryLock} +
 * {@code unlock}. Callers wrap their critical section in
 * {@link #runWithLock(String, Duration, Duration, Supplier)} — the
 * implementation handles wait timeout, automatic unlock, and re-throw on
 * interrupt.
 */
public interface DistributedLockPort {

    /**
     * Acquire the named lock, run the supplier under it, and release. The
     * supplier's return value is propagated to the caller.
     *
     * @param key       lock key (without namespace — the adapter prepends
     *                  {@code rag-ingestion:lock:})
     * @param waitTime  how long to wait for the lock before failing
     * @param leaseTime auto-release deadline once acquired (≤ 5 min cap)
     * @param work      the critical section
     * @param <T>       return type
     * @return whatever the supplier returns
     * @throws RuntimeException if the lock cannot be acquired in
     *                          {@code waitTime}; the Kafka error handler
     *                          routes the original event to the DLQ.
     */
    <T> T runWithLock(String key, Duration waitTime, Duration leaseTime, Supplier<T> work);
}
