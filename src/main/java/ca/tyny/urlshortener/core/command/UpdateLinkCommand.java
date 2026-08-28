package ca.tyny.urlshortener.core.command;

import ca.tyny.urlshortener.core.model.UtmParams;
import java.time.Instant;
import java.util.List;

/**
 * Command for partially updating a short link.
 * All fields are optional; only supplied fields are updated.
 * {@code utmSupplied}/{@code expiresAtSupplied} distinguish "supplied with null (clear)"
 * from "not supplied (keep)" for those group/single-value fields.
 */
public record UpdateLinkCommand(
        String originalUrl,
        String title,
        List<String> tags,
        UtmParams utm,
        boolean utmSupplied,
        Instant expiresAt,
        boolean expiresAtSupplied
) {}