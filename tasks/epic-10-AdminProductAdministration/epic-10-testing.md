# Epic 10 – Admin surface: test matrix (contract tests)

Source of truth for the 18 contractual tests of Epic 10. Test classes:

- **Story 10.2:** `ca.tyny.urlshortener.AdminUsersIT` (root package, **non-gated** — new components
  are never born `@spec-complete`).
- **Story 10.3:** `AdminInspectIT` (planned, root package).
- **Story 10.4:** `AdminArchiveIT` (planned, root package) + `UrlShortenerServiceTest` additions
  (write-path check, gated `core/service` → traced or registered).
- **Story 10.5:** contract checks (OpenAPI paths/schemas via springdoc slice) + live proof.

Matrix mapping → DoD: 1–6 land in 10.2, 7–8 in 10.3, 9–11 in 10.4, 12–18 in 10.5 (docs/contract).

| # | Contract test (DisplayName) | Endpoint(s) | Expected | Story |
|---|-----------------------------|-------------|----------|-------|
| 1 | admin lists users with role and blocked fields | `GET /api/v1/admin/users` | 200; items `{userId,email,name,role,blocked,createdAt}`; role = env-list truth (ADMIN for listed email, USER otherwise) | 10.2 |
| 2 | email prefix filter returns only matching users | `GET /api/v1/admin/users?q=<prefix>` | 200; only matching emails; matching is case-insensitive | 10.2 |
| 3 | cursor pagination walks all users without overlap | `GET /api/v1/admin/users?limit=2&cursor=<next>` | 200; first page `hasMore=true`, second page hasMore=false, no userId overlap | 10.2 |
| 4 | blocked user loses login and block is idempotent | `POST /users/{id}/block`; `POST /api/v1/auth/login` | block 204 (repeated block → 204); blocked login → 403 `"Account blocked."` | 10.2 |
| 5 | blocked user cannot refresh its token | block + `POST /api/v1/auth/refresh` (pre-block refresh token) | refresh → 403 `"Account blocked."` | 10.2 |
| 6 | self-block is rejected (400) and unblock restores access | `POST /users/{id}/block\|unblock` | self-block → 400; unblock 204 (idempotent); login after unblock → 200 | 10.2 |
| 7 | admin inspects a user's URLs | `GET /api/v1/admin/users/{id}/urls` | 200; cursor-paginated, owner-scoped items; unknown user → 404 | 10.3 |
| 8 | admin looks up a URL by code | `GET /api/v1/admin/urls?code=<code>` | 200 `ShortUrlResponse` + `ownerUserId`/`ownerEmail`; unknown code → 404 | 10.3 |
| 9 | force archive sequence | `DELETE /api/v1/admin/urls/{id}` → `GET /{id}` → `DELETE` again | 204 → 404 → 204 (idempotent) | 10.4 |
| 10 | blocked user cannot shorten (write path), but can read | pre-block token: `POST /api/v1/urls` | 403 before any side effect; `GET /api/v1/urls` (same token) → 200; anonymous `POST /api/v1/urls` → 200 | 10.4 |
| 11 | blocked user cannot update/archive their own links | pre-block token: `PATCH`/`DELETE /api/v1/urls/{id}` | 403 before any side effect | 10.4 |
| 12 | OpenAPI exposes the 6 admin paths | `GET /v3/api-docs` | paths `/api/v1/admin/**` (users, users/{id}/urls, urls?code, block, unblock, urls/{id}); `role` in register/login/refresh/me schemas | 10.5 |
| 13 | authenticated non-admin is forbidden from every admin endpoint | each admin endpoint | 403 for all (worst-path: block) | 10.5 |
| 14 | anonymous is rejected from every admin endpoint | each admin endpoint | 401 | 10.5 |
| 15 | blocked-account error contract | `login`/`refresh` of blocked | 403 body `{status,error:"Forbidden",message:"Account blocked.",timestamp}` | 10.5 |
| 16 | wrong credential of a blocked account | `POST /api/v1/auth/login` | 401 `Invalid credentials` (order proof: auth first, then block) | 10.5 |
| 17 | self-block contract | `POST /admin/users/{selfId}/block` | 400 `You cannot block your own account` | 10.5 |
| 18 | no new meters / living-spec ratchet intact | `check-metrics-frozen.sh`, `check-living-spec.sh`, `check-boundaries.sh` | PASS; Admin packages remain ungated; 0 new Micrometer series | 10.5 |

## Execution notes

- Source-of-truth `@TracesRequirement` applies **only** to gated packages. All `Admin*` test classes
  live in the **non-gated** root package; new gated-package tests (UrlShortenerService write-path is
  `core/service`) must be traced to an existing REQ or registered in the AGENTS.md debt registry.
- Test count guard: story 10.2 door is **10** tests in `AdminUsersIT` (matrix 1–6 + 404/403/401
  extras); story 10.5 grows to the full 18 across the suite.