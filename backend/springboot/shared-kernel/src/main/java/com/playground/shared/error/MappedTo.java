package com.playground.shared.error;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that pins an {@link ErrorCode} enum constant to its
 * {@link AbstractException} subclass. Used by {@link ExceptionCreator} to
 * resolve the right subclass reflectively.
 *
 * <p>Example:
 * <pre>
 * public enum IdentityErrorCode implements ErrorCode {
 *     {@literal @MappedTo(NotFoundException.class)}
 *     USER_NOT_FOUND("IDENTITY-USER-001", "User not found: {0}"),
 *     ...
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MappedTo {
    Class<? extends AbstractException> value();
}
