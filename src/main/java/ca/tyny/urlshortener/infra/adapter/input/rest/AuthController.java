package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimitScope;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.core.service.UserService;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.AuthResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.LoginRequest;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.MeResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.RefreshTokenRequest;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.RegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "API for user registration and login")
public class AuthController {

  private static final String ACCESS_COOKIE = "access_token";
  private static final String REFRESH_COOKIE = "refresh_token";
  private static final String REFRESH_COOKIE_PATH = "/api/v1/auth/refresh";
  private final UserService userService;
  private final RateLimiterPort rateLimiter;
  private final MetricsPort metricsPort;
  private final HttpServletRequest request;
  private final ClientAddressResolver clientAddressResolver;

  @Value("${app.jwt.expiration-ms:86400000}")
  private long jwtExpirationMs;

  @Value("${app.jwt.refresh-expiration-ms:604800000}")
  private long jwtRefreshExpirationMs;

  public AuthController(
      UserService userService,
      RateLimiterPort rateLimiter,
      MetricsPort metricsPort,
      HttpServletRequest request,
      ClientAddressResolver clientAddressResolver) {
    this.userService = userService;
    this.rateLimiter = rateLimiter;
    this.metricsPort = metricsPort;
    this.request = request;
    this.clientAddressResolver = clientAddressResolver;
  }

  @PostMapping("/register")
  @Operation(
      summary = "Register a new user",
      description =
          "Creates a new user account and returns a JWT token. On success also sets two HttpOnly "
              + "cookies carrying the same JWTs: access_token (Path=/, Max-Age=access TTL) and "
              + "refresh_token (Path=/api/v1/auth/refresh, Max-Age=refresh TTL). Both are Secure, "
              + "SameSite=Lax, HttpOnly. Cache-Control: no-store.")
  public ResponseEntity<AuthResponse> register(
      @Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
    UserService.AuthResult result =
        userService.register(request.getEmail(), request.getName(), request.getPassword());
    setAuthCookies(response, result.token(), result.refreshToken());
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(toAuthResponse(result));
  }

  @PostMapping("/login")
  @Operation(
      summary = "Login",
      description =
          "Authenticates a user and returns a JWT token. On success also sets two HttpOnly cookies "
              + "carrying the same JWTs: access_token (Path=/, Max-Age=access TTL) and "
              + "refresh_token (Path=/api/v1/auth/refresh, Max-Age=refresh TTL). Both are Secure, "
              + "SameSite=Lax, HttpOnly. Cache-Control: no-store.")
  public ResponseEntity<AuthResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    RateLimitVerdict verdict = rateLimiter.tryAcquire(RateLimitScope.AUTH, resolveClientIp());
    if (!verdict.allowed()) {
      return tooManyRequests(verdict);
    }
    UserService.AuthResult result = userService.login(request.getEmail(), request.getPassword());
    setAuthCookies(response, result.token(), result.refreshToken());
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(toAuthResponse(result));
  }

  @PostMapping("/refresh")
  @Operation(
      summary = "Refresh Token",
      description =
          "Refreshes the access token using a valid refresh token. The refresh token is read from "
              + "the request body (refreshToken field) when present, otherwise from the "
              + "refresh_token cookie — never both. A request with neither a body token nor the "
              + "cookie returns 401. On success sets a new access_token cookie and re-sets the "
              + "refresh_token cookie with the same value (sliding Max-Age). "
              + "Cache-Control: no-store.")
  public ResponseEntity<AuthResponse> refresh(
      @RequestBody(required = false) RefreshTokenRequest request,
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshCookie,
      HttpServletResponse response) {
    RateLimitVerdict verdict = rateLimiter.tryAcquire(RateLimitScope.AUTH, resolveClientIp());
    if (!verdict.allowed()) {
      return tooManyRequests(verdict);
    }
    String refreshToken = null;
    if (request != null && StringUtils.hasText(request.getRefreshToken())) {
      refreshToken = request.getRefreshToken();
    } else if (StringUtils.hasText(refreshCookie)) {
      refreshToken = refreshCookie;
    }
    if (refreshToken == null) {
      return ResponseEntity.status(401).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
    UserService.AuthResult result = userService.refreshToken(refreshToken);
    setAuthCookies(response, result.token(), result.refreshToken());
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(toAuthResponse(result));
  }

  @GetMapping("/me")
  @Operation(
      summary = "Get current user identity",
      description =
          "Returns the identity {userId, email, role, name} of the authenticated user. The role is "
              + "resolved from the configured admin email list (ADR 0011 D1), mirroring the role "
              + "claim: ADMIN when the email is listed, USER otherwise. Authentication via the "
              + "Authorization Bearer header or the access_token cookie. "
              + "Cache-Control: no-store.")
  public ResponseEntity<MeResponse> me(Authentication authentication) {
    UserService.AuthResult result = userService.me(authentication.getName());
    MeResponse meResponse =
        new MeResponse(result.userId(), result.email(), result.role(), result.name());
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(meResponse);
  }

  @PostMapping("/logout")
  @Operation(
      summary = "Logout",
      description =
          "Clears both auth cookies (access_token and refresh_token) with their exact paths. "
              + "Idempotent — works with no cookies present and requires no authentication. "
              + "Returns 204 with no body. Cache-Control: no-store.")
  public ResponseEntity<Void> logout(HttpServletResponse response) {
    clearAuthCookies(response);
    return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
  }

  private void setAuthCookies(
      HttpServletResponse response, String accessToken, String refreshToken) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(ACCESS_COOKIE, accessToken)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(jwtExpirationMs / 1000)
            .build()
            .toString());
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(REFRESH_COOKIE, refreshToken)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path(REFRESH_COOKIE_PATH)
            .maxAge(jwtRefreshExpirationMs / 1000)
            .build()
            .toString());
  }

  private void clearAuthCookies(HttpServletResponse response) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(ACCESS_COOKIE, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(0)
            .build()
            .toString());
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from(REFRESH_COOKIE, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path(REFRESH_COOKIE_PATH)
            .maxAge(0)
            .build()
            .toString());
  }

  private String resolveClientIp() {
    return clientAddressResolver.resolve(request);
  }

  /**
   * 429 with standard throttling headers (Retry-After + RateLimit-*). Single 429 egress: records
   * the frozen meter {@code rate.limit.exceeded.total} (numerator of the {@code
   * RateLimitExcessiveTrafficRejected} alert) exactly once per rejected request.
   */
  private <T> ResponseEntity<T> tooManyRequests(RateLimitVerdict verdict) {
    metricsPort.recordRateLimitExceeded();
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", Long.toString(verdict.resetSeconds()))
        .header("RateLimit-Limit", "*")
        .header("RateLimit-Remaining", "0")
        .header("RateLimit-Reset", Long.toString(verdict.resetSeconds()))
        .build();
  }

  private AuthResponse toAuthResponse(UserService.AuthResult result) {
    return AuthResponse.builder()
        .token(result.token())
        .refreshToken(result.refreshToken())
        .userId(result.userId())
        .email(result.email())
        .role(result.role())
        .name(result.name())
        .build();
  }
}
