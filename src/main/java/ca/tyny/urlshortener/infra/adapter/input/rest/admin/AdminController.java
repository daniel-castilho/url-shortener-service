package ca.tyny.urlshortener.infra.adapter.input.rest.admin;

import ca.tyny.urlshortener.core.model.AdminUrlLookup;
import ca.tyny.urlshortener.core.model.Cursor;
import ca.tyny.urlshortener.core.model.PageRequest;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.model.UserAdminItem;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminBlockUserUseCase;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminListUserUrlsUseCase;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminListUsersUseCase;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminLookupUrlUseCase;
import ca.tyny.urlshortener.core.ports.incoming.admin.AdminUnblockUserUseCase;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.LinkListResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortUrlResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.admin.AdminUrlLookupResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.admin.AdminUserItem;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.admin.AdminUserListResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.mapper.LinkMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product administration data-plane surface ({@code /api/v1/admin/**}).
 *
 * <p>Thin front controller: extracts the caller identity (email + role) from the authenticated
 * principal and delegates to the admin use cases in {@code core/service}. The use cases enforce the
 * ADMIN role at the application layer (403 for non-admins); anonymous callers are rejected by the
 * security chain (401) via the explicit {@code /api/v1/admin/**} matcher.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Product administration (ADMIN role only)")
public class AdminController {

  private final AdminListUsersUseCase listUsersUseCase;
  private final AdminBlockUserUseCase blockUserUseCase;
  private final AdminUnblockUserUseCase unblockUserUseCase;
  private final AdminListUserUrlsUseCase listUserUrlsUseCase;
  private final AdminLookupUrlUseCase lookupUrlUseCase;
  private final LinkMapper linkMapper;

  public AdminController(
      AdminListUsersUseCase listUsersUseCase,
      AdminBlockUserUseCase blockUserUseCase,
      AdminUnblockUserUseCase unblockUserUseCase,
      AdminListUserUrlsUseCase listUserUrlsUseCase,
      AdminLookupUrlUseCase lookupUrlUseCase,
      LinkMapper linkMapper) {
    this.listUsersUseCase = listUsersUseCase;
    this.blockUserUseCase = blockUserUseCase;
    this.unblockUserUseCase = unblockUserUseCase;
    this.listUserUrlsUseCase = listUserUrlsUseCase;
    this.lookupUrlUseCase = lookupUrlUseCase;
    this.linkMapper = linkMapper;
  }

  @GetMapping("/users")
  @Operation(
      summary = "List users",
      description =
          "Cursor-paginated list of users (newest first), optionally filtered by an email prefix "
              + "via `q`. role on each item is the live env-list truth and blocked mirrors the "
              + "account flag.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Paginated user list"),
        @ApiResponse(responseCode = "400", description = "Malformed cursor"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Forbidden (not an ADMIN)")
      })
  public ResponseEntity<AdminUserListResponse> listUsers(
      @Parameter(description = "Optional email prefix filter", example = "joa")
          @RequestParam(required = false)
          String q,
      @Parameter(description = "Page size (max 100)", example = "20")
          @RequestParam(defaultValue = "20")
          int limit,
      @Parameter(description = "Opaque cursor for pagination") @RequestParam(required = false)
          String cursor,
      Authentication authentication) {

    PageRequest request =
        PageRequest.of(
            Math.min(limit, PageRequest.MAX_LIMIT), cursor != null ? new Cursor(cursor) : null);
    PageResult<UserAdminItem> page =
        listUsersUseCase.list(callerEmail(authentication), callerRole(authentication), q, request);

    AdminUserListResponse response =
        new AdminUserListResponse(
            page.items().stream().map(this::toItem).toList(),
            page.nextCursor() != null ? page.nextCursor().value() : null,
            page.hasMore());
    return ResponseEntity.ok(response);
  }

  @PostMapping("/users/{userId}/block")
  @Operation(
      summary = "Block a user account",
      description =
          "Writes blocked=true (expand-only). Idempotent (blocking a blocked user answers the same "
              + "204). Blocked users lose login and refresh (403 \"Account blocked.\"). "
              + "Self-block is rejected with 400.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "User blocked"),
        @ApiResponse(responseCode = "400", description = "Self-block is not allowed"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Forbidden (not an ADMIN)"),
        @ApiResponse(responseCode = "404", description = "User not found")
      })
  public ResponseEntity<Void> blockUser(
      @Parameter(description = "User id to block", required = true) @PathVariable String userId,
      Authentication authentication) {
    blockUserUseCase.block(callerEmail(authentication), callerRole(authentication), userId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/users/{userId}/unblock")
  @Operation(
      summary = "Unblock a user account",
      description =
          "Clears the blocked flag (expand-only). Idempotent — unblocking an unblocked user answers "
              + "the same 204 and restores login/refresh immediately.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "204", description = "User unblocked"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Forbidden (not an ADMIN)"),
        @ApiResponse(responseCode = "404", description = "User not found")
      })
  public ResponseEntity<Void> unblockUser(
      @Parameter(description = "User id to unblock", required = true) @PathVariable String userId,
      Authentication authentication) {
    unblockUserUseCase.unblock(callerEmail(authentication), callerRole(authentication), userId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/users/{userId}/urls")
  @Operation(
      summary = "List a user's links",
      description =
          "Cursor-paginated list of the given user's short links, newest first — the owner list "
              + "contract, including archived links (deletedAt visible). Read-only.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Paginated list of the user's links"),
        @ApiResponse(responseCode = "400", description = "Malformed cursor"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Forbidden (not an ADMIN)"),
        @ApiResponse(responseCode = "404", description = "User not found")
      })
  public ResponseEntity<LinkListResponse> listUserUrls(
      @Parameter(description = "User id whose links are inspected", required = true) @PathVariable
          String userId,
      @Parameter(description = "Page size (max 100)", example = "20")
          @RequestParam(defaultValue = "20")
          int limit,
      @Parameter(description = "Opaque cursor for pagination") @RequestParam(required = false)
          String cursor,
      Authentication authentication) {

    PageRequest request =
        PageRequest.of(
            Math.min(limit, PageRequest.MAX_LIMIT), cursor != null ? new Cursor(cursor) : null);
    PageResult<ShortUrl> page =
        listUserUrlsUseCase.listUserUrls(callerRole(authentication), userId, request);

    LinkListResponse response =
        new LinkListResponse(
            linkMapper.toResponseList(page.items(), BASE_URL),
            page.nextCursor() != null ? page.nextCursor().value() : null,
            page.hasMore());
    return ResponseEntity.ok(response);
  }

  @GetMapping("/urls")
  @Operation(
      summary = "Look up a short URL by code",
      description =
          "Resolves any short code globally (any owner, including archived). The code is the "
              + "document id. Returns the standard link view plus ownerUserId and ownerEmail "
              + "(ownerEmail is null when the owner document no longer exists). Read-only.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Resolved short URL with ownership"),
        @ApiResponse(responseCode = "400", description = "Missing code parameter"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Forbidden (not an ADMIN)"),
        @ApiResponse(responseCode = "404", description = "Short URL not found")
      })
  public ResponseEntity<AdminUrlLookupResponse> lookupUrl(
      @Parameter(description = "Short URL code (document id)", required = true, example = "vE1GpYK")
          @RequestParam
          String code,
      Authentication authentication) {

    AdminUrlLookup lookup = lookupUrlUseCase.lookup(callerRole(authentication), code);
    ShortUrlResponse item = linkMapper.toResponse(lookup.shortUrl(), BASE_URL);
    return ResponseEntity.ok(new AdminUrlLookupResponse(item, item.userId(), lookup.ownerEmail()));
  }

  private static final String BASE_URL = "http://localhost"; // mirrors LinkController.getBaseUrl()

  private String callerEmail(Authentication authentication) {
    return authentication.getName();
  }

  private String callerRole(Authentication authentication) {
    boolean admin =
        authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    return admin ? "ADMIN" : "USER";
  }

  private AdminUserItem toItem(UserAdminItem item) {
    return new AdminUserItem(
        item.userId(), item.email(), item.name(), item.role(), item.blocked(), item.createdAt());
  }
}
