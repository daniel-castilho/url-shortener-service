# Release Engineering — from validated code to production

**Scope (Epic 8):** make the path **tag → gates → GitHub Release → deploy → post-deploy
verification** predictable, automated and provable. This document is the contract; ADR 0007
(blue-green bare metal) and ADR 0008 (artifact promotion) record the two structural decisions.

Anchors: `docs/release-runbook.md` (operator procedures), `docs/slos.md` (what the canary
watches), `docs/adr/0007`, `docs/adr/0008`, `scripts/deploy.sh`, `scripts/smoke.sh`,
`scripts/rollback.sh`, `scripts/ci-restore-drill.sh`, `.github/workflows/release.yml`.

---

## 1. The release flow (step by step)

```
            (operator)                    (CI — release.yml, trigger: tag v*)                (operator, on the host)
  1. promote [Unreleased] →  ┌──────────────────────────────────────────────┐   8. scripts/deploy.sh vX.Y.Z
     [X.Y.Z] in CHANGELOG.md │ 2. gates        ./mvnw verify -Drevision=…    │      ├─ download jar from Release
     + annotated tag         │                bash gates + self-tests         │      │  (sha256 verified)
     git tag -a vX.Y.Z       │                promtool/amtool                │      ├─ stage idle color (graceful)
     git push origin vX.Y.Z  │                CHANGELOG gate (Unreleased must │      ├─ wait readiness (budget 90s)
                             │                be empty at the tag)           │      ├─ canary 10/30/100
                             │                → uploads THE jar (single      │      │   render → nginx -t → reload
                             │                  build, promoted verbatim)    │      │   → smoke after each bump
                             ├──────────────────────────────────────────────┤      ├─ 100%: last-deploy.txt,
                             │ 3. k6-gate       load-tests/mixed.js on the    │      │   drain+stop old color
                             │ 4. runtime-smoke scripts/smoke.sh + graceful- │      └─ one-liner DEPLOY OK
                             │                  shutdown drain                 │   9. post-deploy verification
                             │ 5. restore-drill ci-restore-drill.sh         │      (smoke + 10 min burn-rate watch
                             │                  (RTO ≤ budget)               │       + schema.migrations.* logs)
                             ├──────────────────────────────────────────────┤
                             │ 6. release       image (non-root + Trivy      │
                             │                  HIGH/CRITICAL + CycloneDX    │
                             │                  SBOM) + gh release create    │
                             │                  (jar + SHA256SUMS + SBOM)   │
                             └──────────────────────────────────────────────┘
```

Everything is **fail-closed**: a failed gate means no Release, and no Release means no deploy
(`deploy.sh` hard precondition). A failed step *inside* `deploy.sh` aborts to the old color at
100% (ADR 0007).

## 2. Step × executor × failure × behaviour matrix

| # | Step | Executor | What can fail | Behaviour (fail-closed) |
|---|------|----------|---------------|--------------------------|
| 1 | CHANGELOG promotion + annotated tag | Operator | Unreleased not promoted; tag on wrong commit | No — operator discipline; step 2's CHANGELOG gate catches it (no Release) |
| 2 | `gates` job: full `verify -Drevision=<semver>` + bash gates + self-tests + promtool/amtool + CHANGELOG gate | CI | Any test/gate; Unreleased non-empty at the tag; jar build | Workflow fails → **no Release** → deploy precondition blocks deploy |
| 3 | `k6-gate`: `mixed.js` against the promoted jar (SLO thresholds in-script) | CI | p95 ≥ 200ms or `http_req_failed` ≥ 0.1% (k6 exit ≠ 0) | Workflow fails → no Release |
| 4 | `runtime-smoke`: `smoke.sh` (8 business legs) + graceful-shutdown drain | CI | Any leg; connection-refused during drain | Workflow fails → no Release |
| 5 | `restore-drill`: isolated compose, backup → drop → restore `--verify`, RTO ≤ 300s | CI | Row-count divergence; RTO over budget | Workflow fails → no Release |
| 6 | `release` job: image non-root gate + Trivy HIGH/CRITICAL + SBOM + `gh release create` | CI | Image runs as root; CVE ≥ HIGH; asset upload | Workflow fails → **no Release** (assets never partially published — job is the last step) |
| 7 | (nothing — Release exists) | — | — | `deploy.sh` refuses tags without a Release |
| 8 | `deploy.sh <tag>`: sha256-verified download → stage idle color → readiness (budget 90s) → canary 10/30/100 (nginx -t + reload + smoke per bump) → cutover | Operator (host) | Download/sha256; readiness timeout; `nginx -t`; smoke leg; systemd failure | **Abort:** old color rendered back to 100% + reload, new color drained + stopped, exit ≠ 0 **naming the step** |
| 9 | Post-deploy verification: smoke + 10 min SLO/burn-rate watch + `schema.migrations.*` in new color logs | Operator | Burn-rate alert; unexpected migration | Operator decision: watch or `scripts/rollback.sh` (one command, no rebuild) |
| 10 | Rollback (`scripts/rollback.sh`) | Operator | Previous color won't start | Fallback = manual runbook §2 (named fallback) |

## 3. Identity: revision, tag, artifact

- The pom version is `${revision}` (default `0.0.1-SNAPSHOT` locally); the release build passes
  `-Drevision=<semver>` so the jar is `target/url-shortener-service-<semver>.jar` (flatten-maven,
  `resolveCiFriendliesOnly` — story 8.2).
- `## [Unreleased]` in `CHANGELOG.md` must be **empty at the tag commit** (the promotion happened);
  the CHANGELOG gate enforces keep-a-changelog discipline — a tag with draft entries is refused.
- The GitHub Release is the **only** promotion channel: jar + `SHA256SUMS` + CycloneDX SBOM.
  `deploy.sh` resolves the jar by semver tag, never "latest build" (ADR 0008).

## 4. Deploy topology (blue-green) — summary

- `url-shortener-blue.service` :8080, jar `/opt/url-shortener/blue/url-shortener.jar`;
  `url-shortener-green.service` :8081, jar `/opt/url-shortener/green/url-shortener.jar`
  (hardening identical to the scale-out template, which remains for ≥ 3 instances).
- nginx `deploy/proxy/nginx.conf` (template, human-owned) → `deploy/runtime/nginx.conf`
  (rendered, gitignored) — the two `server 127.0.0.1:808x` lines carry the weights.
- `deploy/runtime/last-deploy.txt` (`previous/current/tag/at`) is written **before the first
  weight flip** — that is what makes rollback a single command.
- Full decision record: ADR 0007.

## 5. What the canary watches

During each 30s dwell the operator watches (Grafana dashboards, `docs/slos.md` series — all in
the frozen metrics set):

- `http_server_requests` (rate + status) — the redirect/shorten error ratio,
- `redirect.latency` / `shorten.latency` (p95 within the SLO dashboard),
- `resilience4j.circuitbreaker.state` — neither color trips a breaker during the flip,
- `analytics.queue.depth` — the stream keeps draining on the new color,
- `schema.migrations.applied.total` / `schema.migrations.failed.total` — the new color's
  migrator ran clean at boot (logged before readiness turns green).

The smoke probe after each bump is the automated floor; the dashboards are the human layer.

## 6. Backup & restore as a release gate

The `restore-drill` job (script `scripts/ci-restore-drill.sh`) boots an isolated compose stack,
seeds data through the API, takes a manifest backup, drops `short_urls`, restores with
`--verify` (row counts re-compared), and asserts the RPO/RTO contract (pre-backup codes 302,
post-backup codes 404, wall-clock RTO ≤ `RTO_BUDGET_S=300`). "Backup without a verified
restore is hope" — the drill is the proof, and its manifests are the deliverable.

## 7. Runbook

Operator procedures (deploy, rollback, pre-release checklist, post-deploy verification,
incident "deploy failed") live in `docs/release-runbook.md` §1/§2/§7 — this document is the
contract, the runbook is the hands-on procedure.
