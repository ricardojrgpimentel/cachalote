# Cachalote — DeepSeek Harness no Android (versão própria)

> Plano de arquitectura. Estado: **rascunho para revisão** (v0.1).
> Base factual: análise do projecto [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app) (MIT),
> do kernel `@deepseek-ai/dsh` 0.2.0-rc.2 instalado nesta máquina, e de testes feitos no dispositivo real.

---

## 0. O que já está verificado (evidência, não opinião)

Dispositivo de teste: **Samsung Galaxy S10+ (SM-G975F, beyond2lte)** — LineageOS 23.2 (Android 16, SDK 36),
`userdebug`, **Magisk 30.7 com root a funcionar** (`su -c id` → `uid=0(root) context=u:r:magisk:s0`),
8 núcleos, 7,4 GB RAM, 107 GB livres, `arm64-v8a`. Shizuku **não** instalado, Termux **não** instalado.

| # | Verificação | Resultado |
|---|---|---|
| V1 | Runtime Node para Android | ✅ **Node v24.18.0** (`process.platform=android`, `arm64`, `abi=137`, openssl 3.6.5) obtido dos pacotes Termux (`nodejs-lts`) e a correr a partir de `/data/local/tmp` **sem root**, uid shell |
| V2 | Node do DSH desktop | `runtime.json` → **node 24.21.0**, desktop `0.2.0-rc.2`; o Android fica na mesma major (24.x) ⇒ ABI compatível |
| V3 | Flag de arranque do kernel | o lançador oficial (`runtime/cli/bin/dsh`) corre `ELECTRON_RUN_AS_NODE=1 … --expose-internals app.asar/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js` ⇒ **`--expose-internals` é obrigatório** |
| V4 | W^X / noexec (o truque do projecto chinês) | ✅ confirmado no dispositivo: `plat_seapp_contexts` mapeia `minTargetSdkVersion=28 → domain=untrusted_app_27`, e a política contém `(allow untrusted_app_27 app_data_file (file (execute_no_trans)))`. Os domínios `untrusted_app[_29|_30|_32]` têm **zero** regras `allow … app_data_file` |
| V5 | `/data/local/tmp` | executável por uid shell — serve para o motor fora do sandbox da app |
| V6 | Perfil DSH (o que é preciso para arrancar um perfil) | `~/.dsh/profiles/desktop/` = `cordis.yml` (array vazio) + `cordis.patch.yml` + `package.json` (`dsh.profile.bundles`) + `pnpm-workspace.yaml`. Templates: `web`=[dsh-base,dsh-web-app], `headless`=[dsh-base,dsh-headless], `sdk`=[dsh-base,dsh-sdk-app], `sdk-minimal`=[dsh-sdk-minimal] |
| V7 | Kernel no disco | `app.asar` = 116 MB; árvore do kernel em `dsh/node_modules/` = **12.416 ficheiros / 2.513 dirs / 316,5 MB** (inclui variantes darwin). Só **7 ficheiros `.node`**, todos darwin-arm64: `koffi`, `node-addon-system`, `sharp`, `node-addon-require-builtin`, `node-pty`, `sherpa-onnx`. `dsh/lib/bin.js` presente (11,7 KB) |
| V8 | Perfis disponíveis | `PROFILE_TEMPLATES` em `dsh-app-boot` tem **5**: `acp`=[dsh-base,dsh-acp-app], `web`, `headless`, `sdk`, `sdk-minimal`. (`headless` é normalizado para base+web-app+headless) |
| V9 | Perfis programáticos e módulos nativos | `dsh-headless` (5 deps), `dsh-sdk-app` (6), `dsh-sdk-minimal` (28), `dsh-sdk-jsonrpc-server` (2), `dsh-acp-app` (3): **nenhum declara koffi/sharp/node-pty/sherpa** nas dependências directas ⇒ a via JSON-RPC evita o campo de minas dos nativos |

Consequência directa de V4: **o `targetSdk 28` é mesmo necessário se o motor correr dentro da app** — não é superstição.
E a alternativa moderna é não correr o motor dentro do sandbox (ver D2).

---

## 1. Decisões de arquitectura

### D1 — Runtime: **Node 24** (Termux ou NDK), não Bun
Ver §2. Resumo: o `--expose-internals` do Bun não resolve os internals do Node (falha fatal no arranque, sem `try/catch`) e o binário Android oficial do Bun ainda é morto por `SIGSYS` em `close_range`. O Node 24.18.0 do Termux já corre no dispositivo (V1) e casa com a major do DSH desktop (24.21.0).

### D2 — O motor corre **fora** do sandbox da app (daemon), não dentro dela
Três razões, todas com evidência:

1. **Elimina o problema do `targetSdk`** (V4): o processo do daemon não é a app, logo o noexec do `app_data_file` não se aplica; execução a partir de `/data/adb/cachalote` (root) ou `/data/local/tmp` (shell) funciona (V5).
2. **Elimina o problema de keep-alive**: um processo lançado por `su`/Magisk `service.d` não depende de foreground service nem do AMS — sobrevive a swipe-out, reboot, e não está sujeito ao limite de 6 h/24 h que o Android 15+ impõe ao tipo `dataSync`.
3. **Atualizações limpas**: o payload de 25 k ficheiros deixa de ser re-extraído a cada upgrade do APK (um dos pontos mais frágeis do projecto de referência).

Localizações: `/data/adb/cachalote/` (root, sobrevive a reboot, invisível ao utilizador) com fallback `/data/local/tmp/cachalote/` (shell).
Lançamento, por ordem de preferência: **(a)** Magisk `service.d` no boot + `su -c` pedido pela app; **(b)** Shizuku (uid 2000); **(c)** flavor "in-app" com `targetSdk 28` para quem não tem root nem Shizuku.

### D3 — Interface: **nativa (Kotlin + Compose)** como cliente do JSON-RPC
Decisão do utilizador (controlamos o UX). Consequências:
- **Perfil principal: `acp`** (Agent Client Protocol, `@agentclientprotocol/sdk@1.4.0`, protocolo v1). Motivo: o perfil `sdk`/`sdk-minimal` só tem **3 métodos** e não tem cancelamento, fecho de sessão nem aprovações; o ACP tem `session/new|list|resume|close|set_config_option|prompt|cancel`, `session/update` (streaming), resultado por turno e `session/request_permission`. `sdk-minimal` fica como alternativa mínima.
- O motor arranca num perfil **programático** em vez de `web` (V8).
- Com ACP, **aprovações e cancelamento vêm de graça** — deixam de exigir plugin nosso. O `cachalote-rpc-ext` reduz-se ao que o ACP não cobre (modelos/plugins/settings) e à ponte `host.*` das capacidades Android (ver Anexo A).
- Mantemos um **"modo completo"** com WebView a apontar para o perfil `web` do próprio daemon, para paridade total (gestão de modelos/plugins/anexos) enquanto a UI nativa não cobrir tudo. Isto é seguro porque o daemon pode servir os dois perfis em portas/sockets distintos.
- Ver `research/dsh-sdk-surface.md` para o que a superfície programática cobre hoje (e o que falta).

### D4 — Um único socket, uma única ponte (inversão do desenho de referência)
O projecto de referência tem **4 servidores HTTP em loopback com portas derivadas do package name** (notificação, acessibilidade, ponte da virtual screen, core privilegiado) e os plugins JS chamam-nos por HTTP, com um token passado por variável de ambiente.

Nós fazemos ao contrário e mais simples:
- **App → motor**: JSON-RPC (cliente nativo).
- **Motor → app** (capacidades Android: acessibilidade, notificações, overlay, browser, ficheiros): **o mesmo socket**, chamadas reversas numa namespace própria (`host.*`).
- Transporte: **AF_UNIX** (`/data/adb/cachalote/cachalote.sock`, modo 0600) com **token** de 128 bits como defesa em profundidade. Loopback TCP só como fallback quando o socket não for viável.
- **O transporte é um campo de configuração de plugin**, não código: o servidor SDK aceita `input`/`output`, o ACP aceita `stream`. Verificado ao vivo: **um único `--patch` overlay** repontou `dsh --profile sdk` para TCP `127.0.0.1:45999` e o handshake `initialize`→`shutdown` fez round-trip completo. Logo **não é preciso patch ao kernel** para servir um socket — basta o nosso overlay de perfil, e o daemon só supervisiona o processo.

Ganhos: uma superfície de ataque em vez de quatro; sem portas hardcoded; sem "derivei a porta do package name"; sem env vars a transportar segredos para dentro do motor.

### D5 — `targetSdk` da app: **35** (moderno) com flavors
A app em si deixa de precisar de `exec` (D2) ⇒ não precisamos do SDK 28 na app principal: FGS moderno, permissões de media/notificações correctas, e potencialmente distribuível.
Flavor `legacy` (`targetSdk 28`) só para o modo (c) de D2 — e nesse flavor o node é empacotado como `jniLibs/arm64-v8a/libnode.so` (o `nativeLibraryDir` é executável mesmo com targetSdk ≥ 29), evitando o truque do domínio legado quando possível. **Isto é uma hipótese a validar com um teste real** (ver F0.3).

### D6 — Kernel: extrair do `app.asar` + pipeline de patches reproduzível
Em vez de copiar a árvore já patcheada do projecto chinês (rápido mas opaco e preso à versão deles), construímos:
`tools/extract-kernel.mjs` (asar → `dsh/node_modules`, remove binários darwin/nativos) + `tools/patch-android.mjs` (aplica os 4 patches Android, informados pelos deles, MIT) + materialização dos perfis → `cachalote-kernel-<versão>.tar.zst`.
Bootstrap de emergência para F0: extrair `assets/payload.zip` do APK deles (já patcheado) — mas o caminho oficial é o pipeline.

### D6b — O motor é distribuído como **SEA + snapshot V8**
Medido: empacotar o núcleo fixo por esbuild ⇒ −68 % no arranque; somar snapshot/SEA com `execArgv:["--expose-internals",…]` ⇒ **31,3 ms, sem flags na linha de comandos** (5,9×). Restrições conhecidas: o blob é específico de **arch+OS+versão de Node** ⇒ tem de ser construído **no binário aarch64-android**; o builder de snapshot recusa módulos de utilizador extra ⇒ tudo inline num único entry; `dsh/lib/bin.js` é ESM ⇒ **wrapper CJS**; `NODE_COMPILE_CACHE` só depois de empacotar (`portable:true`, pré-aquecido na primeira extracção). Plugins ficam **externos** para não partir o `require`/`import()` dinâmico do cordis.

### D7 — Base de conhecimento obrigatória
As ~25 armadilhas já pagas por eles ficam em [`docs/knowledge-android.md`](docs/knowledge-android.md), cada uma com sintoma/causa/solução/origem. Regra do projecto: **nenhum bug de plataforma resolvido duas vezes**.

### D8 — Licenças
Kernel DSH (MIT), Node (MIT), plugins deles (MIT) → reutilizáveis com atribuição. **Excepção**: `vscreen/` (virtual display) é derivado do Operit e é **LGPL-3.0**; se adoptarmos virtual display, ou cumprimos LGPL (ficheiro + fonte + aviso) ou reimplementamos. Virtual display fica para F4+ e é decisão explícita.

### D10 — Aviso de segurança: o confinamento no Android é *declarativo*
O motor corre como **root** e no Android **não há Landlock**. O `sandboxMode` que o
`bash-local` declara (`workspace-write`, patch 3) serve para satisfazer o
`dsh-permission-presets` e manter o fluxo de aprovação — mas **não é imposto pelo kernel**:
quem trava um comando fora do workspace é a camada de aprovação (`approval: ask`), não o SO.

Declarar `danger-full-access` seria pior (a política deixaria de pedir aprovação) e deixar
`sandboxMode` indefinido impede o plugin de montar. Portanto:

- **Enquanto o motor correr como root, qualquer comando aprovado pode tocar em tudo.** Isto é
  uma decisão consciente, não um descuido — e tem de estar visível na UI (F2) e no README.
- O confinamento a sério é trabalho de **F3**, com duas vias complementares: (a) executar as
  ferramentas pela ponte `host.*`, onde impomos allowlist de caminhos e auditoria; (b) no
  flavor sem privilégios, deixar o sandbox da própria app Android ser o limite real.
- Mitigação imediata já em vigor: o preset por omissão é `workspace-write` **com
  `approval: ask`** (nunca `never`), e cada comando fica registado no log do motor.

---

## 2. Bun vs Node (resposta à tua pergunta)

**Não. Para correr o kernel DSH, o Bun não é uma melhoria — é uma reescrita da camada de arranque do DSH contra uma API que não existe no Bun.**
Relatório completo, com fontes e experiências em primeira mão: [`research/bun-vs-node-android.md`](research/bun-vs-node-android.md) (Bun 1.4.3 testado de facto, não só lido).

Três bloqueios, cada um suficiente por si:

1. **`--expose-internals` no Bun é decorativo.** O `dsh-app-boot` vai buscar `internal/modules/{esm/loader, cjs/loader, helpers, esm/utils, esm/resolve}` através do addon nativo `node-addon-require-builtin`, **num caminho sem `try/catch`** — falhar ali é fatal (“host preparation failed”; nem o perfil `web` arranca). Executando o mesmo script nos dois runtimes: **Bun → `MODULE_NOT_FOUND` nos cinco; Node → os cinco presentes.** O Bun mapeia `internal/x` → `internal:<x>` no *seu próprio* registo (`src/jsc/ModuleLoader.rs`); o loader do Node não existe lá. Pior: o Bun **ignora flags desconhecidas em silêncio**, portanto o erro aparece tarde, não no arranque.
2. **O Bun é JavaScriptCore, não V8** — logo toda a caixa de ferramentas de arranque/distribuição de que o DSH depende desaparece: `node:sea` = “não implementado”, `--build-snapshot`/`--snapshot-blob` engolidos em silêncio, `v8.serialize` com formato do JSC, `process.binding` parcial. A própria documentação do Bun admite `AsyncLocalStorage` que não atravessa `MessagePort`/`BroadcastChannel`/`Worker`, `worker_threads` que ignora `resourceLimits`, `http` `keepAlive`/`listen(fd)`/`signal` como no-ops.
3. **Android é não-oficial e está partido.** Existe um `bun-linux-aarch64-android.zip` oficial (Bionic, `/system/bin/linker64`, **94,7 MB** vs os **45,3 MB** do node do Termux que já validámos), mas o README só lista Linux/macOS/Windows, o `install.sh` não tem alvo Android (o Termux recebe o binário glibc — bug aberto #44918) e o binário oficial **é morto por `SIGSYS` em `close_range` em qualquer dispositivo stock** (#30766, aberto desde 2026-05-15; PR de correcção por mergear; **zero** tratamento de `SYS_SECCOMP` na árvore 1.4.3). O Termux só o traz com **11 patches** Android.

E, decisivo para nós: **trocar para Bun não resolvia o problema que motivou a pergunta.** `sharp` 0.35.5 tem 25 prebuilds de plataforma e **nenhum para Android**; `node-pty` 1.1.0 só traz darwin/win32; `@vscode/ripgrep` é um downloader de binário. Só `koffi` tem prebuild oficial `@koromix/koffi-android-arm64`. Os patches do porto (`child_process` + shim JS do `sharp`) continuam a ser a resposta certa.

### Os ganhos reais estão no Node (medidos nesta sessão; macOS arm64, árvore sintética de 5 000 módulos)

| # | Optimização | Cold start medido | Veredicto |
|---|---|---|---|
| 1 | **Empacotar o núcleo fixo** (esbuild), plugins externos | 187,6 ms → **60,0 ms (−68 %)** | o maior ganho pelo menor custo; em f2fs deve amplificar |
| 2 | **SEA com `useSnapshot:true` + `execArgv:["--expose-internals",…]`** | **31,3 ms sem flags na linha de comandos** (5,9×) | funciona com `--expose-internals` e alcança `internal/modules/cjs/loader`; exige construção **no binário aarch64-android** |
| 3 | **Motor sempre quente** (daemon; nunca re-`exec` ao recarregar a UI) | não medido aqui, mas o porto mostra 17–43 s de espera visível com o motor pronto em ~5 s | melhor ganho de latência percebida — é o nosso D2 |

**Explicitamente a evitar:** `NODE_COMPILE_CACHE` *antes* de empacotar (perda líquida: 187,6 → 247 ms; 4 712 ficheiros/5,3 MiB) — só depois de empacotar compensa (60,0 → 51,0 ms), com `portable:true` e pré-aquecimento durante a primeira extracção; e afinação de `--max-semi-space-size`/`--max-old-space-size` (sem efeito mensurável).

Consequência para o plano: **o motor passa a ser distribuído como SEA com snapshot, construído no mesmo binário/versão de Node** (blob ~10 MB, específico de arch+OS+versão; o `bin.js` do DSH é ESM ⇒ precisa de wrapper CJS). Se um dia o Bun suportar Android+Bionic a sério, o D2 torna a troca de runtime um detalhe local do daemon — outra vantagem de tirar o motor do sandbox.

---

## 3. Arquitectura alvo

```
┌─────────────────────────── Telefone ───────────────────────────┐
│                                                                │
│  /data/adb/cachalote/            (root; fallback /data/local/tmp)   │
│  ├── runtime/   node 24 arm64 + .so                            │
│  ├── kernel/    DSH 0.2.0-rc.2 + patches Android               │
│  ├── home/      DSH_HOME (perfis sdk/ + web/, sessões, config) │
│  ├── cachalote.sock  AF_UNIX 0600  ◄──────────┐                     │
│  └── cachalote.log                              │                    │
│                                            │                    │
│  daemon (node, uid root ou shell)          │ JSON-RPC           │
│   └── perfil sdk → agente + tools          │ (2 sentidos)       │
│                                            │                    │
│  ┌───────────────── App Android (Kotlin/Compose) ───────────┐  │
│  │ :app        UI nativa: chat, sessões, aprovações, diffs  │  │
│  │ :rpc        cliente JSON-RPC + codegen                   │  │
│  │ :caps       AccessibilityService, notificações, overlay, │──┘
│  │             SAF/ficheiros, WebView (browser tool), share │
│  │ WebView     modo completo: frontend DSH do perfil "web"  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

Ferramentas do modelo (JS, dentro do kernel): `android_screen/tap/type/see/scroll`, `notify`, `shell` (root/Shizuku), `fs`, `browser_*`, `host_status`.
Todas falam com o `:caps` do app pela ponte reversa — sem portas, sem tokens em env vars.

---

## 4. Fases

Cada fase tem critério de saída verificável no dispositivo. **F0+F1 dão um agente a funcionar no telemóvel em ~2 dias.**

### F0 — Spike end-to-end no dispositivo (½–1 dia) ← *maior risco, primeiro*
Receita já a correr (`.work/f0/run-f0.sh`, tudo em `/data/local/tmp`, sem root):
1. Extrair a árvore do kernel do `app.asar` com `tools/extract-kernel.cjs --trim` (corta plataformas estranhas e `.node`).
2. **Aplicar o overlay MIT** de `repo/dsh-patches/overlay/` (os 4 patches Android: subprocess/pty, attachment/sharp, bash, session-persistence/`node-addon-system`).
3. Empacotar, empurrar por adb, extrair no telemóvel e arrancar o perfil **`acp`** com `smoke.cjs` (cliente NDJSON que faz `initialize` + `session/new`, sem credenciais).
4. Confirmar o handshake e, depois, repetir com `sdk-minimal` para comparar.
5. Repetir com o transporte já em **AF_UNIX**, via overlay `--patch` (o passo que o F1 vai usar).
- **Saída:** transcrição de `initialize`/`session/new` respondidos no telemóvel + decisão fechada sobre transporte e perfil.
- **Riscos:** ~~os patches do porto serem de uma versão anterior de kernel~~ (confirmado: **são**), nativos a faltar, prefixos Termux.

#### Resultado do F0 (1.ª volta) — ✅ motor arranca no telemóvel

Executado em `/data/local/tmp/cachalote` (uid shell, **sem root**), node 24.18.0 + kernel 0.2.0-rc.2 trimado (145 MB de tar):

```
[smoke] node v24.18.0 | perfil acp | stdio
[cliente] {"jsonrpc":"2.0","id":1,"method":"initialize", ...}
[motor]   {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,
           "agentInfo":{"name":"deepseek-harness-acp","version":"0.0.1"},
           "agentCapabilities":{"mcpCapabilities":{"http":true},
             "sessionCapabilities":{"close":{},"list":{},"resume":{}}},
           "authMethods":[]}}
```

Ou seja: **servidor ACP a responder no S10+, sem credenciais.** `session/new` ainda falha (`ACP session persistence flush failed`) — causa identificada abaixo.

#### O que destravou o arranque (e o que o bloqueava)

1. **Bloqueio real:** o `dsh-app-boot` carrega cinco internals do Node através do addon **nativo** `node-addon-require-builtin`, que **não tem build Android** ⇒ `host preparation failed`, fatal e sem `try/catch`.
2. **Solução (5 linhas, nossa):** o Node 24.18.0 do telemóvel carrega os cinco internals com `require()` normal desde que se use `--expose-internals` (verificado no dispositivo, os cinco OK). O addon passa a opcional, com fallback. → `tools/patch-android.cjs`, patch 1. **Já funciona.**
3. **Armadilha descoberta:** o overlay do projecto de referência é **0.1.5-rc.2** enquanto o kernel é **0.2.0-rc.2** ⇒ o loader recusa (`failed to import`) `subprocess`, `sandbox`, `tool-fs-search`… Aplicá-lo cegamente é pior que não aplicar nada. **Não reutilizamos o overlay**; escrevemos os nossos patches contra os originais 0.2.0-rc.2.

#### Lista de patches própria (mínima, contra 0.2.0-rc.2)

| # | Alvo | Problema | Patch |
|---|---|---|---|
| 1 | `dsh-app-boot` | addon nativo `node-addon-require-builtin` sem Android | ✅ addon opcional + fallback `require()` |
| 2 | `dsh-session-persistence-jsonl` | `import { tryLockExclusive } from "@deepseek-ai/node-addon-system/flock"` é **estático** (linha 11) | stub de `flock` (daemon é mono-processo: o lock cruzado não é preciso) — desbloqueia `session/new` |
| 3 | `dsh-sandbox-local` | `.../node-addon-system/landlock-run` também **estático** (linha 6) | stub com `probe` = indisponível (o `confine()` continua *fail-closed* se algum dia for chamado) |
| 4 | `dsh-session-persistence-jsonl` + `dsh-attachment-local` | SELinux nega `link(2)` → `EACCES` ao publicar artefactos (foi o erro real do `session/new`) | 5 publicações `link()` → `rename()` |
| 5 | `dsh-tool-bash` | lê `ctx.get("sandboxPolicy")` **sem o declarar em `inject`** → corrida de composição quando o `bash-local` substitui o `bash-sandbox` | `inject += "sandboxPolicy"` |
| 6 | `cordis.patch.yml` | telemetria OTel arrasta `@opentelemetry/*`; e o serviço `shell` tem de vir de algum lado | desactivar otel/telemetria/`*-sandbox`; `insert` do `bash-local`; presets de permissão |
| 5 | `sharp` (attachment) / `@vscode/ripgrep` | já são **lazy** — não bloqueiam arranque | tratar só quando chegarmos a imagens/pesquisa: shim JS e/ou `rg` arm64 |

Nada disto exige compilar addons para Android — que era o risco grande deste projecto.

### F1 — Daemonização (1–2 dias) — ✅ **feito e verificado**
Instalador (`daemon/install.sh` via adb), layout em `/data/adb/cachalote`, arranque idempotente, socket 0600, log com rotação, watchdog, autostart Magisk `service.d`, comando de estado (`cachalote status`).

Resultado em `/data/adb/cachalote` (root, 558 MB) — todas as provas no dispositivo:

```
$ cachalote status
daemon : a correr (pid 28591)
socket : /data/adb/cachalote/run/cachalote.sock (modo 600)
motor  : a correr
handshake:  OK deepseek-harness-acp 0.0.1
```

- **Supervisão:** `kill -9` ao motor → o daemon reiniciou-o sozinho em <9 s.
- **Sobrevive a sessões adb:** o daemon continua vivo numa sessão nova.
- **Autostart:** `/data/adb/service.d/cachalote.sh` testado por execução directa (parar → correr o script → daemon de volta).
- **Transporte:** ponte própria `daemon/bridge.cjs` (stdio ↔ AF_UNIX) em vez de apontar o motor directamente ao socket. Motivo: reconexão do app sem matar o motor, supervisão com backoff, um só socket para os dois sentidos (incluindo a futura ponte `host.*`), e o stderr do motor num log. O ACP aceita `config.stream` e o SDK aceita `input`/`output`, portanto a alternativa sem ponte existe (verificada ao vivo com TCP pelo subagente) — fica como plano B.
- **Chave:** `daemon/set-key.sh` grava `home/.env` com modo 600. Verificado com chave de teste: o erro passou de *"no API key"* para **`Authentication Fails, Your api key: ****0000 is invalid (request_id: …)`** — a chave chegou mesmo à API.

### F2 — App nativo mínimo (3–5 dias)
Gradle + Kotlin + Compose. Módulos `:app`, `:rpc`, `:caps`. Cliente do protocolo com streaming, lista/criação de sessões, aprovações, escolha de modelo, histórico local leve.
- **Saída:** conversa completa pela UI própria, com streaming e uso de ferramentas visível.

### F3 — Capacidades Android (3–5 dias)
Ponte reversa `host.*`; `AccessibilityService` (ler árvore, tap/type/scroll, screenshot), notificações, share sheet, SAF/ficheiros, arranque/paragem/diagnóstico do daemon na UI, ferramentas do modelo correspondentes.
- **Saída:** “abre a app X e faz Y” e “tira screenshot e descreve” sem tocar no ecrã.

### F4 — Modo completo, plugins e extras (2–4 dias)
Perfil `web` no daemon + WebView na app (paridade), gestão de plugins (pnpm), browser tool, virtual display **se** valer a pena (decisão LGPL em D8).
- **Saída:** paridade funcional com o DSH desktop.

### F5 — Empacotamento (2–3 dias)
Flavors (`root`/`shizuku`/`in-app`), onboarding de permissões, actualização do kernel com payload assinado, docs, atribuições.
- **Saída:** instalação limpa num segundo dispositivo.

---

## 5. Riscos

| Risco | Prob. | Impacto | Mitigação |
|---|---|---|---|
| Superfície JSON-RPC insuficiente para a UI nativa | média | alto | modo WebView desde F2/F4; contribuir a montante |
| Kernel carrega nativos mesmo no perfil programático | **alta** | alto | Confirmado: `@deepseek-ai/node-addon-system` (Node-API, flock/Landlock) é **import estático** de `dsh-session-persistence-jsonl` ⇒ todos os perfis persistentes o carregam; `sdk-minimal` ainda monta `dsh-terminal` (⇒ `node-pty`); `sharp`/`ripgrep` entram na primeira imagem/pesquisa. Todos os binários são darwin-arm64. ⇒ **aplicar os 4 patches do porto (MIT) é obrigatório**, não opcional |
| Prefixos Termux (openssl/git/CA) | alta | baixo | já resolvido e documentado (knowledge 4); ou construir node com NDK |
| Node morto por LMK em repouso | média | médio | daemon com `oom_score_adj` baixo, watchdog, estado visível na UI |
| AI com shell root = risco real | alta | alto | presets do DSH (`workspace-write` por omissão), aprovações na UI, auditoria de comandos, allowlist |
| Kernel upstream muda e quebra patches | alta | médio | pipeline de patches versionado + teste F0 automatizado por versão |
| LGPL do virtual display | certa se usado | médio | decidir em F4; alternativa: reimplementar só o necessário |

---

## 6. Decisões (estado)

| # | Decisão | Estado |
|---|---|---|
| A | Transporte app↔motor | ✅ **AF_UNIX + token** (fallback TCP+token) — aceite por omissão |
| B | Privilégio do motor | ✅ **root (Magisk) com fallback** (Shizuku → in-app `targetSdk 28`) — decidido pelo utilizador |
| C | Ordem da UI | ✅ **nativa primeiro**, WebView como rede de segurança — aceite por omissão |
| D | Credenciais no device | ✅ **API key introduzida na app**; nunca no repositório, nunca copiada do `.credentials.yaml` — decidido pelo utilizador |
| E | Plugins Android | ✅ **reescrever contra a nossa ponte**, usando os deles (MIT) como referência — aceite por omissão |
| F | Identidade | ✅ **Cachalote** · package `dev.ricardopimentel.cachalote` (ver D9) |
| G | Distribuição | só sideload pessoal primeiro, **mas a app principal nasce com `targetSdk 35`** para não fechar a porta a distribuição futura |

Para A/C/E/G considero as minhas recomendações aceites — diz se queres mudar alguma.

### D9 — Identidade: **Cachalote**

- **Nome visível:** *Cachalote* (o cachalote é o cetáceo que mergulha mais fundo — mistura a identidade da baleia do DeepSeek com “fundo”, sem usar nenhum dos nomes protegidos).
- **Package id:** `dev.ricardopimentel.cachalote` (alternativa, se preferires: `io.github.<o-teu-handle>.cachalote`).
- **Daemon:** `/data/adb/cachalote/`, socket `cachalote.sock`, plugin do kernel `cachalote-rpc-ext`.
- **Assinatura honesta no README/UI:** *“cliente Android para o DeepSeek Harness — projecto independente, não afiliado à DeepSeek.”*
- **Porque não “deepdroid”** (a tua sugestão original): duas reservas, ambas evitáveis de graça nesta fase — (a) o sufixo `-droid` convive mal com a marca *Droid* do ecossistema Android (não consegui confirmar o aviso da Lucasfilm nesta ROM, mas a confusão de marca é o risco real); (b) o prefixo `Deep*` continua a evocar a DeepSeek, o que agrava a leitura de “produto oficial” se um dia publicares. O **Cachalote** mantém a ideia (baleia + profundidade) e não colide: 14 repositórios homónimos no GitHub (todos ≤3★) e `cachalote` livre no npm.
- Alternativas testadas e livres no npm, se preferires outra: `jubarte` (32 repos, nenhum relevante), `rorcual` (10), `cachalote` (14). Renomear continua a custar quase nada até ao F2 (antes de haver package assinado e publicado).

---

## 7. Estrutura de repositório proposta

```
cachalote/
├── PLANO.md                    este documento
├── docs/
│   ├── knowledge-android.md    armadilhas já resolvidas (sintoma/causa/solução)
│   └── research/               relatórios de investigação
├── tools/
│   ├── extract-kernel.mjs      app.asar → árvore JS do kernel
│   ├── patch-android.mjs       patches Android + materialização de perfis
│   └── fetch-node-android.sh   runtime Node arm64 (Termux/NDK)
├── daemon/
│   ├── install.sh              instala/mantém /data/adb/cachalote
│   └── cachalote.service.sh    autostart Magisk
├── rpc/                        schema + cliente + codegen
├── app/                        Android (Kotlin + Compose + Gradle)
└── plugins/                    ferramentas do modelo (JS) sobre a ponte reversa
```

---

## Anexo A — Protocolo do motor (JSON-RPC), verificado no kernel 0.2.0-rc.2

Descoberto por execução real do kernel (`dsh --profile sdk-minimal --help`) e leitura de
`dsh/node_modules/@deepseek-ai/dsh-sdk-jsonrpc-server/lib/index.js` dentro do `app.asar`.

### Perfis programáticos

| Comando | O que faz |
|---|---|
| `dsh --profile sdk-minimal` | “**Serve DeepSeek Harness SDK clients over stdio JSON-RPC**” — um runtime até o cliente desligar |
| `dsh --profile acp` | “Serve automation clients over Agent Client Protocol stdio” |
| `dsh --profile headless [task]` | responde a **uma** tarefa e sai; `--json` emite eventos *newline-delimited*; `--session-id <id>` retoma uma sessão; `-` lê a tarefa do stdin |
| `dsh --profile web` | frontend web completo (modo “completo” da app) |
| `dsh --from-default-profile <template>` | cria um perfil próprio a partir de um template |
| `dsh --dump-config` / `--dump-config-schema` | imprime a árvore composta / o JSON Schema das entradas de perfil (útil para validar a nossa extensão) |

Flags do lançador: `--profile <nome>`, `--patch <ficheiro>` (repetível), `--dump-config*`.

### Transporte

`JsonRpcLineTransport` (pacote `@deepseek-ai/dsh-sdk-protocol`): **JSON newline-delimited** em stdio.
`stdout` é **reservado** a frames de protocolo — o perfil não pode montar loggers para stdout.

### Métodos (os três que existem)

| Método | Params | Resultado |
|---|---|---|
| `initialize` | `{ cwd, provider, model, reasoningEffort?, maxTokens? }` | `{ serverInfo: { name: "deepseek-harness-sdk-runtime", version: "0.0.1" } }` |
| `session/prompt` | `{ sessionId, contentBlocks }` | `{ messageId }` |
| `shutdown` | — | `{}` (flush, dispose, exit 0) |

Notas: `cwd` e `provider`/`model` são **obrigatórios** (sem `cwd`, o erro é `The "paths[0]" argument must be of type string`); `provider` tem de ter adapter registado, com fallback `deepseek-official`; a sessão é criada implicitamente no primeiro `prompt` para aquele `sessionId`.
`contentBlocks` são blocos do DSH: texto, e imagens como `{ type:"image", data, mimeType }` → convertidas em anexos duráveis (exige attachment store).

### Notificações (motor → cliente)

`session.event` `{sessionId, event}` · `session.status` `{sessionId, status}` · `subagent.started` `{parentSessionId, childSessionId}` · `subagent.finished` `{provider, agentId, parentSessionId, childSessionId, status, stopReason, lastAssistantMessage?}`

### Lacunas (importantes para o nosso desenho)

1. **Não há método de aprovação.** O servidor só responde a `initialize`, `session/prompt` e `shutdown` ⇒ não se pode aprovar/negar um pedido de permissão pela superfície SDK. Consequência: com este perfil, o preset tem de ser não-interactivo (`danger-full-access`/`never`) — o que é inaceitável para nós, dado que o motor corre como root.
2. **Não há listagem/histórico de sessões, escolha de modelos, settings nem gestão de plugins** (isso vive nas rotas internas do `dsh-web-app`, não numa API suportada).
3. Por isso: escrevemos o plugin `cachalote-rpc-ext` (cordis) que (a) acrescenta `session/approve`, `session/list`, `session/history`, `settings/*` e (b) serve de ponte `host.*` para as capacidades Android. Alternativa a estudar: usar o perfil `acp`, que já expõe um protocolo de agente com permissões (ver `research/dsh-sdk-surface.md`).

### Como isto se usa na prática

- **F0 (spike):** `headless --json "tarefa"` dá um teste de fumo automatizável por `adb shell` (exit code + eventos NDJSON) — é o nosso “hello world” no telemóvel.
- **App nativo:** cliente Kotlin do transporte NDJSON; 3 métodos + 4 notificações são triviais de implementar, e o cliente não depende de tipos internos do frontend.
- **Daemon:** como o perfil é *stdio*, o daemon é um supervisor fino que arranca o kernel em `sdk-minimal` e faz ponte `stdio ↔ AF_UNIX`, ganhando supervisão e restart de graça.
