package ca.tyny.urlshortener.infra.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Set;

/**
 * Typed configuration for security hardening (prefix {@code app.security}).
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        @DefaultValue("true") boolean actuatorEnabled,
        Actuator actuator,
        Swagger swagger
) {

    public record Actuator(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("false") boolean healthDetailEnabled,
            @DefaultValue({"health/liveness", "health/readiness", "info"}) Set<String> publicEndpoints
    ) {}

    public record Swagger(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") List<String> allowedIPs
    ) {}
}