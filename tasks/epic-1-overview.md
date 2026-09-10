# Epic 1: Maintainable – Fundação & Padrões

**Projeto:** url-shortener-service  
**Contexto:** Java 25, Spring Boot 4.1.1, Arquitetura Hexagonal (Ports & Adapters), MongoDB, Redis  
**Objetivo:** Consolidar a base do projeto para que todas as entregas futuras respeitem contratos claros, convenções compartilhadas e estrutura de código verificável, evitando retrabalho e decadência arquitetural.

---

## Por que este épico em primeiro lugar?

A manutenibilidade é o alicerce sobre o qual todos os outros pilares são construídos. Sem um padrão comum definido agora, as seguintes problémáticas se multiplicam nas iterações seguintes:

- Importações cruzadas `infra.*` em `core/` (violando a regra hexagonal) → exigir refatoração laterante em EP2/EP3.
- Convenções de logging, naming e erro não definidas → testes cegos e ruído nos logs (EP3).
- Matriz de dívida técnica desconectada dos documentos de código → dificuldade de onboarding e auditoria (EP7/EP8).

**Relação com outros épicos:**

| Epico | Dependência direta |
|--------|-------------------|
| EP2 – Secure | Convenções de logging (`logSafe`), nomes de classes e DTOs; regras de validação de entrada. |
| EP3 – Observable | Formato de métricas Prometheus, séries `dargent_*`, estrutura de MDC e correlation‑id. |
| EP4 – Testes | Histórias de teste derivadas dos *stories* abaixo; regra de cobertura por módulo. |
| EP5 – Performance | Gargalos identificados apenas após o código estar dentro de convenções estáveis. |
| EP6 – Scalable | Front‑door (NGINX, rate‑limiter) depende de nomes de endpoints e payloads definidos aqui. |
| EP7 – Reliable | Circuit‑breaker e bulkhead names alinhados com os pacotes e exceções definidos. |
| EP8 – Deployable | Imagens Docker, tags e scripts de CI baseiam‑se nos pacotes e configurações estáveis. |

**Critério de Aceitação Elevado:**

1. `bash scripts/check-boundaries.sh` → **PASS** (0 violações)  
2. `bash scripts/check-boundaries.sh --self-test` → **PASS** (gate auto‑verifica).  
3. Todas as anotações `@Component/@Service/@Repository` removidas de `core/`; beans registrados via `infra/config/ServiceConfig`.  
4. `lessons.md` → `coding-standards.md` promoções concluídas; lições repetidas > 2 migradas, removidas da lista de pendentes.  
5. Pacote `core/` livre de wildcard imports; todos os imports são explícitos e ≤ 3 linhas.  
6. Matriz de dívida técnica em `AGENTS.md` sincronizada: status `open/in-progress/resolved` para cada item, com data de previsão.  
7. `./mvnw compile` + `./mvnw spotless:check` → **verde**; `./mvnw spotbugs:check` → 0 bugs novo; ArchUnit boundary tests → verdes.

**Rastreabilidade:**

| Story | Documento de referência | Agentes/AGENTS.md |
|-------|------------------------|-------------------|
| 1.1 | `core/` livres de imports `infra.*` | Regra 1 (Arquitetura de Borda) |
| 1.2 | `lessons.md` → `coding-standards.md` promoção | Regra 10 (Doc Sync is Part of Done) |
| 1.3 | Pacote `core/model/`, `core/ports/{incoming,outgoing}/`, `infra/adapter/` definidos | Regra 9 (Namig & Structure) |
| 1.4 | Remoção de classes `unused`, javatrans em inglês, comentários “por que” | Regra 1 (Coding Conventions) |
| 1.5 | `AGENTS.md` ↔ `lessons.md` ↔ `coding-standards.md` sincronizados | Regra 10 + Regra 11 (recém‑adicionada) |

--- 

*Próximo passo: revisar `core/` com `check-boundaries.sh` e promover a primeira lição repetida para `coding-standards.md`.*