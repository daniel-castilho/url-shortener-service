# Links as Resource (Phase B) — Implementation Sequence

**Companions:** `links-as-resource-spec.md` · `links-as-resource-backlog.md`
**Rule:** complete each step's acceptance and verification before starting the next. Do not invent
out-of-scope work.

---

## Global execution rules

1. Work in small, reviewable vertical commits.
2. Read the referenced story acceptance before coding.
3. **The redirect path must stay fast and independent** — never couple it to list/patch/delete.
4. **Owner guard is application-level**, not just a route rule. Test non-owner → 403.
5. Add tests with the production change, not at the end.
6. `core/` stays framework-free; pagination types are pure domain.
7. No new Maven coordinate. Metadata is additive (no breaking change).
8. After each step, update task status and docs; do not silently alter the spec.

### Fast verification (throughout)

```bash
mvn test
```

### Integration verification

```bash
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

### Full gate

```bash
mvn verify
```

---

## Step 0 — Baseline, confirm the gap
### Stories: (context)

### Actions

1. Confirm HEAD (`7e13ae8`) and fast tests green.
2. Confirm the API is only `POST /api/v1/urls` + `GET /{id}`; `UrlRepositoryPort` lacks
   list/update/delete; `ShortUrl` has no metadata or `deletedAt`.
3. Confirm `GET /{id}` and `/api/v1/urls/...` don't conflict.
4. Record the decisions: owner guard, cursor pagination, `302` while mutable, soft-archive (`deletedAt`).

### Done when

- gap understood; decisions recorded.

### Verify

```bash
mvn test
```

---

## Step 1 — Metadata + deletedAt on model/entity/mapper
### Stories: L1

### Actions

1. Add `title`, `tags`, `utmSource/Medium/Campaign/Term/Content`, `deletedAt` (Instant, nullable) to
   `ShortUrl` + `ShortUrlEntity`.
2. Update `ShortUrlMapper` to round-trip; keep existing constructors as overloads.

### Done when

- fields additive; mapper round-trips; existing links unaffected.

### Verify

```bash
mvn test
```

---

## Step 2 — Repository + migration V7 + cursor pagination types
### Stories: L2, L3

### Actions

1. Add `findByUserId`, `update`, `archive` to `UrlRepositoryPort` (or a focused port); implement in
   `MongoUrlRepository` (`(userId, createdAt)` query + cursor).
2. Add migration V7: non-unique index `(userId, createdAt)`.
3. Add pure `PageRequest`/`PageResult` domain types.

### Done when

- list/update/archive work against real Mongo (IT); V7 index created (idempotent);
- cursor pagination stable; `core/` framework-free.

### Verify

```bash
mvn test
mvn test -Dtest='MongoUrlRepositoryIT' -DfailIfNoTests=false
```

---

## Step 3 — Use cases (list/get/update/archive)
### Stories: L4, L5, L6, L7

### Actions

1. Implement `ListUserLinksUseCase`, `GetLinkUseCase`, `UpdateLinkUseCase`, `ArchiveLinkUseCase`.
2. Owner-guard each: `userId` from auth context; non-owner → domain `ForbiddenException` (403); unknown →
   `UrlNotFoundException` (404); archived + update → immutable (409/400).
3. `UpdateLinkCommand` carries the optional fields; partial update.

### Done when

- owner reads/lists/mutates own links; non-owner → 403; update doesn't change the code; archived immutable.

### Verify

```bash
mvn test
```

---

## Step 4 — Controller endpoints
### Stories: L8, L9

### Actions

1. Add `GET /api/v1/urls` (list, cursor-paginated), `GET /api/v1/urls/{id}` (detail + stats),
   `PATCH /api/v1/urls/{id}`, `DELETE /api/v1/urls/{id}`.
2. Map to DTOs (`ShortUrlResponse` with metadata + shortUrl); add OpenAPI annotations.
3. Resolve `userId` as the existing shorten endpoint does (auth context → `findByEmail` → `id`).

### Done when

- endpoints work, authenticated, owner-scoped; OpenAPI updated.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 5 — SecurityConfig + owner guard
### Stories: L10

### Actions

1. Add `/api/v1/urls/**` → `authenticated()` in `SecurityConfig` (keep `GET /{id}` permitAll, `POST
   /api/v1/urls` public).
2. Ensure owner guard is at the application layer (not just route); add 401/403 tests.

### Done when

- unauthenticated → 401 on new routes; non-owner → 403 (application guard); public shorten still works.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 6 — Redirect: archived → 404; keep 302
### Stories: L11

### Actions

1. In the redirect read path, treat `deletedAt` present as 404 (alongside expiry).
2. Keep `302` for valid, non-expired, non-archived links; document the `301`/`302` rule.
3. Confirm the redirect path is not coupled to the new features.

### Done when

- archived link → 404 in redirect; valid link still `302`; path stays fast/independent.

### Verify

```bash
mvn test
mvn test -Dtest='*IT' -DfailIfNoTests=false
```

---

## Step 7 — Tests + docs sync
### Stories: V1

### Actions

1. Add ITs: list (own only, cursor), get (owner/non-owner), patch (code unchanged, partial,
   archived-immutable), delete (archive, idempotent, redirect→404), and a 403 matrix for non-owners.
2. Sync `docs/data-model-decisions.md`, `docs/coding-standards.md`, `docs/testing-playbook.md`,
   `README.md` (endpoints), `AGENTS.md` (debt). OpenAPI updated.

### Done when

- ownership + CRUD ITs green; non-owner → 403 everywhere; docs/AGENTS/OpenAPI synced.

### Verify

```bash
mvn verify
bash scripts/check-boundaries.sh
```

---

## Final smoke / acceptance path

1. Create a link (public shorten) → get the code.
2. `GET /api/v1/urls` (authed) → your links list; a different user → empty/403.
3. `GET /api/v1/urls/{id}` → detail + `clickCount` + metadata.
4. `PATCH /api/v1/urls/{id}` with a new `originalUrl` → the same `id` now redirects to the new destination;
   the code is unchanged.
5. `DELETE /api/v1/urls/{id}` → archived; `GET /{id}` now returns 404; the row/history remains.
6. Non-owner attempts list/read/patch/delete → 403; unauthenticated → 401.
7. Redirect path stays fast (not coupled to list/patch); `301`/`302` rule documented.
8. `mvn verify` + `check-boundaries.sh` pass.

---

_Pre-implementation sequence. Preserve deviations and final evidence as an as-built record after delivery._
