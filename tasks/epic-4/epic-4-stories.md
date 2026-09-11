# Epic 4 – Stories (Aceitação)

**Aterragem:** os ACs abaixo refletem o estado real do repo (Java 25 / Boot 4.1.1, suíte baseline
270 unit / 139 IT, 24 séries frozen). Nomes de teste citados = classes reais
(`SsrfProtectionIT`, `ProdConfigValidatorIT`, `SecurityHeadersIT`).

| # | Story | Critérios de Aceitação | Referência / Âncora |
|---|-------|------------------------|----------------------|
| **4.1** | **Pirâmide de testes** – manter a divisão clara entre unitários (`*Test`, sem Docker), slices (`@WebMvcTest`) e integração (`*IT`, Testcontainers singleton via `BaseIntegrationTest`). | • `./mvnw test` (unit + slices) roda verde **sem Docker** (baseline 270). <br>• `./mvnw test -Dtest='*IT'` → (Testcontainers) 1 único par MongoDB + Redis para toda a suíte (padrão singleton, sem `@DirtiesContext`). <br>• `./mvnw verify` → unit + IT + E2E em sequência (failsafe). <br>• Matriz JaCoCo: BUNDLE LINE/BRANCH ≥ 60% e `core.*` LINE/BRANCH ≥ 70%. | `docs/testing-playbook.md` §1–2 (estratificação já aterrada) |
| **4.2** | **Boundary gates** – fade de fronteira verde em cada PR; ArchUnit impede imports `infra.*` em `core/`. | • `bash scripts/check-boundaries.sh` → PASS (0 violações). <br>• `bash scripts/check-boundaries.sh --self-test` → PASS (gate auto‑verifica). <br>• `ArchUnit BoundaryRulesTest` + `BoundaryRulesSelfTestTest` verdes no job Unit Tests. | Regra 1 do `AGENTS.md`; `scripts/check-boundaries.sh`; `ArchUnit` |
| **4.3** | **SSRF, validators e headers** – `SsrfProtectionIT` (8 testes, +1 IPv4‑mapped IPv6 nesta story), `ProdConfigValidatorIT` (5/5) fail‑fast, `SecurityHeadersIT` (3/3) headers HTTP. | • `./mvnw test -Dtest='SsrfProtectionIT,ProdConfigValidatorIT,SecurityHeadersIT'` → todos verdes. <br>• Saída colada no handoff‑DOD. <br>• IPv6: brackets `[::1]` classificados como loopback; **gap fechado**: CIDR `::ffff:169.254.169.254/128` (IPv4‑mapped) com teste de regressão. | Histórias 2.2‑2.5 do Épico 2; `DefaultUrlValidator` |
| **4.4** | **Métricas frozen + health checks + diagnóstico** – 24 séries de negócio congeladas; actuator tiered; triagem operacional. | • `bash scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS. <br>• `promtool check rules`, `promtool test rules`, `promtool check config`, `amtool check-config` → verdes. <br>• `ProductionLockdownIT` (7/7) → liveness/readiness tiered. <br>• `bash scripts/debug-health.sh` → saída legível com ação recomendada. | Story 3.7 do Épico 3; `docs/slos.md` §2; `deploy/monitoring/` |
| **4.5** | **Rastreabilidade AGENTS.md / DoD** – todo número, sha ou contagem no `epic-4-dod.md` tem par correspondente em `gh run list` e output de comando colado; nenhuma hipótese sem etiquetar. | • `bash scripts/check-doc-sync.sh` (+ `--self-test`) → PASS. <br>• 5/5 stories com traceability direta ao `AGENTS.md` (matriz de dívida) e ao `epic-4-dod.md`. <br>• CI: `ci.yml` (5 jobs) verde no push do flip — AC preenchido pelo CI existente (sem workflow `test.yml` separado, evitando duplicação). | Rule zero do `handoff-dod.md`; `scripts/check-doc-sync.sh` |

---

**Rastreabilidade rápida:**

| Story | Doc referência | AGENTS.md |
|-------|----------------|-----------|
| 4.1 | `docs/testing-playbook.md` §1–2 | Pirâmide de testes (`## 🧪 Testing Strategy`) |
| 4.2 | `scripts/check-boundaries.sh`, `ArchUnit` | Regra 1 |
| 4.3 | `AGENTS.md`, `DefaultUrlValidator` | Regras 2‑6 (SSRF) + rule zero |
| 4.4 | `docs/slos.md` §2, `deploy/monitoring/` | `scripts/check-metrics-frozen.sh` (dívidas #24, #27) |
| 4.5 | `handoff-dod.md`, `ci.yml` | Rule zero; `## 🛠️ Commands Matrix` |

---

*Execução: `tasks/epic-4/epic-4-technical-tasks.md`; evidências no `tasks/epic-4/epic-4-dod.md`.*