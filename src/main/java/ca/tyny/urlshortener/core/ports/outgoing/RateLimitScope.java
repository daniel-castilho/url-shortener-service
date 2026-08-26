package ca.tyny.urlshortener.core.ports.outgoing;

/**
 * Identifies which operation is being rate limited. Scopes are structurally
 * distinct buckets: exhausting one never affects the other.
 */
public enum RateLimitScope {
    /** POST /api/v1/urls — shortening requests. */
    SHORTEN,
    /** GET /{id} — the hot redirect path (anti-enumeration control). */
    REDIRECT
}
