package ca.tyny.urlshortener.infra.adapter.input.rest.dto.auth;

/**
 * Identity response for {@code GET /api/v1/auth/me}.
 *
 * <p>The body is exactly the three identity fields — no tokens, no secrets (Rule 6). The same trio
 * is already present on {@link AuthResponse}; this record is the minimal authenticated identity
 * shape.
 */
public record MeResponse(String userId, String email, String name) {}
