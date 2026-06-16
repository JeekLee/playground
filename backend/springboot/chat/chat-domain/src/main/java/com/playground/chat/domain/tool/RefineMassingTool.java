package com.playground.chat.domain.tool;

import java.net.URI;
import java.time.Duration;

/**
 * M9 — the {@code refine_massing} tool descriptor. Edits an EXISTING massing
 * (.3dm) generated earlier in the conversation: re-runs the deterministic
 * massing algorithm on the prior program with typed edits applied. Hosted by
 * the {@code architecture} BC on the agent-tools service (ADR-19 §D2),
 * {@code POST /internal/tools/refine-massing} (application/x-ndjson stream).
 *
 * <p>chat injects a {@code [YOUR MODELS]} manifest of the session's model
 * artifacts; the LLM copies the {@code baseAttachmentId} of the model to edit.
 * chat resolves + validates that id to a storage key before dispatch — the LLM
 * never sees internal keys.
 */
public final class RefineMassingTool {

    private static final String INPUT_SCHEMA = """
            {"$schema":"https://json-schema.org/draft/2020-12/schema",
             "type":"object","required":["baseAttachmentId","edits"],"additionalProperties":false,
             "properties":{
               "baseAttachmentId":{"type":"string","format":"uuid",
                 "description":"수정할 기존 매싱 모델의 attachment id — [YOUR MODELS] 목록에서 정확히 복사. 절대 지어내지 말 것"},
               "edits":{"type":"array","minItems":1,
                 "description":"적용할 수정 목록 — 사용자가 요청한 변경을 각각 하나의 항목으로",
                 "items":{"type":"object","required":["op"],"additionalProperties":false,
                   "properties":{
                     "op":{"type":"string","enum":["RenameRoom","AddRoom","SetFloors","SetArea"],
                       "description":"수정 종류"},
                     "from":{"type":"string","description":"RenameRoom: 기존 실 이름"},
                     "to":{"type":"string","description":"RenameRoom: 새 실 이름"},
                     "name":{"type":"string","description":"AddRoom: 추가할 실 이름"},
                     "zone":{"type":"string","description":"AddRoom: 소속 존(선택)"},
                     "target":{"type":"string","description":"SetArea: 면적을 바꿀 존 또는 실 이름"},
                     "targetFloorsAbove":{"type":"integer","minimum":1,
                       "description":"SetFloors: 새 지상 층수"},
                     "areaM2":{"type":"number","exclusiveMinimum":0,
                       "description":"AddRoom/SetArea: 면적(㎡)"}}}}}}
            """;

    private static final String DESCRIPTION = """
            Apply edits to an EXISTING massing model (.3dm) and produce a revised \
            version. STRICT TRIGGER: invoke ONLY when the user's most recent message \
            explicitly asks to MODIFY / 수정 / 변경 / 층 추가 / 층 줄여 / 면적 조정 / edit / \
            change a massing that was ALREADY generated in THIS conversation. \
            A brand-new massing uses generate_massing, NOT this tool. \
            Provide baseAttachmentId by copying the exact id of the target model from \
            the [YOUR MODELS] list; if no prior massing exists, DO NOT call this tool — \
            use generate_massing or ask the user. Never invent the id, and never point \
            it at a non-model attachment (document, image). If several models exist and \
            the user is ambiguous, ask which one. \
            Express the change as one or more edits: RenameRoom{from,to}, \
            AddRoom{name,areaM2,zone?}, SetFloors{targetFloorsAbove}, SetArea{target,areaM2}. \
            A regeneration/edit request is only satisfied by invoking this tool in THIS \
            turn — never claim a model was modified from history. The revised .3dm is \
            delivered via a download button in the UI automatically — DO NOT write any \
            URL, file link, or download path in your text response. Relay the tool-result \
            summary and confirm completion.""";

    private static final URI DEFAULT_ENDPOINT =
            URI.create("http://playground-agent-tools:18083/internal/tools/refine-massing");

    public static final ToolDescriptor REFINE = new ToolDescriptor(
            "refine_massing",
            "매싱 수정",
            DESCRIPTION,
            INPUT_SCHEMA,
            resolveEndpoint(),
            Duration.ofSeconds(60),
            Duration.ofSeconds(600));

    private RefineMassingTool() {
        // constants class — instantiation disallowed
    }

    private static URI resolveEndpoint() {
        String override = System.getenv("PLAYGROUND_REFINE_MASSING_TOOL_URL");
        if (override == null || override.isBlank()) {
            return DEFAULT_ENDPOINT;
        }
        return URI.create(override);
    }
}
