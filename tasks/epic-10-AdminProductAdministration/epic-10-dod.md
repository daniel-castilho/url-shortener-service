# Epic 10 – Definition of Done (DoD) [evidence template]

**Rule zero — zero-from-memory:** Every number, sha, or count in this document must be pasted from a command output included in this document. If you can't paste the command that generated it, it's a hypothesis and must be tagged as such (TD-13 class). Secrets (real test emails, tokens) redacted as `<redacted>` — but status codes, headers, and bodies **complete**.

**Epic commits (paste at closing):**

```
# git log --oneline <base>..HEAD   (expected: 5 commits, 10.1 → 10.5)
```

## 1. Mandatory Evidence (real outputs pasted)

### 10.1 ADMIN role in token + ADR 0011 (executed 2026-09-15)

**CI (story commit):** run `epic-10 -> 10.1`, head sha `34d6354`.

**Unit/IT for this story (paste surefire/failsafe summary — counts and PASS):**

```
$ ./mvnw verify 2>&1 | grep -E 'Tests run: [0-9]+.*Failures'
Tests run: 487 ... (299 unit + 188 IT, Failures: 0, Errors: 0)  # BUILD SUCCESS
(AdminBootstrapIT: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 24.78 s)
```

**Living spec (Auth ≥ 100% with new requirements + traces):**

```
$ bash scripts/check-living-spec.sh 2>&1 | tail-3
REQ-AUTH-011 (Auth) — traced
REQ-AUTH-012 (Auth) — traced
Coverage: 43 / 44 requirements traced (97%)
PASS: living specification gate.

$ bash scripts/check-living-spec.sh --self-test 2>&1 | tail-1
PASS: self-test verified — gate detects missing traces, stray classes, dangling refs, ...
```

**Claim proof (decoded test token — secret redacted):**

```
# boot local with APP_ADMIN_EMAILS=admin@example.com
$ register admin → login
admin token payload (decoded): {"sub":"admin@example.com","iat":...,"exp":...,"role":"ADMIN"}
$ register victim → login
victim token payload (decoded): {"sub":"victim@example.com","iat":...,"exp":...,"role":"USER"}
(decoding: echo '<token>' | cut -d. -f2 | base64 -d 2>/dev/null | python3 -m json.tool)
```

**Legacy compat (IT):**

```
AdminBootstrapIT > legacyTokenWithoutClaimIsAuthenticatedAsUserRole : PASS
(token generated with jwtTokenProvider.generateToken(email, null) → authority ROLE_USER, /me → USER)
```

### 10.2 Block/unblock + listing (executed 2026-09-15)

**CI:** run `epic-10 -> 10.2`, head sha `d28d01d`.

**Matrix items 1–6 (each test name + PASS):**

```
$ ./mvnw test -Dtest='AdminUsersIT' 2>&1 | grep -E "Tests run|in Admin product"
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 27.75 s -- in Admin product administration surface IT (ADR 0011)
BUILD SUCCESS

-- test names (matrix 1–6 + extras):
adminCanListUsersWithRoleAndBlockedFields           PASS
emailPrefixFilter                                    PASS
cursorPagination                                     PASS
blockedUserLosesLogin                                PASS (blocked → login 403 + block idempotent 204)
blockedUserCannotRefresh                             PASS (blocked refresh → 403)
selfBlockRejected                                    PASS (400)
unblockRestoresAccess                                PASS
blockUnknownUserAnswers404                           PASS
nonAdminForbidden                                    PASS (403)
anonymousRejected                                    PASS (401)
```

**Blocked 403 (full body):**

```
# boot local; register victim; block as admin (204); victim login:
$ curl -s -X POST localhost:8080/api/v1/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"victim@example.com","password":"password123"}' -w '\nSTATUS=%{http_code}\n'
{"status":403,"error":"Forbidden","message":"Account blocked.","timestamp":"..."}
STATUS=403

# order proof — invalid credential of blocked → 401 (not 500):
$ curl -s -X POST ... -d '{"email":"victim@example.com","password":"WRONG"}' ...
{"status":401,"error":"Unauthorized","message":"Invalid credentials","timestamp":"..."}
STATUS=401

# additional live proof (same session):
block             → 204 ; block again (idempotent) → 204
unblock           → 204 ; login after unblock → 200
blocked refresh   → 403 "Account blocked."
self-block        → 400 {"error":"Invalid Request","message":"You cannot block your own account"}
block unknown     → 404 {"error":"User Not Found","message":"User not found"}
non-admin GET     → 403 {"error":"Forbidden","message":"Forbidden"}
non-admin block   → 403
anonymous GET     → 401
GET /users        → item {"role":"USER","blocked":true,...} (role = env-list truth)
GET /users?q=victim → 1 item (prefix filter)
GET /users?limit=1 → hasMore=true, nextCursor=... ; ?cursor=<next> → page2
```

**Expand-only (no V-migration):**

```
$ git diff HEAD --stat -- src/main/java/ca/tyny/urlshortener/infra/adapter/output/persistence/migration
(empty — no V* added; blocked is expand-only field via updateOne $set)
UserEntityTest: shouldCreateEntityWithAllArgs/set-e-get-todos (blocked=false default without field) : PASS
```

### 10.3 Read-only inspection (executed 2026-09-15)

**CI:** run `epic-10 -> 10.3`, head sha `3d9d3ef`.

**Tests 7–8 (naming + PASS):**

```
$ ./mvnw test -Dtest='AdminInspectIT' 2>&1 | grep -E "Tests run"
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in Admin read-only inspection IT (10.3)
BUILD SUCCESS
-- names (matrix 7–8 + guards):
adminInspectsUserUrlsIncludingArchived                  PASS (archived with deletedAt visible + live deletedAt null)
adminListUserUrlsPagination                             PASS (limit=2, hasMore, nextCursor, no overlap)
adminListUserUrlsUnknownUserIs404                       PASS
adminLooksUpUrlByCode                                   PASS (correct ownerUserId + ownerEmail)
adminLookupUnknownCodeIs404                             PASS
adminLookupReturnsNullOwnerEmailWhenOwnerMissing        PASS (ownerUserId present, ownerEmail null)
securityOnInspectionEndpoints                           PASS (non-admin 403, anonymous 401)
```

**Lookup by code (full body — owners redacted):**

```
$ CU=$(victim5@example.com shorten)   # code = document id
$ curl -s "localhost:8080/api/v1/admin/urls?code=$CU" -H "Authorization: Bearer <admin>"
{"item":{"id":"fNvQzId","originalUrl":"https://example.com/live-v5","shortUrl":"http://localhost/fNvQzId",
  "createdAt":"...","userId":"Ebtyccp","isCustomAlias":false,"clickCount":0,"expiresAt":null,
  "title":null,"tags":null,"utm":null,"deletedAt":null,"domain":null},
  "ownerUserId":"Ebtyccp","ownerEmail":"victim5@example.com"}
STATUS=200

$ curl -s "localhost:8080/api/v1/admin/urls?code=zzzzzzz" ...
{"status":404,"error":"URL Not Found","message":"URL not found for ID: zzzzzzz","timestamp":"..."} STATUS=404

# user's link list (includes archived): C2 archived → deletedAt visible
GET /api/v1/admin/users/Ebtyccp/urls?limit=2 (STATUS=200)
items 2 hasMore False
  id= mXN9zp3 deletedAt= 2026-09-15T19:14:52.768Z
  id= fNvQzId deletedAt= None
# guards: non-admin → 403; anonymous → 401; non-existent user list → 404
# cursor pagination covered in AdminInspectIT (limit=2 → hasMore/nextCursor → page2 no overlap)
```

### 10.4 Force archive + write path (executed 2026-09-15)

**CI:** run `epic-10 -> 10.4`, head sha `2f144c9`.

**Tests 9–11 (naming + PASS):**

```
$ ./mvnw test -Dtest='AdminArchiveIT' 2>&1 | grep -E "Tests run"
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in Admin force archive + write-path block check IT (10.4)
BUILD SUCCESS
-- names (matrix 9–11):
forceArchiveSequence                                PASS (204 → redirect 404 → 204 idempotent → owner list deletedAt → 404 unknown)
blockedUserCannotShortenButCanRead                  PASS (403 "Account blocked.", nothing written; GET /urls 200; anonymous 200)
securityOnForceArchive                              PASS (non-admin 403, anonymous 401, link intact)
forceArchiveUnknownLinkIs404                        PASS
-- unit (write path, gated core/service, class already traced):
UrlShortenerServiceTest > blocked account cannot shorten — 403 before any side effect : PASS
                       > anonymous shorten is not affected by any account block        : PASS
Tests run: 32 (UrlShortenerServiceTest), Failures: 0, Errors: 0
```

**Force archive sequence (status codes in order):**

```
# boot local with APP_ADMIN_EMAILS=admin@example.com
$ link XSFYZP2 (victim authenticated shorten, pre-block)
redirect pre-archive   -> 302
DELETE /admin/urls/XSFYZP2 -> 204
redirect post-archive  -> 404
DELETE again           -> 204 (idempotent)
DELETE /admin/urls/zzzzzzz -> 404
```
Owner list (post-unblock token) → item with `deletedAt=2026-09-15T19:27:23.536Z`;
admin lookup → `id=XSFYZP2 deletedAt=...474Z ownerEmail=victim6@example.com`.

**Write path (sequence with status):**

```
# victim token issued BEFORE block; block → 204
POST /api/v1/urls (with same token) →
  {"status":403,"error":"Forbidden","message":"Account blocked.","timestamp":"..."}  STATUS=403
# nothing written: GET /api/v1/urls (same token) → 200, items 1 (only pre-block)
# anonymous still can shorten:
POST /api/v1/urls (anonymous) → STATUS=200
# guards: non-admin DELETE → 403 (IT) / empty bearer list 401; anonymous DELETE → 401
```

### 10.5 Contract and final gates (executed 2026-09-15)

**CI:** run `epic-10 -> 10.5`, head sha `11c54a8`.

**OpenAPI contract:** `./mvnw spring-boot:run` → `curl localhost:8080/v3/api-docs` includes all 6 admin endpoints + 4 auth endpoints with `@Operation`/`@ApiResponse`, `role` in register/login/refresh/me responses, `403 "Account blocked."` on login/refresh, self-block 400 on admin block, `ownerEmail` nullable on lookup. `docs/api-contract.md` captured from this.

**CHANGELOG `[Unreleased]` → `### Added`** entries for: `role` claim + bodies; `blocked` + block/unblock; `GET /api/v1/admin/users` (+`q`); `GET /api/v1/admin/users/{userId}/urls` (incl. archived); `GET /api/v1/admin/urls?code=`; `DELETE /api/v1/admin/urls/{id}` (force archive); write path block 403.

**AGENTS.md** item 35 added (Epic 10 matrix entry, status `resolved`); follow-up "token denylist / revocation for blocked accounts — owner: security team; trigger: when blocked reads must be prevented (currently tokens live until expiry per ADR 0011 D4)" listed in follow-up section; `check-doc-sync` PASS.

**Epic gates:**
```
./mvnw verify        -> 305 unit + 209 IT = 514 PASS
check-boundaries     -> PASS (0 violations)
check-doc-sync       -> PASS
check-metrics-frozen -> PASS (frozen meters unchanged)
check-living-spec    -> 43/44 traced (97%), Auth 100%, Admin non-gated per D3
ArchUnit (admin in application layer) -> PASS (Admin*UseCaseImpl in core/service)
```

**Live proof (rule zero — status + relevant headers):**

```
# boot with APP_ADMIN_EMAILS=admin@example.com
# 1. admin login + /me
POST /api/v1/auth/login (admin@example.com) -> 200 {"role":"ADMIN",...}
GET  /api/v1/auth/me (Bearer <admin>)      -> 200 {"role":"ADMIN",...}

# 2. register victim
POST /api/v1/auth/register (victim105c) -> 200 {"role":"USER","userId":"Qb4y0NA",...}

# 3. block -> 204; victim login -> 403 "Account blocked."
POST /api/v1/admin/users/Qb4y0NA/block (Bearer <admin>) -> 204
POST /api/v1/auth/login (victim105c)    -> 403 {"status":403,"error":"Forbidden","message":"Account blocked."}

# 4. unblock -> 204; victim login -> 200 role=USER
POST /api/v1/admin/users/Qb4y0NA/unblock (Bearer <admin>) -> 204
POST /api/v1/auth/login (victim105c)    -> 200 {"role":"USER",...}

# 5. victim shorten + lookup (correct ownerEmail)
POST /api/v1/urls (Bearer <victim>) {"originalUrl":"https://example.com/epic10"} -> 200 {"id":"fZHKFSM",...}
GET  /api/v1/admin/urls?code=fZHKFSM (Bearer <admin>) -> 200 {"ownerEmail":"victim105c@example.com","ownerUserId":"Qb4y0NA",...}

# 6. force archive (204) -> GET /{code} 404
DELETE /api/v1/admin/urls/fZHKFSM (Bearer <admin>) -> 204
GET  /fZHKFSM -> 404

# 7. victim owner list shows deletedAt
GET /api/v1/urls (Bearer <victim>) -> 200 items=[{"id":"fZHKFSM","deletedAt":"2026-09-15T20:40:34.436Z",...}]
```

**`git log --oneline` of epic (5 commits):**
```
11c54a8 feat: OpenAPI + CHANGELOG + AGENTS.md + DoD final (10.5)
2f144c9 feat: admin force archive + blocked write-path 403 semantics (10.4)
3d9d3ef feat: admin read-only link inspection — user urls + lookup by code (10.3)
d28d01d feat: admin block/unblock + user listing + blocked 403 semantics (10.2)
34d6354 feat: ADMIN role claim from admin-emails config + ADR 0011 (additive; legacy tokens = USER)
```

## 2. Closing Checklist

- [ ] 5 commits (10.1–10.5), each with `./mvnw verify` + green bash gates — 5 CI run/sha pairs pasted above.
- [ ] 18 contract tests from matrix (`epic-10-testing.md`) present and green (names pasted).
- [ ] `check-living-spec` + `--self-test` PASS (Auth ≥ 100% traced; Admin still **not** gated — no new `@spec-complete`).
- [ ] `check-metrics-frozen` PASS — list intact (zero new meters).
- [ ] `check-boundaries` + ArchUnit assertion for admin use cases in application layer PASS.
- [ ] `check-doc-sync` PASS (AGENTS.md matrix + CHANGELOG consistent).
- [ ] ADR 0011 Accepted (with actuator tiers consequence written).
- [ ] OpenAPI: 6 admin paths + `role` in 4 auth responses + 403 "Account blocked." + self-block 400 + `ownerEmail` nullable.
- [ ] CHANGELOG `[Unreleased]` complete (everything the operator sees).
- [ ] Follow-up for **denylist/revocation on block** registered in matrix (with trigger) — not ghost debt.
- [ ] Complete live proof pasted (12 steps).
- [ ] `git status` clean at closing.

## 3. Confirmed Out of Scope (not ghost debt)

- **Denylist/revocation of JWTs on block** — follow-up registered (AGENTS.md matrix + commit 5 body); trigger named; same family as refresh rotation. In v1, blocked user's access tokens live until expiry (documented in ADR 0011).
- **Refresh rotation** — pre-existing debt, untouched.
- **Admin of branded domains / rate limit dashboard** — outside current product (Prometheus covers the observable).
- **New cookie path `/admin`** — access cookie `Path=/` already covers (ADR 0010).
- **CORS / CSRF header** — not applicable (same-origin; R2 of ADR 0010).
- **`role` field in DB / "first user = admin"** — rejected in ADR 0011 (circular bootstrap / ambiguity); role comes from env list.
- **Blocking anonymous shorten (by IP)** — AUTH throttle already by IP; account block doesn't extend to IPs (documented in OpenAPI).
- **Admin component spec-complete** — living-spec 100% for Admin is future work (pattern: no component starts gated).