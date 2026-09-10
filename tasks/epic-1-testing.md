# Epic 1 – Estratégia de Testes

Este épico foca na fundação; portanto os testes aqui são de **conferência de estrutura** e **validação de convenções**, não de lógica de negócio (essa responsabilidade vem nos épicos seguintes).

## 1.1 Unit‑testes de fronteira (boundary checks)
- **Objetivo:** Confirmar que o `check-boundaries.sh` relta 0 violações.
- **Ação:** 
  - `./mvnw test` (apenas unitários do `core/`), sem Docker.
  - Verificar saída do script e capturar o código de retorno.
- **Critério de aceite:** Script retorna 0 e imprime “PASS”.

## 1.2 Testes de integração de pacotes (`*Test`)
- **Objetivo:** Garantir que a reorganização de pacotes não quebra a compilação nem os testes unitários.
- **Ação:** 
  - `./mvnw test -Dtest='*Test'` (classe que termina em `Test`, não `IT`).
  - Verificar cobertura JaCoCo por módulo (`core` ≥ 70 % linha, se o floor for subido após medição — gate atual: LINE ≥ 60% global).
- **Critério aceite:** Cobertura não inferior ao definido; `./mvnw verify` verde.

## 1.3 Auto‑auditoria da matriz de dívida
- **Objetivo:** Validar que `AGENTS.md` ↔ `lessons.md` ↔ `coding-standards.md` estão alinhados.
- **Ação:** 
  - Script simples (bash/python) que extrai rótulos `status:` de cada arquivo e compara.
  - Caso haja divergência, o script falha e imprime diferenças.
- **Critério aceite:** Saída “Sincronizado” ou lista de divergências a corrigir antes de fechar o épico.

## 1.4 Documentação como código (Handoff‑DOD)
- **Objetivo:** Garantir que todo relato de conclusão deste épico siga a regra *zero‑from‑memory* do `handoff-dod.md`.
- **Ação:** 
  - Ao gerar o resumo do épico, colar sempre comandos reais (`git log`, `git status`, `gh run list`, contagens do Surefire).
  - Marcar qualquer número ou sha que venha de memória como **Hipótese (TD‑13)**.
- **Critério aceite:** O bloco de evidências do handoff contém apenas outputs colados; nenhuma linha “eu acho que foi …”.

## 1.5 Integração contínua (CI)
- **Objetivo:** Que todo *push* para `main` valide a manutenibilidade antes de permitir merge.
- **Pipeline (resumo):**
  - `build` → `./mvnw test` + `./mvnw verify` (inclui ArchUnit, SpotBugs, JaCoCo).
  - `boundary-gate` → execução `scripts/check-boundaries.sh` (com `--self-test`).
  - `doc-sync` → verificação de promoção de lições e sincronia da matriz.
- **Critério aceite:** Pipeline verde em branch `main`; falha em qualquer um dos gates bloqueia merge.

--- 

**Pós‑Épico 1:** Todos os testes acima fazem parte da gate `./mvnw verify` a partir de agora; qualquer nova história que toque em `core/` deve passar pelos checks de fronteira antes de ser mergida.