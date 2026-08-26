# Roadmap de correção — url-shortener-service → padrão "alto nível de excelência"

**Baseado na:** `AUDITORIA_URL_SHORTENER.md` (evidências do código) + decisões do dono:
- ✅ **Estratégia de ID → migrar para base62 aleatório** (sair de contador + Hashids).
- ✅ **Dedup de URL → NÃO** (remover o índice único de `originalUrl`).
- 🎯 **Entregável:** plano priorizado de correção (roadmap), com ordem, esforço e critérios de aceite.

> **Premissa de priorização:** primeiro o que quebra correção/segurança (P0), depois a mudança estrutural de ID/dedup (as duas decisões), depois recursos prometidos que não existem (analytics/TTL), depois integridade de arquitetura, depois operação/doc/testes.

---

## Fase 0 — Fixes rápidos de correção/segurança (baixo risco, alto valor)

### T0.1 — Métrica duplicada `urls.shortened.total` (+2/request)  ·  Esforço: **S**
- **Problema:** `recordUrlShortened()` é chamado em `UrlShortenerService` (via `MetricsPort`) e em `UrlController` (via `MetricsService`), ambos no mesmo `Counter` Micrometer → cada encurtamento conta 2.
- **Ação:** manter apenas **uma** chamada (recomendo remover a do `UrlShortenerService`/`MicrometerMetricsAdapter` e deixar o controller, que já mede latência). Como `MetricsPort` ainda é usada para cache hit/miss/bloom, manter a interface, só remover o método `recordUrlShortened` de lá (ou não chamar).
- **Aceite:** teste verifica que 1 encurtamento → `urls.shortened.total` = +1.

### T0.2 — Rate limit no redirect (`GET /{id}`)  ·  Esforço: **S**
- **Problema:** throttling só existe no `POST`. O path de redirecionamento (quente e enumerável) não tem limite.
- **Ação:** aplicar `rateLimiter.isAllowed(clientIp)` também no método `redirect`, com uma política configurável (pode ser mais alta ou separada da de create).
- **Aceite:** ao disparar >N requisições ao mesmo `/{id}` a partir do mesmo IP, o serviço responde `429`; testes de integração cobrem o path de redirect.

### T0.3 — Bloom filter: curto-circuitar de fato o acesso ao banco  ·  Esforço: **S/M**
- **Problema:** `urlCache.get()` retorna `null` para IDs fora do bloom, mas `getOriginalUrl` consulta o Mongo mesmo assim → o claim "invalid IDs don't reach the database" é falso.
- **Ação:** no `getOriginalUrl`, tratar o resultado do cache como "não encontrado" sem ir ao DB quando o bloom rejeitar. Duas opções:
  - A) `UrlCachePort.get` devolver um tipo que distinga "não existe (bloom)" de "não está no cache (miss)" — aí o serviço não consulta o banco para bloom-negative (usa um `Optional`/sentinel).
  - B) O service checa `bloomFilter.contains` via uma nova operação na porta e lança 404 direto.
- **Aceite:** sequência `GET /id-inexistente` N vezes não gera N queries no Mongo (verificável por logs/meteria de DB).

### T0.4 — Remover métricas mortas (`id.generation.duration`, `url.retrieval.duration`)  ·  Esforço: **S**
- **Ação:** ou ligar os timers (cronometrar `generateId` e a busca) ou removê-los de `MetricsService` para não deixar métricas que nunca aparecem.
- **Aceite:** não há métricas definidas e nunca gravadas; ou todas as métricas registradas são escritas.

---

## Fase 1 — Migração estrutural (as 2 decisões): base62 aleatório + sem dedup  ·  Esforço: **L**  ·  *acopladas, fazer juntas*

Cobre as decisões do dono e é a mudança que **inverte** o design atual do repo.

### T1.1 — Nova geração de ID: base62 aleatório (CSPRNG)  ·  Esforço: **M**
- **Objetivo:** substituir `RangeAwareIdGenerator` (contador Redis + Hashids) por um gerador puramente aleatório.
- **Design:**
  - Alfabeto base62: `0-9 A-Z a-z`.
  - Comprimento: **7** (configurável via `app.shortener.code-length`).
  - Fonte aleatória **criptograficamente segura**: `java.security.SecureRandom` (não `Random`).
  - Implementar como estratégia `RandomUrlIdStrategy` que produz o código **sem** depender de Redis.
- **Colisão:** probabilística. Usar o `_id` único do Mongo como guarda: na persistência, se houver `DuplicateKeyException` → **retry** (gerar novo código) até N tentativas; garantir limite de retries para não loopar infinito.
  - Ajustar o `MongoUrlRepository.save` para distinguir "colisão de código" (retry no caso de código gerado) de "alias vanity já existe" (409).
- **Limpeza:** remover a dependência `org.hashids`, a config `app.shortener.salt`, e o `RangeAwareIdGenerator`/`IdGeneratorPort` se não forem mais usados.
- **Aceite:** IDs são base62 de 7 chars, verificados como aleatórios; teste de colisão (repetir N vezes) passa; sem chamadas ao Redis no caminho de geração.

### T1.2 — Isolamento de namespace (código gerado × alias vanity)  ·  Esforço: **S**
- **Problema:** código gerado e alias de usuário compartilham o mesmo `_id`/collection.
- **Ação:**
  - Manter o `ReservedWordsValidator` (bloqueia palavras de rota).
  - Adicionalmente, no gerador, **checar que o código não colide com palavras reservadas** (barato) e garantir que o alias do usuário, ao ser criado, passe pela checagem `existsById`.
  - Recomendado (design): gerar código com **exatamente 7** e exigir que aliases tenham **≠ 7** ou pertençam a conjunto separado — documentar a convenção.
- **Aceite:** nenhum código gerado coincide com palavra reservada; teste tenta criar alias com valor de rota e é rejeitado.

### T1.3 — Remover dedup: tirar o índice único de `originalUrl`  ·  Esforço: **S**
- **Problema:** `@Indexed(unique = true)` em `originalUrl` força 1 curta por URL e, no fluxo atual, uma URL duplicada vira `AliasAlreadyExistsException` (409 enganoso).
- **Ação:**
  - Remover o `unique` de `originalUrl` (ajustar `ShortUrlEntity` + criar uma migração para dropar o índice no Mongo).
  - **Opcional (futuro):** se mais tarde quiser consultar por URL (analytics/dedup), adicionar um **índice não-único** e um campo `urlHash` (SHA-256) — guardar desde já o hash para não precisar migrar depois (custo zero agora).
  - Ajustar a semântica: `409` passa a significar "alias customizado já existe", **não** "URL duplicada". URL duplicada agora é permitida e gera um link novo.
- **Aceite:** encurtar a mesma URL duas vezes cria **2** códigos distintos, ambos redirecionando certo; não há mais único em `originalUrl`.

### T1.4 — Atualizar docs/config da migração de ID  ·  Esforço: **S**  ·  **feito (V2)**
- Remover de `README.md`/docs referências a "Counter-Based Shuffle", "Hashids", "Zero Collision por contador", e `SHORTENER_SALT` como desenho atual.
- Corrigir a estratégia em `MONGODB_ARCHITECTURE.md`.
- **Aceite:** docs descrevem a estratégia travada (base62 aleatório + retry de colisão).

---

## Fase 2 — Recursos prometidos que não existem (valor real)

### T2.1 — Analytics real: persistir cliques + contador  ·  Esforço: **L**
- **Problema:** `ClickBatchWorker` só loga; eventos são descartados; não há `click_count`.
- **Ação:**
  - Persistir `ClickEvent` em um **novo collection** (`click_events`) no worker (batch insert), **fora** do path de redirect.
  - Adicionar `clickCount` a `ShortUrl` e **incrementar atomicamente** (Mongo `$inc`) no worker.
  - Considerar **fila durável** (Redis Stream/Kafka) em vez da fila em memória (que descarta quando cheia).
- **Aceite:** N cliques em um link → `click_events` tem N registros e `clickCount` = N; o redirect **não** bloqueia esperando a escrita.

### T2.2 — Expiração (TTL) de links  ·  Esforço: **M**
- **Ação:** adicionar `expiresAt` a `ShortUrl`; campo opcional na criação; **índice TTL** no Mongo; o `getOriginalUrl` deve validar expiração (retornar erro/link expirado, não redirecionar); job de purga opcional.
- **Aceite:** link com `expiresAt` no passado não redireciona e responde com status adequado; teste cobre expirado.

---

## Fase 3 — Integridade de arquitetura

### T3.1 — Corrigir Dependency Inversion em `core/service/UserService`  ·  Esforço: **M**
- **Problema:** importa `MongoUserRepository`, `JwtTokenProvider` e DTOs de infra (`AuthResponse`, `LoginRequest`, `RegisterRequest`).
- **Ação:** fazer `UserService` depender de **portas** (`UserRepositoryPort`, uma abstração de token/JWT) e trabalhar com objetos de **domínio** (`User`, comandos); mover o mapeamento DTO→domínio para a camada de adaptador (`AuthController`). 
- **Aceite:** a camada `core` **não** importa classes de `infra.*`; gregar `grep` por `import ca.tyny.urlshortener.infra` dentro de `core/` retorna vazio (ou apenas tipos que você decida acitar).

### T3.2 — Camada `core` sem anotações Spring (ou documentar a escolha)  ·  Esforço: **S/M**
- **Ação:** mover `@Component`/`@Service`/`@RequiredArgsConstructor` de `core` para config/registro (bean) na camada de infra, deixando `core` puro; ou, se preferir manter, **documentar explicitamente** que o core usa as anotações embora não dependa de Spring em runtime.
- **Aceite:** ou `core` não tem imports/annotations de Spring, ou a doc declara e justifica.

### T3.3 — Livrar de names inline (FQCN)  ·  Esforço: **S**
- `UrlShortenerService` referencia `ca.tyny.urlshortener.core.validation.ReservedWordsValidator` sem `import`; idem `GlobalExceptionHandler`/`UrlController`.
- **Aceite:** sem nomes totalmente qualificados inline (usar `import`).

---

## Fase 4 — Endurecimento de segurança

### T4.1 — Validação de URL e bloqueio de destino  ·  Esforço: **M**
- Fortalecer o value object `Url`: validar host de verdade, preferir/bloquear `http://` por config, **bloquear IPs privados/metadata** (169.254.0.0/16, 127.0.0.0/8, RFC1918...) para mitigar SSRF, e integrar **blocklist/reputação** do destino (hook p/ Safe Browsing, VirusTotal, PhishTank) — pelo menos como ponto de extensão.
- **Aceite:** URLs com host malformado/interno são rejeitadas; há hook documentado para verificar reputação.

### T4.2 — Restringir exposição de operação  ·  Esforço: **S**
- `/actuator/**` e Swagger `permitAll` + `health.show-details: always` vazam informação.
- **Ação:** em profile de produção, restringir actuator/health (auth ou rede interna) e reduzir detalhes; limitar exposição do Swagger.
- **Aceite:** sem info sensível em endpoints públicos em prod.

---

## Fase 5 — Operação, doc, padronização de qualidade

### T5.1 — Migração de schema/índices versionada  ·  Esforço: **M**
- Trocar `auto-index-creation: true` por **framework de migração** (ex.: mongock/fluent migrations) e gerenciar os índices em passo de deploy. Necessário, em especial, para **dropar** o índice único de `originalUrl` (T1.3).
- **Aceite:** migrações versionadas, aplicadas de forma explícita e reproduzível.

### T5.2 — Alinhar documentação à realidade  ·  Esforço: **S/M**  ·  **feito (V2)**
- Remover/corrigir links para `AUDIT_FINAL_REPORT.md`, `VALIDATION_CHECKLIST.md`, `LESSONS_LEARNED.md` (inexistentes).
- Remover referências a **Cassandra** (é MongoDB).
- **Remover os scores auto-atribuídos** ("9.2/10", "Clean Architecture 10/10", "Production Ready") ou substituí-los por meta verificável.
- Remover artefatos de build (`build.log`, `build_out.txt`) e adicionar a `.gitignore`.
- Product docs descrevem o **modelo de identidade travado** (Base62, sem dedup, namespace); Hashids/unique-on-URL não são o desenho do produto.
- **Aceite:** docs descrevem o contrato travado; gaps de código ficam na matriz de dívida (`AGENTS.md`).

### T5.3 — Corrigir bug do build native  ·  Esforço: **S**
- `pom.xml` `native` → `<mainClass>ca.tyny.urlshortener.infra.Application</mainClass>` deve ser `ca.tyny.urlshortener.Application`.
- **Aceite:** `mvn clean package -Pnative` resolve o main class corretamente.

### T5.4 — Observabilidade, TLS e deploy on-prem  ·  Esforço: **M/L**
- Adicionar **tracing (OpenTelemetry)** e alertas; definir **SLOs** e **harness de carga** (k6/JMeter) para registrar p50/p95/p99 reais.
- Documentar **terminação TLS** (reverse proxy) e deploy em bare metal (systemd/manual); opcionalmente incluir o app no compose.
- **Aceite:** há medilão de latência/throughput real e rota documentada de deploy+TLS.

---

## Fase 6 — Testes e CI

### T6.1 — Preencher lacunas de cobertura  ·  Esforço: **M**
- **Read path** com stack completo de cache (L1/L2/Bloom) e o comportamento de "bloom-negative".
- **Analytics worker** e persistência de cliques.
- **Concorrência**: race do alias vanity e **incremento atômico** de cota.
- **Segurança**: JWT com secret default, redirect aberto, URL inválida, SSRF/IP privado, rate limit no redirect.
- **Colisão de código** no novo gerador base62.

### T6.2 — CI com verificação real  ·  Esforço: **S/M**
- Rodar `mvn verify` com Testcontainers em CI (validar "todos os testes passam" de fato).

---

## Ordem recomendada de execução

```
Fase 0 (S)  →  Fase 1 (L, decisões)  →  Fase 2 (L, recursos)  →  Fase 3 (M)  →  Fase 4 (M)  →  Fase 5 (M/L)  →  Fase 6 (M)
```
- **Fase 0** e **Fase 1** primeiro (correção + decisões de ID/dedup) — destravam o resto.
- **Fase 1** e **Fase 2** são as mais impactantes (mudam comportamento e adicionam valor). 
- **Fase 5.2 (doc)** pode ser feita cedo/em paralelo para travar as alegações.

### Definição de "alto padrão" (Definition of Done do esforço)
- [ ] Todos os itens **P0** resolvidos (correção + segurança).
- [ ] Migração de ID concluída (base62 aleatório) e **sem dedup**.
- [ ] Analytics **persistem** e `clickCount` é correto; **TTL/expiração** funcional.
- [ ] `core` não depende de `infra.*` (Dependency Inversion ok) e sem nomes inline.
- [ ] Segurança: sem redirect aberto indevido, com rate limit no redirect, URL validada, actuator restrito.
- [ ] Migrações versionadas; docs alinhadas à realidade; build native funciona.
- [ ] CI rodando; testes cobrindo os cenários críticos (inclusive concorrência/segurança).
- [ ] SLOs medidos (latência p50/p95/p99) sob carga no volume real.

---

## Observações
- **Escopos Fase 0/1 são os de maior ROI** (correção + mudança de design).
- **Não inflar para hyperscale**: o repositório já tem CDN/sharding/Kafka/Blom/Redis cluster como "features", mas para **on-prem/bare metal** isso costuma ser over-engineering — priorize apenas o que resolve o problema do seu volume real. (Reforço do item P2-9 da auditoria.)
- Qualquer item pode ser dividido em PRs menores; o roadmap é o alvo, não o plano de commits.
