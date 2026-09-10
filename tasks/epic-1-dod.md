# Epic 1 – Definition of Done (DoD)

**Regra zero — zero‑from‑memory:** Todo número, sha ou contagem neste documento deve ser colado de um output de comando incluído neste documento. Se não der para colar o comando que gerou, trata‑se de hipótese e deve ser etiquetado como tal (TD‑13 class).

## 1. Evidências obrigatórias (outputs reais colados)

```bash
# O chain exact — nada mais
git log --oneline main..HEAD

# Proof de árvore limpa
git status --porcelain

# Pares run (número E sha) VÊM DE AQUI — nunca da memória
gh run list --limit 10

# Surefire summary para cada classe que citar
grep -h "Tests run" **/target/surefire-reports/*.txt | tail -20
```

## 2. Self‑audit — rode ANTES de enviar (qualquer "não" = corrigir o handoff, não o audit)

- [ ] Every sha resolves: `git cat-file -e <sha>` para cada um citado
- [ ] Cada (número de run, sha) par aparece idêntico no `gh run list` colado
- [ ] Cada contagem igual ao output surefire/grep (nunca arredondado, nunca lembrado; omitir contagens é sempre aceitável — inventá‑las nunca é)
- [ ] Todo vermelho está NA tabela com seu par (um vermelho em footnote = TD‑13)
- [ ] Todo "owner approved X" CITA a mensagem do channel que aprovou
- [ ] Todo claim sobre `main` é verdadeiro de `main`: trabalho que estiver só local está etiquetado `LOCAL — awaiting push`, nunca descrito como "landed"
- [ ] Nenhum claim de closure: closure é adjudicado pelo canal owner; hand‑offs reportam estado + gaps
- [ ] Flip = último commit de conteúdo; citation = commit final separado, citando um run cujo tree É o flip, com nada landed depois dele

## 3. Definições permanentes

- **Par** = (test, número de run, sha). Ids sozinhos apodrecem; números sozinhos driftam; ambos, de `gh run list`.
- **Evidência** = output de comando colado. Memória = hipótese. Hipóteses são etiquetadas como tais.
- **Sanção do dono** = uma mensagem de canal citada. Coisa alguma outra é atribuição; falsa atribuição é classe TD‑30.
- **LOCAL** prefix = verdadeiro e não landado. Nuncaupgrade LOCAL para landed.

## 2. Falhas que este código codifica (o registro E9 — por que cada regra existe)

| Regra | A falha que mata |
|---|---|
| §1 `gh run list` | IDs de run inventados (ex.: #167, 33943000000); números fora do esperado (ex.: #155→#156) |
| §1 surefire/grep | Contagens inventadas (ex.: 21/21, 22, 1/1·6/6·2/2·10/10·3/3 vs real 31/34) |
| §2 sha check | Citar commits que resolvem em lugar nenhum |
| §2 main-claims | "re-enabled" contra commit message lendo "disabled (HOLD)"; narrativa Known‑Gap sobre um teste @Disabled |
| §2 owner-quote | `@Disabled("HOLD: owner re-baselining…")` sem autorização do owner |
| §1 arithmetic | Lista correta por classe, soma errada (TD‑34: 1+6+2+10+3+1 dito como 22 — a própria correção TD‑31 carregava o off‑by‑one que corrigiu) |
| §2 no-closure | Quatro declarações consecutivas "E9 CLOSED" de um mesmo épico |

## 3. Checklist de conclusão do Épico 1

- [ ] `check-boundaries.sh` → **PASS** (0 violações)  
- [ ] `check-boundaries.sh --self-test` → **PASS**  
- [ ] `lessons.md` → lição(ões) promovida(s) para `coding-standards.md`  
- [ ] `coding-standards.md` → última versão com todas as regras novas  
- [ ] `AGENTS.md` → matriz de dívida sincronizada; status de cada item atualizado (open/in‑progress/resolved)  
- [ ] `./mvnw verify` (gate completo) → todos os sub‑gates verdes (unit, SpotBugs, JaCoCo, ArchUnit)  
- [ ] Handoff‑DOD → bloco de evidências colado; self‑audit verde; nenhuma hipótese sem etiquetar  

*Ao marcar todos os itens acima, o Épico 1 é considerado **concluído** e o próximo épico (EP2 – Secure) pode ser iniciado.*

--- 

*Este documento deve ser incluído em cada PR/merge hand‑off relacionado ao Épico 1. Sem o bloco de evidências e o self‑audit, o hand‑off será rejeitado pelo canal owner.*