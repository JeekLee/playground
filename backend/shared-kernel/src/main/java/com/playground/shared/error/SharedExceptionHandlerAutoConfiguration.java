package com.playground.shared.error;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Auto-configures {@link SharedExceptionHandler} on any module that has Spring
 * Web on the classpath (i.e. every {@code *-api}). BCs override by declaring
 * their own {@code @RestControllerAdvice} bean — {@link ConditionalOnMissingBean}
 * stands this one down.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RestControllerAdvice.class)
public class SharedExceptionHandlerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SharedExceptionHandler.class)
    public SharedExceptionHandler sharedExceptionHandler() {
        return new SharedExceptionHandler();
    }
}
