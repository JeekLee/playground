package com.playground.chat.application.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.playground.chat.application.repository.AttachmentRepository;
import com.playground.chat.domain.model.Attachment;
import com.playground.chat.domain.model.id.AttachmentId;
import com.playground.chat.domain.tool.ToolErrorCode;
import com.playground.shared.chat.ChatStreamEvent;
import java.util.UUID;
import reactor.core.publisher.Sinks;

/**
 * Resolve + validate + transform the {@code refine_massing} args before dispatch
 * (M9). Tool-specific logic extracted from {@code ToolLoop}: the LLM picks a
 * {@code baseAttachmentId} from the {@code [YOUR MODELS]} manifest, and chat
 * resolves it to a storage key BEFORE dispatch (so agent-tools can read the
 * .3dm) while the key never reaches the LLM or the SSE stream.
 */
public final class RefineMassingResolver {

    private RefineMassingResolver() {
    }

    /**
     * For {@code refine_massing}: look the {@code baseAttachmentId} up
     * owner-scoped, reject anything that is not a massing model (.3dm from
     * generate/refine_massing — including a missing / non-owned / non-UUID id) by
     * emitting a non-terminal {@code tool_error} ({@code UPSTREAM_4XX}) on
     * {@code sink} and returning {@code null} (caller skips dispatch); otherwise
     * strip {@code baseAttachmentId} and substitute the resolved
     * {@code baseStorageKey}. Non-{@code refine_massing} tools pass through.
     *
     * @return transformed args, the original args for non-refine tools, or
     *     {@code null} when validation failed (a {@code tool_error} was emitted).
     */
    public static JsonNode resolve(String toolName, JsonNode args, String callId,
                                   UserContext userCtx, AttachmentRepository attachmentRepository,
                                   Sinks.Many<ChatStreamEvent> sink) {
        if (!"refine_massing".equals(toolName)) {
            return args;
        }
        JsonNode baseIdNode = (args == null) ? null : args.get("baseAttachmentId");
        Attachment base = null;
        if (baseIdNode != null && baseIdNode.isTextual()) {
            try {
                base = attachmentRepository
                        .findOwned(AttachmentId.of(UUID.fromString(baseIdNode.asText())), userCtx.userId())
                        .orElse(null);
            } catch (IllegalArgumentException ignored) {
                base = null; // non-UUID id
            }
        }
        if (base == null || !isModelAttachment(base)) {
            sink.tryEmitNext(new ChatStreamEvent.ToolError(
                    callId, toolName, ToolErrorCode.UPSTREAM_4XX.name(),
                    "지정한 첨부는 수정 가능한 매싱 모델이 아닙니다. 어떤 모델을 수정할지 알려주세요."));
            return null;
        }
        ObjectNode transformed = ((ObjectNode) args).deepCopy();
        transformed.remove("baseAttachmentId");
        transformed.put("baseStorageKey", base.storageKey());
        return transformed;
    }

    /**
     * A massing model is a {@code .3dm} produced by generate_massing or
     * refine_massing. refine_massing may only target one of these — never a
     * document, image, or other attachment kind.
     */
    private static boolean isModelAttachment(Attachment a) {
        String tool = a.toolName();
        return ("generate_massing".equals(tool) || "refine_massing".equals(tool))
                && a.filename() != null && a.filename().endsWith(".3dm");
    }
}
