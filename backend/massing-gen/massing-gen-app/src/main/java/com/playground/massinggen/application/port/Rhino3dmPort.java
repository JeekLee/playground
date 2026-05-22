package com.playground.massinggen.application.port;

import com.playground.massinggen.domain.model.RoomBox;
import java.util.List;

/**
 * Outbound port — serializes a list of {@link RoomBox}es into a .3dm
 * binary via the {@code rhino3dm-bridge} Node sidecar per ADR-18 §11.
 *
 * <p>The adapter ({@code Rhino3dmAdapter} in {@code massing-gen-infra})
 * issues {@code POST http://rhino3dm-bridge:4000/serialize} with a JSON
 * box list and reads back the {@code application/octet-stream} response.
 * Wrapped by the Resilience4j {@code rhino3dm-bridge} breaker per
 * ADR-18 §17; sidecar 5xx / timeout / {@code CallNotPermittedException}
 * surfaces as {@code SIDECAR_FAILED} / {@code SIDECAR_TIMEOUT} per ADR-18 §7.
 */
public interface Rhino3dmPort {

    /**
     * Serialize the room boxes into a .3dm binary.
     *
     * @param boxes algorithm output, ordered by floor / placement
     * @return .3dm bytes — to be persisted to {@code arch.outputs.file_bytes}
     */
    byte[] serialize(List<RoomBox> boxes);
}
