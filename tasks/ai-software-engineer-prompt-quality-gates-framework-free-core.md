# AI Software Engineer Prompt — Quality Gates & Framework-Free Core
## Complete the framework-free `core/`, make the boundary gate green & wire quality gates

**Status:** ready for implementation from current `main` (`9691b31`).
**Priority:** P0 — foundation integrity. Second epic.
**Target:** make the framework-free `core/` claim true, turn the boundary gate green/enforced, and add a
real quality floor (JaCoCo + SpotBugs) — without touching the identity model or any API contract.

You implement the complete **Quality Gates & Framework-Free Core** epic. You will largely be *finishing*
work the first epic started: the `UserService` DIP leak and the CI command gate are already done; the
remaining gap is that `core/` still contains Spring/Lombok annotations, which makes the boundary check
fail, and there is no coverage/static-analysis gate.

---

## Sources of truth — read in this order

1. `AGENTS.md` (rules 1, 8, 10; Known Technical Debt items 2 and 12)
2. `pom.xml` and `src/main/resources/application.yaml`
3. `docs/coding-standards.md`
4. `docs/testing-playbook.md`
5. `docs/data-model-decisions.md` and `docs/lessons.md`
6. `tasks/quality-gates-framework-free-core-spec.md`
7. `tasks/quality-gates-framework-free-core-backlog.md`
8. `tasks/quality-gates-framework-free-core-implementation-sequence.md`
9. Current production code and colocated `*Test` / `*IT` classes

If documentation disagrees with executable configuration, stop, report the mismatch and resolve it in
the same change set. Do not rely on an analysis file that is not tracked in the repository.

---

## Goal

The architecture boundary gate currently fails and feels "present but red" — it does not protect
anything in practice because `core/` still imports Spring/Lombok. And beyond tests there is no quality
floor. This epic makes the boundary gate **real and green**, makes `core/` genuinely framework-free, and
adds **JaCoCo** coverage and **SpotBugs** static-analysis gates so the project has a stop-the-line
quality standard.

The epic closes these gaps:

- five `core/` classes still carry `@Component`/`@Service`/`@RequiredArgsConstructor` (debt item 2),
  which breaks `scripts/check-boundaries.sh` and the CI boundary step;
- `core/` is not truly framework-free despite the architecture claim;
- no coverage floor (JaCoCo) and no static analysis (SpotBugs) are wired into the build or CI.

---

## Already done in the first epic — verify, do not re-work

- CI command gate uses `ca.tyny` and `mvn verify` (failsafe).
- Collision semantics: `ShortCodeCollisionException` + narrowed retry.
- `IndexMigration` ensures the `userId` index; `auto-index-creation: false`.
- `UserService` DIP: depends on `AuthenticationPort`/`TokenPort`/`PasswordEncoderPort` /
  `UserRepositoryPort`/`IdGeneratorPort`; wired as `@Bean` in `ServiceConfig`.
- English-only in touched files.

Your job is the **remaining** five classes and the missing quality gates.

> **Reality check:** the CI "Architecture boundary check" step is currently **red** because five core
> classes import Spring/Lombok. That is the central thing this epic fixes. Do **not** weaken the gate
> to make it green — fix `core/` instead.

---

## Locked technical decisions

1. **`core/` is annotation-free.** No `@Component`, `@Service`, `@Repository`, `@Configuration`,
   `@RequiredArgsConstructor`, or any Spring/Lombok/Mongo/Redis/jjwt/Micrometer type.
2. **Wiring via `@Bean` in `infra/config`.** The five affected classes are registered as beans
   (extending `ServiceConfig` or a dedicated `DomainConfig`), using explicit constructor injection.
   This is the pattern already used for `UrlShortenerService`/`UserService`/`Base62CodeGenerator`.
3. **Boundary gate green and enforced.** `scripts/check-boundaries.sh` (updated to also scan `lombok`)
   and the CI step both exit 0. Do not exclude files or weaken the expression.
4. **Quality gates:** add **JaCoCo** (0.8.12, `LINE ≥ 0.60`, `BRANCH ≥ 0.40`) and **SpotBugs** (4.8.6,
   `effort Max`, `threshold High`) to `mvn verify` and CI.
5. **No new public API or identity change.** Only wiring, annotation removal and gate additions.
6. **No Maven coordinate beyond JaCoCo/SpotBugs** without explicit approval.

---

## Non-negotiable engineering rules

- Keep the framework-free `core/` rule enforced by the boundary gate; never weaken it.
- Replace Lombok with explicit constructors; never re-introduce Lombok in `core/`.
- Preserve behaviour — removing an annotation must not change what a class does, only how it is wired.
- The Spring context must still start after the change (verify with a context smoke test).
- Quality gates must actually gate: a coverage drop or SpotBugs finding fails the build.
- Do not add broad suppressions; justify any SpotBugs exclusion.
- English only in code, comments, logs, tests and docs.
- Do not push unless the human explicitly asks.
- Do not expand into analytics, TTL, rate-limit-on-redirect, `$inc` quota, SSRF, actuator lockdown,
  tracing/SLOs, or API redesign.

---

## Required behaviour summary

### `core` classes (5) — remove annotations & Lombok, add explicit constructors

`CompositeUrlIdGenerator`, `RandomUrlIdStrategy`, `VanityUrlIdStrategy`, `QuotaService`,
`ReservedWordsValidator`.

### `infra/config` — add the beans

```java
@Bean public ReservedWordsValidator reservedWordsValidator() { return new ReservedWordsValidator(); }
@Bean public CompositeUrlIdGenerator compositeUrlIdGenerator(List<UrlIdGenerationStrategy> s) { return new CompositeUrlIdGenerator(s); }
@Bean public RandomUrlIdStrategy randomUrlIdStrategy(IdGeneratorPort idGen) { return new RandomUrlIdStrategy(idGen); }
@Bean public VanityUrlIdStrategy vanityUrlIdStrategy(UserRepositoryPort userRepo, UrlRepositoryPort urlRepo) { return new VanityUrlIdStrategy(userRepo, urlRepo); }
@Bean public QuotaService quotaService(UserRepositoryPort userRepo) { return new QuotaService(userRepo); }
```

Keep `UrlShortenerService` wiring intact and resolving from these beans.

### Boundary gate

`scripts/check-boundaries.sh` updated to also scan `lombok`; it and the CI step must exit 0.

### Quality gates

JaCoCo `check` (LINE ≥ 0.60, BRANCH ≥ 0.40) and SpotBugs `check` (effort Max, threshold High) wired to
`mvn verify` and CI.

---

## Scope exclusions

Do not implement in this epic: analytics persistence, link expiry (TTL), rate limit on the redirect
path, `$inc`-based counters, SSRF/URL hardening, actuator/Swagger lockdown, tracing/OpenTelemetry/SLOs,
API redesign/v2, or changing the identity model.

---

## Definition of Done

### Framework-free core
- [ ] No `org.springframework`, `lombok`, `org.mongodb`, `org.redisson`, `io.jsonwebtoken` or
      `io.micrometer` import/annotation in `core/`.
- [ ] All five previously-annotated classes have explicit constructors and are registered as `@Bean`
      in `infra/config`.
- [ ] The Spring context starts with no bean-definition error.

### Boundary gate
- [ ] `scripts/check-boundaries.sh` (updated for `lombok`) exits 0.
- [ ] The CI "Architecture boundary check" step passes.
- [ ] An intentionally-injected `@Component`/`lombok` import in `core/` makes it fail.

### Quality gates
- [ ] JaCoCo enforces the coverage floor via `mvn verify`.
- [ ] SpotBugs runs via `mvn verify` and fails on configured findings.
- [ ] CI runs both gates and fails on regression.

### Verification & delivery
- [ ] `mvn test`, `mvn verify` and `bash scripts/check-boundaries.sh` all pass.
- [ ] `AGENTS.md` clears debt item 2 and closes item 12; `docs/coding-standards.md`,
      `docs/testing-playbook.md`, `README.md` and `docs/lessons.md` are synchronized.

Start at **Step 0** of `quality-gates-framework-free-core-implementation-sequence.md`. Stop immediately
if the baseline is red in an unexpected way, a locked decision cannot be implemented with the approved
dependency graph, or repository state contradicts the specification.
