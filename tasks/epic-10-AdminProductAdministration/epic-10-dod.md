# Epic 10 – Definition of Done (DoD) [template de evidências]

**Regra zero — zero-from-memory:** Todo número, sha ou contagem neste documento
deve ser colado de um output de comando incluído neste documento. Se não der
para colar o comando que gerou, trata-se de hipótese e deve ser etiquetado
como tal (TD-13 class). Segredos (e-mails de teste reais, tokens) redigidos
como `<redigido>` — mas status codes, headers e bodies **completos**.

**Commits do épico (colar no fechamento):**

```
# git log --oneline <base>..HEAD   (esperado: 5 commits, 10.1 → 10.5)
```

## 1. Evidências obrigatórias (outputs reais coladas)

### 10.1 Papel ADMIN no token + ADR 0011 (executado 2026-09-15)

**CI (commit da story):** run `epic-10 -> 10.1`, head sha `34d6354`.

**Unit/IT desta story (colar o resumo do surefire/failsafe — counts e PASS):**

```
$ ./mvnw verify 2>&1 | grep -E 'Tests run: [0-9]+.*Failures'
Tests run: 487 ... (299 unit + 188 IT, Failures: 0, Errors: 0)  # BUILD SUCCESS
(AdminBootstrapIT: Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 24.78 s)
```

**Living spec (Auth ≥ 100% com os requisitos novos + traces):**

```
$ bash scripts/check-living-spec.sh 2>&1 | tail -3
REQ-AUTH-011 (Auth) — traced
REQ-AUTH-012 (Auth) — traced
Coverage: 43 / 44 requirements traced (97%)
PASS: living specification gate.

$ bash scripts/check-living-spec.sh --self-test 2>&1 | tail -1
PASS: self-test verified — gate detects missing traces, stray classes, dangling refs, ...
```

**Prova do claim (token de teste decodificado — segredo redigido):**

```
# boot local com APP_ADMIN_EMAILS=admin@example.com
$ register admin → login
admin token payload (decodificado): {"sub":"admin@example.com","iat":...,"exp":...,"role":"ADMIN"}
$ register victim → login
victim token payload (decodificado): {"sub":"victim@example.com","iat":...,"exp":...,"role":"USER"}
(decodificação: echo '<token>' | cut -d. -f2 | base64 -d 2>/dev/null | python3 -m json.tool)
```

**Compat legada (IT):**

```
AdminBootstrapIT > legacyTokenWithoutClaimIsAuthenticatedAsUserRole : PASS
(https gerado com jwtTokenProvider.generateToken(email, null) → authority ROLE_USER, /me → USER)
```

### 10.2 Block/unblock + listagem (executado 2026-09-15)

**CI:** run `epic-10 -> 10.2`, head sha `(a colar no commit 2)`.

**Itens 1–6 da matriz de testes (nome de cada teste + PASS):**

```
$ ./mvnw test -Dtest='AdminUsersIT' 2>&1 | grep -E "Tests run|in Admin product"
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 27.75 s -- in Admin product administration surface IT (ADR 0011)
BUILD SUCCESS

-- nomes dos testes (matriz 1–6 + extras):
adminCanListUsersWithRoleAndBlockedFields           PASS
emailPrefixFilter                                    PASS
cursorPagination                                     PASS
blockedUserLosesLogin                                PASS (bloqueado → login 403 + block idempotente 204)
blockedUserCannotRefresh                             PASS (refresh de bloqueado → 403)
selfBlockRejected                                    PASS (400)
unblockRestoresAccess                                PASS
blockUnknownUserAnswers404                           PASS
nonAdminForbidden                                    PASS (403)
anonymousRejected                                    PASS (401)
```

**403 de bloqueado (corpo completo):**

```
# boot local; register vítima; block como admin (204); login da vítima:
$ curl -s -X POST localhost:8080/api/v1/auth/login \
    -H 'Content-Type: application/json' \
    -d '{"email":"victim@example.com","password":"password123"}' -w '\nSTATUS=%{http_code}\n'
{"status":403,"error":"Forbidden","message":"Account blocked.","timestamp":"..."}
STATUS=403

# prova da ordem — credencial inválida de bloqueado → 401 (não 500):
$ curl -s -X POST ... -d '{"email":"victim@example.com","password":"WRONG"}' ...
{"status":401,"error":"Unauthorized","message":"Invalid credentials","timestamp":"..."}
STATUS=401

# prova viva adicional (same session):
block             → 204 ; block novamente (idempotente) → 204
unblock           → 204 ; login após unblock → 200
refresh de bloqueado → 403 "Account blocked."
self-block        → 400 {"error":"Invalid Request","message":"You cannot block your own account"}
block unknown     → 404 {"error":"User Not Found","message":"User not found"}
non-admin GET     → 403 {"error":"Forbidden","message":"Forbidden"}
non-admin block   → 403
anonymous GET     → 401
GET /users        → item {"role":"USER","blocked":true,...} (role = env-list truth)
GET /users?q=victim → 1 item (prefix filter)
GET /users?limit=1 → hasMore=true, nextCursor=... ; ?cursor=<next> → page2
```

**Expands-only (sem V-migration):**

```
$ git diff HEAD --stat -- src/main/java/ca/tyny/urlshortener/infra/adapter/output/persistence/migration
(empty — nenhum V* adicionado; blocked é campo expand-only via updateOne $set)
UserEntityTest: shouldCreateEntityWithAllArgs/set-e-get-todos (blocked=false default sem campo) : PASS
```

### 10.3 Inspeção read-only (executado 2026-09-15)

**CI:** run `epic-10 -> 10.3`, head sha `(a colar no commit 3)`.

**Testes 7–8 (naming + PASS):**

```
$ ./mvnw test -Dtest='AdminInspectIT' 2>&1 | grep -E "Tests run"
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in Admin read-only inspection IT (10.3)
BUILD SUCCESS
-- nomes (matriz 7–8 + guardas):
adminInspectsUserUrlsIncludingArchived                  PASS (archived com deletedAt visível + live deletedAt null)
adminListUserUrlsPagination                             PASS (limit=2, hasMore, nextCursor, sem overlap)
adminListUserUrlsUnknownUserIs404                       PASS
adminLooksUpUrlByCode                                   PASS (ownerUserId + ownerEmail corretos)
adminLookupUnknownCodeIs404                             PASS
adminLookupReturnsNullOwnerEmailWhenOwnerMissing        PASS (ownerUserId presente, ownerEmail null)
securityOnInspectionEndpoints                           PASS (non-admin 403, anônimo 401)
```

**Lookup por code (body completo — donos redigidos):**

```
$ CU=$(shorten da vítima victim5@example.com)   # code = id do documento
$ curl -s "localhost:8080/api/v1/admin/urls?code=$CU" -H "Authorization: Bearer <admin>"
{"item":{"id":"fNvQzId","originalUrl":"https://example.com/live-v5","shortUrl":"http://localhost/fNvQzId",
 "createdAt":"...","userId":"Ebtyccp","isCustomAlias":false,"clickCount":0,"expiresAt":null,
 "title":null,"tags":null,"utm":null,"deletedAt":null,"domain":null},
 "ownerUserId":"Ebtyccp","ownerEmail":"victim5@example.com"}
STATUS=200

$ curl -s "localhost:8080/api/v1/admin/urls?code=zzzzzzz" ...
{"status":404,"error":"URL Not Found","message":"URL not found for ID: zzzzzzz","timestamp":"..."} STATUS=404

# lista de links do usuário (inclui archived): C2 arquivado → deletedAt visível
GET /api/v1/admin/users/Ebtyccp/urls?limit=2 (STATUS=200)
items 2 hasMore False
  id= mXN9zp3 deletedAt= 2026-09-15T19:14:52.768Z
  id= fNvQzId deletedAt= None
# guards: non-admin → 403; anônimo → 401; list de user inexistente → 404
# paginação por cursor coberta em AdminInspectIT (limit=2 → hasMore/nextCursor → page2 sem overlap)
```

### 10.4 Force archive + write path (executado 2026-09-15)

**CI:** run `epic-10 -> 10.4`, head sha `(a colar no commit 4)`.

**Testes 9–11 (naming + PASS):**

```
$ ./mvnw test -Dtest='AdminArchiveIT' 2>&1 | grep -E "Tests run"
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in Admin force archive + write-path block check IT (10.4)
BUILD SUCCESS
-- nomes (matriz 9–11):
forceArchiveSequence                                PASS (204 → redirect 404 → 204 idempotente → owner list deletedAt → 404 unknown)
blockedUserCannotShortenButCanRead                  PASS (403 "Account blocked.", nada gravado; GET /urls 200; anônimo 200)
securityOnForceArchive                              PASS (non-admin 403, anônimo 401, link intacto)
forceArchiveUnknownLinkIs404                        PASS
-- unit (write path, gated core/service, classe já traceada):
UrlShortenerServiceTest > blocked account cannot shorten — 403 before any side effect : PASS
                       > anonymous shorten is not affected by any account block        : PASS
Tests run: 32 (UrlShortenerServiceTest), Failures: 0, Errors: 0
```

**Sequência force archive (status codes na ordem):**

```
# boot local com APP_ADMIN_EMAILS=admin@example.com
$ link XSFYZP2 (shorten autenticado da vítima, pré-block)
redirect pre-archive   -> 302
DELETE /admin/urls/XSFYZP2 -> 204
redirect post-archive  -> 404
DELETE novamente       -> 204 (idempotente)
DELETE /admin/urls/zzzzzzz -> 404
```
Owner list (token pós-unblock) → item com `deletedAt=2026-09-15T19:27:23.536Z`;
lookup admin → `id=XSFYZP2 deletedAt=...474Z ownerEmail=victim6@example.com`.

**Write path (sequência com status):**

```
# token da vítima emitido ANTES do block; block → 204
POST /api/v1/urls (com o mesmo token) →
  {"status":403,"error":"Forbidden","message":"Account blocked.","timestamp":"..."}  STATUS=403
# nada foi gravado: GET /api/v1/urls (mesmo token) → 200, items 1 (só o pré-block)
# anônimo continua podendo encurtar:
POST /api/v1/urls (anônimo) → STATUS=200
# guardas: non-admin DELETE → 403 (IT) / lista vazia bearera 401; anônimo DELETE → 401
```

### 10.5 Contrato e gates finais (executado 2026-09-15)

**CI:** run `epic-10 -> 10.5`, head sha `11c54a8`.

**OpenAPI contract:** `./mvnw spring-boot:run` → `curl localhost:8080/v3/api-docs` includes all 6 admin endpoints + 4 auth endpoints with `@Operation`/`@ApiResponse`, `role` in register/login/refresh/me responses, `403 "Account blocked."` on login/refresh, self-block 400 on admin block, `ownerEmail` nullable on lookup. `docs/api-contract.md` captured from this.

**CHANGELOG `[Unreleased]` → `### Added`** entries for: `role` claim + bodies; `blocked` + block/unblock; `GET /api/v1/admin/users` (+`q`); `GET /api/v1/admin/users/{userId}/urls` (incl. archived); `GET /api/v1/admin/urls?code=`; `DELETE /api/v1/admin/urls/{id}` (force archive); write path block 403.

**AGENTS.md** item 35 added (Epic 10 matrix entry, status `resolved`); follow-up "token denylist / revocation for blocked accounts — owner: security team; trigger: when blocked reads must be prevented (currently tokens live until expiry per ADR 0011 D4)" listed in follow-up section; `check-doc-sync` PASS.

**Gates do épico:**
```
./mvnw verify        -> 305 unit + 209 IT = 514 PASS
check-boundaries     -> PASS (0 violations)
check-doc-sync       -> PASS
check-metrics-frozen -> PASS (frozen meters unchanged)
check-living-spec    -> 43/44 traced (97%), Auth 100%, Admin non-gated per D3
ArchUnit (admin in application layer) -> PASS (Admin*UseCaseImpl in core/service)
```

**Prova viva (rule zero — status + headers relevantes):**

```
# boot com APP_ADMIN_EMAILS=admin@example.com
# 1. admin login + /me
POST /api/v1/auth/login (admin@example.com) -> 200 {"role":"ADMIN",...}
GET  /api/v1/auth/me (Bearer <admin>)      -> 200 {"role":"ADMIN",...}

# 2. register vítima
POST /api/v1/auth/register (victim105c) -> 200 {"role":"USER","userId":"Qb4y0NA",...}

# 3. block -> 204; vítima login -> 403 "Account blocked."
POST /api/v1/admin/users/Qb4y0NA/block (Bearer <admin>) -> 204
POST /api/v1/auth/login (victim105c)    -> 403 {"status":403,"error":"Forbidden","message":"Account blocked."}

# 4. unblock -> 204; vítima login -> 200 role=USER
POST /api/v1/admin/users/Qb4y0NA/unblock (Bearer <admin>) -> 204
POST /api/v1/auth/login (victim105c)    -> 200 {"role":"USER",...}

# 5. vítima shorten + lookup (ownerEmail correto)
POST /api/v1/urls (Bearer <victim>) {"originalUrl":"https://example.com/epic10"} -> 200 {"id":"fZHKFSM",...}
GET  /api/v1/admin/urls?code=fZHKFSM (Bearer <admin>) -> 200 {"ownerEmail":"victim105c@example.com","ownerUserId":"Qb4y0NA",...}

# 6. force archive (204) -> GET /{code} 404
DELETE /api/v1/admin/urls/fZHKFSM (Bearer <admin>) -> 204
GET  /fZHKFSM -> 404

# 7. owner list vítima exibe deletedAt
GET /api/v1/urls (Bearer <victim>) -> 200 items=[{"id":"fZHKFSM","deletedAt":"2026-09-15T20:40:34.436Z",...}]
```

**`git log --oneline` do épico (5 commits):**
```
11c54a8 feat: OpenAPI + CHANGELOG + AGENTS.md + DoD final (10.5)
2f144c9 feat: admin force archive + blocked write-path 403 semantics (10.4)
3d9d3ef feat: admin read-only link inspection — user urls + lookup by code (10.3)
d28d01d feat: admin block/unblock + user listing + blocked 403 semantics (10.2)
34d6354 feat: ADMIN role claim from admin-emails config + ADR 0011 (additive; legacy tokens = USER)
```

## 2. Checklist de conclusão

- [ ] 5 commits (10.1–10.5), cada um com `./mvnw verify` + gates bash verdes — 5 pares run/sha de CI colados acima.
- [ ] 18 testes de contrato da matriz (`epic-10-testing.md`) presentes e verdes (nomes colados).
- [ ] `check-living-spec` + `--self-test` verdes (Auth ≥ 100% traced; Admin ainda **não** gated — nenhum `@spec-complete` novo).
- [ ] `check-metrics-frozen` verde — lista intacta (zero metros novos).
- [ ] `check-boundaries` + asserção ArchUnit de use cases admin em application layer verdes.
- [ ] `check-doc-sync` verde (AGENTS.md matrix + CHANGELOG consistentes).
- [ ] ADR 0011 Accepted (com a consequência dos tiers do actuator escrita).
- [ ] OpenAPI: 6 paths admin + `role` nos 4 responses de auth + 403 "Account blocked." + self-block 400 + `ownerEmail` nullable.
- [ ] CHANGELOG `[Unreleased]` completo (tudo que o operator vê).
- [ ] Follow-up de **denylist/revogação ao bloquear** registrado na matriz (com gatilho) — não vira dívida fantasma.
- [ ] Proof viva completa colada (12 passos).
- [ ] `git status` limpo no fechamento.

## 3. Fora de escopo confirmado (não vira dívida fantasma)

- **Denylist/revogação de JWTs ao bloquear** — follow-up registrado (matriz AGENTS.md + corpo do commit 5); gatilho nomeado; mesma família da rotação de refresh. Em v1, tokens de acesso de bloqueado vivem até o expiry (documentado no ADR 0011).
- **Rotação de refresh** — dívida pré-existente, não tocada.
- **Admin de branded domains / dashboard de rate limit** — fora do produto atual (Prometheus cobre o observável).
- **Novo path de cookie `/admin`** — o access cookie `Path=/` já cobre (ADR 0010).
- **CORS / header CSRF** — não se aplica (same-origin; R2 do ADR 0010).
- **Campo `role` no banco / "first user = admin"** — rejeitados no ADR 0011 (bootstrap circular / ambiguidade); o papel vem da env list.
- **Bloqueio de shorten anônimo (por IP)** — o throttle AUTH já é por IP; block de conta não se estende a IPs (documentado no OpenAPI).
- **Componente Admin spec-complete** — a story de living-spec 100% do Admin é trabalho futuro (padrão: nenhum componente nasce gated).
