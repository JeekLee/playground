package com.playground.ragchat.application.port;

import com.playground.ragchat.domain.model.id.UserId;

/**
 * Per-user concurrent-stream cap per ADR-14 §D. Redisson {@code RLock} with a
 * 35-second TTL (matches the streaming watchdog from ADR-14 §14). One in-flight
 * stream per user; a second concurrent {@code POST /api/rag/chat} from the same
 * user aborts the first (latest-wins).
 *
 * <p>The lock is acquired at turn start and released in a Reactor
 * {@code doFinally} sink. If the JVM crashes between acquire and release, the
 * 35 s TTL guarantees a stale lock auto-releases.
 */
public interface ConcurrentStreamLockPort {

    /**
     * Try-acquire non-blocking; returns a {@link Handle} the caller must
     * close (typically in {@code doFinally}). Returns null if a fresh
     * acquire failed AND the latest-wins abort path could not free the
     * previous holder.
     */
    Handle acquire(UserId user);

    /** Lock handle — call {@link #release()} once the stream terminates. */
    interface Handle extends AutoCloseable {
        void release();

        @Override
        default void close() {
            release();
        }
    }
}
