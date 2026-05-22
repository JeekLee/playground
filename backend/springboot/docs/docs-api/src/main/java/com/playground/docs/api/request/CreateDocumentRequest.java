package com.playground.docs.api.request;

import com.playground.docs.application.dto.CreateDocumentCommand;
import com.playground.docs.domain.model.vo.DocumentBody;
import com.playground.docs.domain.model.vo.DocumentTitle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request body for {@code POST /api/docs} per M2 spec §6.4
 * {@code CreateDocRequest}. Per the S1 brief the editor-driven multipart
 * upload affordance is deferred — only the JSON path is wired here.
 *
 * @param title  required, non-blank
 * @param body   optional; null is normalized to empty in the application
 *               service. 1 MB cap enforced both at this layer
 *               ({@link DocumentBody#MAX_OCTET_LENGTH}) and at the DB CHECK
 *               constraint.
 * @param path   optional; null defaults to {@code "/"} (root)
 */
public record CreateDocumentRequest(
        @NotBlank @Size(max = DocumentTitle.MAX_LENGTH) String title,
        @Size(max = DocumentBody.MAX_OCTET_LENGTH) String body,
        String path) {

    public CreateDocumentCommand toCommand(UUID authorId) {
        return new CreateDocumentCommand(authorId, title, body, path);
    }
}
