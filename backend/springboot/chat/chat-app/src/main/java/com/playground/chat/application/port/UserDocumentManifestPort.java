package com.playground.chat.application.port;

import com.playground.chat.domain.model.UserDocumentRef;
import com.playground.chat.domain.model.id.UserId;
import java.util.List;

/**
 * Fetches the caller's document manifest for the prompt's {@code [YOUR DOCUMENTS]}
 * section so the model can resolve a natural-language document reference (ordinal
 * / title) to a concrete {@code documentId} when a tool requires one.
 *
 * <p>SP3a spec D2: the manifest now arrives via docs-api's internal endpoint
 * (the WebClient adapter) rather than a cross-schema SELECT — the chat BC no
 * longer reads docs.* directly. Returns documents in stable upload order
 * ({@code created_at ASC}) with 1-indexed {@link UserDocumentRef#ordinal()},
 * capped at {@code limit}. Degrades gracefully — an empty list means the
 * section is omitted, leaving the M4 prompt shape unchanged.
 */
public interface UserDocumentManifestPort {

    List<UserDocumentRef> recentForUser(UserId userId, int limit);
}
