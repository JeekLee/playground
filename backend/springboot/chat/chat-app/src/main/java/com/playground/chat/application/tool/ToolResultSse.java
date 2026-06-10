package com.playground.chat.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.chat.domain.model.Attachment;

/**
 * Builds the {@code tool_result} SSE result payload (ADR-20 §D4). Presentation
 * concern extracted from {@code ToolLoop}: the LLM-visible result body, plus —
 * when an artifact was captured — an {@code attachment} object + top-level
 * {@code fileUrl} the FE's MassingResultCard reads.
 */
public final class ToolResultSse {

    private ToolResultSse() {
    }

    /** Download URL prefix for message attachments (ADR-20 §D4). FE-facing, gateway-routed. */
    private static final String ATTACHMENT_DOWNLOAD_PREFIX = "/api/chat/attachments/";

    /**
     * Enrich the LLM-visible body for the SSE {@code tool_result}. With no
     * artifact the body is returned unchanged (M7 wire shape, byte-identical for
     * plain tools); otherwise an attachment object + fileUrl are grafted on.
     */
    public static Object enrich(ObjectMapper objectMapper, JsonNode body, Attachment attachment) {
        if (attachment == null) {
            return body;
        }
        ObjectNode enriched;
        if (body != null && body.isObject()) {
            enriched = ((ObjectNode) body).deepCopy();
        } else {
            // Non-object result (rare for a file-producing tool) — nest it under
            // `result` so the attachment fields have somewhere to live.
            enriched = objectMapper.createObjectNode();
            enriched.set("result", body == null ? objectMapper.nullNode() : body.deepCopy());
        }
        String downloadUrl = ATTACHMENT_DOWNLOAD_PREFIX + attachment.id();
        ObjectNode attachmentNode = objectMapper.createObjectNode();
        attachmentNode.put("id", attachment.id().toString());
        attachmentNode.put("filename", attachment.filename());
        attachmentNode.put("contentType", attachment.contentType());
        attachmentNode.put("sizeBytes", attachment.sizeBytes());
        attachmentNode.put("downloadUrl", downloadUrl);
        enriched.set("attachment", attachmentNode);
        enriched.put("fileUrl", downloadUrl);
        if (attachment.briefTitle() != null) {
            enriched.put("briefTitle", attachment.briefTitle());
        }
        return enriched;
    }
}
