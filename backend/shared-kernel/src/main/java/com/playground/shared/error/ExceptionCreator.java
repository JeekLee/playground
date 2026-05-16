package com.playground.shared.error;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Fluent factory per ADR-11 §"ExceptionCreator". Reads the {@link MappedTo}
 * annotation on the supplied {@link ErrorCode} enum constant, reflectively
 * instantiates the right {@link AbstractException} subclass, and either throws
 * or returns it.
 *
 * <pre>
 * ExceptionCreator.of(IdentityErrorCode.USER_NOT_FOUND, userId).throwIt();
 * </pre>
 */
public final class ExceptionCreator {

    private final ErrorCode errorCode;
    private final Object[] messageArgs;
    private Class<? extends AbstractException> override;

    private ExceptionCreator(ErrorCode errorCode, Object[] messageArgs) {
        this.errorCode = errorCode;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs;
    }

    public static ExceptionCreator of(ErrorCode errorCode, Object... messageArgs) {
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        return new ExceptionCreator(errorCode, messageArgs);
    }

    /** Override the subclass that {@link MappedTo} would otherwise pick. */
    public ExceptionCreator as(Class<? extends AbstractException> subclass) {
        this.override = subclass;
        return this;
    }

    public AbstractException build() {
        Class<? extends AbstractException> target = override != null ? override : resolveMapping(errorCode);
        if (target == null) {
            // Fall back to InternalServerErrorException so callers never silently swallow a mapping miss.
            target = InternalServerErrorException.class;
        }
        try {
            Constructor<? extends AbstractException> ctor =
                    target.getDeclaredConstructor(ErrorCode.class, Object[].class);
            ctor.setAccessible(true);
            return ctor.newInstance(errorCode, messageArgs);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "ExceptionCreator could not instantiate " + target.getName(), e);
        }
    }

    public void throwIt() {
        throw build();
    }

    private static Class<? extends AbstractException> resolveMapping(ErrorCode code) {
        if (!(code instanceof Enum<?> enumConstant)) {
            return null;
        }
        try {
            Field field = code.getClass().getField(enumConstant.name());
            MappedTo mappedTo = field.getAnnotation(MappedTo.class);
            return mappedTo == null ? null : mappedTo.value();
        } catch (NoSuchFieldException unused) {
            return null;
        }
    }
}
