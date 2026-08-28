package ca.tyny.urlshortener.core.command;

import ca.tyny.urlshortener.core.model.UtmParams;
import java.time.Instant;
import java.util.List;

/**
 * Command for partially updating a short link.
 * All fields are optional; only supplied fields are updated.
 */
public record UpdateLinkCommand(
        String originalUrl,
        String title,
        List<String> tags,
        UtmParams utm,
        Instant expiresAt
) {}