# Epic 6 – Estratégia de Testes [aterrado]

## 6.1 ADRs
- **Objetivo:** Decisões de escala registradas e revisáveis.
- **Ação:** 4 ADRs em `docs/adr/` (template status/date/context/decision/consequences); `git log --oneline -- docs/adr/` colado.
- **Critério aceite:** ≥2 ADRs (target 4) presentes; saída colada no `epic-6-dod.md`.

## 6.2 Índices MongoDB + explain
- **Objetivo:** Provar que as queries críticas usam os índices V1–V9 (não criar às cegas).
- **Ação:**
  - mongosh na infra isolada com dados reais (pool do stress).
  - `getIndexes()` + `explain("executionStats")`: lookup `_id` (redirect), cursor pagination (V7), analytics (V4), TTL (V5).
  - Confirmar `ID_SCAN`/`IXSCAN` e `totalDocsExamined` mínimo; **sem** novas migrations.
- **Critério aceite:** Outputs colados no `epic-6-dod.md`; `./mvnw verify` verde.

## 6.3 Rate-limit + circuit breakers
- **Objetivo:** Evidenciar o comportamento dos mecanismos já implementados.
- **Ação:**
  - `./mvnw test -Dtest='RedirectRateLimitIT'` → verde (429 após capacidade, escopos, burst).
  - Sob carga 2×: `GET /actuator/circuitbreakers` → estados `CLOSED`.
- **Critério aceite:** Outputs colados.

## 6.4 Deploy multi-instância (artefatos)
- **Objetivo:** Artefatos prontos para N instâncias em bare-metal.
- **Ação:**
  - nginx upstream multi-server (weight-flip), systemd template `url-shortener@.service`, build da imagem (tamanho + tag sha), runbook atualizado.
- **Critério aceite:** Configs no repo; `docker images` e procedimento colados.

## 6.5 Escala horizontal sob carga (2 instâncias + LB)
- **Objetivo:** Validar que o design stateless de fato escala horizontalmente.
- **Ação:**
  - 2 instâncias compartilhando Mongo/Redis atrás de nginx LB.
  - `stress.js` 2× via LB: p95 < 200ms, 0 5xx.
  - Prova de rate-limit compartilhado: limites reais → burst via LB → 429 após a capacidade **global**.
- **Critério aceite:** Relatórios colados no `epic-6-dod.md`.

## 6.6 Integração retro-compatível (gates)
- [ ] `./mvnw verify` conjunto → verde (métricas frozen, boundaries, doc-sync, SpotBugs, OWASP).
- [ ] `promtool` + `amtool` verdes.
- [ ] Saídas coladas no `epic-6-dod.md`.

---

**Checklist de conclusão do Épico 6:**

- [ ] 4 ADRs criados (`docs/adr/`)
- [ ] Auditoria explain limpa
- [ ] `RedirectRateLimitIT` verde + circuit breakers CLOSED sob carga
- [ ] Artefatos multi-instância prontos (nginx, systemd template, imagem sha, runbook)
- [ ] Stress 2× via LB (2 instâncias) com SLOs ok e rate-limit compartilhado provado
- [ ] `./mvnw verify` conjunto verde
- [ ] Evidências coladas no `epic-6-dod.md`

*Ao marcar todos os itens acima, o Épico 6 está **concluído**.*