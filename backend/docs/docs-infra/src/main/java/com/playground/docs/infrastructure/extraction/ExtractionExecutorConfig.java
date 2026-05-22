package com.playground.docs.infrastructure.extraction;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M6.1 ADR-12 §A12.6 — the dedicated {@link ThreadPoolExecutor} backing all
 * async extraction work in docs-api.
 *
 * <ul>
 *   <li>Core/max pool size: configurable (default 5) — pins the maximum number
 *       of concurrent Vision OCR calls in flight, across the whole JVM, across
 *       all PDFs.</li>
 *   <li>Bounded queue: capacity 200 page-tasks — beyond that the rejection
 *       policy applies natural back-pressure to the Kafka listener thread.</li>
 *   <li>Rejection policy: {@code CallerRunsPolicy} — the dispatcher thread
 *       runs the work itself when the queue overflows. This means the Kafka
 *       listener thread can run an extraction (slow) but the consumer will
 *       not poll a new message until the workflow returns, so back-pressure
 *       propagates into Kafka's flow control.</li>
 *   <li>Daemon: false — graceful shutdown lets in-flight extractions finish.</li>
 *   <li>Thread name prefix: {@code docs-extract-} for thread dumps + log
 *       MDC observability.</li>
 * </ul>
 *
 * <p>The bean is qualified with {@code extractionExecutor} so workflow code
 * does not accidentally pick up Spring's default {@code TaskExecutor} bean.
 */
@Configuration(proxyBeanMethods = false)
public class ExtractionExecutorConfig {

    @Bean(name = "extractionExecutor", destroyMethod = "shutdown")
    @Qualifier("extractionExecutor")
    public ThreadPoolExecutor extractionExecutor(
            @Value("${playground.docs.extraction.executor.parallelism:5}") int parallelism,
            @Value("${playground.docs.extraction.executor.queue-capacity:200}") int queueCapacity) {
        AtomicLong threadCounter = new AtomicLong(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setDaemon(false);
            t.setName("docs-extract-" + threadCounter.incrementAndGet());
            return t;
        };
        return new ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
