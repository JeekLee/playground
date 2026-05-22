package com.playground.metrics.api.controller;

import com.playground.metrics.app.BuildServicesUseCase;
import com.playground.metrics.app.dto.ServicesResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Reactive controller for {@code GET /api/metrics/services} per spec §5.1.
 * Gateway routes {@code /api/metrics/**} with {@code StripPrefix=2}, so this
 * controller listens on {@code /services}.
 *
 * <p>Lightweight subset of the dashboard payload — just the service health
 * grid. Cached aggressively (P0: per-request; slice 2 may add a short-TTL
 * in-process cache).
 */
@RestController
public class ServicesController {

    private final BuildServicesUseCase useCase;

    public ServicesController(BuildServicesUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping(value = {"/services", "/api/metrics/services"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ServicesResponse> services() {
        return useCase.execute().map(ServicesResponse::new);
    }
}
