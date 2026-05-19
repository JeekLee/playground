package com.playground.metrics.infra.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@code metrics-infra} adapters into the Spring context. The
 * {@code metrics-api}'s {@code @ComponentScan} picks this up via the
 * {@code com.playground.metrics} root scan.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "com.playground.metrics.infra")
@EnableConfigurationProperties(MetricsHttpProperties.class)
public class MetricsInfraConfig {
}
