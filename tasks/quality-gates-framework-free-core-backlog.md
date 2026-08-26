# Quality Gates & Framework-Free Core — Backlog
## Complete the framework-free `core/`, make the boundary gate green & wire quality gates

**Priority:** P0 — foundation integrity. Second epic.
**All stories:** Must.
**Companions:** `quality-gates-framework-free-core-spec.md` · `quality-gates-framework-free-core-implementation-sequence.md`

**Execution status:** ready from `main` (`9691b31`). Items already landed in the first epic's
corrective-fixes commit are marked **done** below so nothing is duplicated.

---

## Epic outcome

`core/` is genuinely framework-free (no Spring, Lombok, Mongo, Redis, jjwt or Micrometer), the
architecture boundary gate is **green and enforced** in CI, and the project has a real quality floor
(JaCoCo coverage + SpotBugs static analysis) that stops the line on regressions.

---

## Story map

```text
ALREADY DONE (from the first epic / corrective-fixes commit 9691b31)
D0  CI command gate (failsafe + mvn verify)
D1  Collision semantics (ShortCodeCollisionException + narrowed retry)
D2  Index migration (userId index, auto-index-creation off)
D3  UserService DIP (AuthenticationPort/TokenPort/PasswordEncoderPort; @Bean wiring)
D4  English-only in touched files

REMAINING
Q1  Remove Spring/Lombok annotations from five core classes
Q2  Register those classes as @Bean in infra/config
Q3  Make the boundary gate green (incl. lombok scan) and enforced in CI
Q4  Wire JaCoCo coverage gate
Q5  Wire SpotBugs static-analysis gate
Q6  CI steps for the quality gates; doc sync
```

---

## Already done (do not re-do; verify only)

- **D0 — CI command gate**: CI runs `mvn verify -DfailIfNoTests=false` for the integration suite and
  `mvn clean package` for the build; the boundary-check grep uses `ca.tyny`.
- **D1 — Collision semantics**: `ShortCodeCollisionException` exists; the retry in `UrlShortenerService`
  catches only the collision signal; `MongoUrlRepository.save` distinguishes generated-code collision
  from alias conflict.
- **D2 — Index management**: `IndexMigration` drops `originalUrl_1` and ensures the `userId` index;
  `auto-index-creation: false`.
- **D3 — UserService DIP**: `UserService` no longer imports `infra`; it depends on
  `UserRepositoryPort`, `PasswordEncoderPort`, `TokenPort`, `AuthenticationPort`, `IdGeneratorPort`;
  `ServiceConfig.userService(...)` wires it via `@Bean`.
- **D4 — English-only**: touched files' comments/logs are English.

> **Verification (not work):** run `mvn test` and confirm the boundary-check CI step is the only thing
> still failing — the five annotated core classes (see Q1).

---

## Q1 — Remove Spring/Lombok annotations from five core classes

**Goal:** `core/` carries no framework or annotation-processor dependency.

### Work

- Remove `@Component`/`@Service`/`@RequiredArgsConstructor` and their imports from:
  - `core/idgeneration/CompositeUrlIdGenerator`
  - `core/idgeneration/RandomUrlIdStrategy`
  - `core/idgeneration/VanityUrlIdStrategy`
  - `core/service/QuotaService`
  - `core/validation/ReservedWordsValidator`
- Replace Lombok `@RequiredArgsConstructor` with an **explicit constructor** in each.
- Ensure no `import lombok...` remains in `core/`.

### Acceptance

- [ ] No `org.springframework`, `lombok`, `org.mongodb`, `org.redisson`, `io.jsonwebtoken` or
      `io.micrometer` import in `core/`.
- [ ] Each class has an explicit constructor (no Lombok).

---

## Q2 — Register the five classes as `@Bean` in `infra/config`

**Goal:** preserve Spring wiring after removing the annotations.

### Work

- Add `@Bean` methods for `ReservedWordsValidator`, `CompositeUrlIdGenerator`, `RandomUrlIdStrategy`,
  `VanityUrlIdStrategy` and `QuotaService` in `infra/config` (extend `ServiceConfig` or add a
  `DomainConfig`), using explicit constructor injection.
- Keep the `UrlShortenerService` `@Bean` wiring resolving from the newly-explicit beans.

### Acceptance

- [ ] The application context starts with no `NoSuchBeanDefinitionException`.
- [ ] All five beans resolve with their concrete constructor dependencies.
- [ ] No class in `core/` is component-scanned (no `@Component`/`@Service` anywhere).

---

## Q3 — Make the boundary gate green and enforced

**Goal:** `scripts/check-boundaries.sh` and the CI boundary step both pass.

### Work

- Add `import lombok` (and a Lombok-annotation check) to `scripts/check-boundaries.sh`.
- Confirm the CI "Architecture boundary check" step uses the same expression (or calls the script).
- Run the check; it must exit 0.

### Acceptance

- [ ] `scripts/check-boundaries.sh` exits 0.
- [ ] The CI boundary step passes.
- [ ] A deliberately-injected `@Component`/`lombok` import in `core/` makes the check fail.

---

## Q4 — Wire the JaCoCo coverage gate

**Goal:** a measurable coverage floor stops regressions.

### Work

- Add `jacoco-maven-plugin` (0.8.12) to `pom.xml` with `prepare-agent` + a `check` rule at `verify`.
- Set a realistic floor (e.g. `LINE` ≥ 0.60, `BRANCH` ≥ 0.40) — adjust only with a documented reason.
- Confirm coverage is measured in the fast/test execution and the gate runs via `mvn verify`.

### Acceptance

- [ ] `mvn verify` enforces the coverage floor.
- [ ] A test removal that drops coverage below the floor fails the build.
- [ ] Floor values are documented and justified in coding-standards.

---

## Q5 — Wire the SpotBugs static-analysis gate

**Goal:** catch latent bugs (unclosed resources, nullability, correctness) at build time.

### Work

- Add `spotbugs-maven-plugin` (4.8.6) to `pom.xml` with `effort Max`, `threshold High`, `check` goal.
- Wire it to run in `mvn verify`.
- Fix or document any findings that surface (narrowly-justified suppressions with human review).

### Acceptance

- [ ] `mvn verify` runs SpotBugs and fails on findings at the configured threshold.
- [ ] No broad suppressions; any suppression is justified and documented.

---

## Q6 — CI steps + doc sync

**Goal:** the gates are enforced in CI and documented.

### Work

- Add CI steps (or confirm `mvn verify` runs them) for JaCoCo and SpotBugs after the existing build.
- Update `docs/coding-standards.md`, `docs/testing-playbook.md`, `README.md` and `AGENTS.md` debt
  matrix (clear item 2, close item 12).
- Add a durable lesson in `docs/lessons.md` about moving `@Component` → `@Bean`.

### Acceptance

- [ ] CI runs the coverage + static gates and fails on regression.
- [ ] `AGENTS.md` marks item 2 resolved and item 12 (JaCoCo/SpotBugs) resolved.
- [ ] Docs describe the gates and the framework-free core rule.

---

## Epic Definition of Done

- [ ] Q1–Q6 complete.
- [ ] `scripts/check-boundaries.sh` exits 0; CI boundary step green.
- [ ] `core/` has zero Spring, Lombok, Mongo, Redis, jjwt or Micrometer imports/annotations.
- [ ] All five previously-annotated core classes are registered as `@Bean` in `infra/config`.
- [ ] JaCoCo coverage floor and SpotBugs static analysis are enforced in `mvn verify` and CI.
- [ ] `mvn test`, `mvn verify` and `scripts/check-boundaries.sh` all pass.
- [ ] `AGENTS.md`, `coding-standards.md`, `testing-playbook.md`, `README.md` and `lessons.md` reflect
      the framework-free core and the quality gates.
