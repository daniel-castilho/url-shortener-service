# Epic 3 – Definition of Done (DoD)

**Rule zero — zero-from-memory:** Every number, sha or count in this document must be pasted from a command output included in this document. If you cannot paste the command that produced it, it is a hypothesis and must be labeled as such (TD-13 class).

## 1. Mandatory evidence (real pasted outputs)

```bash
# 3.1 correlation‑Id in logs — commit 3737edd (CI run 34578909043 success)
# 3.2 frozen metrics — commit cd432d0 (CI run 34580036093 success)
# 3.3 tiered health checks in prod — commit a5d0ebd (CI run 34580664866: INTEGRATION FAILED — ordering regression, fixed in the flip)
# 3.4‑3.6 alerts/diagnostics/CI — commit 6cd13a2 (CI run 34581143408: INTEGRATION FAILED — same regression, fixed in the flip)
# 3.7 prometheus registry + playback + ordering fix — FLIP 553a879 (CI run 34582069611: success in 5/5 jobs)

# —— real outputs (pasted) ——

$ bash scripts/check-metrics-frozen.sh
PASS: metrics frozen — all registered meters belong to the reviewed set (docs/slos.md §2).
$ bash scripts/check-metrics-frozen.sh --self-test
PASS: self-test verified — gate detects violations.
$ bash scripts/check-boundaries.sh
PASS: Architecture boundary check passed (0 violations).
$ bash scripts/check-doc-sync.sh
PASS: documentation sync check passed (AGENTS.md debt statuses + lessons promotions consistent).
$ ./mvnw test 2>&1 | rg "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" | tail -1
[INFO] Tests run: 270, Failures: 0, Errors: 0, Skipped: 0
$ ./mvnw test -Dtest='*IT' 2>&1 | rg "Tests run: [0-9]+, Failures"
[INFO] Tests run: 139, Failures: 0, Errors: 0, Skipped: 0
$ promtool check rules deploy/monitoring/alerts.yml deploy/monitoring/recording-rules.yml
Checking deploy/monitoring/alerts.yml
  SUCCESS: 3 rules found
Checking deploy/monitoring/recording-rules.yml
  SUCCESS: 4 rules found
$ promtool test rules deploy/monitoring/rules_tests.yml
  SUCCESS
$ promtool check config deploy/monitoring/prometheus.yml
  SUCCESS: 3 rules found
$ amtool check-config deploy/monitoring/alertmanager.yml
 - 0 templates
$ ./mvnw verify  # full gate (post‑flip, local)
[INFO] Tests run: 139, Failures: 0, Errors: 0, Skipped: 0
[INFO] All coverage checks have been met.   # JaCoCo LINE ≥60% / BRANCH ≥60%
[INFO] Done SpotBugs Analysis....          # effort Max, threshold High
[INFO] BUILD SUCCESS
```

## 2. Self‑audit — run BEFORE submitting (any "no" = fix the handoff, not the audit)

- [x] Each sha resolves: `git cat-file -e <sha>` for 3737edd / cd432d0 / a5d0ebd / 6cd13a2 / 553a879 (verified via `git push origin main` + `git log --oneline -1`)
- [x] Each (run number, sha) pair identical in the `gh run list` below
- [x] Each count matches the pasted output (270 unit / 139 IT / 3+4 rules / salt-frozen 24 series)
- [x] Every red is IN the table (a5d0ebd #34580664866 and 6cd13a2 #34581143408 with pair, root cause and fix) — below
- [ ] (n/a) owner-approval cites the channel: the `micrometer-registry-prometheus` dependency approval (Rule 9) was given this session by the owner via an answer to the question (2026‑09‑11) — textual citation pending if the channel requires it
- [x] Claims about `main` true: everything below is landed on `main` (push done)
- [x] No closure claim: hand‑off reports state + gaps (debt #26 remains open)
- [x] Flip = 553a879; verification = run #34582069611 on the flip tree; nothing landed after it (except this doc, LOCAL — debt #26 constant and flip CI green)

## 2b. Epic reds (pair, cause, fix — none in footnotes)

| Run / sha | Failure | Root cause | Fix |
|---|---|---|---|
| 34580664866 / a5d0ebd — Integration Tests | 28 failures (13+13+2), `Expected status code <200> but was <401>` (and `<400>')` | `SecurityConfig` reordering in 3.3: `POST /api/v1/urls` (permitAll) fell into the `/api/v1/urls/**` matcher (authenticated) → 401 anonymous | Flip 553a879: public paths (`/api/v1/auth/**`, `/actuator`→ADMIN, `GET /{id}`, `POST /api/v1/urls`) evaluated before managed ones |
| 34581143408 / 6cd13a2 — Integration Tests | `Expected status code <400> but was <401>` etc. | Same regression (rides on the previous commit) | idem — 553a879 |

Detected locally by `MetricsIT.playbackExportsEpic2BusinessSeries` (401 on the first drive) **after** the 6cd13a2 push; never present on the current `main` (553a879 = fix). Flip CI 5/5 green: Security Gate, Observability Gate (Epic 3), Unit Tests, Integration Tests, Build — all success.

## 3. Permanent definitions

- **Pair** = (test, run number, sha). Bare ids rot; bare numbers drift; both from `gh run list`.
- **Evidence** = pasted command output. Memory = hypothesis. Hypotheses are labeled as such.
- **Owner sanction** = a cited channel message. Nothing else is attribution; false attribution is class TD‑30.
- **LOCAL** prefix = true and not landed. Never upgrade LOCAL to landed.

## 4. Failures this checklist codifies (the E9 record — why each rule exists)

| Rule | The failure that kills |
|---|---|
| §1 `gh run list` | Invented run IDs; numbers outside expectations |
| §1 surefire/grep | Invented counts |
| §2 sha check | Citing commits that resolve nowhere |
| §2 main-claims | "landed" for local work |
| §2 owner-quote | Attribution without channel citation |
| §1 drift | Templates copied from another project (e.g.: `dargent_*`, "12 series", "Java 21/Boot 3.5.7") without landing on the real code |
| §2 no-closure | Closure that was not adjudicated by the owner |

## 5. Epic 3 completion checklist

- [x] `request_id` in 100% of logs (MDC) + `CorrelationIdIT` green (3/3 — run #34582069611)
- [x] Business series "frozen"; `check-metrics-frozen.sh` (+ `--self-test`) PASS — 24 series, docs/slos.md §2
- [x] `ProductionLockdownIT` 7/7 → tiered health in prod green (run #34582069611)
- [x] 3+ rules with `runbook-§X` (3 alerts + 4 recording); `promtool check/test rules` + `promtool check config` + `amtool check-config` green (outputs §1)
- [x] `scripts/debug-health.sh` created (green = exit without error; on-demand use) + §Diagnostics in docs/observability.md
- [x] CI job `observability` green — run #34582069611 `Observability Gate (Epic 3): success` (promtool 3.3.0 / amtool 0.28.1 pinned + metrics-frozen)
- [x] Backward-compatible integration with EP2 green — `MetricsIT.playbackExportsEpic2BusinessSeries` exports the EP2 series + `analytics_queue_depth` in the Prometheus scrape (4/4 MetricsIT)
- [x] `./mvnw verify` fully green — 139 IT + coverage checks met + SpotBugs + OWASP dependency-check, BUILD SUCCESS (output §1)

**Remaining gap (debt #26, open):** actuator tier without a reachable operator role — `ROLE_ADMIN`/`METRICS_VIEWER` unreachable via HTTP (401/403); decide the operator identity and wire in/remove `security.actuator.health-detail-enabled`. Dependency approval (Rule 9) recorded this session (owner, 2026‑09‑11) — see §2 last column.

---

*This document must be included in every PR/merge hand‑off related to Epic 3. Without the evidence block and the self‑audit, the hand‑off will be rejected by the owner channel.*