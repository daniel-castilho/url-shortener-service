# Epic 5 – Estratégia de Testes

## 5.1 Testes de performance com k6
- **Objetivo:** Validar os SLOs de latência (S2/S3) e throughput sob carga.
- **Ação:** 
  - `k6 run scripts/perf/load-test.k6` → gerar relatório HTML/JSON.
  - Verificar p95 `GET /{id}` ≤ 200 ms e p95 `POST /v1/urls` ≤ 300 ms.
  - Confirmar que não há `5xx` e que a latência se mantém dentro do limite no pico de carga.
- **Critério aceite:** Relatório `k6` verde; p95 dentro dos SLOs; saída colada no `epic-5-dod.md`.

## 5.2 Perfil JVM e validação de mitigações
- **Objetivo:** Confirmar que as mitigações de perfil reduziram gargalos e não introduziram regressões.
- **Ação:** 
  - Executar `mvn verify` após aplicar as mitigações do profiling.
  - Comparar p95 dos SLOs antes e após a mitigação (registrado em `docs/performance-profiling.md`).
- **Critério aceite:** `mvn verify` verde; p95 não se deteriora; melhoria registrada em documentação.

## 5.3 Testes de regressão de performance (Baseline)
- **Objetivo:** Garantir que mudanças futuras não degradem a performance.
- **Ação:** 
  - `mvn verify` → `metrics-frozen-check` PASS.
  - `promtool test rules` → verde.
  - `amtool check-config` → verde.
- **Critério aceite:** Todos os checks verdes; saída colada.

## 5.4 Integração retro‑compatível com EP2 e EP3
- **Objetivo:** Garantir que as mudanças de performance não afetem métricas de segurança ou observabilidade.
- **Ação:** 
  - `mvn verify` conjunto (EP1+EP2+EP3+EP5) → verde.
  - Verificar que contadores de segurança (`dargent_security_*_total`) continuam a ser coletados corretamente.
- **Critério aceite:** Teste verde e saída colada.

--- 

**Checklist de conclusão do Épico 5:**

- [ ] Scripts k6 gerados e SLOs validados (p95 dentro dos limites)
- [ ] Profiling de hot‑path concluído e mitigações aplicadas
- [ ] Índices MongoDB otimizados
- [ ] `metrics-frozen-check` PASS + `promtool test rules` verde + `amtool check-config` verde
- [ ] `mvn verify` conjunto (EP1‑EP5) verde
- [ ] Evidências coladas no `epic-5-dod.md`

*Ao marcar todos os itens acima, o Épico 5 está **concluído** e o projeto entra na fase de manutenção de longo prazo com SLOs validados e performance comprovada.*