# Epic 2 – Definition of Done (DoD)

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

## 1. Evidências obrigatórias (outputs reais colados)

```bash
# 2.1 logSafe em infra
git log --oneline main..HEAD

# 2.2 SSRFIT output
grep -R "logSafe" src/main/java/infra

# 2.3 ConfigValidatorIT output
mvn test -Dtest=ConfigValidatorIT --no-transfer-progress 2>&1 | tail -5

# 2.4 SecurityHeadersIT + curl output
curl -I https://localhost:8080/actuator/health/liveness

# 2.5 OWASP Dependency‑Check gate
mvn verify -DskipTests 2>&1 | grep -i "owasp\|BUILD"

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

## 2. Falhas que este código codifica (o registro E9 — por que cada regra existe)

| Regra | A falha que mata |
|---|---|
| §1 `gh run list` | IDs de run inventados; números fora do esperado |
| §1 surefire/grep | Contagens inventadas (ex.: 21/21, 22, 1/1·6/6·2/2·10/10·3/3 vs real 31/34) |
| §2 sha check | Citar commits que resolvem em lugar nenhum |
| §2 main-claims | "re-enabled" contra commit message lendo "disabled (HOLD)"; Known‑Gap narrativa sobre um teste @Disabled |
| §2 owner-quote | `@Disabled("HOLD: owner re-baselining…")` sem autorização do owner |
| §1 arithmetic | Lista correta por classe, soma errada (TD‑34: 1+6+2+10+3+1 dito como 22 — a própria correção TD‑31 carregava o off‑by‑one que corrigiu) |
| §2 no-closure | Quatro declarações consecutivas "E9 CLOSED" de um mesmo épico |

## 3. Checklist de conclusão do Épico 2

- [ ] `logSafe` em 100 % dos logs `infra` (grep + CI green)
- [ ] `SSRFIT` → 400 para IPs internos
- [ ] `ConfigValidatorIT` → falha em prod com secret default
- [ ] `SecurityHeadersIT` → headers presentes em resposta `curl -I`
- [ ] `./mvnw verify` verde com `owasp-dependency-check` green
- [ ] `scripts/check-security.sh` → PASS
- [ ] `./mvnw verify` completo verde (unit + IT + gates)

*Ao marcar todos os itens acima, o Épico 2 está **concluído** e o próximo épico (EP3 – Observable) pode iniciar.*

--- 

*Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 2. Sem o bloco de evidências e o self‑audit, o hand‑off será rejeitado pelo canal owner.*