package com.playground.chat.domain.enums;

/**
 * Role of a chat turn per ADR-14 §F. Wire value matches the
 * {@code chat.messages.role} CHECK constraint.
 */
public enum Role {
    USER("user"),
    ASSISTANT("assistant");

    private final String wireValue;

    Role(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Role fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (Role r : values()) {
            if (r.wireValue.equalsIgnoreCase(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unknown role: " + value);
    }

    public boolean isUser() {
        return this == USER;
    }

    public boolean isAssistant() {
        return this == ASSISTANT;
    }
}
