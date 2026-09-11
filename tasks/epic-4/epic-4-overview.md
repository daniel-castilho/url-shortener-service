# Epic 4: Testes – Qualidade como Fundação

**Projeto:** url-shortener-service
**Contexto:** Java 25, Spring Boot 4.1.1, Arquitetura Hexagonal, MongoDB e Redis (Testcontainers 2.0.5)
**Objetivo:** Consolidar a estratégia de testes como o pilar que garante que cada mudança (feature,
refatoração, upgrade) seja provada antes de chegar à produção, com cobertura controlada, testes
determinísticos e feedback rápido.

---

## Por que este épico em quarto lugar?

- **EP1 (Maintainable)** fornece a base de pacotes, nomes e convenções que os testes dependem para
  serem localizados e executados previsivelmente (Spotless + ArchUnit + \`check-boundaries.sh\`).
- **EP2 (Secure)** introduziu o status de login seguro, SSRF protection, ConfigValidator e headers
  HTTP; o EP4 valida que esses recursos são cobertos por testes de integração e unitários
  (\`SsrfProtectionIT\`, \`ProdConfigValidatorIT\`, \`SecurityHeadersIT\`).
- **EP3 (Observable)** fornece as métricas (24 séries frozen), logs (correlation-id/MDC) e health
  checks que os testes de performance e carga usam como *ground truth*
  (\`check-metrics-frozen.sh\`, \`ProductionLockdownIT\`, promtool/amtool).
- **EP4 → EP5/Performance:** Testes de carga e SLO validation só são confiáveis se a base de testes
  unitários e de integração for sólida (baseline k6 já publicado em \`docs/load-test-baseline.md\`).
- **EP4 → EP6/Escalável:** A confiança em escalar vem de testes de concorrência, races e boundary
  gates que já vivem neste épico.
- **EP4 → EP7/Reliable:** A tolerância a falhas é validada por testes de caos, circuit‑breaker
  states e reconciler scenarios.
- **EP4 → EP8/Deployable:** A pipeline de deploy só é considerada segura quando o gate
  \`./mvnw verify\` (unit + IT + E2E) é verde consistentemente.

**Critério de Aceitação Elevado:**

1. \`./mvnw test\` → verde para todos os módulos \`core\` e \`infra\` (270 unit tests, sem Docker).
2. \`./mvnw verify\` (inclui \`*IT\`) → cobertura JaCoCo respeita os floors configurados no pom:
   BUNDLE LINE/BRANCH ≥ 60% e \`core.*\` LINE/BRANCH ≥ 70% (medido 2026-09-10: core LINE 90.6% /
   BRANCH 81.0%).
3. Zero *flaky* tests na base principal: testes que falham once e passam no rerun são investigados e
   corrigidos.
4. Todos os *stories* do épico têm pelo menos um teste automatizado associado e citado no
   \`AGENTS.md\`.
5. **Rule zero — zero‑from‑memory:** todo número, sha ou contagem nas evidências é colado de output
   de comando real.

**Relação com outros épicos:**

| Épico          | Dependência direta                                                                 |
|----------------|------------------------------------------------------------------------------------|
| EP1 – Maintainable | Pacotes, nomes, convenções de código que os testes validam (ArchUnit, boundary gate) |
| EP2 – Secure      | Testes de SSRF, ConfigValidator, SecurityHeaders (\`SsrfProtectionIT\`, \`ProdConfigValidatorIT\`, \`SecurityHeadersIT\`) |
| EP3 – Observable  | Métricas (24 séries frozen) e logs que os testes de performance/SLO consomem        |
| EP5 – Performance | Testes de carga (k6) e benchmarks que validam latência e throughput                |
| EP7 – Reliable    | Testes de circuito‑breaker, bulkhead, reconciler, outbox exhaustion                |
| EP8 – Deployable  | Pipeline \`./mvnw verify\` como gate final antes de qualquer deploy                |

---

*Próximo passo: executar as stories (4.1‑4.5) conforme aterradas em \`tasks/epic-4/epic-4-stories.md\`.*