package com.playground.identity.domain.model.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Email VO. We trust Google to deliver a well-formed RFC-5322 address but
 * still reject the most obvious cases (blank, missing {@code @}) so a domain
 * test exercising malformed input can be expressed cleanly.
 */
public record Email(String value) {

    private static final Pattern SIMPLE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public Email {
        Objects.requireNonNull(value, "Email.value must not be null");
        if (value.isBlank() || !SIMPLE.matcher(value).matches()) {
            throw new IllegalArgumentException("Email.value is not a well-formed address: " + value);
        }
    }

    public static Email of(String value) {
        return new Email(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
