package com.example.urlshortener.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/**
 * Composite annotation to activate the {@link TestSecurityConfig} in test
 * classes.
 * This disables security for @WebMvcTest slices, allowing tests to run without
 * authentication concerns.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(TestSecurityConfig.class)
public @interface WithMockSecurity {
    // No attributes needed – the presence of the annotation triggers the import.
}
