package ca.tyny.urlshortener.infra.adapter.input.rest;

import ca.tyny.urlshortener.core.model.Cursor;
import ca.tyny.urlshortener.core.model.PageRequest;
import ca.tyny.urlshortener.core.model.PageResult;
import ca.tyny.urlshortener.core.model.ShortUrl;
import ca.tyny.urlshortener.core.model.ClicksSeries;
import ca.tyny.urlshortener.core.model.AnalyticsUnit;
import ca.tyny.urlshortener.core.ports.incoming.ArchiveLinkUseCase;
import ca.tyny.urlshortener.core.ports.incoming.GetClickAnalyticsUseCase;
import ca.tyny.urlshortener.core.ports.incoming.GetLinkUseCase;
import ca.tyny.urlshortener.core.ports.incoming.ListUserLinksUseCase;
import ca.tyny.urlshortener.core.ports.incoming.UpdateLinkUseCase;
import ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort;
import ca.tyny.urlshortener.core.model.User;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ClickAnalyticsResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.LinkListResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortUrlResponse;
import ca.tyny.urlshortener.infra.adapter.input.rest.dto.UpdateLinkRequest;
import ca.tyny.urlshortener.infra.adapter.input.rest.mapper.LinkMapper;
import ca.tyny.urlshortener.infra.config.properties.ShortenerProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/urls")
@Tag(name = "Links", description = "Manage short links (authenticated, owner-scoped)")
public class LinkController {

    private final ListUserLinksUseCase listUserLinksUseCase;
    private final GetLinkUseCase getLinkUseCase;
    private final GetClickAnalyticsUseCase getClickAnalyticsUseCase;
    private final UpdateLinkUseCase updateLinkUseCase;
    private final ArchiveLinkUseCase archiveLinkUseCase;
    private final LinkMapper linkMapper;
    private final UserRepositoryPort userRepository;
    private final ShortenerProperties shortenerProperties;

    public LinkController(ListUserLinksUseCase listUserLinksUseCase,
                          GetLinkUseCase getLinkUseCase,
                          GetClickAnalyticsUseCase getClickAnalyticsUseCase,
                          UpdateLinkUseCase updateLinkUseCase,
                          ArchiveLinkUseCase archiveLinkUseCase,
                          LinkMapper linkMapper,
                          UserRepositoryPort userRepository,
                          ShortenerProperties shortenerProperties) {
        this.listUserLinksUseCase = listUserLinksUseCase;
        this.getLinkUseCase = getLinkUseCase;
        this.getClickAnalyticsUseCase = getClickAnalyticsUseCase;
        this.updateLinkUseCase = updateLinkUseCase;
        this.archiveLinkUseCase = archiveLinkUseCase;
        this.linkMapper = linkMapper;
        this.userRepository = userRepository;
        this.shortenerProperties = shortenerProperties;
    }

    @GetMapping
    @Operation(summary = "List authenticated user's links", description = "Returns a cursor-paginated list of the caller's short links, newest first.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Paginated list of links"),
            @ApiResponse(responseCode = "400", description = "Malformed cursor"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    public ResponseEntity<LinkListResponse> list(
            @Parameter(description = "Page size (max 100)", example = "20") @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "Opaque cursor for pagination", example = "eyJjcmVhdGVkQXQiOjE3MDAwMDAwMDAwMDB9") @RequestParam(required = false) String cursor) {

        String userId = getCurrentUserId();
        String baseUrl = getBaseUrl();

        PageRequest request = PageRequest.of(Math.min(limit, PageRequest.MAX_LIMIT), cursor != null ? new Cursor(cursor) : null);
        PageResult<ShortUrl> page = listUserLinksUseCase.list(userId, request);

        LinkListResponse response = new LinkListResponse(
                linkMapper.toResponseList(page.items(), baseUrl),
                page.nextCursor() != null ? page.nextCursor().value() : null,
                page.hasMore());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get link details", description = "Returns the link details with metadata and summary stats. Owner only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Link details"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "403", description = "Not the owner"),
            @ApiResponse(responseCode = "404", description = "Link not found")
    })
    public ResponseEntity<ShortUrlResponse> get(
            @Parameter(description = "Short URL code", required = true, example = "abc123") @PathVariable String id) {

        String userId = getCurrentUserId();
        String baseUrl = getBaseUrl();

        ShortUrl link = getLinkUseCase.get(userId, id);
        return ResponseEntity.ok(linkMapper.toResponse(link, baseUrl));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a link", description = "Partially updates a link's destination, title, tags, UTM params, or expiresAt. Only supplied fields change. Owner only. Archived links are immutable.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Link updated"),
            @ApiResponse(responseCode = "400", description = "Invalid input (e.g., malformed URL, too many tags)"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "403", description = "Not the owner"),
            @ApiResponse(responseCode = "404", description = "Link not found"),
            @ApiResponse(responseCode = "409", description = "Link is archived (immutable)")
    })
    public ResponseEntity<ShortUrlResponse> update(
            @Parameter(description = "Short URL code", required = true, example = "abc123") @PathVariable String id,
            @Valid @RequestBody UpdateLinkRequest request) {

        String userId = getCurrentUserId();
        String baseUrl = getBaseUrl();

        ca.tyny.urlshortener.core.command.UpdateLinkCommand command = toCommand(request);
        ShortUrl updated = updateLinkUseCase.update(userId, id, command);
        return ResponseEntity.ok(linkMapper.toResponse(updated, baseUrl));
    }

    @GetMapping("/{id}/clicks")
    @Operation(summary = "Get link click analytics", description = "Returns the click time series (unit=day from the daily rollup, or a bounded hourly series) plus a device/geo/referrer breakdown. Owner only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Click time series + breakdown"),
            @ApiResponse(responseCode = "400", description = "Invalid unit or range"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "403", description = "Not the owner"),
            @ApiResponse(responseCode = "404", description = "Link not found")
    })
    public ResponseEntity<ClickAnalyticsResponse> getClicks(
            @Parameter(description = "Short URL code", required = true, example = "abc123") @PathVariable String id,
            @Parameter(description = "Granularity: day (rollup) or hour (bounded raw series, max 30 days)", example = "day") @RequestParam(defaultValue = "day") String unit,
            @Parameter(description = "Range start (UTC date, yyyy-MM-dd); defaults to 29 days before 'to'") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Range end (UTC date, yyyy-MM-dd); defaults to today") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        String userId = getCurrentUserId();
        ClicksSeries series = getClickAnalyticsUseCase.get(
                userId, id, AnalyticsUnit.fromParam(unit), from, to);
        return ResponseEntity.ok(toResponse(series));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Archive a link", description = "Soft-deletes the link (sets deletedAt). The redirect will return 404. Idempotent. Owner only.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Link archived"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "403", description = "Not the owner"),
            @ApiResponse(responseCode = "404", description = "Link not found")
    })
    public ResponseEntity<Void> archive(
            @Parameter(description = "Short URL code", required = true, example = "abc123") @PathVariable String id) {

        String userId = getCurrentUserId();
        archiveLinkUseCase.archive(userId, id);
        return ResponseEntity.noContent().build();
    }

    private String getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("Unauthenticated");
        }
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .map(User::id)
                .orElseThrow(() -> new IllegalStateException("User not found: " + email));
    }

    private String getBaseUrl() {
        return "http://localhost"; // In production, use ServletUriComponentsBuilder.fromCurrentContextPath()
    }

    private ca.tyny.urlshortener.core.command.UpdateLinkCommand toCommand(UpdateLinkRequest req) {
        ca.tyny.urlshortener.core.model.UtmParams utm = null;
        if (req.isFieldSupplied("utm") && req.getUtm() != null) {
            UpdateLinkRequest.UtmParamsRequest u = req.getUtm();
            utm = new ca.tyny.urlshortener.core.model.UtmParams(
                    u.getSource(),
                    u.getMedium(),
                    u.getCampaign(),
                    u.getTerm(),
                    u.getContent());
        }

        Instant expiresAt = null;
        if (req.isFieldSupplied("expiresAt")) {
            expiresAt = req.getExpiresAt(); // null = clear, present = set
        }

        return new ca.tyny.urlshortener.core.command.UpdateLinkCommand(
                req.isFieldSupplied("originalUrl") ? req.getOriginalUrl() : null,
                req.isFieldSupplied("title") ? req.getTitle() : null,
                req.isFieldSupplied("tags") ? req.getTags() : null,
                utm,
                req.isFieldSupplied("utm"),
                expiresAt,
                req.isFieldSupplied("expiresAt"),
                req.isFieldSupplied("domain") ? req.getDomain() : null,
                req.isFieldSupplied("domain"));
    }

    private ClickAnalyticsResponse toResponse(ClicksSeries series) {
        return new ClickAnalyticsResponse(
                series.shortCode(),
                series.unit(),
                series.from(),
                series.to(),
                series.totalClicks(),
                series.series().stream()
                        .map(b -> new ClickAnalyticsResponse.ClickSeriesPoint(b.time(), b.clicks()))
                        .toList(),
                series.breakdown(),
                series.uniquePerBucket());
    }
}