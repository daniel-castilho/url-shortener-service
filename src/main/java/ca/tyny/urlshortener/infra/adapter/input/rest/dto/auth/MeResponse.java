package ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth;

/**
 * Identity response for {@code GET /api/v1/auth/me}.
 *
 * <p>The body is exactly the identity fields and the resolved role — no tokens, no secrets (Rule
 * 6). The role mirrors the access-token claim (ADR 0011 D1); the same fields are present on {@link
 * AuthResponse}; this record is the minimal authenticated identity shape.
 */
public record MeResponse(String userId, String email, String role, String name) {}
