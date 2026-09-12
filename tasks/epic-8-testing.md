# Epic 8 – Estratégia de Testes

Princípio: testar o **contrato de deploy** (fail-closed, zero-downtime, rollback, backup verificado), não a implementação do bash. Happy path de negócio já foi EP4/5/6; falha de dependência já foi EP7. Aqui o teste é: **o pipeline de release e o cutover fazem o que o ADR 0007/0008 promete, e falham da forma prometida.**

## 8.1 Contrato de release

- **Objetivo:** Decisões revisáveis; o operador não depende de memória do autor; o fluxo tag→produção é legível em uma sessão.
- **Ação:** `docs/release-engineering.md` + ADR 0007/0008; `git log` colado.
- **Critério aceite:** fluxo completo escrito (etapa × executor × falha × comportamento); rejeitados escritos (k8s, SSH-deploy, registry obrigatório, rolling sem canary); consequência da frota 1 ativa + 1 idle aceita e documentada.
- **Não fazer:** inventar métricas novas para "vigiar o deploy" — o canary usa as séries frozen existentes (`http.server.requests`, `*.latency`, `schema.migrations.*`). Se surgir necessidade nova, passa pelo freeze gate **no mesmo PR**.

## 8.2 Identidade do artifact

- **Objetivo:** `0.14.0` na tag = `url-shortener-service-0.14.0.jar` promovido; ninguém recompila na mão.
- **Ação:**
  - `./mvnw -q help:evaluate -Dexpression=project.version -Drevision=0.14.0 -DforceStdout` → `0.14.0` (colado).
  - `./mvnw clean package -DskipTests -Drevision=0.14.0` → nome do jar colado (`ls target/*.jar`).
  - `./mvnw verify` completo verde com o flatten (nenhum gate regredido) — output colado.
  - Gate CHANGELOG red/green: tag real verde + run intencionalmente vermelho (Unreleased sujo em branch descartável) — os dois outputs colados.
- **Critério aceite:** semver da tag propagada para jar e label da imagem; tag com Unreleased sujo **não** pode criar Release.
- **Não fazer:** `mvn release:perform` ou git-flow de version — o padrão `revision`+tag é o escolhido (ADR 0008); não adicionar mais um mecanismo.

## 8.3 Blue-green fail-closed

- **Objetivo:** O cutover zero-downtime acontece no sucesso e **aborta para a cor antiga** em qualquer falha — ambos os caminhos provados, não narrados.
- **Ação:**
  - `scripts/deploy.sh --self-test`: render em dir temporário com asserções de peso (10/30/100 e complementos), `nginx -t` quando disponível, e **prova do abort** (`READY_BUDGET_SECONDS=1` contra porta morta → runtime conf volta com a cor antiga a 100%, exit ≠ 0, passo nomeado). Output colado.
  - Exercício local completo (stack dev: compose + jar + nginx ou render asserido): deploy de uma "tag" fake → canary 10/30/100 com smoke verde em cada bump → `last-deploy.txt` correto → cor antiga parada. Colar pesos do runtime conf em cada bump + summary do smoke.
  - Zero-downtime do cutover: durante o flip 10→30→100, um loop `curl` de redirect (os seeds do EP7 ou da smoke) não recebe 5xx/connection-refused — contagem colada (permissão: o único downtime aceitável é o `systemctl restart` da cor **idle**, que não carrega tráfego — afirmar com `--active` antes do restart).
- **Critério aceite:** self-test verde; exercício local com outputs; abort provado com a cor antiga de volta a 100%.
- **Não fazer:** mockar o `systemctl` — o self-test testa render + lógica de abort; o exercício local testa systemd de verdade (host/VM do operador, evidence no DoD). Não mutar o template `deploy/proxy/nginx.conf` em nenhuma asserção.

## 8.4 Smoke + rollback

- **Objetivo:** a sonda é **crucial e barata o suficiente** para rodar em cada bump e em cada release; o rollback desfaz sem rebuild.
- **Ação:**
  - `smoke.sh` contra stack local dev (rate limits padrão — as 8 pernas convivem com o bucket de 60/min, 2 shortens por run): verde colado.
  - **Perna de expiração** (410): `ttlSeconds: 1` + poll ≤15s — valida o contrato eager de expiry (EP: expired nunca sai do cache) sem depender do TTL index (purge ~60s).
  - CI `runtime-smoke` (job da `release.yml`): services mongo+redis + jar do artifact → `smoke.sh` → `verify-graceful-shutdown.sh` (dreno EP7: zero connection-refused). Run colado.
  - `rollback.sh --self-test` (last-deploy sintético → asserções do render da cor anterior) + exercício local: deploy fake → rollback → pesos asseridos (anterior 100 / atual down) + one-liner impresso.
- **Critério aceite:** 8 pernas verdes em stack real; rollback simétrico ao deploy (mesmo mecanismo de render); falha de perna nomeada no exit (provar rodando contra porta morta — exit message colada).
- **Não fazer:** smoke não deve criar usuário/autenticar (anônimo é o contrato público do shorten); smoke não pode seguir redirects (`curl -o /dev/null -w '%{http_code}'` + inspecionar `Location`, nunca `-L`).

## 8.5 Backup agendado e verificado

- **Objetivo:** o backup roda sozinho e o restore é um **contrato que falha** — divergência é exit ≠ 0, não log amarelo.
- **Ação:**
  - `backup-mongodb.sh` em stack dev → `manifest.json` colado (counts reais).
  - `restore-mongodb.sh --verify` verde (stack dev isolada/paralela) + **negative test**: manifest corrompido (count divergente) → exit ≠ 0 com a tabela de divergência colada.
  - `ci-restore-drill.sh` completo (projeto isolado `urlshortener-drill`): seeds pré=302 / pós=404 (semântica de RPO do EP7), RTO medido ≤ 300s, manifests em artifact. Output + RTO colados.
  - Timer: `systemd-analyze verify` quando disponível + `systemctl list-timers` no host de homologação colado (CI sem systemd: o timer é evidence de host, não de CI — declarar).
- **Critério aceite:** restore divergente **falha**; drill verde com RTO; timer enabled no host de homologação.
- **Não fazer:** destruir o volume de dev do dia a dia — o drill é isolado por projeto + portas 18xxx (padrão EP5/6/7, `down -v` só atinge `urlshortener-drill`). Não exigir backup off-host neste épico (fora de escopo, TD nomeado).

## 8.6 Release como gate

- **Objetivo:** a primeira tag real prova o pipeline inteiro; nenhum gate do CI é pulado no caminho da release.
- **Ação:**
  - Tag `v0.14.0` (ou `v0.14.0-epic8`) no `main` → workflow `release.yml` de ponta a ponta: gates (verify + bash + promtool/amtool + CHANGELOG), k6-gate (thresholds de SLO no artifact — o exit do k6 é o gate), runtime-smoke, restore-drill, release (non-root gate, Trivy HIGH/CRITICAL, SBOM, `gh release create`).
  - Artifacts do Release listados no DoD: `url-shortener-service-<semver>.jar` + sha256 (o mesmo sha256 que o `deploy.sh` vai verificar), `SHA256SUMS`, SBOM CycloneDX, notas.
  - `deploy.sh <tag>` no host de homologação baixando **aquele** jar da Release (sha256 verificado no download) — colado.
- **Critério aceite:** workflow 100% verde; Release existindo com assets; jar da Release = jar que o deploy consome (mesmo sha256 nos dois lugares).
- **Não fazer:** `if: always()` escondendo falha de gate (o release precisa de **todos** os jobs verdes); Trivy com `ignore-failure: true`; pular o k6-gate "porque o CI já rodou" — o artifact da tag é o que o k6 valida.

## 8.7 Integração retro-compatível (gates)

- [ ] `./mvnw verify` conjunto → verde (JaCoCo floors, SpotBugs, Spotless, OWASP, ArchUnit, doc-sync, metrics-frozen).
- [ ] `promtool` + `amtool` verdes.
- [ ] Self-tests dos novos scripts verdes (`deploy.sh`, `rollback.sh`, `--verify` do restore).
- [ ] Nenhuma série nova sem passar pelo freeze gate (se algo criar métrica, atualizar `scripts/check-metrics-frozen.sh` **no mesmo PR**).
- [ ] `.gitignore` cobre `deploy/runtime/` (e artifacts k6/dumps de dev) — `git status` limpo após os exercícios.

---

**Checklist de conclusão do Épico 8:**

- [ ] Matriz/fluxo + ADRs 0007/0008
- [ ] Versioning + gate CHANGELOG (red/green)
- [ ] `deploy.sh` self-test + exercício local (abort provado)
- [ ] `smoke.sh` 8 pernas + `rollback.sh` self-test + exercício
- [ ] Backup manifest + restore `--verify` (negative test) + drill com RTO
- [ ] `release.yml` verde no primeiro tag + Release com assets
- [ ] `./mvnw verify` verde
- [ ] Evidências coladas no `epic-8-dod.md`

*Ao marcar todos os itens acima, o Épico 8 está **concluído**.*
