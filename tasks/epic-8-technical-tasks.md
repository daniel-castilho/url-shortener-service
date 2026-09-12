# Epic 8 – Tasks Técnicas

Marcos `[x]` preenchidos durante a execução; evidências coladas no `epic-8-dod.md`.

## 8.1 Contrato de release + ADRs

- [ ] Criar `docs/release-engineering.md`: fluxo tag → gates → GitHub Release → `deploy.sh` → post-deploy verification; tabela etapa × executor (CI × operador) × falha × comportamento (fail-closed).
- [ ] ADR `docs/adr/0007-blue-green-bare-metal.md`: context (canary manual §12.2; jar único compartilhado no template impede artifact por cor) / decision (units concretas blue+green, runtime conf renderizada, cutover fail-closed, canary 10/30/100 dwell 30s, `last-deploy.txt`) / consequences (frota 1 ativa + 1 idle; capacidade 2× = instância extra fora do plano) / rejeitados (k8s, rolling sem canary, in-place fleet update, compose para o app).
- [ ] ADR `docs/adr/0008-artifact-promotion.md`: jar da gates job é o promovido (single build); `deploy.sh` baixa o jar exato da Release (nunca rebuild local); imagem secundária (non-root + Trivy + SBOM); tag imutável (fix-forward); rejeitados (SSH-deploy, registry obrigatório, `release:perform`).
- [ ] Ligar o documento às séries frozen que o canary vigia (`http.server.requests`, `redirect.latency`, `shorten.latency`, `schema.migrations.*` — `docs/slos.md`).
- [ ] Colar `git log --oneline -- docs/adr/ docs/release-engineering.md` no DoD.

## 8.2 Identidade do artifact (versioning + CHANGELOG gate)

- [ ] `pom.xml`: `<revision>0.0.1-SNAPSHOT</revision>` + `<version>${revision}</version>` + `flatten-maven-plugin` (`resolveCiFriendliesOnly`, goals `flatten` em `process-resources` / `flatten.clean` em `clean`).
- [ ] Verificar que **nenhum** gate quebra com o flatten: `./mvnw verify` verde (JaCoCo, SpotBugs, Spotless, OWASP, ArchUnit, metrics-frozen) — output colado. Se algum plugin presumir versão literal (ex.: nome do artifact), corrigir na origem, não com workaround.
- [ ] Evidência de resolução: `./mvnw -q help:evaluate -Dexpression=project.version -Drevision=0.14.0 -DforceStdout` → `0.14.0`; `./mvnw clean package -DskipTests -Drevision=0.14.0` → `target/url-shortener-service-0.14.0.jar` (nome colado).
- [ ] `Dockerfile`: `ARG VERSION=local` + `LABEL org.opencontainers.image.version="${VERSION}"` (o `COPY --from=build` do jar continua valendo).
- [ ] Gate CHANGELOG (step na `release.yml`): fail se `## [Unreleased]` do `CHANGELOG.md` no commit da tag estiver ausente **ou** contiver entries (`###` ou linhas de conteúdo). Provar red/green: run verde na tag real + run vermelho intencional (branch descartável com Unreleased sujo, screenshot/output colado).
- [ ] Contratos de HTTP lidos do código e colados no DoD (não inventados): `ShortenResponse{String id, String shortUrl}`; `POST /api/v1/urls` → 200 (`@ApiResponse`); `GET /{id}` → 302/404/410; `X-Request-Id` echoed (`RequestCorrelationFilter`); `HEAD` espelha `GET` (fix EP7).

## 8.3 Blue-green com canary fail-closed (`scripts/deploy.sh`)

- [ ] Units concretas `deploy/url-shortener-blue.service` (:8080, `WorkingDirectory`/jar `/opt/url-shortener/blue/url-shortener.jar`, `SyslogIdentifier=url-shortener-blue`) e `deploy/url-shortener-green.service` (:8081, `.../green/...`) — hardening idêntica à do template (`Type=notify`, `KillMode=mixed`, `KillSignal=SIGTERM`, `TimeoutStopSec=30`, `NoNewPrivileges=true`, `ProtectSystem=strict`, `Restart=on-failure`). O template `url-shortener@.service` **não é deletado** (scale-out §12) — comentar nele que o plano de deploy é blue/green.
- [ ] Normalizar o upstream do `deploy/proxy/nginx.conf`: duas linhas ativas (`server 127.0.0.1:8080 weight=100 ...` + `server 127.0.0.1:8081 weight=100 ...`, a 8081 descomentada) — o render do deploy substitui exatamente essas duas linhas.
- [ ] `deploy/runtime/` → `.gitignore` (target de render + `last-deploy.txt`, nunca commitado).
- [ ] `scripts/deploy.sh` (bash, `set -euo pipefail`):
  - [ ] `--init` (renderiza runtime conf blue 100 / green down), `--check <tag>` (plano + última deploy, zero mutação), `--active` (cor ativa a partir do runtime conf), `--self-test` (render em dir temporário + asserções de peso + **prova do abort**: `READY_BUDGET_SECONDS=1` contra porta morta → cor antiga volta a 100%, exit ≠ 0 com passo nomeado). Output do self-test colado.
  - [ ] Fluxo: baixar jar da Release pela semver (API `gh release download` / cURL API) + **verificar sha256** contra o asset publicado → estagiar em `/opt/url-shortener/<cor>/` (escrita via sudo/cp nomeado; usuário `urlshortener`) → `systemctl restart` da cor idle (graceful 30s) → wait readiness `http://127.0.0.1:<porta>/actuator/health/readiness` com `READY_BUDGET_SECONDS=90` (comentário no script: HEALTHCHECK do Dockerfile = start-period 40s; 90 cobre boot prod + migrator fail-fast) → canary 10/30/100: render → `nginx -t` → `nginx -s reload` → `scripts/smoke.sh` → dwell 30s → sucesso: `last-deploy.txt` (previous/current/tag/at) + dreno e `systemctl stop` da cor antiga + one-liner.
  - [ ] **Fail-closed em cada passo:** trap/verificação — qualquer falha (download, sha256, readiness timeout, `nginx -t`, smoke) → render cor antiga 100% → `nginx -t` + reload → dreno/parada da cor nova → exit ≠ 0 com o passo ofensor nomeado. O template do nginx **nunca** é escrito pelo script (render lê template → escreve `deploy/runtime/nginx.conf` em tmp e `cat` para cima — nunca awk in-place no mesmo path, lição dargent E12 S2).
- [ ] `bash -n scripts/deploy.sh` + self-test verde; simulação completa em stack local (compose + jar + nginx local ou `--check`/`--init` + render asserido) com outputs colados.

## 8.4 Smoke + rollback (`scripts/smoke.sh`, `scripts/rollback.sh`)

- [ ] `scripts/smoke.sh <base>`: 8 pernas (liveness 200 → readiness 200 → info 200 → shorten 200 + `id`/`shortUrl` + `X-Request-Id` → `GET /<id>` 302 + `Location`==originalUrl → `HEAD /<id>` 302 → `GET /zzzzzzz` 404 → shorten `ttlSeconds:1` + poll 410 em ≤15s). Exit ≠ 0 nomeando a perna. `originalUrl` único por run (`https://<base>/smoke/$(date +%s%N)`).
- [ ] Executar o smoke contra stack local dev (compose + jar, rate limits padrão) → verde colado.
- [ ] `scripts/rollback.sh`: parse de `last-deploy.txt` (máquina: `previous`/`current`/`tag`/`at`) → `systemctl start` da cor anterior (no-op se já up) → render (anterior 100 / atual down, via tmp + `cat`) → `nginx -t` + reload → `smoke.sh` → one-liner de incidente. `--self-test` com last-deploy sintético em dir temporário; output colado.
- [ ] Último item: smoke + rollback exercitados juntos em stack local (deploy fake tag → rollback → ambos os sentidos de pesos asseridos) — output colado.

## 8.5 Backup agendado, manifestado e verificado

- [ ] `scripts/backup-mongodb.sh`: gerar `manifest.json` (timestamp ISO, db, `mongo --version`, dump size, row counts de `short_urls`, `users`, `custom_domains`, `click_events`, `click_daily`, `schema_migrations` via `mongosh --quiet --eval 'db.<c>.countDocuments()'`); manter rotação de 30d; se `mongosh`/`mongodump` não estiverem em PATH, documentar no header o fallback `docker exec urlshortener-mongo` (padrão do drill EP7) e falhar fechado com mensagem clara.
- [ ] `scripts/restore-mongodb.sh --verify <dir>`: após o restore, re-contar as mesmas collections e comparar com o manifest; tabela impressa; **exit ≠ 0 em qualquer divergência** (collection ausente, count menor, manifest ilegível). Self-test: manifest corrompido → exit ≠ 0 (colado).
- [ ] `deploy/systemd/url-shortener-backup.service` (`Type=oneshot`, `User=<dedicado>`, `After=docker.service`, `ExecStart=/opt/url-shortener/scripts/backup-mongodb.sh /var/backups/url-shortener`) + `.timer` (`OnCalendar=*-*-* 03:30:00`, `Persistent=true`, comentário com a racional de janela: retention purge 02:00 UTC / rollup 01:10 UTC).
- [ ] `scripts/ci-restore-drill.sh` (corpo do job `restore-drill`, executável local): projeto compose isolado `urlshortener-drill` (override de portas 18xxx — padrão EP5/6/7; `down -v` só destrói o volume do drill) → app (jar da tag/branch atual, rate limits relaxados) em 18080 → seed 20 códigos via API (lista no DoD) + 2 pós-backup → `backup-mongodb.sh` (MONGODB_URI→18017) → `mongosh drop short_urls` → `restore-mongodb.sh --verify` → asserts: pré-backup **302** / pós-backup **404** → **RTO wall-clock** (backup→restore→verify) vs `RTO_BUDGET_S=300` → artifact dos manifests.
- [ ] Rodar o drill localmente (ou via `workflow_dispatch` da release antes do primeiro tag) → outputs + RTO colados.
- [ ] Runbook: seção de rotina (timer `systemctl enable --now url-shortener-backup.timer`) + item pré-release `ls -lt /var/backups/url-shortener | head -1` (backup < 26h).

## 8.6 Release workflow + runbook

- [ ] `.github/workflows/release.yml`: trigger tag `v*` apenas; `permissions: contents: read` top-level; jobs `gates` → `k6-gate` / `runtime-smoke` / `restore-drill` → `release` (needs de todos). Jobs conforme story 8.6: gates (`verify -Drevision` + gates bash + self-tests + promtool/amtool pinned + gate CHANGELOG + upload do jar), k6-gate (services mongo+redis, app rate-limits relaxados, `k6 run load-tests/mixed.js`, artifact sempre), runtime-smoke (jar do artifact + `smoke.sh` + `verify-graceful-shutdown.sh`), restore-drill (script 8.5), release (imagem + non-root gate + Trivy HIGH/CRITICAL SHA-pinned + SBOM CycloneDX + sha256 do jar do artifact + `gh release create` com notas = anotação da tag + `--generate-notes`; `permissions: contents: write, packages: write` só neste job).
- [ ] `docs/release-runbook.md`: §1 (deploy via `deploy.sh` + post-deploy verification: smoke + 10 min de burn-rate no dashboard SLO + `schema.migrations.*` nos logs da cor nova), §2 (`rollback.sh`; manual vira fallback nomeado), §7 checklist acrescido (tag anotada; Unreleased vazio; migrations `V*` desde o tag do `last-deploy.txt` **expand-only** — rationale: migrator fail-fast protege, mas migração destrutiva quebra a cor antiga ainda servindo no meio do cutover; backup < 26h; drill verde na release), nova seção "Release artifacts & promotion" e incidente "deploy falhou" (one-liner + rollback + quando chamar o playbook de Mongo/Redis do §5b).
- [ ] Primeiro tag de teste: `v0.14.0-epic8` (ou `v0.14.0`) no `main` pós-épico — workflow verde de ponta a ponta; assets do Release listados no DoD (nome + sha256 + SBOM).
- [ ] Fechamento: item na matriz de debt do `AGENTS.md` (EP8 → resolved com sha) + linha Deployable no README Current State; `check-doc-sync` verde.

## 8.7 Gates finais do épico

- [ ] `./scripts/check-metrics-frozen.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-boundaries.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-doc-sync.sh` (+ `--self-test`) → PASS.
- [ ] `./scripts/check-security.sh` (+ `--self-test`) → PASS.
- [ ] `promtool check rules` + `promtool test rules` + `amtool check-config` → verdes.
- [ ] `scripts/deploy.sh --self-test` + `scripts/rollback.sh --self-test` + `smoke.sh` verde + `ci-restore-drill.sh` verde → PASS.
- [ ] `./mvnw verify` conjunto → BUILD SUCCESS.
- [ ] Evidências coladas no `epic-8-dod.md`; self-audit da regra zero.

---

**Checklist de conclusão do Épico 8:**

- [ ] `docs/release-engineering.md` + ADR 0007 + ADR 0008
- [ ] Versioning `revision` + flatten + gate CHANGELOG (red/green provados)
- [ ] `deploy.sh` blue-green fail-closed + units blue/green + self-test
- [ ] `smoke.sh` (8 pernas, 2 consumidores) + `rollback.sh` + self-test
- [ ] Timer de backup + manifest + `--verify` + `ci-restore-drill.sh` com RTO
- [ ] `release.yml` verde de ponta a ponta no primeiro tag + Release com assets
- [ ] Runbook atualizado (deploy/rollback/checklist/post-deploy/incidente)
- [ ] `./mvnw verify` verde (todos os gates)
- [ ] Evidências coladas no `epic-8-dod.md`

*Ao marcar todos os itens acima, o Épico 8 está **concluído** com o caminho "tag → produção" automatizado, contratado e provado.*
