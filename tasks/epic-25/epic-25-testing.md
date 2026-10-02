# Epic 25 Testing Plan: Production Metrics-Gated Blue-Green Canary

**Target repository:** `daniel-castilho/url-shortener-service` only  
**Status:** Draft; exact policy assertions depend on S0 approval.

## 1. Configuration validation

- Run pinned `promtool check config` against the final scrape configuration.
- Run pinned `promtool check rules` and `promtool test rules` for every new or changed rule.
- Test the color labels, actual blue/green targets, scrape path, authentication configuration, and non-public query API binding without using production credentials.
- CI must be self-contained; it must not contact or depend on a live Prometheus instance.

## 2. Gate unit/self-tests

Use fixtures or a local mock Prometheus HTTP API. Cover at least:

| Scenario | Expected result |
|---|---|
| Candidate color `up == 1`, fresh samples, approved traffic floor met, every approved metric passes | Pass the current stage only |
| 5xx/error threshold exceeded | Fail stage and invoke the approved hold/abort path |
| Latency threshold exceeded | Fail stage and invoke the approved hold/abort path |
| `up == 0`, missing target, empty result, or missing series | Never pass |
| Stale sample, malformed timestamp, or sample predating the current stage | Never pass |
| Zero denominator, `NaN`, infinity, non-numeric value, or invalid JSON | Never pass |
| Minimum request volume not met | Hold only within the approved bounded deadline; then follow the approved terminal action |
| Prometheus HTTP error, authentication failure, timeout, or connection refusal | Never pass; follow the approved bounded failure action |
| Query response contains multiple/unexpected series | Reject or handle only as explicitly specified in S0; never silently aggregate the wrong color |
| Valid `blue`/`green` CLI inputs and invalid/missing arguments | Correct parser behavior; invalid inputs cause no network query or host mutation |

Verify that tests do not contain production secrets and cannot mutate runtime configuration, services, NGINX, or real hosts.

## 3. Deployment-flow tests

Use stubs for Prometheus queries, `sudo`, `systemctl`, NGINX, curl, sleep, and smoke probes. Assert:

- The candidate JAR still passes the full release-artifact validation and the installed bytes match the validated bytes.
- No metrics query happens before the corresponding color/weight has been applied and its observation window has elapsed.
- A passing stage is the only condition that permits the next weight.
- A failing or indeterminate stage never advances traffic; the old color is restored to 100% and the idle color is handled exactly as approved.
- The old color is not stopped before the final 100% stage passes its approved metrics gate.
- Gate failures preserve the existing error naming and cleanup/rollback guarantees.
- Any approved bypass is explicit, audited, and tested; otherwise the parser rejects it.

## 4. Integration tests

- Run an ephemeral, pinned Prometheus instance in CI or a dedicated integration-test profile when feasible.
- Load test-only scrape/rule configuration and synthetic per-color time series; exercise the Prometheus query API and verify the gate's decisions.
- Confirm that blue and green are independent: a healthy old color cannot mask a failing canary color, and metrics from one color cannot satisfy the other's gate.
- Test scrape authentication with disposable CI credentials and prove anonymous scrape/query behavior matches the approved security design.
- Keep this integration isolated from deployment hosts and production data.

## 5. Regression and evidence

- Run `bash -n` for changed scripts, their self-tests, `promtool` checks, the repository monitoring CI job, and the relevant Maven test/full verification commands required by `AGENTS.md`.
- Run existing deployment, artifact-verifier, metrics-freeze, observability, documentation-sync, and documentation-impact checks that the change touches.
- Report exact commands/results; distinguish local tests, CI evidence, and any authorized non-production rehearsal.
- Do not call a fixture-backed self-test proof that Prometheus is deployed or reachable in the actual environment.
