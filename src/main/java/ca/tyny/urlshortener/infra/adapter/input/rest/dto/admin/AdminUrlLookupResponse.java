package ca.tyny.urlshortener.infra.adapter.input.rest.dto.admin;

import ca.tyny.urlshortener.infra.adapter.input.rest.dto.ShortUrlResponse;

/**
 * Admin lookup of a short URL by its code: the standard link view plus ownership metadata.
 *
 * <p>{@code ownerEmail} is {@code null} when the owner document no longer exists at lookup time
 * (documented as nullable in the OpenAPI contract); {@code ownerUserId} is the link's owning user.
 */
public record AdminUrlLookupResponse(
    ShortUrlResponse item, String ownerUserId, String ownerEmail) {}
