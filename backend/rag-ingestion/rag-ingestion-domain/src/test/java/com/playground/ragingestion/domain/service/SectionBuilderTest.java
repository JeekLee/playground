package com.playground.ragingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.commonmark.node.Heading;
import org.junit.jupiter.api.Test;

class SectionBuilderTest {

    private final SectionBuilder builder = new SectionBuilder();

    @Test
    void empty_body_yields_no_sections() {
        assertThat(builder.build("")).isEmpty();
    }

    @Test
    void document_with_no_headings_is_one_root_section_with_empty_path() {
        String md = "Plain paragraph one.\n\nPlain paragraph two.";
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).headingPath()).isEmpty();
        assertThat(sections.get(0).blocks()).hasSize(2);
    }

    @Test
    void content_before_first_heading_is_a_pathless_section() {
        String md = "Intro paragraph.\n\n# First heading\n\nBody under H1.";
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).headingPath()).isEmpty();
        assertThat(sections.get(1).headingPath()).containsExactly("First heading");
    }

    @Test
    void nested_headings_build_a_path_stack() {
        String md = """
                # Top
                p1
                ## Mid
                p2
                ### Deep
                p3
                """;
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).headingPath()).containsExactly("Top");
        assertThat(sections.get(1).headingPath()).containsExactly("Top", "Mid");
        assertThat(sections.get(2).headingPath()).containsExactly("Top", "Mid", "Deep");
    }

    @Test
    void sibling_headings_pop_back_to_correct_depth() {
        String md = """
                # A
                a1
                ## A1
                a1-body
                # B
                b1
                """;
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(3);
        assertThat(sections.get(0).headingPath()).containsExactly("A");
        assertThat(sections.get(1).headingPath()).containsExactly("A", "A1");
        assertThat(sections.get(2).headingPath()).containsExactly("B");
    }

    @Test
    void heading_level_skip_h1_to_h3_keeps_intermediate_slots_empty_string() {
        String md = """
                # A
                ### Deep
                body
                """;
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(1).headingPath()).containsExactly("A", "", "Deep");
    }

    @Test
    void heading_node_itself_is_included_as_the_first_block_of_its_section() {
        String md = "# Title\n\nBody.";
        List<Section> sections = builder.build(md);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).blocks().get(0)).isInstanceOf(Heading.class);
    }
}
