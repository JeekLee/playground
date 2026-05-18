package com.playground.ragingestion.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Kafka consumer + DLQ wiring for the three consumed docs topics per ADR-13
 * §4 + §8. The consumer factory is shared across all {@code @KafkaListener}
 * methods; the {@link DefaultErrorHandler} routes retry-exhausted failures
 * to {@code <topic>.dlq} per ADR-13 §8.
 *
 * <p>Spring Kafka 3.x setter-vs-properties caveat (see
 * {@code DocsKafkaConsumerConfig}'s comment) — deserializers go on the
 * factory via setters, not in the props map, to avoid the "configured with
 * setters or properties, not both" exception.
 *
 * <p>Producer side is fully Spring-Boot-auto-configured from
 * {@code spring.kafka.producer.*} in {@code application.yml} (acks=all,
 * StringSerializer key, JsonSerializer value, lz4 compression,
 * idempotence). The auto-created {@code kafkaTemplate} bean satisfies the
 * Spring Modulith {@code KafkaEventExternalizerConfiguration}'s
 * {@code KafkaOperations} dependency. Declaring a custom ProducerFactory
 * here would trip Spring Boot's
 * {@code @ConditionalOnMissingBean(ProducerFactory.class)} gate and
 * silently disable both the auto ProducerFactory and the auto KafkaTemplate
 * — which manifests as
 * "kafkaEventExternalizer required a bean of type KafkaOperations"
 * on startup.
 */
@EnableKafka
@Configuration(proxyBeanMethods = false)
public class RagKafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, JsonNode> ragConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:kafka-playground:9092}") String bootstrap,
            ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Group id is set on the @KafkaListener; not in the factory.

        JsonDeserializer<JsonNode> valueDeserializer = new JsonDeserializer<>(JsonNode.class, objectMapper);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.addTrustedPackages("*");

        DefaultKafkaConsumerFactory<String, JsonNode> factory = new DefaultKafkaConsumerFactory<>(props);
        factory.setKeyDeserializer(new ErrorHandlingDeserializer<>(new StringDeserializer()));
        factory.setValueDeserializer(new ErrorHandlingDeserializer<>(valueDeserializer));
        return factory;
    }

    /**
     * Routes retry-exhausted records to {@code <sourceTopic>.dlq} per ADR-13
     * §8 + ADR-03 convention. {@code partition % 3} mirrors the ADR-pinned
     * 3-partition DLQ shape — partition affinity per documentId is preserved
     * within the bounds of the smaller partition count.
     *
     * <p>The {@code KafkaOperations} parameter is injected by type — Spring
     * resolves it to the Boot-auto-configured {@code kafkaTemplate} bean.
     */
    @Bean
    public DefaultErrorHandler ragKafkaErrorHandler(KafkaOperations<?, ?> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlq", record.partition() % 3));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(2);
        backOff.setInitialInterval(400L);
        backOff.setMultiplier(4.0);
        backOff.setMaxInterval(5_000L);

        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, JsonNode> kafkaListenerContainerFactory(
            ConsumerFactory<String, JsonNode> ragConsumerFactory,
            DefaultErrorHandler ragKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, JsonNode> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(ragConsumerFactory);
        factory.setCommonErrorHandler(ragKafkaErrorHandler);
        return factory;
    }
}
