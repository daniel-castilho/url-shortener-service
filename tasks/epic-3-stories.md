# Epic 3 – Stories (Aceitação)

Baseline = já existe (verificável por grep/read); Gap = trabalho novo. Prefixo de métricas: `service=url-shortener`; nomes reais em `MicrometerMetricsAdapter` (10 séries de negócio: 8 counters + 2 timers).

| # | Story | Baseline (já existe) | Gap (novo) | Critérios de Aceitação | Referência |
|---|-------|----------------------|------------|------------------------|------------|
| **3.1** | **Correlation-Id em todo o pipeline** | — (nenhum `MDC`/`request_id`/`X-Request-Id` em `src/main/java`, verificado 2026-09-11) | Filtro `OncePerRequestFilter` que ecoa/gera `X-Request-Id` (charset seguro, ≤ 64 chars) e injeta `request_id` no MDC; propagação a consumers async | • `CorrelationIdIT` verde: 100% dos logs de request contêm `request_id` no MDC <br>• script/grep de validação verde <br>• header ecoado na resposta | `docs/observability.md` (nova seção) |
| **3.2** | **Métricas "frozen"** | `MetricsPort` + `MicrometerMetricsAdapter` com 10 séries; `/actuator/prometheus` exposto (dev) | Gate `metrics-frozen-check` (script ou Micrometer) que compara as séries registradas com a lista congelada; zero séries novas sem revisão | • lista congelada == séries do adapter (10 de negócio + JVM/pessoas esperadas) <br>• gate PASS no `verify` <br>• sem `Counter`/`Timer` novo no commit sem revisão | `docs/slos.md` §2 + `docs/observability.md` §Metrics |
| **3.3** | **Health checks tiered + lockdown prod** | Lockdown já implementado (dívida 9): liveness/readiness/info públicos; health detail `when-authorized`; prod `health-detail-enabled: false`; `/actuator` via `app.security.actuator` | Teste `ProductionLockdownIT` bootando `prod` (ou teste fim-a-fim RestAssured) | • `health/liveness` 200 <br>• `health/readiness` 200 (Mongo+Redis up) <br>• prod: health detail não vaza <br>• `ProductionLockdownIT` PASS | `src/main/resources/application.yaml` (liga `management.health.*` + `app.security.actuator`) |
| **3.4** | **Regras de alerta validadas** | `deploy/monitoring/alerts.yml` com 3 regras de burn-rate dos SLOs; `docs/slos.md` §Burn-rate + §Response runbook | Cobertura/anotações `runbook-§X` em todas as regras; `promtool test rules` + `amtool check-config` no CI (promtool/amtool **não instalados** localmente — baixar binário ou container no job) | • `promtool test rules` → 0 erros <br>• `amtool check-config` verde em cada push <br>• cada regra tem anotação `runbook-§X` para `docs/slos.md` | `docs/slos.md` §Burn-rate + `deploy/monitoring/alerts.yml` |
| **3.5** | **Painel de diagnóstico rápido** | — (sem `scripts/debug-health.sh`; `docs/observability.md` não tem tabela sintoma→check→ação) | Tabela com ≥5 linhas sintoma→check→ação em `docs/observability.md`; `scripts/debug-health.sh` consulta `/actuator/prometheus` e imprime a ação | • tabela ≥5 linhas <br>• `bash scripts/debug-health.sh` roda sem erros e imprime ação recomendada | `docs/observability.md` (nova seção §Diagnóstico) |
| **3.6** | **Observabilidade no CI** | CI existente: `unit`, `integration-tests`, `security-check`, `build`, `doc-sync` | Job `observability` (ou passos no CI atual) com: `metrics-frozen-check`, `promtool test rules`, `amtool check-config` | • job verde no push <br>• qualquer falha bloqueia merge | `.github/workflows/ci.yml` |
| **3.7** | **Retro-compat com EP2** | `security.ssrf.blocked.total` incrementada e testada (SsrfProtectionIT); headers testados (SecurityHeadersIT) | Garantir entrada na lista frozen + playback em `/actuator/prometheus`; sem colisão de nomes | • séries EP2 presentes na lista frozen e no playback <br>• `./mvnw verify` conjunto verde | `Tasks EP2` + `docs/observability.md` |

---

**Rastreabilidade rápida:**

| Story | Doc referência | Aspecto-chave |
|-------|----------------|---------------|
| 3.1 | `observability.md` (nova §Correlation) | Correlation-Id no MDC |
| 3.2 | `slos.md` §2 + `observability.md` §Metrics | Métricas frozen |
| 3.3 | `application.yaml` + dívida 9 | Health tiered / lockdown |
| 3.4 | `slos.md` §Burn-rate + `deploy/monitoring/alerts.yml` | Regras de alerta |
| 3.5 | `observability.md` (nova §Diagnóstico) | Painel diagnóstico |
| 3.6 | `.github/workflows/ci.yml` | Gates no CI |
| 3.7 | EP2 + lista frozen | Retro-compat |

---

*Próximo passo: tasks técnicas (3.1–3.7) em `epic-3-technical-tasks.md`.*