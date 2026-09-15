# Epic 10 – Tasks Técnicas

Regra geral (house): commits em inglês sem acentos; código em inglês; docs de
épico em PT-BR (padrão `tasks/epic-*`); ADR/CHANGELOG em inglês; `git add -p`
por story; cada commit com `./mvnw verify` + gates bash verdes antes do push
(rule zero: colar outputs no DoD). Linha-âncora: re-verificar com grep antes
de editar — os nomes abaixo foram verificados em `d48bc88`.

## 10.1 Papel ADMIN no token + ADR 0011 (commit 1)

- `docs/adr/0010-*.md` → **novo `docs/adr/0011-admin-role-and-account-block.md`**:
  Status Accepted. Context: SPA owner-scoped, abuso tratado por Mongo+curl,
  operador único, ADR 0010 (dual-write) já vigente. Decision: (1) role por
  **env list** `app.admin-emails` — bootstrap circular (flag no banco) e
  "first user" rejeitados e por quê; claim `role` só no access token; token
  legado sem claim = USER; (2) ocupação do **slot `ROLE_ADMIN` reservado** —
  tiers do actuator (`/actuator` bare, `/actuator/**`) passam a satisfazíveis
  pelo admin de produto (aceito: operador único já tem OPERATOR; demais
  bloqueados); (3) block = campo `blocked` no `User` (dados, não config;
  expands-only, sem V-migration); 403 (não 401) em login/refresh — credencial
  válida, acesso negado; write path check (sessão de bloqueado → 403 em
  `POST /urls`); self-block 400; limite documentado (block por conta, não IP);
  (4) **sem revogação em v1** — JWTs vivem até o expiry (semântica do logout);
  Consequences + Revisit triggers: cross-origin com credenciais → double-submit
  (R2 do ADR 0010); denylist/revogação ao bloquear (mesma família de refresh
  rotation) → design separado; access-TTL reduction → requer o loop de refresh
  da SPA em cookie mode (ver nota do owner).
- `infra/config/properties/AdminProperties.java` (novo, mesmo pacote padrão
  dos `*Properties`): `app.admin-emails` — lista; binding do env
  `APP_ADMIN_EMAILS` comma-separated (ver o padrão de binding da
  `RateLimiterProperties`); normalização trim+lowercase; getter
  `isAdminEmail(String)` (case-insensitive).
- `src/main/resources/application.yaml` + `deploy/url-shortener.env.example`:
  `app.admin-emails: []` (comentário: "Comma-separated admin emails (env
  APP_ADMIN_EMAILS). Empty = no admin. (ADR 0011)") + linha no env.example.
- `core/ports/outgoing/TokenPort`: `generateToken(String email)` →
  `generateToken(String email, String role)` (Javadoc: claim `role`; refresh
  inalterado). Atualizar TODOS os callers (`UserService`) e o adapter
  (`JwtTokenAdapter`) — grep `generateToken` no repo inteiro antes de commit.
- `infra/security/JwtTokenProvider`: claim `role` no access token (parser do
  claim novo: `getRoleFromToken`, ausente → `null`); TTLs/assinatura intactos.
- `infra/security/JwtAuthenticationFilter`: claim → authorities
  (`ROLE_ADMIN`/`ROLE_USER`; `null` → `ROLE_USER`) — o principal de hoje
  (username = email) não muda; manter Bearer-ganha e o Javadoc do
  `getJwtFromRequest` (ADR 0010) intacto.
- `core/service/UserService`: `AuthResult` + campo `role`; `login`/`register`/
  `refreshToken`/`me` computam `role` via `AdminProperties.isAdminEmail`
  (injetar o properties); `me` continua sem criar nada (reusa `findByEmail`).
- `infra/adapter/input/rest/dto/auth/AuthResponse` + o DTO do `/me`
  (`UserResponse` — localizar; é o corpo de `GET /me`): + `role` (aditivo).
  `AuthController.toAuthResponse` passa o campo.
- OpenAPI (neste commit, mínimo): `role` nos schemas/response de
  login/register/refresh/`me` (as story 10.5 completa o resto).
- **Living spec:** `infra/security/package-info.java` (gated): novos requisitos
  EARS na granularidade dos testes — ex. `REQ-AUTH-011` (token carrega claim
  `role` conforme a env list; ausente → `ROLE_USER`), `REQ-AUTH-012`
  (`/me` e bodies de auth expõem `role`); traces nos testes novos
  (`@TracesRequirement`).
- **Testes** (mapeados no `epic-10-testing.md`): `JwtTokenAdapterTest`
  (claim presente/ausente); `JwtAuthenticationFilter` (3 casos de authority —
  localizar a classe de teste existente do filtro ou criar no mesmo pacote,
  traced); `UserServiceTest` (role nos AuthResults + `me`); IT (novo, pacote
  raiz de ITs `ca.tyny.urlshortener.`): **bootstrap** — register com
  `APP_ADMIN_EMAILS` do app de teste → login body `role=ADMIN` → `/me`
  `role=ADMIN`; register fora da list → `USER`; **token legado** (token
  gerado sem claim via provider → request autorizada como USER + `/me`
  `role=USER`).
- CHANGELOG `[Unreleased]` → Added: papel `role` (claim + bodies, aditivo;
  tokens legados sem claim = USER).

## 10.2 Block/unblock + listagem de usuários (commit 2)

- `core/model/User`: + `boolean blocked` (depois de `name`); atualizar todas
  as construções (factory `createFreeUser`, testes, mappers) — grep `new User(`
  no repo.
- `infra/adapter/output/persistence` (entity + mapper do user): campo
  `blocked`; **ausente no doc → `false`**; serializer nunca omite
  `false`-implícito de forma ambígua (mapeamento nos dois sentidos testado em
  `UserEntityTest` — estender).
- `core/ports/outgoing/UserRepositoryPort`: + `PageResult<User> findPage(
  Cursor cursor, int limit)`, + `PageResult<User> findPageByEmailPrefix(
  String emailPrefix, Cursor cursor, int limit)`, + `void setBlocked(String id,
  boolean blocked)`. Impl Mongo: sort `createdAt desc, id` (estável, mesmo
  contrato do list de urls — copiar o padrão do `findPage` existente do link);
  prefixo com `Pattern.quote` (regex-safe); `setBlocked` = `updateOne`
  (expand-only, sem tocar demais campos).
- **Pacotes novos (D3):**
  - `core/ports/incoming/admin/`: `AdminListUsersUseCase`,
    `AdminBlockUserUseCase`, `AdminUnblockUserUseCase` (assinaturas recebem a
    identidade do caller: `callerEmail`, `callerRole` — o port não conhece
    Spring).
  - `core/service/`: `AdminListUsersUseCaseImpl`, `AdminBlockUserUseCaseImpl`,
    `AdminUnblockUserUseCaseImpl` — **enforce de ADMIN no topo de cada impl**
    (`callerRole != ADMIN → throw ForbiddenException`); block/unblock:
    `findById` (404) → **self-block → 400** (`IllegalArgumentException`
    mapeado ao 400 existente no handler — verificar o mapping atual; se não
    existir, criar o handler) → `setBlocked` (idempotente).
  - `infra/adapter/input/rest/admin/AdminController` (novo):
    `@RequestMapping("/api/v1/admin")` — fino: `Authentication` →
    (email = principal name, role = authority) → delega.
- `UserService.login` / `refreshToken`: **após** validar credencial,
  `user.blocked() → throw ForbiddenException("Account blocked.")` (a ordem é
  contratada: inválida → 401 primeiro).
- `infra/config/SecurityConfig`: + `.requestMatchers("/api/v1/admin/**")
  .authenticated()` com comentário (autodocumentação; default já é
  `anyRequest().authenticated()` — o matcher explicita a intenção).
- **Testes** (mapeados no `epic-10-testing.md`): IT (novo
  `AdminUsersIT` no pacote raiz de ITs — package novo = não-gated): USER em
  rota admin → 403; anônimo → 401; paginação (3 users, limit 2); `q` prefixo
  (positivo + negativo); `role` vivo na listagem (register dentro/fora da
  list); `blocked` visível pós-block; block 2× → 2× 204; login de bloqueado →
  403 (body "Account blocked."); credencial inválida de bloqueado → 401
  (ordem); refresh de bloqueado → 403; unblock → login 200; self-block → 400;
  unknown → 404. Unit: `UserEntityTest` (mapper `blocked` nos dois sentidos +
  doc sem campo → `false`).

## 10.3 Inspeção read-only: urls por usuário + lookup por code (commit 3)

- `core/ports/incoming/admin/`: + `AdminListUserLinksUseCase`,
  `AdminLookupUrlUseCase`; impls em `core/service/` (enforce de ADMIN no topo;
  reuso de ports existentes — **nenhum método novo de repositório**):
  - list por user: mesmo port/contrato do owner list (localizar o uso do
    cursor por `userId` em `ListUserLinksUseCaseImpl` e espelhar, incluindo
    archived — o owner list já inclui archived com `deletedAt`).
  - lookup por code: `LinkQueryPort.findById(code)` (o code É o id) +
    `UserRepositoryPort.findById` para o owner; `ownerEmail` null se o doc do
    usuário não existir (DTO com campo nullable + Javadoc).
- `AdminController`: + `GET /users/{userId}/urls` (query `limit`/`cursor` —
  mesmo default/cap do owner list) e `GET /urls?code=`; DTOs novos em
  `.../rest/admin/dto/` (`AdminUrlLookupResponse` = `ShortUrlResponse` +
  `ownerUserId` + `ownerEmail`; itens da lista reusing `ShortUrlResponse`).
- 404s: `UrlNotFoundException` (link/código) e `UserNotFoundException`
  (verificar se existe; se não, criar em `core/exception` + mapping no
  `GlobalExceptionHandler` → 404, padrão `UrlNotFoundException`).
- **Testes** (mapeados): urls por userId (itens + **archived com `deletedAt`**
  + paginação) ; 404 user; lookup 200 (`ownerUserId`/`ownerEmail` corretos);
  lookup 404 código inexistente; lookup com usuário deletado (setup via
  `UserRepositoryPort.deleteById`) → `ownerEmail` null.

## 10.4 Force archive + write path block check (commit 4)

- `core/ports/incoming/admin/`: + `AdminArchiveUrlUseCase`; impl em
  `core/service/` (enforce ADMIN) — **reaproveitar a semântica do
  `ArchiveLinkUseCase`** (set `deletedAt` idempotente + `urlCachePort.evict`);
  404 link inexistente via `UrlNotFoundException`.
- `AdminController`: + `DELETE /urls/{id}` (204).
- **Write path (D4):** `ShortenUrlUseCase`/`UrlShortenerService` — quando o
  caller (sessão Bearer **ou** cookie) está presente: carregar o usuário e
  `blocked() → throw ForbiddenException` **antes** de gerar/gravar
  (quota/metrics/counter não sofrem efeito — verificar a ordem dos passos do
  shorten e posicionar o check logo após a resolução do caller, antes de
  qualquer side-effect); caminho anônimo (sem sessão) sem lookup. O controller
  já resolve o caller opcional (ver `UrlController` — o `User` já é importado
  lá) — não mudar o contrato do controller, só o uso case no service.
- **Testes** (mapeados): force archive → `GET /{id}` 404; owner list da vítima
  exibe `deletedAt`; segundo force archive → 204; 404 inexistente; bloqueado
  com token emitido **antes** do block → `POST /urls` 403 (e o link não
  existe — lookup por code 404) + `GET /api/v1/urls` 200; `POST /urls`
  anônimo → 200 para e-mail bloqueado (limite documentado).

## 10.5 Contrato e gates finais (commit 5)

- **OpenAPI completo** (o que 10.1–10.4 não documentaram): os 6 endpoints
  admin com `@Operation`/`@ApiResponse` (summary/description em inglês;
  params `limit`/`cursor`/`q`/`code`; respostas 200/204/400/401/403/404 com
  bodies `ErrorResponse`); semântica escrita: **403 "Account blocked."**
  (login/refresh/write/admin), **403 "Forbidden"** (role), **400 self-block**,
  `ownerEmail` nullable, `q` = prefixo, cursor opaco estável. `GET /v3/api-docs`
  validado na prova viva (o JSON novo é o contrato da SPA).
- **CHANGELOG** `[Unreleased]` completado (todas as entradas dos 4 commits
  anteriores revisadas + consolidadas se preciso).
- **AGENTS.md**: item EP10 na matriz de debt/status (superfície admin;
  follow-up de **denylist/revogação ao bloquear** listado com gatilho —
  "primeiro incidente de abuso com token válido > expiry" — e nota de que é
  a mesma família da rotação de refresh); linha de Current State se o
  README/AGENTS tiver a seção (padrão EP8: item na matriz + `check-doc-sync`
  verde).
- **ArchUnit:** teste de front controller (o repo já tem `check-boundaries` +
  ArchUnit — localizar a classe): asserção de que use cases admin vivem em
  `core/service` (application) e o controller não contém regra de negócio
  (padrão do boundary gate existente — espelhar, não inventar novo framework).
- **Gates do épico:** `./mvnw verify` (completo); `check-boundaries`,
  `check-doc-sync`, `check-metrics-frozen` (zero metros novos — asserção: a
  lista frozen inalterada), `check-living-spec` (+ `--self-test`) — Auth ≥
  100% traced, Admin ainda **não-gated** (nenhum `@spec-complete` novo).
- **Prova viva** (rule zero; segredos redigidos) — sequência exata no DoD:
  boot com `APP_ADMIN_EMAILS` → register admin → login (`role=ADMIN` no body)
  → `/me` → register vítima → **block** 204 → login vítima **403** →
  **unblock** 200-login → shorten vítima → **lookup por code** → **force
  archive** 204 → `GET /{id}` 404 → owner list da vítima com `deletedAt`.
- DoD preenchido (todos os outputs colados) + `git log --oneline` dos 5
  commits.

## 10.6 Gates finais do épico (checklist de fechamento)

- [ ] 5 commits, cada um verde por si (`./mvnw verify` + gates bash) — runs de CI colados no DoD.
- [ ] `check-living-spec` verde (Auth 100%; nenhum componente novo gated).
- [ ] `check-metrics-frozen` verde (lista intacta).
- [ ] `check-boundaries` + ArchUnit admin verde.
- [ ] OpenAPI `/v3/api-docs` contém os 6 endpoints + `role` (curl colado).
- [ ] CHANGELOG/AGENTS.md/ADR 0011 consistentes (`check-doc-sync` verde).
- [ ] Proof viva completa colada no DoD.
- [ ] Follow-up de denylist registrado (matriz + corpo do commit 5).
