# Epic 6 – Definition of Done (DoD) [aterrado]

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

## 1. Evidências obrigatórias (outputs reais coladas)

```bash
# 6.1 ADRs
git log --oneline -- docs/adr/

# 6.2 Índices MongoDB (infra isolada, dados reais) — sem criação às cegas
mongosh --quiet mongodb://localhost:27018/url_shortener --eval 'db.short_urls.getIndexes()'
mongosh --quiet mongodb://localhost:27018/url_shortener --eval 'db.short_urls.find({_id:"<code>"}).explain("executionStats")'
#   -> cursor pagination (V7), click_events (V4), TTL (V5) idem; IXSCAN/ID_SCAN + totalDocsExamined

# 6.3 Rate-limit + circuit breakers (já implementados — evidência)
./mvnw test -Dtest='RedirectRateLimitIT' --no-transfer-progress 2>&1 | tail -5
#   sob carga 2x (6.5): curl -s :<port>/actuator/circuitbreakers -> databaseCb CLOSED

# 6.4 Artefatos multi-instância
docker build -t url-shortener:sha-$(git rev-parse --short HEAD) . && docker images | grep url-shortener
#   nginx upstream multi-server (deploy/proxy/nginx.conf), systemd template (deploy/url-shortener@.service),
#   procedimento em docs/release-runbook.md

# 6.5 Escala horizontal (2 instâncias + LB, stress 2x via LB)
BASE_URL=http://localhost:<lb-port> docker run --rm --network host ... grafana/k6 run load-tests/stress.js
#   + prova de rate-limit compartilhado: limites reais, burst via LB -> 429 apos capacidade global

# 6.6 Integração completa
./mvnw verify --no-transfer-progress 2>&1 | grep -E "BUILD|SUCCESS|FAILURE|Tests run"

# Limpeza de árvore
git status --porcelain
```

## 2. Self‑audit — rode ANTES de enviar (qualquer "não" = corrigir o handoff, não o audit)

- [ ] Cada sha resolve: `git cat-file -e <sha>` para cada um citado acima
- [ ] Cada (número de run, sha) par aparece idêntico no `gh run list` colado (use `gh run list --limit 5`)
- [ ] Cada contagem (por ex., número de testes verdes, hits do grep) igual ao output colado (nunca arredondado, nunca lembrado)
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
| §1 surefire/grep | Contagens inventadas (ex.: 21/21, 22, 1/1·6/6·2/2·10/10·3/3 vs real 31/34) |
| §2 sha check | Citar commits que resolvem em lugar nenhum |
| §2 main-claims | "re-enabled" contra commit message lendo "disabled (HOLD)"; Known‑Gap narrativa sobre um teste @Disabled |
| §2 owner-quote | `@Disabled("HOLD: owner re-baselining…")` sem autorização do owner |
| §1 arithmetic | Lista correta por classe, soma errada (TD‑34: 1+6+2+10+3+1 dito como 22 — a própria correção TD‑31 carregava o off‑by‑one que corrigiu) |
| §2 no-closure | Quatro declarações consecutivas "E9 CLOSED" de um mesmo épico |

## 5. Checklist de conclusão do Épico 6

- [ ] 4 ADRs criados (`docs/adr/`)
- [ ] Auditoria explain limpa (IXSCAN/ID_SCAN evidenciado, sem índice às cegas)
- [ ] `RedirectRateLimitIT` verde + circuit breakers CLOSED sob carga
- [ ] Artefatos multi-instância (nginx upstream, systemd template, imagem com tag sha, runbook)
- [ ] Stress 2× via LB (2 instâncias): SLOs ok, 0 5xx, rate-limit compartilhado provado
- [ ] `./mvnw verify` conjunto (EP1‑EP6) verde
- [ ] Evidências coladas abaixo; self-audit rodado

---

*Evidências e self-audit são adicionados abaixo conforme as stories são executadas. Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 6.*