package com.playground.chat.domain.model.vo;

/**
 * Non-negative integer wrapper for token counts per ADR-14 §8. Lets the budget
 * arithmetic (system + retrieval + history + completion) be explicit about
 * what units it's adding without leaking raw ints into VOs.
 */
public record TokenCount(int value) {

    public TokenCount {
        if (value < 0) {
            throw new IllegalArgumentException("TokenCount must be >= 0, got " + value);
        }
    }

    public static TokenCount of(int value) {
        return new TokenCount(value);
    }

    public static TokenCount zero() {
        return new TokenCount(0);
    }

    public TokenCount plus(TokenCount other) {
        return new TokenCount(this.value + other.value);
    }

    public TokenCount minus(TokenCount other) {
        return new TokenCount(Math.max(0, this.value - other.value));
    }

    public boolean exceeds(TokenCount budget) {
        return this.value > budget.value;
    }

    public boolean exceeds(int budget) {
        return this.value > budget;
    }
}
