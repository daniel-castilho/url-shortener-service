# AI Software Engineer Prompt — Links as Resource (Phase B)

**Status:** ready for implementation from current `main` (`7e13ae8`, `v0.11.0`).
**Priority:** P1 — first product jump toward a "Bitly-like". Second epic of this phase.
**Target:** turn the short URL into a **resource** — list, read, **edit the destination without changing
the code**, and **archive** a link, all owner-guarded — while the redirect stays fast and independent.

You implement the complete **Links as Resource** epic. The API today is only `POST /api/v1/urls` and
`GET /{id}`; there is no way to list, read details, edit the destination, or delete a link. This epic adds
those, plus persisted metadata, and is the base for branded domain + analytics (Phases C/D).

---

## CLARIFICATIONS — RESOLVED (locked; do not re-ask)

These were verified against the code at `main` and are binding:

1. **Repository port split (CORRECT THE PREMISE).** `UrlRepositoryPort` does **NOT** have
   `findByEmail`/`save(User)`/`incrementVanityUsage` — those are on **`UserRepositoryPort`**
   (a different port for the `User` aggregate). `UrlRepositoryPort` has only four methods:
   `save(ShortUrl)`, `findById`, `existsById`, `incrementClickCount`. So the "add to existing port"
   question refers only to the URL aggregate's new operations.
   **Recommendation: Option B — create focused `LinkQueryPort` and `LinkMutationPort`** (the house
   coding-standards explicitly endorses ISP/Reader-Writer splitting for NEW ports and flags the wide
   `*Repository` as known debt). Define them so the existing `MongoUrlRepository` can implement all three
   interfaces (they are satisfied by one adapter):
   - `LinkQueryPort`: `List<ShortUrl> findByUserId(String userId, int limit, Cursor cursor)` and
     `Optional<ShortUrl> findById(String id)`.
   - `LinkMutationPort`: `void save(ShortUrl)`, `void update(ShortUrl)` (upsert by `_id`),
     `void archive(String id)`, `void incrementClickCount(String id, long delta)`.
   - Keep `UrlRepositoryPort` (used by the shortener/redirect write path) **unchanged** so the redirect
     path never depends on the new query/mutation surface. (Option A — widening `UrlRepositoryPort` —
     is acceptable if you prefer less churn, but the split is the house-consistent default; pick A only
     if you also update coding-standards to allow it.)

2. **Pagination cursor — Option A (Base64, opaque, stable).** Cursor = Base64url of
   `<epochMillis>:<id>` (the `createdAt` as epoch seconds/millis plus the `_id`). It is **opaque to the
   client**; the server decodes and validates it. Order is **`createdAt` DESC, then `_id` DESC** as the
   tiebreaker so a page does not skip/duplicate when two links share `createdAt`. A malformed cursor → 400.
   Encode/decode helpers live in the adapter (or a small `infra` helper); `core/` stays framework-free
   (the cursor is just a `String` on the domain `PageRequest`).

3. **Ownership error types — Option A: create a `ForbiddenException`** in `core/exception`, handled →
   `403` in `GlobalExceptionHandler`. Do NOT reuse `IllegalArgumentException`: the existing handler maps
   `IllegalArgumentException` → `400`, so it cannot produce a `403`. Follow the house pattern (typed
   exceptions each with a dedicated handler, like `InvalidDestinationException`, `UrlExpiredException`).
   Keep `UrlNotFoundException` → `404`. Also add the domain `ForbiddenException`.

4. **UTM fields — Option B: a nullable `UtmParams` value object (record).** Group them as an immutable
   value object: `UtmParams(String source, String medium, String campaign, String term, String content)`,
   `null` when absent (the record may be `null` — each field may be `null`). It matches the codebase's
   value-object style (`Url`, `QuotaUsage`) and is cleaner to extend/validate as a unit than five loose
   `String` fields. Persist as an embedded sub-document (or flattened fields) via the mapper; expose as a
   nested object in the DTO. Do NOT store UTM on the redirect — it is metadata on the link only.

5. **Tags constraints (define them).** `List<String>` with explicit bounds:
   - max **20** tags per link;
   - each tag **1–50 chars**, charset `[a-zA-Z0-9_-]` (no spaces/emoji/punctuation);
   - **deduplicated** (case-insensitive) at the input boundary; stored as given (or lowercased — pick and
     document; recommend lowercasing for deterministic filtering);
   - validated at the DTO (Bean Validation, e.g. `@Size(max=20)` + per-element) **and** defensively in the
     domain. Violation → 400.

6. **SecurityConfig — you do NOT strictly need to change it.** Verified: the new
   `GET /api/v1/urls`, `GET /api/v1/urls/{id}`, `PATCH /api/v1/urls/{id}`, `DELETE /api/v1/urls/{id}` are
   not matched by any `permitAll` matcher and therefore already fall through to
   `.anyRequest().authenticated()`. Key points:
   - `GET /{id}` is `permitAll` but only matches a **single** segment → it does **not** catch
     `/api/v1/urls/{id}`.
   - `POST /api/v1/urls` is `permitAll` and only applies to POST.
   - **Recommended (clarity + defense-in-depth):** add an explicit
     `.requestMatchers("/api/v1/urls/**").authenticated()` before `.anyRequest()`, for documentation. It is
     not required for security, but makes the intent explicit and guards against a future reorder.
   - Do **not** touch the existing `permitAll` for `GET /{id}` or `POST /api/v1/urls`.

---

## Sources of truth — read in this order

1. `AGENTS.md` (rules 4, 5, 7, 10; the "redirect always fast/independent" principle)
2. `docs/data-model-decisions.md` (additive metadata, soft-archive, index V7)
3. `docs/coding-standards.md` (owner guard, cursor pagination, PATCH semantics)
4. `docs/testing-playbook.md`
5. `tasks/links-as-resource-spec.md`
6. `tasks/links-as-resource-backlog.md`
7. `tasks/links-as-resource-implementation-sequence.md`
8. `core/model/ShortUrl`, `core/ports/outgoing/UrlRepositoryPort`, `core/service/UrlShortenerService`,
   `infra/adapter/input/rest/UrlController`, `infra/adapter/output/persistence/MongoUrlRepository`,
   `infra/.../migration/*`, `infra/config/SecurityConfig`, `core/service/UserService`,
   `infra/.../dto/*`, `MongoCollections`

If documentation disagrees with executable configuration, stop, report and resolve in the same change.

---

## Goal

Add links-as-resource: `GET /api/v1/urls` (list, cursor-paginated), `GET /api/v1/urls/{id}` (detail +
stats), `PATCH /api/v1/urls/{id}` (edit destination/metadata **without changing the code**), and
`DELETE /api/v1/urls/{id}` (archive/soft-delete). All owner-guarded. The **redirect** (`GET /{id}`) stays
fast and independent; an archived link returns 404; `302` is kept while the destination is mutable.

---

## Locked technical decisions

1. **`PATCH` changes the destination but NEVER the `id`/code** — the core Bitly feature. Only supplied
   fields change (partial update). `expiresAt` may be set/cleared. Invalid URL → 400.
2. **`DELETE` = archive (soft delete)** — sets `deletedAt`; the redirect stops resolving it (404) but the
   row/history is kept. Repeated DELETE → 204 (idempotent).
3. **Owner guard is application-level**, not just a route rule. `userId` comes from the authenticated
   context. Non-owner → 403; unknown → 404; archived + update → immutable (409/400).
4. **Cursor pagination** (stable, `createdAt` desc) via pure domain `PageRequest`/`PageResult`; `limit`
   capped (max 100).
5. **Redirect stays fast & independent**: archived → 404 (alongside expiry); keep `302` while mutable;
   document the `301`/`302` rule. Never couple the redirect to list/patch/delete.
6. **Metadata additive**: `title`, `tags`, `utmSource/Medium/Campaign/Term/Content`, `deletedAt` on
   `ShortUrl`/`ShortUrlEntity`. Add migration V7: non-unique `(userId, createdAt)` index.
7. **`/api/v1/urls/**` requires authentication** in `SecurityConfig`; `GET /{id}` stays `permitAll`,
   `POST /api/v1/urls` stays public. No conflict between `GET /{id}` and `GET /api/v1/urls/{id}`.
8. **No new Maven coordinate.** `core/` stays framework-free.

---

## Non-negotiable engineering rules

- Keep `core/` free of Spring, Mongo, Redis, JWT; pagination types are pure domain.
- Never couple the redirect path to the new list/patch/delete features.
- Owner guard is enforced in the use case (application layer), tested with 403 for non-owners — not just
  the `SecurityConfig` route rule.
- `PATCH` is partial; an archived link is immutable.
- Metadata is additive; existing links and the redirect are unaffected.
- English only in code, comments, logs, tests and docs.
- Do not push unless the human explicitly asks.
- Do not expand into: branded domain (Phase C), analytics rollup (Phase C), API keys/webhooks/bulk (Phase
  D), read/write split (Phase E), orgs/RBAC/billing (Phase F), QR/search.

---

## Required behaviour summary

### Model & persistence
- `ShortUrl`/`ShortUrlEntity` + mapper: `title`, `tags`, `utm*`, `deletedAt` (nullable). Migration V7.
- `UrlRepositoryPort` (or focused port) + `MongoUrlRepository`: `findByUserId`, `update`, `archive`, and
  cursor pagination.

### Use cases
- `ListUserLinksUseCase` (own links, cursor), `GetLinkUseCase` (owner-guarded), `UpdateLinkUseCase`
  (owner-guarded, partial, immutable if archived), `ArchiveLinkUseCase` (owner-guarded, idempotent).

### API
- `GET /api/v1/urls` (list), `GET /api/v1/urls/{id}` (detail + stats), `PATCH /api/v1/urls/{id}`,
  `DELETE /api/v1/urls/{id}`. Authenticated; owner-guarded; OpenAPI added.

### Redirect
- archived → 404; `302` kept while mutable; path stays fast/independent.

---

## Scope exclusions

Do not implement: custom/branded domain (Phase C), analytics temporal/geo/device/unique rollup (Phase C),
API keys/webhooks/bulk create (Phase D), read/write service split (Phase E), orgs/RBAC/SSO/billing (Phase
F), QR/search/dashboards.

---

## Definition of Done

- [ ] Metadata + `deletedAt` on model/entity/mapper; migration V7 (`(userId, createdAt)`); cursor
      pagination (pure domain).
- [ ] Repository list/update/archive against real Mongo; `core/` framework-free.
- [ ] List/get/update/archive use cases; owner-guarded (non-owner → 403); update does not change code;
      archived immutable.
- [ ] `GET /api/v1/urls`, `GET /api/v1/urls/{id}`, `PATCH`, `DELETE` endpoints (OpenAPI added).
- [ ] `/api/v1/urls/**` authenticated; owner guard at application level (tested 403).
- [ ] Redirect: archived → 404; `302` while mutable; path not coupled to new features.
- [ ] Ownership + CRUD ITs green; `mvn test`, `mvn verify`, `check-boundaries.sh` pass.
- [ ] Docs (`data-model-decisions`, `coding-standards`, `testing-playbook`, `README`) and `AGENTS.md`
      synced.

Start at **Step 0** of `links-as-resource-implementation-sequence.md`. Stop immediately if the baseline is
red, a locked decision conflicts with the approved dependency graph, or repository state contradicts the
specification.
