package ca.tyny.urlshortener.infra.adapter.input.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.tyny.urlshortener.config.WithMockSecurity;
import ca.tyny.urlshortener.core.annotation.TracesRequirement;
import ca.tyny.urlshortener.core.exception.ForbiddenException;
import ca.tyny.urlshortener.core.model.RateLimitVerdict;
import ca.tyny.urlshortener.core.ports.outgoing.MetricsPort;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimitScope;
import ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort;
import ca.tyny.urlshortener.core.service.UserService;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.LoginRequest;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.RefreshTokenRequest;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth.RegisterRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(AuthController.class)
@WithMockSecurity
@DisplayName("AuthController Tests")
class AuthControllerTest {

  private static final String ACCESS_COOKIE = "access_token";
  private static final String REFRESH_COOKIE = "refresh_token";

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ca.tyny.urlshortener.infra.security.JwtTokenProvider jwtTokenProvider;

  @MockitoBean private UserService userService;

  @MockitoBean private RateLimiterPort rateLimiter;

  @MockitoBean private MetricsPort metricsPort;

  @MockitoBean private ClientAddressResolver clientAddressResolver;

  @BeforeEach
  void setUp() {
    when(clientAddressResolver.resolve(any())).thenReturn("127.0.0.1");
    when(rateLimiter.tryAcquire(RateLimitScope.AUTH, "127.0.0.1"))
        .thenReturn(RateLimitVerdict.allow(10));
  }

  @Test
  @TracesRequirement("REQ-AUTH-001")
  @DisplayName("Should register user successfully and set cookies")
  void shouldRegisterUser() throws Exception {
    // Given
    RegisterRequest request = new RegisterRequest("Test User", "test@example.com", "password123");
    UserService.AuthResult result =
        new UserService.AuthResult(
            "token", "refresh-token", "id", "test@example.com", "USER", "Test User");

    when(userService.register(eq("test@example.com"), eq("Test User"), eq("password123")))
        .thenReturn(result);

    // When/Then
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value("token"))
        .andExpect(jsonPath("$.email").value("test@example.com"))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(cookie().httpOnly(ACCESS_COOKIE, true))
        .andExpect(cookie().secure(ACCESS_COOKIE, true))
        .andExpect(cookie().maxAge(ACCESS_COOKIE, 86400));
  }

  @Test
  @TracesRequirement("REQ-AUTH-002")
  @DisplayName("Should login user successfully and set both cookies")
  void shouldLoginUser() throws Exception {
    // Given
    LoginRequest request = new LoginRequest("test@example.com", "password123");
    UserService.AuthResult result =
        new UserService.AuthResult(
            "token", "refresh-token", "id", "test@example.com", "USER", "Test User");

    when(userService.login(eq("test@example.com"), eq("password123"))).thenReturn(result);

    // When/Then
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value("token"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(cookie().httpOnly(ACCESS_COOKIE, true))
        .andExpect(cookie().secure(ACCESS_COOKIE, true))
        .andExpect(cookie().path(ACCESS_COOKIE, "/"))
        .andExpect(cookie().maxAge(ACCESS_COOKIE, 86400))
        .andExpect(cookie().httpOnly(REFRESH_COOKIE, true))
        .andExpect(cookie().secure(REFRESH_COOKIE, true))
        .andExpect(cookie().path(REFRESH_COOKIE, "/api/v1/auth/refresh"))
        .andExpect(cookie().maxAge(REFRESH_COOKIE, 604800));
  }

  @Test
  @DisplayName("Should return 401 when credentials are invalid")
  void shouldReturn401OnInvalidCredentials() throws Exception {
    // Given - wrong password: AuthenticationPort.authenticate (inside UserService.login) throws
    LoginRequest request = new LoginRequest("test@example.com", "wrong-password");
    when(userService.login(eq("test@example.com"), eq("wrong-password")))
        .thenThrow(new BadCredentialsException("Bad credentials"));

    // When/Then - mapped by GlobalExceptionHandler, NOT the catch-all 500
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("Unauthorized"))
        .andExpect(jsonPath("$.message").value("Invalid credentials"));
  }

  @Test
  @DisplayName("Should return 403 when the account is blocked (valid credentials)")
  void shouldReturn403OnBlockedAccount() throws Exception {
    // Given - valid credentials, blocked account (ADR 0011 D3: 401 vs 403 ordering)
    LoginRequest request = new LoginRequest("test@example.com", "password123");
    when(userService.login(eq("test@example.com"), eq("password123")))
        .thenThrow(new ForbiddenException("Account blocked."));

    // When/Then
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Forbidden"))
        .andExpect(jsonPath("$.message").value("Account blocked."));
  }

  @Test
  @TracesRequirement("REQ-AUTH-004")
  @DisplayName("Should validate register request")
  void shouldValidateRegisterRequest() throws Exception {
    // Given - Invalid request (empty fields)
    RegisterRequest request = new RegisterRequest("", "invalid-email", "123");

    // When/Then
    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @TracesRequirement("REQ-AUTH-003")
  @DisplayName("Should refresh token successfully (body token)")
  void shouldRefreshToken() throws Exception {
    // Given
    RefreshTokenRequest request = new RefreshTokenRequest("valid-refresh-token");
    UserService.AuthResult result =
        new UserService.AuthResult(
            "new-token", "valid-refresh-token", "id", "test@example.com", "USER", "Test User");

    when(userService.refreshToken(eq("valid-refresh-token"))).thenReturn(result);

    // When/Then
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value("new-token"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(cookie().value(ACCESS_COOKIE, "new-token"))
        .andExpect(cookie().value(REFRESH_COOKIE, "valid-refresh-token"));
  }

  @Test
  @TracesRequirement("REQ-AUTH-009")
  @DisplayName("Should refresh token from cookie when no body token")
  void shouldRefreshTokenFromCookie() throws Exception {
    // Given
    UserService.AuthResult result =
        new UserService.AuthResult(
            "new-token", "refresh-cookie-value", "id", "test@example.com", "USER", "Test User");

    when(userService.refreshToken(eq("refresh-cookie-value"))).thenReturn(result);

    // When/Then - no body at all, only the cookie
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .cookie(new Cookie(REFRESH_COOKIE, "refresh-cookie-value"))
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").value("new-token"))
        .andExpect(cookie().value(ACCESS_COOKIE, "new-token"))
        .andExpect(cookie().value(REFRESH_COOKIE, "refresh-cookie-value"))
        .andExpect(cookie().path(REFRESH_COOKIE, "/api/v1/auth/refresh"));
  }

  @Test
  @TracesRequirement("REQ-AUTH-009")
  @DisplayName("Should return 401 when refresh has neither body token nor cookie")
  void shouldRejectRefreshWithoutTokenOrCookie() throws Exception {
    // When/Then - empty body, no cookie
    mockMvc
        .perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  @TracesRequirement("REQ-AUTH-008")
  @DisplayName("Should clear both cookies on logout")
  void shouldClearCookiesOnLogout() throws Exception {
    // When/Then
    mockMvc
        .perform(post("/api/v1/auth/logout").cookie(new Cookie(ACCESS_COOKIE, "x")))
        .andExpect(status().isNoContent())
        .andExpect(cookie().maxAge(ACCESS_COOKIE, 0))
        .andExpect(cookie().path(ACCESS_COOKIE, "/"))
        .andExpect(cookie().maxAge(REFRESH_COOKIE, 0))
        .andExpect(cookie().path(REFRESH_COOKIE, "/api/v1/auth/refresh"));
  }

  @Test
  @TracesRequirement("REQ-AUTH-008")
  @DisplayName("Should logout idempotently without any cookies (204)")
  void shouldLogoutWithoutCookies() throws Exception {
    mockMvc
        .perform(post("/api/v1/auth/logout"))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  @TracesRequirement("REQ-AUTH-007")
  @DisplayName("Should return current user identity on /me")
  void shouldReturnMe() throws Exception {
    // Given
    UserService.AuthResult result =
        new UserService.AuthResult(null, null, "id", "test@example.com", "USER", "Test User");
    when(userService.me(eq("test@example.com"))).thenReturn(result);

    // When/Then
    mockMvc
        .perform(get("/api/v1/auth/me").with(user("test@example.com")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value("id"))
        .andExpect(jsonPath("$.email").value("test@example.com"))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.name").value("Test User"))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  @TracesRequirement("REQ-AUTH-010")
  @DisplayName("Should reject login with 429 + throttling headers when AUTH rate limit exceeded")
  void shouldRejectLoginWhenRateLimited() throws Exception {
    // Given
    LoginRequest request = new LoginRequest("test@example.com", "password123");
    when(rateLimiter.tryAcquire(RateLimitScope.AUTH, "127.0.0.1"))
        .thenReturn(RateLimitVerdict.block(42));

    // When/Then - the use case must never run for a throttled caller
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "42"))
        .andExpect(header().string("RateLimit-Limit", "*"))
        .andExpect(header().string("RateLimit-Remaining", "0"))
        .andExpect(header().string("RateLimit-Reset", "42"));

    verify(metricsPort).recordRateLimitExceeded();
    verify(userService, never()).login(any(), any());
  }

  @Test
  @TracesRequirement("REQ-AUTH-010")
  @DisplayName("Should reject refresh with 429 when AUTH rate limit exceeded")
  void shouldRejectRefreshWhenRateLimited() throws Exception {
    // Given
    RefreshTokenRequest request = new RefreshTokenRequest("valid-refresh-token");
    when(rateLimiter.tryAcquire(RateLimitScope.AUTH, "127.0.0.1"))
        .thenReturn(RateLimitVerdict.block(7));

    // When/Then
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "7"))
        .andExpect(header().string("RateLimit-Limit", "*"))
        .andExpect(header().string("RateLimit-Remaining", "0"))
        .andExpect(header().string("RateLimit-Reset", "7"));

    verify(metricsPort).recordRateLimitExceeded();
    verify(userService, never()).refreshToken(any());
  }
}
