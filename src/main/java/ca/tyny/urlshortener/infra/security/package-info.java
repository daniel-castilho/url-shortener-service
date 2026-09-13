/**
 * # Component: Auth
 *
 * ## Purpose
 * Registration, login and token refresh for API users: credential hashing, JWT issue/refresh
 * via {@code TokenPort}, and the Spring Security wiring (JWT filter, user details service,
 * password encoder adapter) that turns a valid token into an authenticated request.
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
 * ## Ports (Contracts)
 * - Outbound: {@link ca.tyny.urlshortener.core.ports.outgoing.TokenPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.PasswordEncoderPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.AuthenticationPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort}
 * - Inbound (REST): `POST /api/v1/auth/register|login|refresh` (AuthController)
 *
 * ## Local Decisions (ADR inline)
 * - JWT access + refresh tokens via `JwtTokenProvider`; the default/weak secret is warned on
 *   (Rule 6 — secrets never logged).
 * - Passwords hashed through `PasswordEncoderPort` (BCrypt adapter) — never stored or logged raw.
 * - Registration input validated at the DTO boundary (bean validation) so malformed requests
 *   never reach the use case.
 *
 * @spec-complete true
 */
package ca.tyny.urlshortener.infra.security;
