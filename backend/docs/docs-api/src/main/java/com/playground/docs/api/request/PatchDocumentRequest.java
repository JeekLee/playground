package com.playground.docs.api.request;

import com.playground.docs.application.dto.PatchDocumentCommand;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.docs.domain.model.vo.DocumentTitle;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code PATCH /api/docs/{id}} per M2 spec §6.4
 * {@code PatchDocRequest}: {@code {title?, body?}}. Both fields are optional;
 * {@code null} means "leave unchanged". Publish / unpublish flows through the
 * dedicated {@code POST /api/docs/{id}/publish} + {@code /unpublish}
 * endpoints (spec §6.1); folder moves land in M2.1 via {@code POST
 * /api/docs/{id}/move}. Neither field is carried here.
 *
 * <p>Validation: {@code title}, when present, must not be blank — the
 * application service re-checks via {@code DocumentTitle.of(...)}.
 * {@code body}, when present, must fit the 1 MB cap.
 */
public record PatchDocumentRequest(
        @Size(max = DocumentTitle.MAX_LENGTH) String title,
        @Size(max = DocumentBody.MAX_OCTET_LENGTH) String body) {

    public PatchDocumentCommand toCommand(UUID documentId, UUID callerId) {
        return new PatchDocumentCommand(documentId, callerId, title, body);
    }
}
