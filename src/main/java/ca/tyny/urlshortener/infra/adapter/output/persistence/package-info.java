/**
 * # Component: Persistence
 *
 * ## Purpose
 * MongoDB adapters behind the outgoing ports (`UrlRepositoryPort`, `LinkQueryPort`,
 * `LinkMutationPort`, `UserRepositoryPort`, `LinkCustomDomainRegistryPort`): entities, mappers,
 * atomic counter updates, owner-scoped cursor pagination, soft-delete archiving, and the
 * versioned, checksummed, fail-fast `MongoSchemaMigrator` that owns the schema at boot.
 *
 * ## Requirements (EARS)
 *
 * ### REQ-PERSIST-001
 * **When** the application boots against a database whose schema is outdated,
 * **the Business Component shall** apply all pending migrations in version order and record
 * them (version + checksum) in `schema_migrations` — on a fresh database all migrations apply
 * in order, and repeated application is idempotent (already-applied versions with matching
 * checksums are skipped).
 *
 * ### REQ-PERSIST-002
 * **When** an already-applied migration's checksum has drifted, or two migrations claim the same
 * version, or a migration throws,
 * **the Business Component shall** fail fast at boot (re-applying idempotent migrations on drift,
 * rejecting duplicates, aborting startup on a throwing migration) — a partially migrated
 * database never serves traffic.
 *
 * ### REQ-PERSIST-003
 * **When** migrations are added within a release,
 * **the Business Component shall** keep them expand-only (additive collections/indexes/fields;
 * no destructive drops/renames) — destructive changes ship in an earlier release than the code
 * that stops using the old shape, because blue/green cutover runs the old colour against the new
 * schema.
 *
 * ### REQ-PERSIST-004
 * **When** a short link's click count is incremented,
 * **the Business Component shall** use a server-side atomic `$inc` (missing code = no-op) so
 * concurrent increments are never lost.
 *
 * ### REQ-PERSIST-005
 * **When** a user is persisted,
 * **the Business Component shall** enforce email uniqueness at the storage level (unique index)
 * so concurrent registrations cannot create duplicate identities.
 *
 * ### REQ-PERSIST-006
 * **When** a user lists their links,
 * **the Business Component shall** return only that owner's links, cursor-paginated (opaque
 * cursor, createdAt/id DESC, capped page size) with malformed cursors rejected as errors.
 *
 * ### REQ-PERSIST-007
 * **When** a link is archived,
 * **the Business Component shall** set `deletedAt` (soft delete, queryable and idempotent)
 * rather than remove the document.
 *
 * ### REQ-PERSIST-008
 * **When** a link is updated,
 * **the Business Component shall** persist the supplied field changes while keeping the
 * document's identity (id) stable.
 *
 * ## Ports (Contracts)
 * - Outbound: {@link ca.tyny.urlshortener.core.ports.outgoing.UrlRepositoryPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.LinkQueryPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.LinkMutationPort},
 *   {@link ca.tyny.urlshortener.core.ports.outgoing.UserRepositoryPort}
 *
 * ## Local Decisions (ADR inline)
 * - Migrations are in-code (versioned classes V1..V9), checksummed, recorded in
 *   `schema_migrations`; a Flyway-for-MongoDB attempt was rejected (JDBC driver not on Central;
 *   native connectors CLI-only — human-approved alternative).
 * - Soft delete via `deletedAt`; TTL indexes (V5) evict expired documents opportunistically —
 *   application-level expiry is the source of truth (eager 410 check).
 * - Entities/mappers live here; domain models never leak Mongo types upward (RepositoryException
 *   wrapping, Rule 1).
 *
 * @spec-complete true
 */
package ca.tyny.urlshortener.infra.adapter.output.persistence;
