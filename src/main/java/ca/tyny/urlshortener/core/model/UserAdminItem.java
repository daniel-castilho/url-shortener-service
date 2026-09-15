package ca.tyny.urlshortener.core.model;

import java.time.LocalDateTime;

/**
 * Admin view of a user for the product administration surface.
 *
 * <p>The views {@code userId}, {@code email}, {@code name}, {@code blocked} and {@code createdAt}
 * (data from the user record) plus {@code role} — the live truth resolved from the configured admin
 * email list (ADR 0011 D1), not the possibly-stale token claim.
 */
public record UserAdminItem(
    String userId,
    String email,
    String name,
    String role,
    boolean blocked,
    LocalDateTime createdAt) {}
