# Epic 1 – Stories (Aceitação)

| # | Story | Critérios de Aceitação | Referência Ágil / Âncora |
|---|-------|------------------------|--------------------------|
| **1.1** | **Arquitetura hexagonal formalizada** – mover todas as anotações `@Component/@Service/@Repository` do `core/` para `infra/`; criar `ServiceConfig` registrando beans domain‑only. | • `bash scripts/check-boundaries.sh` → **PASS** (0 violações) <br>• `bash scripts/check-boundaries.sh --self-test` → **PASS** (gate auto‑verifica) <br>• Testes ArchUnit verdes e sem imports `infra.*` em `core/*.java` | Regra 1 do AGENTS.md (Arquitetura de Borda) |
| **1.2** | **Promoção de lições repetidas** – revisar `lessons.md`; todo padrão que apareceu 3 vezes migrar para `coding-standards.md` e remover da lições. | • `git diff lessons.md` → lições > 20 → promovidas para `coding-standards.md` <br>• `coding-standards.md` atualizado com as novas regras <br>• Nenhuma lição repetida permanece sem ação | Regra 10 do AGENTS.md (Doc Sync is Part of Done) |
| **1.3** | **Padronização de nomes & pacotes** – layout hexagonal vigente verificado e documentado: `core/model/`, `core/ports/incoming/`, `core/ports/outgoing/`, `infra/adapter/{input,rest}/`, `infra/config/` (layout canônico do AGENTS.md — sem rename para os nomes divergentes do template original). | • Busca em toda base: nenhum `package` solto fora das pastas definidas <br>• `./mvnw compile` verde <br>• IDE (IntelliJ/Eclipse) consegue resolver todos os imports automaticamente | Regra 9 do AGENTS.md (Naming & Structure) |
| **1.4** | **Depreciação de código legacy** – remover/classes `unused`, javadocs em inglês, comentários “por que” substituindo “o que”. | • `./mvnw spotless:check` → **PASS** <br>• SpotBugs 0 bugs novos <br>• `git diff --stat` mostra apenas removimentos/limpezas | Regra 1 do AGENTS.md (Convenções de Código) |
| **1.5** | **Matriz de dívida técnica atualizada** – `AGENTS.md` ↔ `lessons.md` ↔ `coding-standards.md` sincronizados; status de cada item (open/in-progress/resolved). | • Revisão manual + script de checagem <br>• Todos os itens têm status definido <br>• Nenhum “pending” sem data de previsão | Regra 10 + Regra 11 do AGENTS.md (Doc Sync + Lessons Sync) |

---

**Rastreabilidade rápida:**

| Story | Doc referência | AGENTS.md |
|-------|----------------|-----------|
| 1.1 | `check-boundaries.sh` output | Regra 1 |
| 1.2 | `lessons.md` → `coding-standards.md` diff | Regra 10 |
| 1.3 | Estrutura de pastas do projeto | Regra 9 |
| 1.4 | `spotless:check` + SpotBugs | Regra 1 |
| 1.5 | `AGENTS.md` ↔ `lessons.md` ↔ `coding-standards.md` diff | Regra 10 + Regra 11 |