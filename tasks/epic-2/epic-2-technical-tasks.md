# Epic 2 – Technical Tasks

## 2.1 Implement `logSafe(String)` across all of `infra/`
- [x] Locate all `log.warn(` and `log.info(` in `src/main/java/ca/tyny/urlshortener/infra/` (log source: `LoggerFactory` per class — there is no central `LoggingService`).
- [x] Add a private `logSafe(String s) { return s.replace('\n', '_').replace('\r', '_'); }` helper **in every class that logs a client-controlled value** (sanitizer at the sink — lesson 20).
- [x] Replace each client-controlled argument of that `log` with `logSafe(arg)`.
- [x] `DefaultUrlValidator`: log **host + reason** instead of the full URL (Rule 6: never log full destination URLs).
- [x] Confirm `grep -R logSafe src/main/java/ca/tyny/urlshortener/infra` → all client-controlled sinks covered.
- [ ] Run `./mvnw verify` → log-injection gate PASS.
- [ ] Paste the `grep` and `./mvnw verify` outputs into the handoff‑DOD.

## 2.2 Protect against SSRF in shorten
- [x] The validation already exists (`DefaultUrlValidator`: HTTPS-only, DNS resolve + RFC1918/loopback/link-local/metadata blocklist, userinfo rejection, `SsrfProtectionIT` 7 tests). Epic gap: **literal IP coverage**.
- [x] Extend `SsrfProtectionIT` with literal IP cases: `127.0.0.1`, `10.0.0.1`, `172.16.0.1`, `192.168.0.1`, `169.254.169.254`, `[::1]`.
- [x] Add `security.ssrf.blocked.total` metric (repo namespace) via `MetricsPort` → `MicrometerMetricsAdapter`, exported in `/actuator/prometheus`.
- [x] Run `./mvnw test -Dtest='SsrfProtectionIT'` → green.
- [ ] Paste the test output into the handoff‑DOD.

## 2.3 ConfigValidator fail‑fast in the prod profile
- [x] `ProdConfigValidator` already exists (`infra/config/`: null/blank/<32 chars/default → error). Epic gap: **an IT that proves the fail‑fast**.
- [x] Create `ProdConfigValidatorIT`: `prod` profile + default/short secret → boot fails with a clear message; secret ≥ 32 chars → passes.
- [x] Run `./mvnw test -Dtest='ProdConfigValidatorIT'` → green.
- [ ] Paste the test output into the handoff‑DOD.

## 2.4 Add HTTP security headers globally
- [x] Via **native Spring Security** (owner decision): `.headers()` in `SecurityConfig` — `contentTypeOptions`, `frameOptions deny`, `referrerPolicy strict-origin-when-cross-origin` (no manual `OncePerRequestFilter`).
- [x] `SecurityHeadersIT` test: RestAssured asserts the 3 headers on representative routes (redirect, API, actuator liveness).
- [x] Run `./mvnw test -Dtest='SecurityHeadersIT'` → green.
- [ ] Paste the test output and the `curl -I` (dev server) into the handoff‑DOD.

## 2.5 Configure the OWASP Dependency‑Check gate
- [x] Add `org.owasp:dependency-check-maven` **12.2.2** (12.x pin — keyless on 13.x is broken, upstream #8715; dependabot PRs receive no secrets; dargent pattern) to `pom.xml` (approved under Rule 9).
- [x] `failBuildOnCVSS=7`; `suppressionFile=owasp-suppressions.xml` versioned **empty** (each future entry: rationale + review date); data dir `~/.m2/dependency-check-data` with cache in CI; `nvdApiDelay=6000`.
- [x] CI job with `NVD_API_KEY` passed via `-DnvdApiKey` **only when the secret exists** (keyless = throttled-but-working); documented retry (tool error fails the job — never silent-pass).
- [x] Run `./mvnw dependency-check:check -DskipTests` → green (no known CVE ≥ 7).
- [ ] Paste the gate and `dependency:tree` outputs into the handoff‑DOD.

## 2.6 Security self-audit (quick checklist)
- [x] Create `scripts/check-security.sh` (+ `--self-test`, standard for the existing gates) that verifies:
    - presence of `logSafe` in the client-controlled sinks of `infra/`
    - the 3 security headers declared in `SecurityConfig`
    - JWT secret validation in `ProdConfigValidator` (length + default)
    - absence of hardcoded internal IPs outside tests/blocking
- [x] Confirm “PASS” output or list the missing items.
- [x] Integrate as a GitHub Actions job `security-check` that blocks the merge if it fails.

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
