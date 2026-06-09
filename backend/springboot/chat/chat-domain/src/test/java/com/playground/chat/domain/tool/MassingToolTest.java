package com.playground.chat.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * SP4 — generate_massing accepts EITHER an uploaded brief (briefDocId) OR a
 * conversation-synthesized program (requirements). The schema must declare both
 * as optional (exactly-one is enforced server-side by agent-tools, SP4 D1) and
 * the description must steer the model to ask for the site area when unknown.
 */
class MassingToolTest {

    private static final ToolDescriptor MASSING = MassingTool.MASSING;

    @Test
    void schema_declares_requirements_property() {
        assertThat(MASSING.parameterSchema()).contains("\"requirements\"");
    }

    @Test
    void schema_retains_briefDocId_property() {
        assertThat(MASSING.parameterSchema()).contains("\"briefDocId\"");
    }

    @Test
    void schema_makes_briefDocId_optional() {
        // The old `"required":["briefDocId"]` array is gone → both sources
        // optional; exactly-one is enforced server-side by agent-tools (SP4 D1).
        // Targets the removed token specifically so an unrelated nested
        // `required` array added later wouldn't false-positive this test.
        assertThat(MASSING.parameterSchema()).doesNotContain("\"required\":[\"briefDocId\"]");
    }

    @Test
    void description_mentions_exactly_one_source() {
        assertThat(MASSING.description()).contains("EXACTLY ONE");
    }

    @Test
    void description_instructs_to_ask_for_site_area() {
        // The behavioral heart of SP4 D3: the model must ask for 대지면적 and
        // never send both sources.
        assertThat(MASSING.description()).contains("대지면적");
        assertThat(MASSING.description()).contains("ASK THE USER");
        assertThat(MASSING.description()).contains("Never send both");
    }
}
