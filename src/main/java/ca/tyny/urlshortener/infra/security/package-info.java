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
 * ## Ports (Contracts)
 * - Outbound: {@link ca.tyny.urlshortener.core.ports.outgoing.TokenPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.PasswordEncoderPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.AuthenticationPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort}
 * - Inbound (REST): `POST /api/v1/auth/register|login|refresh|logout` and
 *   `GET /api/v1/auth/me` (AuthController)
 *
 * ## Local Decisions (ADR inline)
 * - JWT access + refresh tokens via `JwtTokenProvider`; the default/weak secret is warned on
 *   (Rule 6 — secrets never logged).
 * - Passwords hashed through `PasswordEncoderPort` (BCrypt adapter) — never stored or logged raw.
 * - Registration input validated at the DTO boundary (bean validation) so malformed requests
 *   never reach the use case.
 * - Cookie transport is additive (ADR 0010): same JWTs in HttpOnly cookies; Bearer stays the
 *   first-class interface permanently; SameSite=Lax is the CSRF control (csrf.disable() with
 *   zero CORS in src/main).
 *
 * @spec-complete true
 */
package ca.tyny.urlshortener.infra.security;
