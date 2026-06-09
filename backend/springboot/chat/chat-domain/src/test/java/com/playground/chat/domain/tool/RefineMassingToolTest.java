package com.playground.chat.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefineMassingToolTest {

    private static final ToolDescriptor REFINE = RefineMassingTool.REFINE;

    @Test
    void registered_in_catalog() {
        assertThat(ToolCatalog.descriptors())
                .extracting(ToolDescriptor::name)
                .contains("refine_massing");
    }

    @Test
    void schema_requires_base_attachment_id_and_edits() {
        assertThat(REFINE.parameterSchema()).contains("\"baseAttachmentId\"");
        assertThat(REFINE.parameterSchema()).contains("\"edits\"");
        assertThat(REFINE.parameterSchema()).contains("\"required\":[\"baseAttachmentId\",\"edits\"]");
    }

    @Test
    void description_steers_to_existing_model_only() {
        assertThat(REFINE.description()).contains("EXISTING");
        assertThat(REFINE.description()).contains("generate_massing");
    }

    @Test
    void display_name_and_name() {
        assertThat(REFINE.name()).isEqualTo("refine_massing");
        assertThat(REFINE.displayName()).isEqualTo("매싱 수정");
    }
}
