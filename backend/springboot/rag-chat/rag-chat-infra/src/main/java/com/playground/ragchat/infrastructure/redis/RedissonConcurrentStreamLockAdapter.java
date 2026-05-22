package com.playground.ragchat.infrastructure.redis;

import com.playground.ragchat.application.port.ConcurrentStreamLockPort;
import com.playground.ragchat.domain.model.id.UserId;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-user concurrent-stream cap per ADR-14 §D. Lock key
 * {@code rag-chat:lock:user:&lt;userId&gt;}, TTL 35 s (matches the streaming
 * watchdog from ADR-14 §14).
 *
 * <p>Acquire policy: non-blocking try-lock; on contention the previous
 * holder's lock is force-unlocked (latest-wins per spec §10). The aborted
 * holder's Reactor pipeline disposes via the 35-s TTL expiry combined with
 * the SSE close on lock release; M4 P0 keeps this simpler than maintaining
 * a {@code ConcurrentHashMap&lt;UserId, Disposable&gt;} for in-flight subscriptions.
 */
@Component
public class RedissonConcurrentStreamLockAdapter implements ConcurrentStreamLockPort {

    private static final Logger log = LoggerFactory.getLogger(RedissonConcurrentStreamLockAdapter.class);

    private static final String KEY_FMT = "rag-chat:lock:user:%s";
    private static final long LEASE_SECONDS = 35L;

    private final RedissonClient redisson;

    public RedissonConcurrentStreamLockAdapter(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public Handle acquire(UserId user) {
        String key = String.format(KEY_FMT, user.value());
        RLock lock = redisson.getLock(key);
        boolean acquired;
        try {
            acquired = lock.tryLock(0L, LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted while acquiring rag-chat lock for user={}", user);
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
                log.warn("rag-chat lock release failed key={} reason={}", key, e.toString());
            }
        }
    }
}
