package ca.tyny.urlshortener.infra.adapter.input.rest.dto.admin;

import java.time.LocalDateTime;

/**
 * Admin view of a user in {@code GET /api/v1/admin/users} responses.
 *
 * <p>{@code role} is the live truth from the configured admin email list (ADR 0011), not the
 * possibly-stale token claim; {@code blocked} mirrors the account flag.
 */
public record AdminUserItem(
    String userId,
    String email,
    String name,
    String role,
    boolean blocked,
    LocalDateTime createdAt) {}
