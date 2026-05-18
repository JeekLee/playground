package com.playground.docs.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

/**
 * Kafka consumer wiring for the in-service search projector per M2 spec §5.1.
 *
 * <p>The projector listens as {@link JsonNode} so it stays immune to
 * backwards-compatible payload schema bumps in either docs (this BC's) or
 * any future producer (it doesn't currently consume anyone else's, but the
 * pattern is the same as identity-infra's consumer wiring).
 *
 * <p>Wrapped in {@link ErrorHandlingDeserializer} so a malformed envelope
 * surfaces as a deserialization exception that Spring Kafka can route to
 * the configured error handler (default: log + skip, the broker advances
 * the offset).
 */
@EnableKafka
@Configuration(proxyBeanMethods = false)
public class DocsKafkaConsumerConfig {

    @Bean
    public ConsumerFactory<String, JsonNode> jsonNodeConsumerFactory(
            @Value("${spring.kafka.bootstrap-servers:kafka-playground:9092}") String bootstrap,
            ObjectMapper objectMapper) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, JsonNode.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        JsonDeserializer<JsonNode> valueDeserializer = new JsonDeserializer<>(JsonNode.class, objectMapper);
        valueDeserializer.setUseTypeHeaders(false);

        DefaultKafkaConsumerFactory<String, JsonNode> factory = new DefaultKafkaConsumerFactory<>(props);
        factory.setKeyDeserializer(new ErrorHandlingDeserializer<>(new StringDeserializer()));
        factory.setValueDeserializer(new ErrorHandlingDeserializer<>(valueDeserializer));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, JsonNode> kafkaListenerContainerFactory(
            ConsumerFactory<String, JsonNode> jsonNodeConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, JsonNode> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(jsonNodeConsumerFactory);
        return factory;
    }
}
