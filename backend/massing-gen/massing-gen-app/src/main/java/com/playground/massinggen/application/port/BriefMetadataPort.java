package com.playground.massinggen.application.port;

import com.playground.massinggen.application.dto.BriefMetadata;
import com.playground.massinggen.application.dto.UserContext;
import java.util.UUID;

/**
 * Outbound port for fetching brief metadata + body from docs-api per
 * ADR-08 §A08.12 Exception 5 + ADR-18 §3.
 *
 * <p>The adapter ({@code HttpBriefBodyAdapter} in {@code massing-gen-infra})
 * forwards {@code X-User-Id} + {@code X-User-Sub} from {@code UserContext}
 * to docs-api. docs-api enforces visibility on the public
 * {@code GET /api/docs/&lbrace;id&rbrace;} route — a private brief whose
 * owner does not match the caller returns 404 (which the adapter maps to
 * {@link com.playground.massinggen.domain.exception.MassingErrorCode#BRIEF_NOT_FOUND}).
 *
 * <p>Returns the metadata + body in one call site. The use case then
 * decides between {@code BRIEF_NOT_READY} (status != "extracted") and the
 * happy path.
 */
public interface BriefMetadataPort {

    /**
     * Fetch brief metadata + body. Throws
     * {@link com.playground.massinggen.domain.exception.MassingException}
     * with a relevant {@link com.playground.massinggen.domain.exception.MassingErrorCode}
     * on 404 / network failure / forbidden.
     *
     * @param briefDocId brief document UUID
     * @param caller     identity to forward (X-User-Id + X-User-Sub)
     */
    BriefMetadata fetch(UUID briefDocId, UserContext caller);
}
