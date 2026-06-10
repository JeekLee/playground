package com.playground.chat.application.service;

/**
 * Audit-log sanitization helpers (ADR-14 §15). Keeps opaque user identifiers
 * out of logs in cleartext while preserving enough prefix to correlate.
 */
final class LogMasking {

    private LogMasking() {
    }

    /**
     * Mask a user-sub (or similar opaque identifier) for audit logs: keep the
     * first 4 chars and replace the rest with {@code ***}; values of 4 chars or
     * fewer are fully masked. Null-safe.
     */
    static String maskSub(String sub) {
        if (sub == null || sub.length() <= 4) {
            return "***";
        }
        return sub.substring(0, 4) + "***";
    }
}
