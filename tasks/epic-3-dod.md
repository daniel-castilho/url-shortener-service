# Epic 3 – Definition of Done (DoD)

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

## 1. Evidências obrigatórias (outputs reais colados)

```bash
# 3.1 correlation‑Id em logs
git log --oneline main..HEAD

# 3.2 métricas frozen
bash scripts/check-metrics-frozen.sh
./mvnw verify 2>&1 | grep -iE "metrics-frozen|BUILD"

# 3.3 health checks tiered em prod
./mvnw test -Dtest='ProductionLockdownIT' --no-transfer-progress 2>&1 | tail -5

# 3.4 regras de alerta
promtool test rules deploy/monitoring/test/rules-test.yml
amtool check-config /Path/to/alertmanager 2>&1 | tail -3

# 3.5 painel de diagnóstico
bash scripts/debug-health.sh

# Limpeza de árvore
git status --porcelain
```

## 2. Self‑audit — rode ANTES de enviar (qualquer "não" = corrigir o handoff, não o audit)

- [ ] Cada sha resolve: `git cat-file -e <sha>` para cada um citado acima
- [ ] Cada (número de run, sha) par aparece idêntico no `gh run list` colado (use `gh run list --limit 5`)
- [ ] Cada contagem (por ex., número de testes verdes, hits do grep, séries no playback) igual ao output colado (nunca arredondado, nunca lembrado)
- [ ] Todo vermelho está NA tabela com seu par (um vermelho em footnote = TD‑13)
- [ ] Todo "owner approved X" CITA a mensagem do channel que aprovou
- [ ] Todo claim sobre `main` é verdadeiro de `main`: trabalho que está só local está etiquetado `LOCAL — awaiting push`, nunca descrito como "landed"
- [ ] Nenhum claim de closure: closure é adjudicado pelo canal owner; hand‑offs reportam estado + gaps
- [ ] Flip = último commit de conteúdo; citation = commit final separado, citando um run cujo tree É o flip, com nada landed depois dele

## 3. Definições permanentes

- **Par** = (test, número de run, sha). Ids sozinhos apodrecem; números sozinhos driftam; ambos, de `gh run list`.
- **Evidência** = output de comando colado. Memória = hipótese. Hipóteses são etiquetadas como tais.
- **Sanção do dono** = uma mensagem de canal citada. Coisa alguma outra é atribuição; falsa atribuição é classe TD‑30.
- **LOCAL** prefix = verdadeiro e não landado. Nunca up‑grade LOCAL para landed.

## 4. Falhas que este código codifica (o registro E9 — por que cada regra existe)

| Regra | A falha que mata |
|---|---|
| §1 `gh run list` | IDs de run inventados; números fora do esperado |
| §1 surefire/grep | Contagens inventadas |
| §2 sha check | Citar commits que resolvem em lugar nenhum |
| §2 main-claims | "landed" para trabalho local |
| §2 owner-quote | Atribuição sem citação do canal |
| §1 drift | Templates copiados de outro projeto (ex.: `dargent_*`, "12 séries", "Java 21/Boot 3.5.7") sem aterrar no código real |
| §2 no-closure | Fechamento que não foi adjudicado pelo owner |

## 5. Checklist de conclusão do Épico 3

- [ ] `request_id` em 100% dos logs (MDC) + `CorrelationIdIT` verde
- [ ] 10 séries de negócio "frozen"; `metrics-frozen-check` PASS
- [ ] `ProductionLockdownIT` → health tiered em prod verde
- [ ] 3+ regras com `runbook-§X`; `promtool test rules` + `amtool check-config` verdes
- [ ] `debug-health.sh` verde
- [ ] Job CI `observability` verde
- [ ] Integração retro-compatível com EP2 verde
- [ ] `./mvnw verify` completo verde (unit + IT + gates)

*Ao marcar todos os itens acima, o Épico 3 está **concluído** e o próximo épico (EP4 – Testes) pode iniciar.*

---

*Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 3. Sem o bloco de evidências e o self‑audit, o hand‑off será rejeitado pelo canal owner.*