package ca.tyny.urlshortener.infra.adapter.input.rest.dto.admin;

import java.util.List;

/** Cursor-paginated user list for {@code GET /api/v1/admin/users}. */
public record AdminUserListResponse(
    List<AdminUserItem> items, String nextCursor, boolean hasMore) {}
