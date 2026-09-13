# Epic 2 – Testing Strategy

## 2.1 Log unit tests (logSafe)
- **Objective:** Confirm that each `log.warn`/`info` in `infra/` uses the `logSafe` helper.
- **Action:** 
  - `grep -R "logSafe" src/main/java/infra` → must cover 100 % of client-controlled logs.
  - `./mvnw test -Dtest='*LogSafeTest'` (unit test that injects strings with `\n`/`\r` and asserts the log does not contain them).
- **Acceptance criterion:** Green tests; 100 % grep coverage.

## 2.2 SSRF test (SSRFIT)
- **Objective:** Validate that shortening endpoints block internal IPs.
- **Action:** 
  - `./mvnw test -Dtest=SSRFIT` → green.
  - Check the test output: 400 status code and `invalid_request` message.
- **Acceptance criterion:** Green test and output pasted into the handoff‑DOD.

## 2.3 ConfigValidator test (ConfigValidatorIT)
- **Objective:** Ensure the `prod` profile refuses a short/default secret.
- **Action:** 
  - `./mvnw test -Dtest=ConfigValidatorIT` → green.
  - Output: IllegalStateException with “invalid JWT secret” message.
- **Acceptance criterion:** Green test and pasted output.

## 2.4 Security headers test (SecurityHeadersIT)
- **Objective:** Confirm that the global filter injects the 3 HTTP headers.
- **Action:** 
  - `./mvnw test -Dtest=SecurityHeadersIT` → green.
  - Pasted `curl -I` output (e.g.: `X-Content-Type-Options: nosniff`, etc.).
- **Acceptance criterion:** Green test + pasted output.

## 2.5 OWASP Dependency‑Check gate test
- **Objective:** Validate that the build fails when a new vulnerable dependency is introduced.
- **Action:** 
  - Temporarily add a test artifact with a known vulnerability (e.g.: `junit:junit:2.13` with a vuln flag in the pom).
  - Run `./mvnw verify` → build fails with an OWASP message.
  - Remove the artifact and confirm `./mvnw verify` is green.
- **Acceptance criterion:** Build fails with the new dep; build green without it.

## 2.6 CI integration (GitHub Actions)
- **Objective:** Block the merge if any security checkpoint fails.
- **Action:** 
  - Add workflow `security.yml` that runs:
    - `./mvnw verify` (includes the OWASP gate).
    - `scripts/check-security.sh` (verifies logSafe, SSRF config, JWT secret, headers).
  - Failure in any job → the pull request cannot be merged.
- **Acceptance criterion:** Red pipeline blocks the merge; green pipeline allows the merge.

## 2.7 Observability integration (EP3)
- **Objective:** Ensure security metrics are collected.
- **Action:** 
  - Verify that counters such as `security.ssrf.blocked.total` and `security.headers.applied.total` are incremented in tests.
  - Confirm the series appear in `/actuator/prometheus`.
- **Acceptance criterion:** Metrics visible and tests green.

--- 

**Epic 2 completion checklist:**

- [ ] `logSafe` in 100 % of `infra` logs (grep + CI green)
- [ ] `SSRFIT` → 400 for internal IPs
- [ ] `ConfigValidatorIT` → fails in prod with a default secret
- [ ] `SecurityHeadersIT` → headers present in the `curl -I` response
- [ ] `./mvnw verify` green with `owasp-dependency-check` green
- [ ] `scripts/check-security.sh` → PASS
- [ ] Full `./mvnw verify` green (unit + IT + gates)

*By checking all items above, Epic 2 is **complete** and the next epic (EP3 – Observable) can start.*
