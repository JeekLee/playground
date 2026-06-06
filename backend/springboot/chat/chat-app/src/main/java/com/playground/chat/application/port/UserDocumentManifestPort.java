package com.playground.chat.application.port;

import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.id.UserId;
import java.util.List;

/**
 * Cross-schema SELECT into {@code docs.documents} for the caller's document
 * manifest, injected into the prompt's {@code [YOUR DOCUMENTS]} section so the
 * model can resolve a natural-language document reference (ordinal / title /
 * type) to a concrete {@code documentId} when a tool requires one.
 *
 * <p>Same cross-schema-read posture as {@link OwnerDisplayNamePort} and the
 * citation resolver (ADR-14 §3). Returns documents in stable upload order
 * ({@code created_at ASC}) with 1-indexed {@link UserDocumentRef#ordinal()},
 * capped at {@code limit}. Degrades gracefully — an empty list means the
 * section is omitted, leaving the M4 prompt shape unchanged.
 */
public interface UserDocumentManifestPort {

    List<UserDocumentRef> recentForUser(UserId userId, int limit);
}
