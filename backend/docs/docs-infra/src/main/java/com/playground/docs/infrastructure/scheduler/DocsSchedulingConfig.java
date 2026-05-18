package com.playground.docs.infrastructure.scheduler;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} discovery for the docs BC. Without
 * {@link EnableScheduling} on at least one bean definition the
 * {@link CounterResyncJob#resyncLikeCounts()} cron is silently ignored.
 *
 * <p>Single-instance dev only — ADR-12 §11 explicitly defers ShedLock /
 * multi-instance coordination to a future cycle.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class DocsSchedulingConfig {}
