package ca.tyny.urlshortener.infra.config;

import ca.tyny.urlshortener.infra.config.properties.SecurityProperties;
import ca.tyny.urlshortener.infra.security.BasicOperatorAuthFilter;
import ca.tyny.urlshortener.infra.security.CustomUserDetailsService;
import ca.tyny.urlshortener.infra.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/** Security configuration for the URL Shortener application. */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthenticationFilter jwtAuthFilter;
  private final CustomUserDetailsService userDetailsService;
  private final SecurityProperties securityProperties;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    SecurityProperties.Swagger swagger = securityProperties.swagger();

    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            headers ->
                headers
                    .contentTypeOptions(Customizer.withDefaults())
                    .frameOptions(frameOptions -> frameOptions.deny())
                    .httpStrictTransportSecurity(
                        hsts ->
                            hsts.maxAgeInSeconds(31536000).includeSubDomains(true).preload(true))
                    .referrerPolicy(
                        referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .xssProtection(Customizer.withDefaults())
                    .contentSecurityPolicy(
                        csp ->
                            csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'"))
                    .xssProtection(Customizer.withDefaults()))
        .authenticationProvider(authenticationProvider())
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        // Operator BasicAuth (debt 26): plain instantiation, NOT a bean — see
        // BasicOperatorAuthFilter javadoc (double-registration avoidance).
        .addFilterBefore(
            new BasicOperatorAuthFilter(securityProperties),
            UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                    (request, response, authException) ->
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
        .authorizeHttpRequests(
            auth ->
                auth
                    // Public Endpoints (order matters: a matcher decides the FIRST rule that fits)
                    // /api/v1/auth/me MUST be matched BEFORE the auth/** permitAll below or it
                    // inherits permitAll (matcher ordering trap, ADR 0010).
                    .requestMatchers(HttpMethod.GET, "/api/v1/auth/me")
                    .authenticated() // Get current user identity (Bearer or cookie)
                    .requestMatchers("/api/v1/auth/**")
                    .permitAll() // Login, Register, Refresh (POST /logout is idempotent-anonymous)

                    // The bare /actuator index is guarded BEFORE the GET /{id} (redirect)
                    // permitAll below, which otherwise matches any single-segment path such
                    // as "/actuator" itself. "actuator" is a reserved word, so no vanity
                    // alias can ever collide with it.
                    .requestMatchers("/actuator")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/{id}")
                    .permitAll() // Redirect
                    // HEAD mirrors GET per HTTP semantics (and ops tooling such as curl -Is
                    // depends on it); Spring forwards HEAD to the GET handler.
                    .requestMatchers(HttpMethod.HEAD, "/{id}")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/urls")
                    .permitAll() // Create Short URL (Anonymous allowed)

                    // Links as Resource — authenticated (owner guard at application layer)
                    .requestMatchers("/api/v1/urls/**")
                    .authenticated()

                    // Custom Domains — authenticated (owner guard at application layer)
                    .requestMatchers("/api/v1/domains/**")
                    .authenticated()

                    // Actuator - tiered access (debt 26: ROLE_OPERATOR is the infrastructure
                    // operator over BasicAuth, env-injected via OPERATOR_USERNAME/PASSWORD;
                    // ADMIN (JWT) retains everything; METRICS_VIEWER keeps metrics/Prometheus)
                    .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness")
                    .permitAll()
                    .requestMatchers("/actuator/info")
                    .permitAll()
                    .requestMatchers("/actuator/health")
                    .hasAnyRole("ADMIN", "OPERATOR")
                    .requestMatchers("/actuator/metrics/**", "/actuator/prometheus")
                    .hasAnyRole("ADMIN", "METRICS_VIEWER", "OPERATOR")
                    .requestMatchers("/actuator/circuitbreakers/**")
                    .hasAnyRole("ADMIN", "OPERATOR")
                    .requestMatchers("/actuator/**")
                    .hasRole("ADMIN")

                    // Swagger - conditional
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .access(swaggerAccessManager(securityProperties.swagger()))

                    // Default
                    .anyRequest()
                    .authenticated());

    return http.build();
  }

  /** Returns an AuthorizationManager that permits all if Swagger is enabled, denies otherwise. */
  private AuthorizationManager<RequestAuthorizationContext> swaggerAccessManager(
      SecurityProperties.Swagger swagger) {
    return (authentication, context) -> {
      if (swagger.enabled()) {
        return new AuthorizationDecision(true);
      }
      return new AuthorizationDecision(false);
    };
  }

  @Bean
  public AuthenticationProvider authenticationProvider() {
    // Spring Security 7: DaoAuthenticationProvider takes the UserDetailsService in the
    // constructor (the no-arg constructor + setUserDetailsService were removed).
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
    authProvider.setPasswordEncoder(passwordEncoder());
    return authProvider;
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
      throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
