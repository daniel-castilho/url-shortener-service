/**
 * # Component: UrlShortener
 *
 * ## Purpose
 * The core use cases: shortening (auto-generated Base62 codes and vanity aliases with quota),
 * redirect resolution with host-bound custom domains and eager expiry, bounded collision retry,
 * and link management (list/get/update/archive, owner-scoped). This is the domain heart behind
 * {@code ShortenUrlUseCase} and {@code GetUrlUseCase}.
 *
 * ## Requirements (EARS)
 *
 * ### REQ-SHORT-001
 * **When** a client shortens a URL without a custom alias,
 * **the Business Component shall** generate a cryptographically random Base62 code of the
 * configured length and retry bounded times on an id collision, rejecting with an error only
 * when retries are exhausted.
 *
 * ### REQ-SHORT-002
 * **When** a client shortens a URL with a custom alias,
 * **the Business Component shall** validate the alias (format, reserved words, quota, plan
 * length rules) and persist it atomically so concurrent creators cannot both win; validation
 * failures reject without persisting, and quota usage increments atomically.
 *
 * ### REQ-SHORT-003
 * **When** a client shortens a URL,
 * **the Business Component shall** validate the destination (HTTPS, host known, no SSRF
 * targets resolving to private/internal/link-local IPs) before persisting, and reject
 * destinations on unbound hosts.
 *
 * ### REQ-SHORT-004
 * **When** a redirect request arrives,
 * **the Business Component shall** resolve the code cache-aside (single source hit on miss,
 * eager expiry check — an expired link is 410 even if a cached value exists) and serve the
 * bound host's link only when the request host matches the link's domain binding.
 *
 * ### REQ-SHORT-005
 * **When** a client manages their links (list/get/update/archive),
 * **the Business Component shall** enforce ownership (403 for foreign links, 404 for unknown),
 * update only supplied fields (destination never the code, domain set/cleared/kept per
 * supplied flags with ownership + verification checks), and archive idempotently.
 *
 * ### REQ-SHORT-006
 * **When** a user claims a custom domain,
 * **the Business Component shall** create a pending claim with a verification token (rejecting
 * duplicates, the default host, malformed hosts and IP literals), list only owned domains, and
 * allow delete/re-verification only by the owner.
 *
 * ### REQ-SHORT-007
 * **When** the redirect path resolves a link for a custom domain,
 * **the Business Component shall** normalize the Host header (IPv6 brackets, port stripping)
 * before matching domain bindings, falling back to the default host only when no Host is
 * present.
 *
 * ## Ports (Contracts)
 * - Inbound: {@link ca.tyny.urlshortener.core.ports.incoming.ShortenUrlUseCase},
 *   {@link ca.tyny.urlshortener.core.ports.incoming.GetUrlUseCase}
 * - Outbound: {@link ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.UrlCachePort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.IdGeneratorPort}
 *
 * ## Local Decisions (ADR inline)
 * - ID model (locked, Rule 2): SecureRandom Base62, length 7 default, bounded collision retry —
 *   no Hashids, no Redis counter, no sequential IDs.
 * - No URL deduplication (Rule 3): the same long URL may yield distinct codes; vanity aliases
 *   and generated codes keep structurally disjoint shapes (Rule 4, length + charset).
 * - Expiry is eager at read time (410) — TTL indexes are opportunistic cleanup only.
 * - SSRF/destination validation per Rule 6 with the reputation-check extension hook.
 *
 * @spec-complete true
 */
package ca.tyny.urlshortener.core.service;
