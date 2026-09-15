/**
 * # Component: Auth
 *
 * ## Purpose
 * Registration, login, token refresh and identity for API users: credential hashing, JWT
 * issue/refresh via {@code TokenPort}, an additive HttpOnly-cookie transport (same-origin SPA,
 * ADR 0010) carrying the same JWTs, and the Spring Security wiring (JWT filter, user details
 * service, password encoder adapter) that turns a valid token into an authenticated request.
 *
 * ## Requirements (EARS)
 *
 * ### REQ-AUTH-001
 * **When** a new user registers with a unique email and valid credentials,
 * **the Business Component shall** persist the user (hashed password) and issue a
 * token + refresh-token pair; registering an email that already exists is rejected
 * without persisting anything.
 *
 * ### REQ-AUTH-002
 * **When** a user logs in with valid credentials,
 * **the Business Component shall** authenticate against the stored user and issue a
 * token + refresh-token pair.
 *
 * ### REQ-AUTH-003
 * **When** a client presents a valid refresh token,
 * **the Business Component shall** issue a new access token (keeping the refresh
 * token); an invalid refresh token is rejected.
 *
 * ### REQ-AUTH-004
 * **When** a registration request carries malformed input (blank name, invalid email, weak
 * password),
 * **the Business Component shall** reject it with HTTP 400 before the use case runs.
 *
 * ### REQ-AUTH-005
 * **When** a request carries a valid JWT only in the {@code access_token} HttpOnly cookie (no
 * Authorization header) and targets a protected endpoint,
 * **the Business Component shall** authenticate the request as that user exactly as it would
 * with the Bearer header (cookie-only request authenticates).
 *
 * ### REQ-AUTH-006
 * **When** a request carries both an Authorization Bearer header and an {@code access_token}
 * cookie,
 * **the Business Component shall** authenticate using the Bearer token and ignore the cookie.
 *
 * ### REQ-AUTH-007
 * **When** an authenticated client calls {@code GET /api/v1/auth/me},
 * **the Business Component shall** return the identity fields {@code userId}/{@code email}/{@code
 * name} of the authenticated principal; an unauthenticated call is rejected with HTTP 401.
 *
 * ### REQ-AUTH-008
 * **When** a client calls {@code POST /api/v1/auth/logout},
 * **the Business Component shall** clear both the {@code access_token} and {@code refresh_token}
 * cookies (exact paths, Max-Age 0) and return HTTP 204 with no body, idempotently and without
 * requiring authentication.
 *
 * ### REQ-AUTH-009
 * **When** a client refreshes without a body refresh token but presents a valid
 * {@code refresh_token} cookie,
 * **the Business Component shall** issue a new access token, re-set the {@code access_token}
 * cookie with it and re-set the {@code refresh_token} cookie with the same value (sliding
 * Max-Age); a request with neither a body token nor the cookie is rejected with HTTP 401.
 *
 * ### REQ-AUTH-010
 * **When** the calling IP exceeds the configured AUTH rate limit ({@code rate-limiter.auth-limit}
 * / {@code rate-limiter.auth-window}) on {@code POST /api/v1/auth/login} or {@code POST
 * /api/v1/auth/refresh},
 * **the Business Component shall** reject the request with HTTP 429 and the standard throttling
 * headers  ({@code Retry-After}, {@code RateLimit-*}) before the use case runs.
 *
 * ### REQ-AUTH-011
 * **When** a user belongs to the configured admin email list ({@code app.admin-emails} /
 * {@code APP_ADMIN_EMAILS}),
 * **the Business Component shall** issue an access token carrying the {@code role} claim
 * {@code ADMIN} and resolve the identity role as {@code ADMIN}; a user outside the list resolves
 * to {@code USER}, and a token issued without the claim is authenticated with the authority
 * {@code ROLE_USER}.
 *
 * ### REQ-AUTH-012
 * **When** an authenticated client calls {@code GET /api/v1/auth/me} or the authentication
 * endpoints succeed ({@code POST /api/v1/auth/register|login|refresh}),
 * **the Business Component shall** return the resolved role ({@code ADMIN} or {@code USER}) in the
 * response body ({@code MeResponse.role} / {@code AuthResponse.role}).
 *
 * ## Ports (Contracts)
 * - Outbound: {@link ca.tyny.urlshortener.core.ports.outgoing.TokenPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.PasswordEncoderPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.AuthenticationPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort} (AUTH scope),
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.AdminEmailPort}
 * - Inbound (REST): `POST /api/v1/auth/register|login|refresh|logout` and
 *   `GET /api/v1/auth/me` (AuthController)
 *
 * ## Local Decisions (ADR inline)
 * - JWT access + refresh tokens via `JwtTokenProvider`; the default/weak secret is warned on
 *   (Rule 6 — secrets never logged).
 * - The ADMIN role is bootstrapped from the environment (ADR 0011): `app.admin-emails` list, never
 *   a DB role field; the role claim lives only in the access token, and a token without the claim
 *   (legacy) is authenticated as `ROLE_USER` by the JWT filter.
 * - Passwords hashed through `PasswordEncoderPort` (BCrypt adapter) — never stored or logged raw.
 * - Registration input validated at the DTO boundary (bean validation) so malformed requests
 *   never reach the use case.
 * - Cookie transport is additive (ADR 0010): same JWTs in HttpOnly cookies; Bearer stays the
 *   first-class interface permanently; SameSite=Lax is the CSRF control (csrf.disable() with
 *   zero CORS in src/main).
 * - Login and refresh are rate-limited per IP via the shared {@code AUTH} scope (10/min default) —
 *   brute-force on login and token harvesting on refresh are bounded before the use case runs;
 *   register/logout/me are not limited (email uniqueness/quota and idempotency already bound
 *   abuse on those paths).
 *
 * @spec-complete true
 */
package ca.tyny.urlshortener.infra.security;
