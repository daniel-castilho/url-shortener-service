# Epic 3: Observable – Visibilidade Total

**Projeto:** url-shortener-service
**Contexto (real, 2026-09):** Java 25, Spring Boot 4.1.1, Tomcat 11 (virtual threads), Arquitetura Hexagonal, MongoDB + Redis (Testcontainers), observabilidade base já existente (`docs/observability.md`, `docs/slos.md`, `deploy/monitoring/{prometheus,recording-rules,alerts}.yml`, `deploy/otel/`).
**Objetivo:** fechar os gaps reais de observabilidade deste repositório: correlation-id em 100% dos logs, gate de métricas "frozen", testes de lockdown de actuator em prod, regras de alerta validadas por `promtool`/`amtool` no CI, e um painel de diagnóstico rápido operacional.

---

## Por que este épico agora?

- **EP1 (Maintainable)** consolidou nomes/pacotes e o `check-doc-sync`; EP3 depende dessa base para correlação consistente de logs.
- **EP2 (Secure)** entregou `logSafe`, headers e `security.ssrf.blocked.total`; EP3 dá visibilidade operacional a essas evidências.
- Blocos de construção **já existem** (baseline): health checks tiered + actuator tiered (dívida 9), `MetricsPort` com 10 séries, SLOs + burn-rate em `docs/slos.md`, 3 regras de alerta em `deploy/monitoring/alerts.yml`, tracing OTel (dívida 12).
- Os **gaps reais** a fechar são listados em `epic-3-technical-tasks.md` §3.1–3.7 (novo) vs. baseline (já existe).

**Critério de Aceitação Elevado:**

1. `request_id` no MDC em **100%** dos logs de request (verificado por `CorrelationIdIT` + script de validação).
2. **Métricas frozen**: todas as séries registradas em `MicrometerMetricsAdapter` têm correspondência no playback de `/actuator/prometheus`; novo contador/timer exige revisão de design (gate em CI).
3. `ProductionLockdownIT` valida actuator em prod (`health/liveness`, `health/readiness`, `show-details` não-vazado) — lockdown já implementado (dívida 9); falta o teste/fim-a-fim.
4. Regras de alerta em `deploy/monitoring/alerts.yml` com anotação `runbook-§X` apontando para `docs/slos.md`; `promtool test rules` + `amtool check-config` verdes em cada push.
5. Painel de diagnóstico rápido: tabela sintoma → check → ação em `docs/observability.md` + `scripts/debug-health.sh` operacional.
6. CI: job `observability` rodando os gates acima; falha = PR bloqueado.
7. **Zero "claims from memory"**: todo número, sha ou contagem nas evidências é colado de output de comando real.

**Relação com outros épicos:**

| Épico | Dependência |
|-------|-------------|
| EP1 – Maintainable | Convenções de logging, pacotes, `logSafe` |
| EP2 – Secure | `security.ssrf.blocked.total`, headers, actuator tiered |
| EP4 – Testes | Histórias de teste derivadas dos stories abaixo |
| EP5 – Performance | SLOs de latência p95 mensurados por métricas/tracing |
| EP6 – Escalável | Tuning de pools/rate-limit baseado em métricas |
| EP7 – Reliable | Circuit-breaker/bulkhead states expostos (se adicionados) |
| EP8 – Deployable | Health checks/métricas estáveis para blue-green/canary |

---

*Próximo passo: stories detalhadas (3.1–3.7) — já preenchidas em `epic-3-stories.md` com baseline vs. gap.*