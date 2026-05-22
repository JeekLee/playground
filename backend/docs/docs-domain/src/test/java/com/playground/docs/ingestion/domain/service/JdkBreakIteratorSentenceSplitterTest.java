package com.playground.docs.ingestion.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class JdkBreakIteratorSentenceSplitterTest {

    private final SentenceSplitter splitter = new JdkBreakIteratorSentenceSplitter();

    @Test
    void korean_text_splits_on_sentence_terminators() {
        String input = "한국어 문장입니다. 두 번째 문장이에요. 그리고 세 번째!";
        List<String> sentences = splitter.split(input, Locale.KOREAN);

        assertThat(sentences).hasSize(3);
        assertThat(sentences.get(0).trim()).isEqualTo("한국어 문장입니다.");
        assertThat(sentences.get(1).trim()).isEqualTo("두 번째 문장이에요.");
        assertThat(sentences.get(2).trim()).isEqualTo("그리고 세 번째!");
    }

    @Test
    void english_text_splits_on_periods_and_exclamation() {
        List<String> sentences = splitter.split(
                "The quick brown fox. It jumps over. Done!", Locale.ENGLISH);
        assertThat(sentences).hasSize(3);
    }

    @Test
    void empty_input_returns_empty_list() {
        assertThat(splitter.split("", Locale.KOREAN)).isEmpty();
        assertThat(splitter.split("   \n  ", Locale.KOREAN)).isEmpty();
    }

    @Test
    void single_sentence_returns_one_element() {
        List<String> sentences = splitter.split("이건 한 문장.", Locale.KOREAN);
        assertThat(sentences).hasSize(1);
    }

    @Test
    void output_concatenation_equals_input_verbatim() {
        String input = "첫 번째.\n두 번째!\n  세 번째?  ";
        String joined = String.join("", splitter.split(input, Locale.KOREAN));
        assertThat(joined).isEqualTo(input);
    }
}
