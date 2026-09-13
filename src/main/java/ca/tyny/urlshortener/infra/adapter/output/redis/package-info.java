/**
 * # Component: RateLimiting
 *
 * ## Purpose
 * Enforces per-IP token-bucket rate limits on the redirect and shorten hot paths
 * to prevent enumeration and abuse. Operates as a Redis-backed adapter implementing
 * the {@link ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort} contract.
 *
 * ## Requirements (EARS)
 *
 * ### REQ-RATE-001
 * **When** a client exceeds the configured request rate for the REDIRECT scope,
 * **the Business Component shall** reject the request with HTTP 429, include a
 * {@code Retry-After} header (seconds until bucket refill), and include
 * {@code RateLimit-Limit}, {@code RateLimit-Remaining}, {@code RateLimit-Reset} headers.
 *
 * ### REQ-RATE-002
 * **When** rate limit checks are performed,
 * **the Business Component shall** maintain strictly separate token buckets for the
 * {@code SHORTEN} and {@code REDIRECT} scopes so that exhaustion of one scope
 * never affects the other.
 *
 * ### REQ-RATE-003
 * **When** the {@code X-Forwarded-For} header is present,
 * **the Business Component shall** trust it only when the request originates from
 * a CIDR listed in {@code rate-limiter.trusted-proxy-cidrs}; otherwise the
 * immediate peer IP is used for bucket identification.
 *
 * ### REQ-RATE-004
 * **When** Redis is unavailable or returns a malformed reply,
 * **the Business Component shall** allow the request (fail-open) rather than
 * block legitimate traffic.
 *
 * ### REQ-RATE-005
 * **When** rate limiting is disabled by configuration,
 * **the Business Component shall** allow every request without contacting Redis.
 *
 * ## Ports (Contracts)
 * - Outbound: {@link ca.tyny.urlshortener.core.ports.outgoing.RateLimiterPort}
 *
 * ## Local Decisions (ADR inline)
 * - Algorithm: Redis token bucket implemented via atomic Lua script (single round-trip, no race).
 * - Storage: Redis sorted set (score = timestamp) with TTL = window + buffer; keys prefixed {@code rl:}.
 * - Scopes: {@code SHORTEN} (default 60/min) and {@code REDIRECT} (default 120/min) are independent keys.
 * - Trusted proxies: Configured via {@code rate-limiter.trusted-proxy-cidrs} (default {@code 127.0.0.0/8, ::1/128});
 *   empty list = no trusted proxies (strict peer IP only).
 * - Fail-open policy: ADR 0005 (requirement REQ-RATE-004); buckets are not enforced during Redis outage.
 *
 * @spec-complete true
 *
 * # Component: Cache
 *
 * ## Purpose
 * Read-through L1 (Caffeine) + L2 (Redis) cache for short-code lookups on the redirect hot path,
 * guarded by a Bloom filter against cache penetration. Implements
 * {@link ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort} with a shape-versioned key
 * prefix so serialized-shape changes never serve stale old-shape entries.
 *
 * ## Requirements (EARS)
 *
 * ### REQ-CACHE-001
 * **When** the serialized shape of a cached URL value changes,
 * **the Business Component shall** include a shape version in the cache key prefix
 * ({@code url:v<n>:<id>}) and bump that version with every shape change, so entries written
 * under a previous shape are never read by new readers (they miss and rebuild from the source).
 *
 * ### REQ-CACHE-002
 * **When** an id has never been cached (Bloom filter negative),
 * **the Business Component shall** return a {@code BLOOM_NEGATIVE} absence without touching
 * Redis, so cache-penetration probing of unknown codes costs one Bloom check instead of an
 * L2 round-trip.
 *
 * ### REQ-CACHE-003
 * **When** a Bloom-positive id is looked up,
 * **the Business Component shall** serve from the L1 local cache on hit, else from the L2
 * Redis entry under the versioned key (re-populating L1), else return a plain {@code MISS}
 * for the caller to resolve from the source.
 *
 * ### REQ-CACHE-004
 * **When** a link is updated or archived,
 * **the Business Component shall** evict the id from L2 and invalidate L1 so the next lookup
 * observes the change.
 *
 * ### REQ-CACHE-005
 * **When** a link is put into the cache,
 * **the Business Component shall** set the L2 TTL to base TTL (24h) plus jitter for
 * never-expiring links, capped at the link's remaining lifetime for expiring links, and skip
 * caching entirely for already-expired links.
 *
 * ## Ports (Contracts)
 * - Outbound: {@link ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort}
 *
 * ## Local Decisions (ADR inline)
 * - Key shape: {@code url:v1:<id>} (was {@code url:<id>}); version bump is the migration —
 *   stale entries expire by TTL, no scan/delete needed.
 * - Serialization: JSON with compact field names ({@code u} = original URL, {@code e} = expiry
 *   epoch second, {@code d} = bound domain).
 * - L1 is Caffeine ({@code app.cache.l1-*}); the Bloom filter guards L2 against penetration
 *   (ADR 0003); Redis/L2 failure degrades to a miss per ADR 0005 — the cache never blocks
 *   the redirect path.
 *
 * @spec-complete true
 */
package ca.tyny.urlshortener.infra.adapter.output.redis;