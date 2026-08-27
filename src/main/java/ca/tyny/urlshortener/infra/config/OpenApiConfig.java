package ca.tyny.urlshortener.infra.config;

import ca.tyny.urlshortener.infra.config.properties.SecurityProperties;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * OpenAPI/Swagger configuration — only enabled when {@code app.security.swagger.enabled=true}.
 */
@Configuration
@Conditional(OpenApiConfig.SwaggerEnabledCondition.class)
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI(@Value("${spring.application.name:URL Shortener}") String appName) {
        return new OpenAPI()
                .info(new Info()
                        .title(appName + " API")
                        .version("v1")
                        .description("High-performance URL Shortener API with rate limiting, analytics, and SSRF protection.")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")));
    }

    /**
     * Condition to enable Swagger only when {@code app.security.swagger.enabled=true}.
     */
    static class SwaggerEnabledCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            return env.getProperty("app.security.swagger.enabled", Boolean.class, false);
        }
    }
}