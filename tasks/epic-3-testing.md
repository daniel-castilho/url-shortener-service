# Epic 3 – Estratégia de Testes

## 3.1 Correlation-Id (CorrelationIdIT)
- **Objetivo:** Prov, gemundo que cada log de request contém `request_id` no MDC e que o header é ecoado.
- **Ação:**
  - `./mvnw test -Dtest='CorrelationIdIT'` → verde (RestAssured contra RANDOM_PORT; logs capturados contêm `request_id`).
  - Grep de validação: todo sink de log dentro do ciclo de request referencia `request_id` do MDC (não em linha fixa).
- **Critério aceite:** teste verde + saída colada no handoff-DOD.

## 3.2 Métricas frozen (check-metrics-frozen)
- **Objetivo:** Nenhuma série nova no playback de `/actuator/prometheus` sem revisão de design.
- **Ação:**
  - `scripts/check-metrics-frozen.sh` em `./mvnw verify` → PASS.
  - Lista congelada == série do `MicrometerMetricsAdapter` (10 de negócio) + esperadas do runtime.
- **Critério aceite:** gate verde + saída colada.

## 3.3 Health checks tiered (ProductionLockdownIT)
- **Objetivo:** Validar comportamento de prod: liveness/readiness públicos, detalhe não vazado, endpoints não-públicos bloqueados.
- **Ação:**
  - `./mvnw test -Dtest='ProductionLockdownIT'` → verde (Testcontainers Mongo/Redis; perfil `prod`).
  - Asserções: `/health/liveness` 200; `/health/readiness` 200; `/health` sem `details` sensíveis; acesso não-autorizado negado.
- **Critério aceite:** teste verde + saída colada.

## 3.4 Regras de alerta (promtool/amtool no CI)
- **Objetivo:** Regras de `deploy/monitoring/alerts.yml` sintaticamente válidas e coerentes com SLOs.
- **Ação:**
  - `promtool test rules <test>` → 0 erros/warnings.
  - `amtool check-config <config>` → verde.
  - Cada regra com anotação `runbook-§X` (`docs/slos.md`).
- **Critério aceite:** bins verdes (local + CI) + outputs colados.

## 3.5 Painel de diagnóstico (debug-health.sh)
- **Objetivo:** Script operacional que transforma `/actuator/prometheus` em "o que fazer agora".
- **Ação:**
  - `bash scripts/debug-health.sh` → saída legível, imprime ação recomendada por SLO.
- **Critério aceite:** script verde + saída colada.

## 3.6 Integração CI
- **Objetivo:** Bloquear merge se qualquer checagem de observabilidade falhar.
- **Ação:**
  - Job `observability` em `.github/workflows/ci.yml`: `check-metrics-frozen.sh` + `promtool test rules` + `amtool check-config`.
  - Falha em qualquer job → PR bloqueado.
- **Critério aceite:** pipeline verde; vermelho bloqueia.

## 3.7 Rastreabilidade com EP2
- **Objetivo:** Séries EP2 (`security.ssrf.blocked.total`) no gate frozen e incrementadas nos testes.
- **Ação:**
  - `./mvnw verify` conjunto → verde; playback inclui séries EP2.
- **Critério aceite:** gate + verify verdes + saída colada.

---

**Checklist de conclusão do Épico 3:**

- [ ] `CorrelationIdIT` → 100% logs com `request_id` no MDC
- [ ] `metrics-frozen-check` PASS
- [ ] `ProductionLockdownIT` → health tiered verde
- [ ] regras + `promtool test rules` + `amtool check-config` verdes
- [ ] `debug-health.sh` verde
- [ ] Job CI `observability` verde
- [ ] Integração retro-compatível com EP2 verde
- [ ] `./mvnw verify` completo verde (unit + IT + gates)

*Ao marcar todos, o Épico 3 está concluído e o próximo épico (EP4 – Testes) pode iniciar.*