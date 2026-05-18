package com.playground.docs.application.service;

import com.playground.docs.application.port.OwnerResolverPort;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around {@link OwnerResolverPort} so {@code DocsController} (or
 * an {@code OwnerController}) can resolve the owner user id without depending
 * on the adapter directly.
 *
 * <p>Per M2 spec §6.3 the endpoint is fail-closed — {@link #ownerUserId()}
 * returns {@link Optional#empty()} when the env var is unset or the boot
 * lookup misses; the controller surfaces {@code { ownerUserId: null }}.
 */
@Service
public class OwnerService {

    private final OwnerResolverPort ownerResolver;

    public OwnerService(OwnerResolverPort ownerResolver) {
        this.ownerResolver = ownerResolver;
    }

    public Optional<UUID> ownerUserId() {
        return ownerResolver.ownerUserId();
    }
}
