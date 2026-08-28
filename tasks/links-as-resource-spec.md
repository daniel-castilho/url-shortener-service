# Links as Resource (Phase B) — Technical Specification

**Status:** ready for implementation from current `main` (`7e13ae8`, `v0.11.0`).
**Priority:** P1 — first product jump toward a "Bitly-like". Second epic of this phase.
**Companions:** `links-as-resource-backlog.md` · `links-as-resource-implementation-sequence.md`

---

## 1. Purpose

Phase A made the read path honest and measured (absent-vs-miss, Policy B, published redirect baseline).
Phase B turns the short URL into a **resource** — the feature Bitly lives on. Today the API is only
`POST /api/v1/urls` (create) and `GET /{id}` (redirect). There is **no way to list a user's links, read a
link's details/stats, edit a link's destination without changing its code, or delete/archive a link.** The
`UrlRepositoryPort` lacks `findByUserId`, `update`, and `delete`.

This epic makes links **editable, listable, inspectable, and archivable** — the base the branded-domain and
analytics phases (C/D) build on. The **core value**: `PATCH` to change the destination **without** breaking
the short code (campaigns live on this), plus persisted metadata (`title`, `tags`, `utm_*`).

---

## 2. Scope

### In scope

- **`GET /api/v1/urls`** — list the authenticated user's links, paginated, newest-first.
- **`GET /api/v1/urls/{id}`** — link details + summary stats (`clickCount`, `createdAt`, `expiresAt`,
  `customAlias`, `title`, `tags`, UTM).
- **`PATCH /api/v1/urls/{id}`** — edit destination, `title`, `tags`, UTM, and `expiresAt` **without
  changing the code**. Owner-only.
- **`DELETE /api/v1/urls/{id}`** — **archive** (soft delete) the link; owner-only; the redirect stops
  resolving it (404) but the row/history is kept.
- **Extended metadata** on `ShortUrl`: `title`, `tags` (`List<String>`, bounded: max 20, each 1–50 chars,
  `[a-zA-Z0-9_-]`, case-insensitively deduped), and a nullable **`UtmParams` value object**
  (`source`, `medium`, `campaign`, `term`, `content`).
- **Ownership/authorization** — a user can only list/read/patch/delete their own links.
- **`302` vs `301`** — document and keep `302` while the destination is mutable via `PATCH`.
- Persistence & repository expansion (port + Mongo adapter), migration for new fields, tests, docs sync.

### Out of scope

- branded domain / custom domain (Phase C);
- analytics temporal/geo/device/unique rollup (Phase C);
- API keys, webhooks, bulk create (Phase D);
- read/write service split (Phase E);
- multi-region, orgs/RBAC/SSO, billing (Phase F);
- QR, dashboards, search.

---

## 3. Architectural constraints

- `core/` stays framework-free. New ports (`*Repository`, `*UseCase`) and models (`PageRequest`/`PageResult`
  as pure domain types) live in `core/`; DTO mappers and the Mongo repo stay in `infra/`.
- The **redirect** path (`GET /{id}`) must remain fast and **never** depend on any of the new read/mutation
  features. `PATCH`/`DELETE` must not slow the redirect.
- `GET /{id}` and `GET /api/v1/urls/{id}` do **not** conflict: `/{id}` matches a single segment;
  `/api/v1/urls/...` is a distinct, more specific path.
- Metadata is optional and additive; adding it must not break existing short links or the redirect.

---

## 4. Data model (additive)

Add to `ShortUrl` and `ShortUrlEntity` (all nullable/optional, default absent):

```text
String title            # optional display name
List<String> tags       # optional tags (max 20; each 1-50 chars [A-Za-z0-9_-]; deduped)
UtmParams utm          # nullable value object (source, medium, campaign, term, content)
Instant deletedAt       # nullable; present = archived (soft-deleted); redirect resolves to 404
```

`UtmParams` is a nullable, immutable record — `null` when absent; each field may be `null`. Persist as an
embedded sub-document via the mapper (or flattened fields); expose as a nested object in the DTO.

- `createdAt` exists already; index on `userId` exists already (migration V3). Add a **non-unique index
  `(userId, createdAt)`** for the list query (migration V7).
- Update `ShortUrlMapper` to round-trip the new fields; keep existing constructors as overloads.
- The redirect read path must check `deletedAt` is null (or treat presence as 404) — alongside the existing
  expiry check.

---

## 5. API contract

### 5.1 `GET /api/v1/urls` (authenticated, owner-scoped)

```http
GET /api/v1/urls?limit=20&cursor=<cursor>
Authorization: Bearer <token>
```

- Returns the caller's short links, newest-first (`createdAt` desc).
- Paginated via an **opaque, stable, Base64url cursor** encoding `createdAt` + `_id`
  (`<epochMillis>:<id>`), ordered by `createdAt` DESC then `_id` DESC (tiebreaker). The server decodes and
  validates it; a malformed cursor → 400. The client treats it as opaque.
- `limit` capped (e.g. max 100). Only **own** links.
- Unauthenticated → 401. Cursor pagination avoids offset drift with inserts.

### 5.2 `GET /api/v1/urls/{id}` (authenticated, owner-scoped)

```http
GET /api/v1/urls/{id}
Authorization: Bearer <token>
```

- Returns the link detail + summary stats (`clickCount`, `createdAt`, `expiresAt`, `customAlias`,
  `title`, `tags`, UTM, `shortUrl`).
- Owner-only; a non-owner → 403; unknown → 404.

### 5.3 `PATCH /api/v1/urls/{id}` (authenticated, owner-scoped)

```http
PATCH /api/v1/urls/{id}
{ "originalUrl": "...", "title": "...", "tags": [...], "utmSource": "...", "expiresAt": "..." }
Authorization: Bearer <token>
```

- **Edits the destination without changing the `id`/code** (the key Bitly feature).
- Owner-only → 403; unknown → 404; invalid URL → 400.
- All fields optional; only supplied fields change. `expiresAt` may be set/cleared (`null` = never).
- Allowed only on **not archived** links (a `DELETE`d link is immutable).

### 5.4 `DELETE /api/v1/urls/{id}` (authenticated, owner-scoped)

```http
DELETE /api/v1/urls/{id}
Authorization: Bearer <token>
```

- **Archives** the link: sets `deletedAt`; the redirect stops resolving it (404) but the row/history is kept.
- Owner-only → 403; unknown → 404; idempotent (repeated DELETE → 204 even if already archived).
- Does **not** delete history/analytics.

---

## 6. Redirect semantics

- `GET /{id}` remains `302` to the destination **only if** the link is not expired **and** not archived.
- An archived link (`deletedAt` set) → `404` (treated as unavailable), consistent with the existing
  `UrlNotFoundException`.
- **`302` vs `301`:** keep `302` (temporary) because the destination is **mutable** via `PATCH` — a `301`
  would cause browsers/SEO to cache the old destination. Document this; only switch individual links to
  `301` when the destination is explicitly immutable (future).

---

## 7. Use cases & ports

Add to `core`:

- **Inbound `*UseCase`** (consumed by controllers):
  - `ListUserLinksUseCase` — `PageResult<ShortUrl> list(userId, limit, cursor)`
  - `GetLinkUseCase` — `ShortUrl get(userId, id)` (owner-guarded)
  - `UpdateLinkUseCase` — `ShortUrl update(userId, id, UpdateLinkCommand)` (owner-guarded, immutable if archived)
  - `ArchiveLinkUseCase` — `void archive(userId, id)` (owner-guarded, idempotent)
- **Outbound focused ports (ISP; the existing `MongoUrlRepository` implements all of them).** Keep
  `UrlRepositoryPort` (used by the shortener/redirect write path) **unchanged**:
  - `LinkQueryPort`: `List<ShortUrl> findByUserId(String userId, int limit, Cursor cursor)`,
    `Optional<ShortUrl> findById(String id)`.
  - `LinkMutationPort`: `void save(ShortUrl)`, `void update(ShortUrl)` (upsert by `_id`),
    `void archive(String id)`, `void incrementClickCount(String id, long delta)`.
- **Commands** (pure), e.g. `UpdateLinkCommand(originalUrl, title, tags, UtmParams utm, expiresAt)`.

Keep the read-path `getOriginalUrl`/`GetUrlUseCase` unchanged (that's the redirect; it stays fast and
independent).

---

## 8. Security

- The new `/api/v1/urls/**` routes require **authentication** (`authenticated()`) in `SecurityConfig`
  (unlike `GET /{id}` which is `permitAll` and `POST /api/v1/urls` which is public).
- **Authorization is application-level (owner guard), not just route-level.** The use cases receive
  `userId` from the authenticated context and enforce that the link `userId` matches.
- Non-owner → `403`; this must be tested, not just the route rule.

---

## 9. Verification commands

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false      # list/get/patch/delete + ownership ITs
mvn verify
bash scripts/check-boundaries.sh                 # core stays framework-free
```

---

## 10. Documentation deliverables

- `docs/data-model-decisions.md` — "Links as resource" + metadata fields + `deletedAt` soft-archive; note
  the `(userId, createdAt)` index (migration V7).
- `docs/coding-standards.md` — owner-guard rule; cursor pagination; PATCH-patch semantics (partial update).
- `docs/testing-playbook.md` — add the list/get/patch/delete + ownership ITs to the suite map/gaps.
- `README.md` / `AGENTS.md` — document the new endpoints and the "redirect stays fast & immutable-archived
  → 404" note; update the tech/debt items.
- OpenAPI: add the new routes to the controller annotations.

The epic is **not** Done while a link's destination cannot be changed without breaking its code, or while a
user can read/mutate another user's link.
