package ca.tyny.urlshortener.infra.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** OpenAPI/Swagger configuration — only enabled when {@code app.security.swagger.enabled=true}. */
@Configuration
@Conditional(OpenApiConfig.SwaggerEnabledCondition.class)
public class OpenApiConfig {

  static final String BEARER_SCHEME = "bearerAuth";
  static final String COOKIE_SCHEME = "cookieAuth";

  @Bean
  public OpenAPI openAPI(@Value("${spring.application.name:URL Shortener}") String appName) {
    return new OpenAPI()
        .info(
            new Info()
                .title(appName + " API")
                .version("v1")
                .description(
                    "High-performance URL Shortener API with rate limiting, analytics, and SSRF "
                        + "protection.\n\n"
                        + "Authentication (ADR 0010): either a JWT `Authorization: Bearer` header "
                        + "or the `access_token` cookie is accepted; Bearer wins when both are "
                        + "present. Cookie-mode clients MUST be same-origin (no CORS). See "
                        + "`docs/backend-frontend-contract.md` §3.1 and §6 for the full contract. "
                        + "Endpoints are annotated individually with the required scheme; schema "
                        + "definitions live here for client generation.")
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "JWT access token. Optional when the access_token cookie is present."))
                .addSecuritySchemes(
                    COOKIE_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name("access_token")
                        .description(
                            "HttpOnly JWT cookie set by register/login/refresh. Same-origin only.")));
  }

  /** Condition to enable Swagger only when {@code app.security.swagger.enabled=true}. */
  static class SwaggerEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      var env = context.getEnvironment();
      return env.getProperty("app.security.swagger.enabled", Boolean.class, false);
    }
  }
}
