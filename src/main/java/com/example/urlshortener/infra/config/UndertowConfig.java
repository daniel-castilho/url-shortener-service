package com.example.urlshortener.infra.config;

import io.undertow.UndertowOptions;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UndertowConfig implements WebServerFactoryCustomizer<UndertowServletWebServerFactory> {

    @Override
    public void customize(UndertowServletWebServerFactory factory) {
        factory.addBuilderCustomizers(builder -> {
            builder.setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, true);
            // Other tuning options can be added here if needed,
            // though many are handled via application.yml
        });
    }
}
