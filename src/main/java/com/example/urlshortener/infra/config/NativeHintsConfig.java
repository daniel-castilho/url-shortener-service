package com.example.urlshortener.infra.config;

import org.hashids.Hashids;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration
@ImportRuntimeHints(NativeHintsConfig.HashidsHints.class)
public class NativeHintsConfig {

    static class HashidsHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            // Garante que o construtor e métodos da Hashids sobrevivam à compilação nativa
            hints.reflection().registerType(Hashids.class,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);

            // Register Netty UnsafeAccess for reflection (required by MongoDB driver and async libraries)
            try {
                Class<?> unsafeAccessClass = classLoader.loadClass(
                        "io.netty.util.internal.shaded.org.jctools.util.UnsafeAccess");
                hints.reflection().registerType(unsafeAccessClass,
                        MemberCategory.DECLARED_FIELDS,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            } catch (ClassNotFoundException e) {
                // Class not in classpath, ignore
            }
        }
    }
}
