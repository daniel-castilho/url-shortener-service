# Relatório: Do Desenvolvimento Orientado a Especificação (SDD) para Especificações Vivas
## Aplicação ao Projeto URL Shortener Service

**Data:** 2026-09-13  
**Baseado em:** "From Spec-Driven Development to Living Specifications in Java Projects" — Maximillian Arruda (foojay.io, 2026-09-04)  
**Projeto:** URL Shortener Service — Java 25, Spring Boot 4.1.1, Arquitetura Hexagonal

---

## Resumo Executivo

O artigo descreve a evolução do **Spec-Driven Development (SDD)** para **Especificações Vivas (Living Specifications)** — especificações que permanecem úteis após a mudança integrar ao software, tornando a divergência visível e a convergência barata.

Nosso projeto **já pratica muitos princípios fundamentais** (limites de arquitetura explícitos, testes como evidência executável, documentação sincronizada via gates), mas há oportunidades claras para aproximar a especificação do código, tornar requisitos rastreáveis e automatizar a detecção de deriva.

---

## 1. Mapeamento Estado Atual vs. Conceitos do Artigo

| Conceito do Artigo | Estado Atual no Projeto | Gap / Oportunidade |
|---|---|---|
| **Especificação como fonte de verdade** | `AGENTS.md`, `README.md`, `MONGODB_ARCHITECTURE.md`, `docs/` — documentação rica, mas **fora do código** | Mover especificações de componente para `package-info.java` (Markdown Javadoc) |
| **Triângulo Spec-Test-Code** | ✅ Forte: Ports definem contratos, testes validam, código implementa | Falta rastreabilidade explícita: `Requisito → Teste → Código` |
| **Requisitos testáveis (EARS)** | Requisitos implícitos em testes e docs; sem IDs estáveis | Adotar notação EARS com IDs (`REQ-XXX`) vinculados a testes |
| **Especificação viva (Living Spec)** | Docs sincronizadas via `check-doc-sync.sh`, mas **manuais** | Automatizar detecção de deriva: especificação ↔ testes ↔ código |
| **SBCE: especificação junto ao código** | Especificações em `/docs` e raiz, separadas do código | `package-info.java` em cada *Business Component* (ex.: `core/model`, `core/service`, `infra/adapter/output/persistence`) |
| **Adaptadores de arquitetura (SDD4J)** | Arquitetura Hexagonal fixa (Ports & Adapters) | Já temos limites claros; poderíamos formalizar *Business Components* dentro da hexagon |
| **Inversão de Controle no processo** | Skills customizados para opencode; gates como validação | Compor skills por componente (ex.: skill de validação de domínio, skill de persistência) |

---

## 2. Oportunidades Concretas de Melhoria

### 2.1 Especificação por Componente (`package-info.java`)

**Proposta:** Cada *Business Component* (capacidade de negócio coesa) ganha `package-info.java` com Markdown Javadoc contendo:
- Propósito do componente
- Requisitos EARS com IDs estáveis (`REQ-SHORTEN-001`, `REQ-REDIRECT-002`, etc.)
- Contratos de entrada/saída (Ports)
- Decisões arquiteturais locais (ADR inline)

**Exemplo — `core/service/package-info.java`:**
```java
/**
 * # Componente: UrlShortenerService (Encurtamento de URLs)
 *
 * ## Propósito
 * Orquestra o fluxo de encurtamento: validação, geração de código, persistência,
 * quota, cache e métricas.
 *
 * ## Requisitos (EARS)
 *
 * ### REQ-SHORTEN-001
 * **Quando** uma requisição de encurtamento chega com URL válida e quota disponível,
 * **o Business Component deve** gerar código Base62 único, persistir e retornar o code.
 *
 * ### REQ-SHORTEN-002
 * **Quando** alias personalizado (vanity) é solicitado e já existe,
 * **o Business Component deve** rejeitar com `AliasAlreadyExistsException`.
 *
 * ### REQ-QUOTA-001
 * **Quando** usuário atinge limite de URLs vanity do plano,
 * **o Business Component deve** rejeitar com `QuotaExceededException`.
 *
 * ## Ports (Contratos)
 * - Inbound: `ShortenUrlUseCase`, `GetUrlUseCase`
 * - Outbound: `UrlRepositoryPort`, `UserRepositoryPort`, `UrlCachePort`, `IdGeneratorPort`, `MetricsPort`, `RateLimiterPort`
 *
 * ## Decisões Locais (ADR inline)
 * - Geração de ID: Base62 aleatório criptográfico, 7 chars, retry em colisão (não Hashids, não contador Redis)
 * - Sem deduplicação de URL: mesma URL longa → códigos distintos
 * - Namespace isolado: códigos gerados (7 chars Base62) vs vanity (min-length + `-`/`_`)
 */
package ca.tyny.urlshortener.core.service;
```

**Benefícios:**
- Especificação **no mesmo diretório** do código → descoberta natural por humanos e agentes
- `javadoc` nativo gera HTML navegável
- Java 25 suporta Markdown em Javadoc nativamente
- Agentes (opencode, etc.) leem `package-info.java` como contexto imediato

---

### 2.2 Requisitos EARS com Rastreabilidade (Requirement → Test → Code)

**Proposta:** Adotar padrão EARS para requisitos e vinculá-los a testes via anotação ou convenção de nomenclatura.

**Formato EARS (do artigo):**
```
When <trigger>, the Business Component shall <response>.
```

**Mapeamento no projeto:**
| ID Requisito | EARS | Teste(s) Vinculados | Código |
|---|---|---|---|
| `REQ-SHORTEN-001` | When a shorten request arrives with valid URL and available quota, the BC shall generate a unique Base62 code, persist it, and return the code. | `ShortenFlowIT.shouldShortenUrl`, `UrlShortenerServiceTest.shouldGenerateUniqueCode` | `UrlShortenerService.shorten()` |
| `REQ-REDIRECT-001` | When a redirect request arrives for an existing code, the BC shall return 302 with original URL and track click asynchronously. | `RedirectIT.shouldRedirect`, `ReadPathIT` | `UrlController.redirect()`, `UrlShortenerService.getOriginalUrl()` |
| `REQ-RATE-001` | When redirect requests exceed per-IP bucket, the BC shall reject with 429 and `Retry-After` header. | `RedirectRateLimitIT` | `RateLimiterAdapter`, `UrlController.redirect()` |

**Implementação prática:**
- Convenção de nomenclatura de testes: `*Test#REQ_XXX_*`
- Anotação custom `@TracesRequirement("REQ-XXX")` (opcional, para tooling)
- Gate CI: verifica se todo `REQ-` em `package-info.java` tem teste correspondente

---

### 2.3 Gate de Detecção de Deriva (Living Spec Gate)

**Proposta:** Novo script `scripts/check-living-spec.sh` (rodando no `verify` + CI) que verifica:

1. **Spec → Test:** Todo `REQ-XXX` em `package-info.java` tem ao menos um teste com `@TracesRequirement("REQ-XXX")` ou nome contendo `REQ_XXX`
2. **Test → Code:** Testes de integração/E2E exercitam os Ports declarados no componente
3. **Code → Spec:** Mudanças em Ports/UseCases sem atualização do `package-info.java` geram warning
4. **Drift Metrics:** Porcentagem de requisitos cobertos por testes (target ≥ 90%)

**Exemplo de saída:**
```
=== Living Specification Gate ===
Component: core/service (UrlShortenerService)
  REQ-SHORTEN-001 ✅ (ShortenFlowIT:REQ_SHORTEN_001)
  REQ-SHORTEN-002 ✅ (ShortenFlowIT:REQ_SHORTEN_002)
  REQ-QUOTA-001 ⚠️  NO TEST LINKED
  REQ-REDIRECT-001 ✅ (RedirectIT:REQ_REDIRECT_001)
  REQ-RATE-001    ✅ (RedirectRateLimitIT:REQ_RATE_001)

Coverage: 4/5 requirements traced (80%) — TARGET 90%
FAIL: Coverage below threshold
```

---

### 2.4 Business Components Explícitos na Hexagonal

Nossa arquitetura já separa `core/` (domínio + casos de uso) de `infra/` (adapters). Podemos **formalizar Business Components** dentro dessa estrutura:

| Business Component | Pacote(s) | Capacidade de Negócio |
|---|---|---|
| `UrlShortener` | `core/model`, `core/idgeneration`, `core/service/UrlShortenerService`, `core/ports/incoming/ShortenUrlUseCase`, `core/ports/incoming/GetUrlUseCase` | Encurtar URL, resolver redirect |
| `UserManagement` | `core/model/User`, `core/service/UserService`, `core/ports/incoming/RegisterUseCase`, `core/ports/incoming/LoginUseCase`, `core/ports/outgoing/UserRepositoryPort` | Registro, login, quota, planos |
| `Analytics` | `core/model/ClickEvent`, `core/ports/outgoing/AnalyticsPort`, `infra/adapter/output/analytics/*` | Coleta, agregação, retenção de cliques |
| `RateLimiting` | `core/ports/outgoing/RateLimiterPort`, `infra/adapter/output/redis/RedisRateLimiterAdapter` | Proteção anti-enumeration no redirect |
| `Cache` | `core/ports/outgoing/UrlCachePort`, `infra/adapter/output/redis/RedisUrlCache` | Cache-aside + Bloom filter no hot path |

Cada um ganha `package-info.java` com seus requisitos, Ports, e decisões locais.

---

### 2.5 Skills Comporíveis por Componente (Inversão de Controle no Processo)

O artigo propõe **skills compostas** em vez de instruções monolíticas. Para opencode/agents:

| Skill | Responsabilidade | Componente(s) |
|---|---|---|
| `skill-domain-validation` | Validações de domínio (Hostnames, ReservedWords, SSRF) | `UrlShortener`, `UserManagement` |
| `skill-persistence-mongo` | Padrões de repositório Mongo, migrações, índices | `UserManagement`, `Analytics` |
| `skill-cache-redis` | Cache-aside, Bloom filter, L1 Caffeine | `Cache` |
| `skill-rate-limiting` | Token bucket Redis, Lua scripts, headers | `RateLimiting` |
| `skill-hexagonal-boundary` | Verifica `core/` não importa `infra/` | Todos (gate global) |
| `skill-living-spec` | Verifica cobertura REQ→Test, deriva spec↔code | Todos |

**Uso:** Agente carrega apenas skills relevantes ao componente em edição → contexto menor, mais preciso.

---

### 2.6 Documentação Arquitetural como Código (ADR + Spec Unificados)

Atualmente temos:
- `docs/adr/0001-...md` a `0008-...md` (ADRs globais)
- `MONGODB_ARCHITECTURE.md` (modelo de dados)
- `AGENTS.md` (regras, debt matrix, comandos)

**Proposta:** ADRs de escopo de componente vivem no `package-info.java` do componente; ADRs globais ficam em `docs/adr/`. Exemplo: decisão "Base62 aleatório vs Hashids" → `core/idgeneration/package-info.java` (local), não ADR global.

---

## 3. Plano de Implementação Faseado

### Fase 1 — Fundação (1-2 semanas)
- [ ] Adicionar `package-info.java` em 5 Business Components iniciais com Markdown Javadoc
- [ ] Definir convenção EARS + IDs (`REQ-<COMPONENT>-<NNN>`)
- [ ] Criar script `scripts/check-living-spec.sh` (MVP: spec→test coverage)
- [ ] Integrar no `./mvnw verify` (profile `living-spec`)

### Fase 2 — Rastreabilidade (2-3 semanas)
- [ ] Anotação `@TracesRequirement` + convenção de nomes de teste
- [ ] Gate CI falha se cobertura < 90%
- [ ] Relatório HTML consolidado (Javadoc + coverage matrix)

### Fase 3 — Automação Avançada (contínuo)
- [ ] Skill opencode `living-spec` para agentes
- [ ] Detecção automática de deriva: PR altera Port → sugere atualização `package-info.java`
- [ ] Integração com ArchUnit: validar que testes só usam Ports declarados no componente

---

## 4. Benefícios Esperados

| Métrica | Atual | Alvo Pós-Implementação |
|---|---|---|
| **Tempo para onboarding** (novo dev entender um componente) | ~2h (ler docs dispersos) | ~15min (ler `package-info.java` + testes vinculados) |
| **Deriva spec↔code detectada em CI** | Manual (code review) | Automática (gate `check-living-spec`) |
| **Cobertura requisito→teste** | Implícita, não medida | ≥ 90% medida e exibida |
| **Contexto para agentes (opencode)** | AGENTS.md global (grande) | `package-info.java` local (preciso, pequeno) |
| **Manutenção de docs** | Sincronização manual via `check-doc-sync` | Sincronização local (mesmo diretório do código) |

---

## 5. Riscos e Mitigações

| Risco | Mitigação |
|---|---|
| **Overhead de manter `package-info.java`** | Começar apenas nos 3-4 componentes mais mutáveis; autogeração de esqueleto via script |
| **Duplicação com `AGENTS.md`/`docs/`** | Regra clara: `package-info.java` = especificação *local* do componente; `AGENTS.md`/`docs/` = regras *globais*, ADRs transversais, guias operacionais |
| **Testes legados sem IDs EARS** | Migração incremental: novos testes ganham ID; legacy marcados `REQ-LEGACY` com debt item |
| **Java 25 Markdown Javadoc — tooling** | Verificar `javadoc` tool, IDE support; fallback: Javadoc HTML + Markdown renderizado no GitHub/GitLab |

---

## 6. Conclusão

O artigo valida que **especificações próximas ao código, testáveis e rastreáveis** são o antídoto para a deriva inevitável entre intenção e implementação — especialmente em projetos com agentes de IA.

Nosso projeto **já tem a base sólida** (hexagonal, ports explícitos, testes em camadas, gates de arquitetura). A adoção de **Living Specifications via `package-info.java` + EARS + gate de deriva** seria uma evolução natural, não uma revolução.

**Próximo passo recomendado:** Prova de conceito no componente `UrlShortener` (maior superfície de mudança) — criar `core/service/package-info.java` com 5-8 requisitos EARS, vincular testes existentes, rodar gate experimental.

---

## Apêndice: Referências do Artigo

- **SLDD (Spec-Driven Development loop):** https://github.com/soujava/sldd-skills
- **SBCE (Specification by Business Component):** https://sbce.space/ | https://bce.design/
- **SDD4J (Spec-Driven Development for Java):** https://github.com/soujava/agent-skills
- **OpenSpec:** https://openspec.dev/
- **GitHub Spec Kit:** https://github.github.com/spec-kit/
- **EARS (Easy Approach to Requirements Syntax):** https://alistairmavin.com/ears/
- **Artigo original Loiane Groner:** https://loiane.com/2026/03/vibe-coding-with-specs-driven-feedback-loops/