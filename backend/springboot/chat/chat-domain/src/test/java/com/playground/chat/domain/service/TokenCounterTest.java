package com.playground.chat.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.playground.chat.domain.model.vo.TokenCount;
import org.junit.jupiter.api.Test;

class TokenCounterTest {

    private final TokenCounter counter = new TokenCounter();

    @Test
    void count_emptyOrNullIsZero() {
        assertThat(counter.count(null)).isEqualTo(TokenCount.zero());
        assertThat(counter.count("")).isEqualTo(TokenCount.zero());
    }

    @Test
    void count_basicTextIsPositive() {
        TokenCount c = counter.count("hello world");
        assertThat(c.value()).isPositive();
        assertThat(c.value()).isLessThan(10);
    }

    @Test
    void truncate_belowBudgetReturnsOriginal() {
        String original = "small text";
        String out = counter.truncateToTokens(original, 1000);
        assertThat(out).isEqualTo(original);
    }

    @Test
    void truncate_aboveBudgetClipsHead() {
        // 1000-char string should be many cl100k tokens; truncate to 10 tokens
        // should yield a much shorter prefix.
        String long_ = "the quick brown fox jumps over the lazy dog. ".repeat(200);
        String out = counter.truncateToTokens(long_, 10);
        assertThat(out.length()).isLessThan(long_.length());
        assertThat(counter.count(out).value()).isLessThanOrEqualTo(10);
    }

    @Test
    void truncate_zeroBudgetReturnsInput() {
        // Per the implementation: maxTokens <= 0 short-circuits with the input.
        assertThat(counter.truncateToTokens("foo", 0)).isEqualTo("foo");
    }
}
