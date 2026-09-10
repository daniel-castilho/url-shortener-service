# Epic 1 – Tasks Técnicas

## 1.1 Auditoriar e corrigir fronteiras do `core/`
- [ ] Executar `bash scripts/check-boundaries.sh` e registrar saída.
- [ ] Executar `bash scripts/check-boundaries.sh --self-test` (planta uma violação temporária e_assert a gate catches it).
- [ ] Corrigir quaisquer imports `infra.*` encontrados em `core/*.java`:
    - Mover a dependência para `infra/` atrás do port/outbound correspondente.
    - Ou criar um port abstrato em `core/` e implementação em `infra/`.
- [ ] Confirmar que `./mvnw compile` ainda verde após cada mudança.
- [ ] Confirmar que `./mvnw spotless:check` continua verde.

## 1.2 Promover lições repetidas para `coding-standards.md`
- [ ] Rodar um script ou revisão manual que conte ocorrências de cada lição em `lessons.md`.
- [ ] Identificar lições com contagem ≥ 3.
- [ ] Copiar o padrão (trecho + regra dourada) para `coding-standards.md` sob uma nova seção “Herde‑de‑Lições”.
- [ ] Remover a lição promovida de `lessons.md` (ou marcar como `→ coding-standards`).
- [ ] Atualizar a matriz de dívida em `AGENTS.md` com a nova referência.

## 1.3 Padronizar pacotes e nomes de classes
- [ ] Listar todos os pacotes atuais sob `src/main/java`.
- [ ] Verificar a aderência ao layout canônico do AGENTS.md (sem rename — o layout vigente é a fonte de verdade):
    - `core/model/`
    - `core/ports/incoming/`
    - `core/ports/outgoing/`
    - `core/service/`
    - `infra/adapter/input/rest/`
    - `infra/adapter/output/persistence/`
    - `infra/adapter/output/redis/`
    - `infra/config/`
- [ ] Confirmar que nenhum `package` fica solto fora das pastas definidas.
- [ ] Confirmar que `./mvnw compile` e `./mvnw test` ainda verdes.

## 1.4 Remover código legacy e comentários “por que”
- [ ] Rodar `./mvnw spotless:check` em modo *check* para identificar arquivos que não seguem o Google Java Style (4‑space, 120‑col).
- [ ] Identificar classes `unused` (por meio de `./mvnw dependency:tree -uf` ou remoção manual).
- [ ] Remover classes/arquivos desnecessários; confirmar que não há efeito colateral.
- [ ] Reescrever comentários que explicam *o que* o código faz para explicar *por que* a decisão foi tomada (ex.: “usamos Instant em vez de Date para evitar fuso‑horário acidental na geração de TTL”).
- [ ] Confirmar `./mvnw spotless:check` verde.

## 1.5 Sincronizar matriz de dívida técnica
- [ ] Garantir que cada item em `AGENTS.md` “Known Technical Debt” tenha campo `status: open/in-progress/resolved` e data de previsão.
- [ ] Cross‑check com `lessons.md`: toda lição promovida deve ter seu rastro na matriz.
- [ ] Manter itens `resolved` na matriz como trilha de auditoria (política Regra 10 do AGENTS.md); apenas garantir que cada item tem status e data corretos.
- [ ] Comitar as alterações em `AGENTS.md` e enviar PR com rótulo `docs: sync technical debt matrix`.

--- 

**Checklist de conclusão do Épico 1:**

- `check-boundaries.sh` → PASS (0 violations)  
- `lessons.md` → promovido(s) para `coding-standards.md`  
- `coding-standards.md` → último versionamento com todas as regras novas  
- `AGENTS.md` → matriz de dívida sincronizada, status atualizado  
- `./mvnw verify` (gate completo) → todos os sub‑gates verdes (unit, SpotBugs, JaCoCo, ArchUnit)  

*Próximo épico: EP2 – Secure (segurança by design).*