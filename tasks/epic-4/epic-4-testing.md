# Epic 4 – Estratégia de Testes

**Contexto real:** Java 25 / Spring Boot 4.1.1 / Testcontainers 2.0.5 / JUnit 5 + Mockito +
RestAssured 6.0.1 + ArchUnit. Estratificação completa documentada em
`docs/testing-playbook.md` §1–2 e §Placement — fonte única, já aterrada.

## 4.1 Pirâmide de testes (unit / slice / IT)

- **Objetivo:** confirmar que a estratificação funciona como esperado e é rápida no loop de dev.
- **Ação:**
  - `./mvnw test` (unit `*Test` + slices `@WebMvcTest`; **sem Docker**) — medir tempo, colar no DoD.
  - `./mvnw test -Dtest='*IT'` (Testcontainers: MongoDB + Redis **singleton** via
    `BaseIntegrationTest`, sem `@DirtiesContext`) — medir tempo, colar no DoD.
  - Verificar JaCoCo: BUNDLE LINE/BRANCH ≥ 60%, PACKAGE `core.*` LINE/BRANCH ≥ 70% (check bound
    ao `verify`; report colado).
- **Critério aceite:** suíte determinística; floors de cobertura respeitados; tempos colados.

## 4.2 Boundary gates (fronteira)

- **Objetivo:** confirmar que `core/` nunca depende de `infra/` (Regra 1 do AGENTS.md).
- **Ação:**
  - `bash scripts/check-boundaries.sh` → PASS (0 violações).
  - `bash scripts/check-boundaries.sh --self-test` → PASS (planta violação temporária e captura).
  - `ArchUnit`: `BoundaryRulesTest` + `BoundaryRulesSelfTestTest` verdes (job Unit Tests do CI).
  - `./mvnw spotless:check` → sem formatação pendente.
- **Critério aceite:** todos PASS; outputs colados no DoD.

## 4.3 SSRF, ConfigValidator e Security Headers

- **Objetivo:** validar as histórias trazidas do Épico 2 com nomes/contagens reais.
- **Ação:**
  - `SsrfProtectionIT` — 8 testes (IPs IPv4 literais, loopback, link‑local, ULA/fd00, IPv6 literal
    `[::1]`) + **1 novo teste desta story**: IPv4‑mapped IPv6 `https://[::ffff:169.254.169.254]/`
    (regressão do CIDR que já existe no validator). Caso unitário correspondente no
    `DefaultUrlValidatorTest`.
  - `ProdConfigValidatorIT` — 5/5: secret ausente / curto / default → fail‑fast; forte + config
    completa → passa; non‑prod ignora.
  - `SecurityHeadersIT` — 3/3: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
    `Referrer-Policy: strict-origin`.
  - `./mvnw test -Dtest='SsrfProtectionIT,ProdConfigValidatorIT,SecurityHeadersIT'` → os três
    verdes; saída colada.
- **Critério aceite:** todos verdes; saída colada; IPv6 brackets e IPv4‑mapped cobertos.

## 4.4 Métricas "frozen" e Health Checks

- **Objetivo:** confirmar que as **24 séries** de negócio (docs/slos.md §2) estão congeladas e os
  health checks tiered funcionam.
- **Ação:**
  - `bash scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS.
  - `promtool check rules`, `promtool test rules`, `promtool check config` → verdes
    (pinned: Prometheus 3.3.0).
  - `amtool check-config` → verde (pinned: Alertmanager 0.28.1).
  - `./mvnw test -Dtest='ProductionLockdownIT'` → verde (7/7).
  - `bash scripts/debug-health.sh` → saída legível com ação recomendada.
- **Critério aceite:** todos os checks verdes; saída colada.

## 4.5 Rastreabilidade e CI

- **Objetivo:** garantir Rule zero (zero‑from‑memory) e bloqueio de merge em qualquer vermelho.
- **Ação:**
  - `bash scripts/check-doc-sync.sh` (+ `--self-test`) → PASS.
  - Workflow CI **`ci.yml`** existente (5 jobs; sem duplicar como `test.yml`):
    Unit Tests → Observability Gate (Epic 3) → Integration Tests → Security Gate → Build.
    Roda unit, ArchUnit, boundaries, doc‑sync, promtool/amtool, `check-metrics-frozen.sh`,
    `*IT`/failsafe, `check-security.sh`, OWASP, jar.
  - Evidência de CI: `gh run list` + conclusões dos jobs do flip (run sobre a tree do flip).
- **Critério aceite:** gates verdes; qualquer falha bloqueia merge.

## 4.6 Integração retro‑compatível (EP1–EP3)

- **Objetivo:** garantir que métricas de segurança e logs já existentes continuam a funcionar.
- **Ação:**
  - `./mvnw verify` conjunto → verde.
  - Série `security_ssrf_blocked_total` incrementada — coberta por
    `MetricsIT.playbackExportsEpic2BusinessSeries`.
  - 24 séries sem colisão (docs/slos.md §2).
- **Critério aceite:** teste verde e saída colada.

---

**Checklist de conclusão do Épico 4:**

- [x] `./mvnw test` → verde (271 unit + slices, sem Docker)
- [x] `./mvnw test -Dtest='*IT'` → verde (140 IT, Testcontainers singleton)
- [x] `./mvnw verify` → verde (JaCoCo, SpotBugs, ArchUnit, OWASP, metrics‑frozen, promtool, amtool)
- [x] `check-boundaries.sh` + `--self-test` + ArchUnit → PASS
- [x] `SsrfProtectionIT` (14), `ProdConfigValidatorIT` (5), `SecurityHeadersIT` (3) → verdes
- [x] `check-doc-sync.sh` → PASS
- [x] `ci.yml` verde (5 jobs) no push do flip
- [x] Integração retro‑compatível com EP1–EP3 verde

*Ao marcar todos os itens acima, o Épico 4 está **concluído** e o próximo épico (EP5 – Performance)
pode iniciar.*