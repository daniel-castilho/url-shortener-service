# Quality Gates & Framework-Free Core — Technical Specification
## Complete the framework-free `core/`, make the boundary gate green & wire quality gates

**Status:** ready for implementation from current `main` (`9691b31`).
**Priority:** P0 — foundation integrity. Second epic (after Foundation Identity Model, which is done
and tagged `v0.1.0`).
**Companions:** `quality-gates-framework-free-core-backlog.md` · `quality-gates-framework-free-core-implementation-sequence.md`

---

## 1. Purpose

The first epic (Foundation Identity Model, incl. the corrective-fixes commit `9691b31`) fixed the
identity model, the CI *command* (failsafe + `mvn verify`), collision semantics, index management, the
`UserService` dependency-inversion leak and English-only cleanup. Two integrity gaps remain, and this
epic closes them:

1. **The architecture boundary check does not pass.** `scripts/check-boundaries.sh` returns exit 1
   because five `core/` classes still carry Spring/Lombok annotations. The CI step that greps
   `org.springframework` in `core/` therefore fails — the gate is present but **red**.
2. **No quality gates beyond tests.** There is no coverage floor (JaCoCo) and no static analysis
   (SpotBugs); the CI has no such steps.

This epic makes the framework-free `core/` claim *true*, turns the boundary gate **green and
enforced**, and adds the coverage/static-analysis gates that give the project a real stop-the-line
quality floor. It does **not** change the identity model or any public API contract.

---

## 2. Scope

### In scope

- **Remove Spring/Lombok annotations from `core/`** and register those beans in `infra/config`
  (the pattern already used for `UrlShortenerService`/`UserService`/`Base62CodeGenerator`):
  - `core/idgeneration/CompositeUrlIdGenerator` (drops `@Component`/`@RequiredArgsConstructor`)
  - `core/idgeneration/RandomUrlIdStrategy` (drops `@Component`/`@RequiredArgsConstructor`)
  - `core/idgeneration/VanityUrlIdStrategy` (drops `@Component`/`@RequiredArgsConstructor`)
  - `core/service/QuotaService` (drops `@Service`/`@RequiredArgsConstructor`)
  - `core/validation/ReservedWordsValidator` (drops `@Component`)
- **Make `core/` fully framework-free** — no Spring, Lombok, Mongo, Redis, jjwt or Micrometer types.
- **Make the boundary gate green and enforced**: `scripts/check-boundaries.sh` and the CI
  "Architecture boundary check" step must pass.
- **Wire quality gates**: add **JaCoCo** (coverage floor) and **SpotBugs** (static analysis) plugins to
  `pom.xml` and add corresponding CI steps.
- Update the `UserService` refactor already landed as *done*; add a **fail-fast** constructor/bean
  wiring so a future annotation slipping back into `core/` is caught at build time.

### Out of scope

- analytics persistence, link expiry (TTL), rate-limit on redirect, `$inc` quota, SSRF/URL hardening,
  actuator/Swagger lockdown, tracing/SLOs, API redesign;
- changing the identity model or any existing endpoint/status contract;
- adding any Maven coordinate beyond JaCoCo and SpotBugs without explicit approval.

---

## 3. Architectural constraints

### 3.1 Dependency direction (now to be strictly true)

- `core/` is **annotation-free and dependency-free**. It contains only domain models, value objects,
  ports, enums, exceptions and plain (non-`@Component`/`@Service`) use-case services.
- Beans needed by Spring are created as `@Bean` in `infra/config` (`ServiceConfig` and friends),
  which construct `core` classes with explicit constructor injection.
- `infra/` implements the outbound ports and wires them; nothing in `core/` imports an `infra` or
  framework type.

### 3.2 Enforcement

The boundary gate (`scripts/check-boundaries.sh` + the CI step) is the enforcement mechanism. Its two
checks must both return 0 matches:

```bash
grep -rEn "import ca\.tyny\.urlshortener\.infra" src/main/java/ca/tyny/urlshortener/core
grep -rlE "org\.springframework|org\.mongodb|org\.redisson|io\.jsonwebtoken|io\.micrometer|import lombok" src/main/java/ca/tyny/urlshortener/core
```

> **Note:** the second check should also include `lombok` (the five offending classes use
> `@RequiredArgsConstructor` from Lombok), so the gate catches the annotation/processor dependency too,
> not just Spring annotations.

---

## 4. Exact change list

### 4.1 `core` classes

Remove the annotation/import and keep the class as a plain Java type with an explicit constructor
(no Lombok):

| File | Change |
| --- | --- |
| `core/idgeneration/CompositeUrlIdGenerator` | remove `@Component`, `@RequiredArgsConstructor`, `lombok` import; add explicit constructor taking `List<UrlIdGenerationStrategy>` |
| `core/idgeneration/RandomUrlIdStrategy` | remove `@Component`, `@RequiredArgsConstructor`; explicit constructor taking `IdGeneratorPort` |
| `core/idgeneration/VanityUrlIdStrategy` | remove `@Component`, `@RequiredArgsConstructor`; explicit constructor taking `UserRepositoryPort`, `UrlRepositoryPort` |
| `core/service/QuotaService` | remove `@Service`, `@RequiredArgsConstructor`; explicit constructor taking `UserRepositoryPort` |
| `core/validation/ReservedWordsValidator` | remove `@Component`; ensure a no-arg constructor (or explicit) |

### 4.2 `infra/config` bean registration

Add the five beans to `infra/config` (extend `ServiceConfig`, or a dedicated `IdGenerationConfig` /
`DomainConfig`) using explicit constructor wiring:

```java
@Bean
public ReservedWordsValidator reservedWordsValidator() { return new ReservedWordsValidator(); }

@Bean
public CompositeUrlIdGenerator compositeUrlIdGenerator(List<UrlIdGenerationStrategy> strategies) {
    return new CompositeUrlIdGenerator(strategies);
}

@Bean
public RandomUrlIdStrategy randomUrlIdStrategy(IdGeneratorPort idGenerator) {
    return new RandomUrlIdStrategy(idGenerator);
}

@Bean
public VanityUrlIdStrategy vanityUrlIdStrategy(UserRepositoryPort userRepository, UrlRepositoryPort urlRepository) {
    return new VanityUrlIdStrategy(userRepository, urlRepository);
}

@Bean
public QuotaService quotaService(UserRepositoryPort userRepository) {
    return new QuotaService(userRepository);
}
```

`UrlShortenerService` already receives these dependencies via `@Bean`; keep that wiring intact and
verify it still resolves from the newly-explicit beans.

### 4.3 Boundary gate

- Update `scripts/check-boundaries.sh` to also scan for `lombok` in `core/`.
- Confirm the CI "Architecture boundary check" step uses the same expression (or calls the script).
- The script must exit 0 on the cleaned-up core.

### 4.4 Quality gates (JaCoCo + SpotBugs)

Add to `pom.xml`:

```xml
<!-- JaCoCo: coverage floor -->
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.12</version>
    <executions>
        <execution><goals><goal>prepare-agent</goal></goals></execution>
        <execution>
            <id>check</id><phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit><counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.60</minimum></limit>
                            <limit><counter>BRANCH</counter><value>COVEREDRATIO</value><minimum>0.40</minimum></limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>

<!-- SpotBugs: static analysis -->
<plugin>
    <groupId>com.github.spotbugs</groupId>
    <artifactId>spotbugs-maven-plugin</artifactId>
    <version>4.8.6</version>
    <configuration><effort>Max</effort><threshold>High</threshold></configuration>
    <executions>
        <execution><goals><goal>check</goal></goals></execution>
    </executions>
</plugin>
```

- Add CI steps after `mvn verify`:
  ```yaml
  - name: Coverage gate (JaCoCo)
    run: mvn verify -DskipTests   # or a dedicated jacoco:check step after tests
  ```
  (Configure so JaCoCo/SpotBugs run as part of `mvn verify`, or as separate explicit steps that
  gate the build.)

### 4.5 Tests

The existing `core` unit tests must still pass **without** a Spring context (they already use mocks /
explicit construction — confirm none relied on component scanning of the removed annotations). A new
`@SpringBootTest`-free unit test verifies `CompositeUrlIdGenerator` picks the right strategy.

---

## 5. Verification commands

```bash
mvn test                       # fast, Docker-free, no Spring context needed for core
bash scripts/check-boundaries.sh   # must exit 0
mvn verify                     # full gate: unit + *IT + JaCoCo + SpotBugs + jar
```

---

## 6. Documentation deliverables

Update in the same epic:

- `AGENTS.md` — clear debt item 2 (Spring annotations in core); update item 12 (JaCoCo/SpotBugs) to
  resolved; note the boundary gate is now green;
- `docs/coding-standards.md` — confirm the framework-free `core/` rule and the `@Bean`-in-config wiring
  convention; document JaCoCo/SpotBugs tolerances;
- `docs/testing-playbook.md` — add JaCoCo/SpotBugs to the command matrix and the coverage/static gate
  interpretation;
- `README.md` — note quality gates and the framework-free core claim;
- `docs/lessons.md` — add a durable lesson about moving `@Component` to `@Bean` to keep `core/` free.

The epic is **not** Done while `scripts/check-boundaries.sh` or the CI boundary step still fails.
