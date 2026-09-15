# AI Software Engineer Prompt — Epic 10: Admin API + ADMIN role (additive)

**Package.** This epic is fully specified in `tasks/epic-10/` — 5 documents,
PT-BR (the repo's epic-doc language; do not "fix" the accents in them):

- `epic-10-overview.md` — context, repo state (what is reused, verified at the
  baseline sha), out of scope, **owner decisions D1–D6 (final)**, acceptance
  criteria.
- `epic-10-stories.md` — stories 10.1–10.5 with landed acceptance criteria.
- `epic-10-technical-tasks.md` — file-level tasks per story + final gates.
- `epic-10-testing.md` — test strategy + the **18 mandatory contract tests**
  (9 from the web squad + 9 owner additions — every one is required).
- `epic-10-dod.md` — evidence template. **Fill it with real outputs** (rule
  zero). A story is done when its DoD section is filled.

**Baseline.** Re-base on `origin/main` (current tip: `d48bc88`). Every anchor
in the docs was verified at that sha — re-check with grep before editing, do
not trust line numbers.

## Scope discipline

- **5 commits, one per story, in order 10.1 → 10.5** (hard dependencies:
  10.2 needs 10.1's role/authority for the ADMIN enforce; 10.3/10.4 need
  10.2's admin packages; 10.5 consolidates the contract).
- **Each commit green on its own:** `./mvnw verify` + bash gates
  (`check-boundaries`, `check-doc-sync`, `check-metrics-frozen`,
  `check-living-spec` + `--self-test`). No half-story merges.
- **Rule zero — paste, never recall:** all outputs (status codes, full
  `ErrorResponse` bodies, headers, test counts, CI run/sha pairs) go into the
  DoD. Secrets redacted as `<redigido>`; bodies and statuses complete.
- **Rule 8:** no accents in code, commit messages, ADR, CHANGELOG.
- **No open questions.** D1–D6 in the overview are owner decisions (env-list
  bootstrap — first-user rejected; occupying the reserved `ROLE_ADMIN` slot;
  application-layer enforce + own admin packages; `blocked` field + **403**
  (not 401) on login/refresh + write-path check included + self-block 400;
  `q` = email prefix + cursor style of the urls list; 5 green commits with
  ADR in commit 1 and OpenAPI/CHANGELOG in commit 5). **Do not re-litigate
  them.** If the code contradicts a decision, stop and report — do not
  improvise an alternative.

## Critical anchors (verified at d48bc88 — if any moved, a doc premise broke: report)

- `SecurityConfig`: the default is `.anyRequest().authenticated()` — **there
  is NO permitAll catch-all** (the `/me` matcher-order trap does not apply to
  `/api/v1/admin/**`). The actuator tiers reference `hasRole("ADMIN")` and
  **nothing grants that role today** (the Basic filter grants `ROLE_OPERATOR`
  only) — the JWT ADMIN claim fills this reserved slot; ADR 0011 must
  document the consequence (product admin can also satisfy the actuator
  ADMIN tiers; other users stay locked out).
- `core.exception.ForbiddenException` → 403 and `UrlNotFoundException` → 404
  are already mapped in `GlobalExceptionHandler` (owner-guard pattern in
  `GetLinkUseCaseImpl`) — reuse, do not invent new error machinery.
- **The short code IS the document id** (`GET /{id}` resolves via
  `findById(code)`) — the code lookup needs **no new repository method**.
- `LinkListResponse{items, nextCursor, hasMore}` + `PageResult`/`Cursor` =
  the cursor contract to reuse for both new list endpoints (same default/cap
  as the urls list).
- Archive semantics: `ArchiveLinkUseCase` sets `deletedAt`;
  `urlCachePort.evict(id)` is the eviction pattern (force archive reuses both).
- `User` is an **immutable record** — the new `blocked` field touches every
  construction site (grep `new User(`).
- The `blocked` field is **expand-only: no V-migration** (this repo's `V*`
  scripts are for indexes/collections only); the Mongo mapper defaults
  absent → `false` (tested).
- `TokenPort.generateToken(email)` gains a `role` parameter — grep ALL
  callers (`UserService` + adapter) before committing.
- The living-spec gate is **package-scoped**: the new admin packages
  (`core/ports/incoming/admin/`, `core/service/` impls,
  `infra/adapter/input/rest/admin/`) are NOT gated — do not add any
  `@spec-complete`. But test changes inside the gated `infra/security`
  package require the new requirements + traces (testing doc: REQ-AUTH-011/
  012 class, one requirement per tested behavior).
- The AUTH-scope throttle runs **before** the use case on login/refresh —
  unchanged (429 still consumes the bucket before a block 403; do not move
  the check).

## Cross-repo note (hard dependency)

The web squad's `/admin` build is **blocked on our OpenAPI**: their
`docs/api-contract.md` is captured verbatim from `/v3/api-docs` (ADR 0010
R1). Story 10.5's OpenAPI completeness is the unblock: the 6 admin paths with
params (`limit`/`cursor`/`q`/`code`), `role` on the 4 auth responses, **403
"Account blocked."**, **self-block 400**, `ownerEmail` nullable, `q` = email
prefix, opaque stable cursor. Undocumented here = stale there.

## Out of scope (do not expand)

Denylist/revocation on block (it is a **registered follow-up** — record it in
the AGENTS.md matrix with the named trigger, and in commit 5's body — same
family as refresh rotation); refresh rotation (pre-existing debt);
branded-domain admin; rate-limit dashboard (Prometheus exists); new cookie
path `/admin` (access cookie `Path=/` covers it); CORS; CSRF header (R2 of
ADR 0010 — Lax holds); DB `role` field; first-user admin; a second role
name; making the Admin component spec-complete (new components never start
gated).

## Commit convention

1. `feat: ADMIN role claim from admin-emails config (additive; legacy tokens = USER) + ADR 0011`
2. `feat: account block/unblock + admin user list (403 on login/refresh; self-block 400)`
3. `feat: admin read-only inspection (user links incl. archived; global code lookup)`
4. `feat: force-archive admin endpoint + blocked write-path check (403)`
5. `docs: epic 10 contract close (OpenAPI, CHANGELOG, AGENTS.md matrix, DoD evidence)`
   — body: denylist/revocation-on-block follow-up (trigger + same family as
   refresh rotation) + EP10 status.

**Definition of done:** DoD checklist fully filled with pasted outputs
(including the 12-step live proof), all gates green, `git log --oneline` of
the 5 commits pasted, working tree clean.
