# Links as Resource (Phase B) — Backlog

**Priority:** P1 — first product jump toward a "Bitly-like". Second epic of this phase.
**All stories:** Must.
**Companions:** `links-as-resource-spec.md` · `links-as-resource-implementation-sequence.md`

**Execution status:** ready from `main` (`7e13ae8`, `v0.11.0`).

---

## Epic outcome

A short URL is a **resource**: the authenticated user can list their links (cursor-paginated), read a
link's details + summary stats, **`PATCH` the destination without changing the code**, and **archive**
(soft-delete) a link. All read/mutate routes are owner-guarded. The redirect stays fast and independent,
and an archived link stops resolving (404) while history is kept.

---

## Story map

```text
MODEL & PERSISTENCE
L1  Metadata fields; deletedAt soft-archive on ShortUrl + entity + mapper
L2  Migration V7: (userId, createdAt) index; repository findByUserId/update/archive
L3  Domain PageRequest/PageResult + cursor pagination

USE CASES
L4  ListUserLinksUseCase
L5  GetLinkUseCase (owner-guarded)
L6  UpdateLinkUseCase (owner-guarded; PATCH semantics; immutable if archived)
L7  ArchiveLinkUseCase (owner-guarded; idempotent)

API
L8  GET /api/v1/urls (list) + GET /api/v1/urls/{id} (detail + stats)
L9  PATCH /api/v1/urls/{id} + DELETE /api/v1/urls/{id}
L10 SecurityConfig: /api/v1/urls/** authenticated; owner guard at application level

REDIRECT & 301/302
L11 Archived link -> 404 in redirect; keep 302 while destination is mutable

VERIFY
V1  Ownership + PATCH/archive + list ITs; docs sync
```

---

## L1 — Metadata fields + deletedAt

**Goal:** links carry immersive metadata and a soft-archive flag.

### Work

- add `title`, `tags` (List<String>), `utmSource`, `utmMedium`, `utmCampaign`, `utmTerm`, `utmContent`,
  `deletedAt` (Instant, nullable) to `ShortUrl` and `ShortUrlEntity`;
- update `ShortUrlMapper` to round-trip; keep existing constructors as overloads.

### Acceptance

- [ ] Fields optional/additive; existing links unaffected.
- [ ] Mapper round-trips all new fields.

---

## L2 — Repository + migration V7

**Goal:** the port supports listing by user, update, and archive.

### Work

- add `findByUserId`, `update(ShortUrl)` (upsert by `_id`), `archive(id)`/`delete` to `UrlRepositoryPort`
  (or a focused `LinkQueryPort`/`LinkMutationPort`);
- implement in `MongoUrlRepository`;
- add migration V7: non-unique index `(userId, createdAt)`.

### Acceptance

- [ ] List/update/archive work against real Mongo (IT).
- [ ] `(userId, createdAt)` index created (V7), idempotent.

---

## L3 — Domain pagination (cursor)

**Goal:** stable, cursor-based pagination, framework-free.

### Work

- add pure `PageRequest(limit, cursor)` and `PageResult<T>(items, nextCursor, hasMore)` in `core/model`;
- the list use case translates the cursor into a query (createdAt desc + cursor via last createdAt/`_id`).

### Acceptance

- [ ] Cursor pagination is stable (no drift with inserts); `limit` capped.
- [ ] `core/` stays framework-free.

---

## L4 — ListUserLinksUseCase

**Goal:** list the caller's links, newest-first.

### Work

- implement `list(userId, limit, cursor)`; only own links.

### Acceptance

- [ ] Only the authenticated user's links are returned.
- [ ] `limit` capped; cursor advances; `hasMore` correct; no leaked rows.

---

## L5 — GetLinkUseCase (owner-guarded)

**Goal:** read a link's details + summary stats.

### Work

- implement `get(userId, id)`; returns `ShortUrl` with `clickCount`, `expiresAt`, metadata.

### Acceptance

- [ ] Owner reads their link; non-owner → 403; unknown → 404.

---

## L6 — UpdateLinkUseCase (owner-guarded, PATCH semantics)

**Goal:** edit destination/metadata **without changing the code**.

### Work

- implement `update(userId, id, UpdateLinkCommand)`; partial update (only supplied fields change);
- reject if the link is archived (`deletedAt` set) — immutable.

### Acceptance

- [ ] `PATCH` changes `originalUrl`/title/tags/utm/expiresAt **without** changing `id`;
- [ ] non-owner → 403; unknown → 404; archived → 409/400; invalid URL → 400;
- [ ] the redirect still resolves the same `id` to the new destination.

---

## L7 — ArchiveLinkUseCase (owner-guarded, idempotent)

**Goal:** soft-delete; redirect stops resolving, history kept.

### Work

- implement `archive(userId, id)`; sets `deletedAt`; idempotent.

### Acceptance

- [ ] Owner archives; non-owner → 403; unknown → 404; repeated → 204 (idempotent);
- [ ] the redirect returns 404 for an archived link.

---

## L8 — GET /api/v1/urls + GET /api/v1/urls/{id}

**Goal:** expose the list + detail endpoints.

### Work

- add controller endpoints (authenticated); map to `ShortUrlResponse` (DTO with metadata + `shortUrl`).

### Acceptance

- [ ] List paginated; detail returns stats + metadata; both require auth.

---

## L9 — PATCH /api/v1/urls/{id} + DELETE /api/v1/urls/{id}

**Goal:** expose edit + archive.

### Work

- add controller endpoints; `PATCH` uses `UpdateLinkCommand`; `DELETE` archives; owner-guarded at the
  application layer (not just route). OpenAPI annotations added.

### Acceptance

- [ ] `PATCH` changes destination without changing code; `DELETE` archives; owner-guarded (403 for non-owner).

---

## L10 — SecurityConfig + ownership

**Goal:** new routes authenticated; owner guard enforced.

### Work

- add `/api/v1/urls/**` → `authenticated()` in `SecurityConfig` (keep `GET /{id}` permitAll, `POST
  /api/v1/urls` public);
- enforce owner guard in the use cases (userId from auth context).

### Acceptance

- [ ] Unauthenticated → 401 on the new routes; owner guard tested (non-owner → 403).

---

## L11 — Redirect: archived → 404; keep 302

**Goal:** redirect is fast/independent; archived links don't resolve.

### Work

- in the redirect read path, treat `deletedAt` present as 404 (alongside expiry);
- keep `302` (mutable destination). Document the `301`/`302` rule.

### Acceptance

- [ ] Redirect ignores archived links (404); still `302` for valid, non-expired, non-archived links;
- [ ] redirect path is not coupled to the new list/patch features.

---

## V1 — Tests + docs sync

**Goal:** prove ownership + PATCH/archive/list, and sync docs.

### Work

- IT: list (own only, cursor); get (owner/non-owner); patch (destination unchanged code, partial,
  archived-immutable); delete (archive, idempotent, redirect 404);
- `docs/data-model-decisions.md`, `docs/coding-standards.md`, `docs/testing-playbook.md`, `README.md`,
  `AGENTS.md` synced; OpenAPI updated.

### Acceptance

- [ ] Ownership + CRUD ITs green; non-owner → 403 everywhere;
- [ ] `mvn test`, `mvn verify`, `check-boundaries.sh` pass;
- [ ] docs and OpenAPI reflect the new routes; "redirect stays fast + 302/301 rule" documented.

---

## Epic Definition of Done

- [ ] L1–L3 complete: metadata + `deletedAt`; migration V7; cursor pagination (`core` framework-free).
- [ ] L4–L7 complete: list/get/update/archive use cases; owner-guarded; update does not change code.
- [ ] L8–L9 complete: list + detail + PATCH + DELETE endpoints (OpenAPI added).
- [ ] L10 complete: new routes authenticated; owner guard at application level (not just route).
- [ ] L11 complete: archived → 404 in redirect; `302` kept while mutable; redirect stays fast/independent.
- [ ] V1 complete: ownership + CRUD ITs green; docs + AGENTS synced.
- [ ] No framework type in `core/`; the redirect hot path is not coupled to the new features.
