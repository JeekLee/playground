package com.playground.shared.error;

/**
 * Marker interface for BC-scoped error-code enums. Per ADR-11 §"Six HTTP-typed
 * exception subclasses", each BC defines its own enum implementing this
 * interface (e.g. {@code IdentityErrorCode implements ErrorCode}).
 * <p>
 * Code format: {@code <BC>-<SUBSYSTEM>-<NNN>} — see {@code IDENTITY-BOOTSTRAP-001}
 * in the identity-domain module for a concrete example.
 */
public interface ErrorCode {
    /** Stable machine-readable code, e.g. {@code IDENTITY-BOOTSTRAP-001}. */
    String code();

    /**
     * Default English message, optionally with {@code {0}}, {@code {1}} ... placeholders
     * filled by {@link AbstractException#messageArgs()}.
     */
    String defaultMessage();
}
