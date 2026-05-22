package com.playground.docs.infrastructure.ingestion.kafka;

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
 * Kafka consumer + DLQ wiring for the three docs.document.* topics that the
 * (now in-BC) ingestion pipeline consumes per ADR-13 §4 + §8. M6.1 absorbed
 * the rag-ingestion BC into docs (ADR-12 §A12.1), so this config now shares
 * a JVM with {@link com.playground.docs.infrastructure.config.DocsKafkaConsumerConfig}
 * (M2's search-projector consumer factory). The two factories carry separate
 * bean names — the search projector uses the default
 * {@code kafkaListenerContainerFactory}; the ingestion listener targets
 * {@code ingestionKafkaListenerContainerFactory} via the
 * {@code containerFactory} attribute on its {@code @KafkaListener}
 * methods.
 *
 * <p>The DLQ error handler is preserved verbatim from the M3 wiring — DLQ
 * routing applies to ingestion listeners only; the search projector keeps
 * its M2 "log + redeliver" semantics.
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
 * {@code KafkaOperations} dependency.
 */
@EnableKafka
@Configuration(proxyBeanMethods = false)
public class RagKafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, JsonNode> ingestionConsumerFactory(
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
    public DefaultErrorHandler ingestionKafkaErrorHandler(KafkaOperations<?, ?> kafkaTemplate) {
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
    public ConcurrentKafkaListenerContainerFactory<String, JsonNode> ingestionKafkaListenerContainerFactory(
            ConsumerFactory<String, JsonNode> ingestionConsumerFactory,
            DefaultErrorHandler ingestionKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, JsonNode> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(ingestionConsumerFactory);
        factory.setCommonErrorHandler(ingestionKafkaErrorHandler);
        return factory;
    }
}
