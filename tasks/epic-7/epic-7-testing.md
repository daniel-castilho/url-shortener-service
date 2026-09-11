# Epic 7 – Estratégia de Testes [aterrado]

Princípio: testar o **contrato de falha**, não a implementação do Resilience4j. Happy path já foi o Épico 4/5/6.

## 7.1 Contrato de falha
- **Objetivo:** Decisões revisáveis; operação não depende de memória do autor.
- **Ação:** `docs/reliability.md` + ADR 0005/0006; `git log` colado.
- **Critério aceite:** matriz completa; RPO/RTO alvo explícitos; rejeitados escritos (replica set fora de escopo, exactly-once fora de escopo).

## 7.2 Isolamento de dependência
- **Objetivo:** Caminho de redirect tem comportamento determinado quando Mongo ou Redis desaparece.
- **Ação:**
  - Inventário + ITs `RedirectMongoFailureIT` / `RedirectRedisFailureIT` (nomes flexíveis se a classe já existir).
  - Testcontainers: parar o container no teste (`mongodb.stop()` / `redis.stop()`) em vez de mock genérico — falha real.
  - CB: afirmar transição via métrica/log.
- **Critério aceite:** testes verdes; status HTTP igual ao da matriz 7.1; output Surefire colado.
- **Não fazer:** mockar `UrlRepositoryPort` para fingir CB — isso não prova o adapter.

## 7.3 Shutdown e health
- **Objetivo:** SIGTERM não corta in-flight; probe de readiness é o sinal de tira-de-rota.
- **Ação:**
  - Script existente `verify-graceful-shutdown.sh` (é o teste de aceite operacional).
  - `HealthProbeSemanticsIT` se for preciso mudar indicators.
  - Experimento manual docker-stop documentado no DoD (não precisa entrar no `mvn verify` se for destrutivo demais; se entrar, isolar por perfil).
- **Critério aceite:** in-flight 302; readiness reflete dependência crítica; liveness não espelha Redis blip.

## 7.4 Analytics sob falha
- **Objetivo:** at-least-once observável; poison isolado.
- **Ação:** estender ITs já existentes da pipeline (`ClickPipelineIT`, `RedisClickEventQueue*`).
- **Asserts:**
  - N publicados, worker restart → persistidos ≥ N (nunca < N sem flag explícita de perda).
  - Evento inválido → consumer lag não cresce sem bound; próximos válidos passam.
  - Enqueue no redirect não lança se Redis Stream estiver down (fail-open já testado).
- **Critério aceite:** Surefire colado; contrato ADR 0006 citado no teste (comentário de uma linha no DoD basta).

## 7.5 DR + carga sob falha
- **Objetivo:** script de backup é verdadeiro; degradação tem número.
- **Ação:**
  - Restore drill em infra isolada (nunca no volume de dev “bom”).
  - k6 `redirect.js` curto + `docker stop` Redis; depois Mongo.
  - Comparar status distribution com a matriz 7.1 — divergência = bug ou doc errado; corrigir um dos dois.
- **Critério aceite:** 302 após restore dos seeds; summaries k6 colados; playbooks no runbook com os comandos usados.
- **Não fazer:** exigir p95 < 200ms **com Mongo down**. O SLO de latência é happy path (EP5). Aqui o aceite é “degradação = matriz”.

## 7.6 Integração retro-compatível (gates)
- [ ] `./mvnw verify` conjunto → verde (JaCoCo floors, SpotBugs, OWASP, boundaries, doc-sync, metrics-frozen).
- [ ] `promtool` + `amtool` verdes.
- [ ] Nenhuma série nova sem passar pelo freeze gate (se 7.2/7.4 criarem métrica de poison/drop, atualizar `scripts/check-metrics-frozen.sh` **no mesmo PR**).

---

**Checklist de conclusão do Épico 7:**

- [ ] Matriz + ADRs 0005/0006
- [ ] ITs de falha Mongo/Redis no redirect
- [ ] Shutdown + probes evidenciados
- [ ] Pipeline: restart e poison
- [ ] Restore drill + dois k6 de injeção
- [ ] `./mvnw verify` verde
- [ ] Evidências coladas no `epic-7-dod.md`

*Ao marcar todos os itens acima, o Épico 7 está **concluído**.*