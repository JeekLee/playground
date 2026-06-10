package com.playground.chat.infrastructure.redis;

import com.playground.chat.application.port.ConcurrentStreamLockPort;
import com.playground.chat.domain.model.id.UserId;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-user concurrent-stream cap per ADR-14 §D. Lock key
 * {@code chat:lock:user:&lt;userId&gt;}, TTL 35 s (matches the streaming
 * watchdog from ADR-14 §14).
 *
 * <p>Acquire policy: non-blocking try-lock; on contention the previous
 * holder's lock is force-unlocked (latest-wins per spec §10). The aborted
 * holder's Reactor pipeline disposes via the 35-s TTL expiry combined with
 * the SSE close on lock release; M4 P0 keeps this simpler than maintaining
 * a {@code ConcurrentHashMap&lt;UserId, Disposable&gt;} for in-flight subscriptions.
 */
@Component
@RequiredArgsConstructor
public class RedissonConcurrentStreamLockAdapter implements ConcurrentStreamLockPort {

    private static final Logger log = LoggerFactory.getLogger(RedissonConcurrentStreamLockAdapter.class);

    private static final String KEY_FMT = "chat:lock:user:%s";
    // Lock lease must outlast the longest legitimate turn. A tool-calling turn can
    // run up to the tool budget (MassingTool 60s) + generation, so a 35s lease would
    // auto-expire mid-turn and let a concurrent stream slip past the 1-stream cap
    // (ADR-14 §D). 120s covers the slow-tool turn; normal cap enforcement still uses
    // explicit latest-wins release, faster than the lease.
    private static final long LEASE_SECONDS = 120L;

    private final RedissonClient redisson;

    @Override
    public Handle acquire(UserId user) {
        String key = String.format(KEY_FMT, user.value());
        RLock lock = redisson.getLock(key);
        boolean acquired;
        try {
            acquired = lock.tryLock(0L, LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while acquiring chat lock for user={}", user);
            return null;
        }
        if (!acquired) {
            // Latest-wins: force-release the previous holder and re-acquire.
            log.info("latest-wins: releasing prior stream lock for user={}", user);
            try {
                lock.forceUnlock();
                acquired = lock.tryLock(0L, LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        if (!acquired) {
            return null;
        }
        return new ReleasableHandle(lock, key);
    }

    private static final class ReleasableHandle implements Handle {
        private final RLock lock;
        private final String key;
        private boolean released = false;

        ReleasableHandle(RLock lock, String key) {
            this.lock = lock;
            this.key = key;
        }

        @Override
        public synchronized void release() {
            if (released) {
                return;
            }
            released = true;
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                } else {
                    // Thread changed mid-stream (Reactor scheduler hop) — force-unlock.
                    lock.forceUnlock();
                }
            } catch (RuntimeException e) {
                log.warn("chat lock release failed key={} reason={}", key, e.toString());
            }
        }
    }
}
