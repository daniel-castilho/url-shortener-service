# Epic 7 – Testing Strategy [grounded]

Principle: test the **failure contract**, not the Resilience4j implementation. The happy path was already Epic 4/5/6.

## 7.1 Failure contract
- **Goal:** Reviewable decisions; operations do not depend on the author's memory.
- **Action:** `docs/reliability.md` + ADR 0005/0006; `git log` pasted.
- **Acceptance criterion:** complete matrix; explicit target RPO/RTO; rejected options written down (replica set out of scope, exactly-once out of scope).

## 7.2 Dependency isolation
- **Goal:** The redirect path has a determined behaviour when Mongo or Redis disappears.
- **Action:**
  - Inventory + ITs `RedirectMongoFailureIT` / `RedirectRedisFailureIT` (flexible names if the class already exists).
  - Testcontainers: stop the container in the test (`mongodb.stop()` / `redis.stop()`) instead of a generic mock — real failure.
  - CB: assert the transition via metric/log.
- **Acceptance criterion:** green tests; HTTP status matching the 7.1 matrix; Surefire output pasted.
- **Do not:** mock `UrlRepositoryPort` to fake the CB — that does not prove the adapter.

## 7.3 Shutdown and health
- **Goal:** SIGTERM does not cut in-flight requests; the readiness probe is the take-out-of-rotation signal.
- **Action:**
  - Existing script `verify-graceful-shutdown.sh` (it is the operational acceptance test).
  - `HealthProbeSemanticsIT` if indicators need to change.
  - Manual docker-stop experiment documented in the DoD (no need for it in `mvn verify` if too destructive; if included, isolate by profile).
- **Acceptance criterion:** in-flight 302; readiness reflects the critical dependency; liveness does not mirror a Redis blip.

## 7.4 Analytics under failure
- **Goal:** observable at-least-once; isolated poison.
- **Action:** extend the already-existing pipeline ITs (`ClickPipelineIT`, `RedisClickEventQueue*`).
- **Asserts:**
  - N published, worker restart → persisted ≥ N (never < N without an explicit loss flag).
  - Invalid event → consumer lag does not grow unboundedly; subsequent valid events pass through.
  - Enqueue on the redirect does not throw if the Redis Stream is down (fail-open already tested).
- **Acceptance criterion:** Surefire pasted; the ADR 0006 contract cited in the test (a one-line comment in the DoD suffices).

## 7.5 DR + load under failure
- **Goal:** the backup script is real; degradation has a number.
- **Action:**
  - Restore drill on isolated infra (never on the "good" dev volume).
  - Short k6 `redirect.js` + `docker stop` Redis; then Mongo.
  - Compare the status distribution with the 7.1 matrix — divergence = bug or wrong doc; fix one of the two.
- **Acceptance criterion:** 302 after restoring the seeds; k6 summaries pasted; playbooks in the runbook with the commands used.
- **Do not:** require p95 < 200ms **with Mongo down**. The latency SLO is a happy-path SLO (EP5). Here the acceptance is "degradation = matrix".

## 7.6 Retro-compatible integration (gates)
- [ ] Full `./mvnw verify` → green (JaCoCo floors, SpotBugs, OWASP, boundaries, doc-sync, metrics-frozen).
- [ ] `promtool` + `amtool` green.
- [ ] No new series without passing the freeze gate (if 7.2/7.4 create a poison/drop metric, update `scripts/check-metrics-frozen.sh` **in the same PR**).

---

**Epic 7 completion checklist:**

- [ ] Matrix + ADRs 0005/0006
- [ ] Mongo/Redis failure ITs on the redirect
- [ ] Shutdown + probes evidenced
- [ ] Pipeline: restart and poison
- [ ] Restore drill + two injection k6 runs
- [ ] `./mvnw verify` green
- [ ] Evidence pasted in `epic-7-dod.md`

*When all items above are checked, Epic 7 is **complete**.*