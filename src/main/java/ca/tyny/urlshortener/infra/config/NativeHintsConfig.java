package ca.tyny.urlshortener.infra.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration
@ImportRuntimeHints(NativeHintsConfig.NettyHints.class)
public class NativeHintsConfig {

    static class NettyHints implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
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
