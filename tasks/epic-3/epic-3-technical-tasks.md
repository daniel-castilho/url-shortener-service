# Epic 3 – Tasks Técnicas

> Prefixo real: `service=url-shortener` (tag Micrometer). Séries de negócio atuais (baseline, `MicrometerMetricsAdapter`):
> counters: `urls.shortened.total`, `cache.hits.total`, `cache.misses.total`, `bloomfilter.rejections.total`, `urls.expired.total`, `schema.migrations.applied.total`, `schema.migrations.failed.total`, `security.ssrf.blocked.total` — timers (p50/p95/p99): `id.generation.duration`, `url.retrieval.duration`.
> **NÃO** existem `dargent_*`/`MetricsConfig.java`/"12 séries" — template drift de outro projeto (dargent) foi corrigido.

## 3.1 Implementar correlation-Id em todo o pipeline
- [ ] Criar `RequestCorrelationFilter` (`OncePerRequestFilter`, registro no `SecurityConfig`/`WebMvcConfig`):
    - Inbound `X-Request-Id` validado (ASCII ≤ 64 chars, sem CR/LF/controle); malformed → UUID gerado.
    - Ausente → gera UUID; **ecoa no header de resposta** (`X-Request-Id`).
    - `MDC.put("request_id", ...)` para cada request; cleanup no `finally`.
    - Propagação para workpath async (analytics/click events) — documentar limitação se outbox/consumers não existirem.
- [ ] Verificar que todo log de request tem `request_id` no MDC (appenders não sobrescrevem).
- [ ] Criar `CorrelationIdIT` (fim-a-fim RestAssured): assere header ecoado + `request_id` presente em logs capturados.
- [ ] Colar output do teste e do grep de validação no handoff-DOD.

## 3.2 Congelar métricas Prometheus (gate)
- [ ] Criar `scripts/check-metrics-frozen.sh` (ou `metrics-frozen-check`) que:
    - Sobe via Micrometer a lista esperada (estática: as 10 de negócio + padrões JVM/web/Jakarta que existirem no playback).
    - Compara `/{actuator}/prometheus` renderizado com a lista frozen → falha se série nova surgir sem atualização da lista (mudança exige revisão de design + bump da lista).
- [ ] Wire no `verify` (execução) OU no CI (job `observability`).
- [ ] `./mvnw verify` → `metrics-frozen-check` PASS.
- [ ] Colar output do script e da lista frozen no handoff-DOD.

## 3.3 Health checks tiered — teste de lockdown prod
- [ ] Baseline: verificar `app.security.actuator` + `management.health.show-details`/`spring security` (dívida 9) cobrem liveness/readiness públicos e detail quando autorizado.
- [ ] Criar `ProductionLockdownIT` (perfil `prod` + actuator): assere
    - `/actuator/health/liveness` → 200
    - `/actuator/health/readiness` → 200 com Mongo/Redis up
    - `/actuator/health` em prod → sem `details` sensíveis (show-details não vaza)
    - endpoints não públicos → 401/403 conforme perfil
- [ ] `./mvnw test -Dtest='ProductionLockdownIT'` → verde.
- [ ] Colar output no handoff-DOD.

## 3.4 Regras de alerta Prometheus — validadas no CI
- [ ] `deploy/monitoring/alerts.yml`: garantir anotação `runbook-§X` (X = seção do `docs/slos.md`) em todas as regras (baseline: 3 regras de burn-rate).
- [ ] CI: job/step que baixa `promtool` + `amtool` (binários versão pinned ou containers prom/alertmanager) e roda `promtool test rules` + `amtool check-config` (promtool/amtool **não** instalados localmente/CI hoje).
- [ ] Subir testes de regras (`promtool test rules`) unit/utest3 friendly na árvore `deploy/monitoring/test/`.
- [ ] Verde em `./^promtool test rules` e `amtool check-config` local + CI.
- [ ] Colar outputs no handoff-DOD.

## 3.5 Implementar painel de diagnóstico rápido
- [ ] Consolidar tabela sintoma → check → ação (≥5 linhas) em `docs/observability.md` (nova seção §Diagnóstico Rápido).
- [ ] Criar `scripts/debug-health.sh`:
    - `curl /actuator/prometheus | grep <série>` para cada série crítica das 10 de negócio.
    - Imprime estado + ação recomendada por SLO (`docs/slos.md`).
- [ ] `bash scripts/debug-health.sh` → saída legível, sem erros.
- [ ] Colar output no handoff-DOD.

## 3.6 Integrar no CI (GitHub Actions)
- [ ] Job `observability` no `.github/workflows/ci.yml`:
    - `scripts/check-metrics-frozen.sh`
    - `promtool test rules` + `amtool check-config` (binários pinned)
    - (opcional) `bash scripts/debug-health.sh` contra imagem da aplicação
- [ ] Falha em qualquer job → PR não mergeável (necessário branch protection existente).
- [ ] Colar trecho do workflow no handoff-DOD.

## 3.7 Observabilidade retro-compatível com EP2
- [ ] `security.ssrf.blocked.total` na lista frozen + verificada em `SsrfProtectionIT` (já incrementada).
- [ ] Sem colisão de nomes com as demais séries (listagem única).
- [ ] `./mvnw verify` conjunto (EP1+EP2+EP3) → verde (unit 280 + IT 128 baseline, 2026-09-10).

---

**Checklist de conclusão do Épico 3:**

- [ ] `request_id` em 100% dos logs (MDC) + `CorrelationIdIT` verde
- [ ] 10 séries de negócio "frozen"; `metrics-frozen-check` PASS
- [ ] `ProductionLockdownIT` → health tiered em prod verde
- [ ] 3+ regras com `runbook-§X`; `promtool test rules` + `amtool check-config` verdes
- [ ] Painel de diagnóstico rápido (`debug-health.sh`) verde
- [ ] Job CI `observability` verde
- [ ] Integração retro-compatível com métricas EP2 verde
- [ ] `./mvnw verify` completo verde (unit + IT + gates)

*Ao marcar todos, o Épico 3 está concluído e o próximo épico (EP4 – Testes) pode iniciar.*