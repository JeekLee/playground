package com.playground.identity.infrastructure.events;

import com.playground.identity.domain.event.UserProfileUpdated;
import com.playground.identity.domain.event.UserRegistered;
import com.playground.shared.event.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/**
 * Wires Spring Modulith's event externalizer to Kafka topics per ADR-03 +
 * ADR-10 §8. Each domain event is wrapped in the shared-kernel
 * {@link EventEnvelope} before publication so consumers see one canonical
 * shape across BCs.
 *
 * <p>Selection is by class (not by {@code @Externalized} annotation) so the
 * domain-event records in {@code identity-domain} stay free of any Modulith
 * import — Spring Modulith never reaches into {@code -domain}.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityEventExternalizationConfig {

    @Bean
    public EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof UserRegistered || event instanceof UserProfileUpdated)
                .route(UserRegistered.class, event -> RoutingTarget.forTarget("identity.user.registered")
                        .andKey(event.id().value().toString()))
                .route(UserProfileUpdated.class, event -> RoutingTarget.forTarget("identity.user.profile-updated")
                        .andKey(event.id().value().toString()))
                .mapping(UserRegistered.class, event -> wrap("identity.user.registered", event))
                .mapping(UserProfileUpdated.class, event -> wrap("identity.user.profile-updated", event))
                .build();
    }

    private static <T> EventEnvelope<T> wrap(String eventType, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                "identity",
                1,
                payload);
    }
}
