# Cachalote — estado do projecto

> Relatório para revisão. Tudo o que está marcado ✅ foi **verificado no Samsung
> Galaxy S10+ (SM-G975F, LineageOS 23.2 / Android 16, Magisk)** — as provas estão citadas.

---

## 1. Resumo em uma linha

O **motor DeepSeek Harness 0.2.0-rc.2 corre no telemóvel como daemon root**, fala
**Agent Client Protocol (ACP) por JSON-RPC sobre um socket AF_UNIX**, arranca sozinho no
boot, e falta apenas **uma coisa tua**: introduzir a API key.

---

## 2. O que está feito e verificado

| # | Peça | Estado | Prova |
|---|---|---|---|
| 1 | Runtime Node arm64 no Android | ✅ | `v24.18.0 \| android \| arm64 \| abi=137 \| openssl=3.6.5`, a correr de `/data/local/tmp` **sem root** |
| 2 | Kernel DSH 0.2.0-rc.2 a arrancar | ✅ | extraído do `app.asar` (10 205 ficheiros / 110,5 MB após trim), 6 patches próprios |
| 3 | Perfil **ACP** completo | ✅ | `initialize` → `session/new` (sessionId + configOptions) → `session/prompt` |
| 4 | Daemon root com socket AF_UNIX | ✅ | `/data/adb/cachalote`, socket `0600`, `status` faz um `initialize` real: `OK deepseek-harness-acp 0.0.1` |
| 5 | Supervisão do motor | ✅ | matei o motor (`kill -9`) → o daemon reiniciou-o sozinho em <9 s |
| 6 | Arranque automático no boot | ✅ | script em `/data/adb/service.d/cachalote.sh`; testado por execução directa (stop → script → daemon de volta) |
| 7 | Persistência de sessões no Android | ✅ | `link()` bloqueado pelo SELinux substituído por `rename()`; sessões gravam em `home/sessions/` |
| 8 | Mecanismo da API key | ✅ | ficheiro `home/.env` com modo 600; com uma chave de teste o erro passou de *"no API key"* para **`Authentication Fails, Your api key: ****0000 is invalid (request_id: …)`** — ou seja, a chave chegou mesmo à API |

### Como o daemon está montado

```
/data/adb/cachalote/                 (root; ecrã do sistema não vê)
├── runtime/bin/node + runtime/lib/  Node 24.18.0 arm64 (Termux) — 45 MB
├── kernel/dsh/node_modules/         kernel DSH 0.2.0-rc.2 + os nossos 6 patches — 192 MB
├── home/                            DSH_HOME: profiles/, sessions/, .env (600), cordis.patch.yml
├── bin/{cachalote,bash}             CLI de controlo + shim bash -> /system/bin/sh
├── bridge.cjs                       ponte stdio ↔ socket AF_UNIX (supervisão + reconexão)
├── run/cachalote.sock               AF_UNIX, modo 600 (é aqui que a app fala)
└── logs/{daemon,engine}.log
```

O motor é arrancado pela ponte como `node --expose-internals …/dsh/lib/bin.js --profile acp`,
com `DSH_HOME`, `LD_LIBRARY_PATH`, `PATH` (com o shim `bash`) e `TMPDIR` injectados.

---

## 3. A tua parte: introduzir a API key

Escrevi `daemon/set-key.sh` para **tu** correres. A chave nunca passa por mim, nunca
entra em `argv` (vai por stdin), nunca é ecoada e nunca fica no histórico.

```sh
cd /Users/ricardopimentel/Documents/dshm
./daemon/set-key.sh                 # pede a chave (não aparece no ecrã) e grava-a
./daemon/set-key.sh --check         # confirma o ficheiro e mascara o valor
./daemon/set-key.sh --remove        # apaga
```

O que ele faz: escreve `DEEPSEEK_API_KEY=<a tua chave>` em
`/data/adb/cachalote/home/.env` com modo `600`. O motor lê esse ficheiro como camada de
credenciais (precedência: ambiente herdado > `.credentials.yaml` > `cwd/.env` > `$DSH_HOME/.env`).

**Depois de o correres**, o teste ponta-a-ponta é:

```sh
adb shell su -c 'LD_LIBRARY_PATH=/data/adb/cachalote/runtime/lib \
  /data/adb/cachalote/runtime/bin/node /data/adb/cachalote/bin/client-smoke.cjs \
  /data/adb/cachalote/run/cachalote.sock --prompt "diz apenas: ok"'
```

Deve responder com texto do modelo. (Se usares outro provedor, o script aceita
`--var NOME_DA_VARIAVEL`; o kernel 0.2.0-rc.2 usa `DEEPSEEK_API_KEY` no perfil ACP.)

---

## 4. Os 6 patches que fizemos ao kernel (todos próprios, contra 0.2.0-rc.2)

Ficam em [`tools/patch-android.cjs`](tools/patch-android.cjs), idempotentes, aplicados
depois de [`tools/extract-kernel.cjs`](tools/extract-kernel.cjs):

| # | Alvo | Problema | Correção |
|---|---|---|---|
| 1 | `dsh-app-boot` | exigia o addon **nativo** `node-addon-require-builtin` (sem build Android) → `host preparation failed` | addon opcional + fallback `require()` (funciona porque usamos `--expose-internals`) |
| 2 | `node-addon-system/flock` | `ERR_FLOCK_UNSUPPORTED_PLATFORM` no Android → `session/new` falhava | lock imediato em Android (mono-processo, como o stub que o próprio upstream faz no worker do browser) |
| 3 | `dsh-bash-local` | sem `sandboxMode`, o `permission-presets` recusava montar | `get sandboxMode() { return "workspace-write" }` — **declarativo**, ver §6 |
| 4 | `dsh-session-persistence-jsonl` + `dsh-attachment-local` | SELinux nega `link(2)` → `EACCES` ao publicar artefactos | 5 publicações `link()` → `rename()` |
| 5 | `dsh-tool-bash` | lê `ctx.get("sandboxPolicy")` **sem o declarar em `inject`** → corrida de composição quando o `bash-local` substitui o `bash-sandbox` | `inject += "sandboxPolicy"` |
| 6 | `config/cachalote.patch.yml` | telemetria OTel sem deps no Android; falta o serviço `shell` | desactivar otel/telemetria; `insert` do `bash-local`; presets de permissão |

**O overlay do projecto de referência (woaiys3) não foi usado**: é **0.1.5-rc.2** e o kernel
é 0.2.0-rc.2, o que faz o loader recusar `subprocess`, `sandbox` e companhia. Isso está
documentado como armadilha na base de conhecimento.

---

## 5. Comandos do dia-a-dia

```sh
# no Mac
./daemon/install.sh --rebuild --autostart    # reinstala tudo (extrai, patcheia, envia, arranca)
./daemon/set-key.sh                          # a chave
adb shell su -c '/data/adb/cachalote/bin/cachalote status'    # estado + handshake
adb shell su -c '/data/adb/cachalote/bin/cachalote logs 60'   # logs do daemon e do motor
adb shell su -c '/data/adb/cachalote/bin/cachalote restart'
```

---

## 6. Nota de segurança importante (D10 do plano)

O motor corre como **root** e no Android **não existe Landlock**. O `sandboxMode` que o
`bash-local` declara (`workspace-write`) é **declarativo**: quem trava um comando fora do
workspace é a **camada de aprovação** (preset `workspace-write` com `approval: ask`), não o
kernel. Declarar `danger-full-access` seria pior — a política deixaria de pedir aprovação.

Consequência prática: **enquanto o motor correr como root, qualquer comando aprovado pode
tocar em qualquer coisa.** O confinamento a sério é trabalho de F3, e há duas vias:
(a) executar as ferramentas pela ponte `host.*`, onde podemos impor allowlist de caminhos e
auditar; (b) no flavor sem privilégios, deixar o próprio sandbox da app Android ser o limite.

---

## 7. O que falta (na ordem em que eu continuaria)

| Fase | Trabalho | Notas |
|---|---|---|
| **F2** | App Android nativo (Kotlin + Compose) como cliente do socket: chat, sessões, streaming de `session/update`, permissões (`session/request_permission`), ecrã da chave | o protocolo já está provado; o cliente de referência em JS é `daemon/client-smoke.cjs` |
| **F3** | Ponte `host.*` (o mesmo socket, sentido inverso) + `AccessibilityService`, notificações, overlay, ficheiros, e as ferramentas do modelo (`android_screen/tap/type/see`) | é aqui que se ganha o "AI que opera o telefone" |
| **F4** | Perfil `web` + WebView (paridade total), gestão de plugins, browser tool | |
| **F5** | Flavors (root/Shizuku/sem privilégios), onboarding de permissões, actualização assinada | |
| **D6b** | SEA + snapshot V8 (medido: 31 ms vs 187 ms) para o arranque | opcional; o daemon já evita o cold start |

---

## 8. Ficheiros deste repositório

```
PLANO.md                      plano completo (evidências, decisões D1–D10, fases, riscos)
STATUS.md                     este relatório
daemon/install.sh             instala/actualiza o daemon no dispositivo
daemon/cachalote              CLI no dispositivo (start/stop/status/logs/env)
daemon/bridge.cjs             ponte motor ↔ socket AF_UNIX (supervisão, reinício, log)
daemon/client-smoke.cjs       cliente de referência do socket (o que a app fará)
daemon/set-key.sh             introdução da API key (a correr por ti)
daemon/cachalote.service.sh   arranque no boot (Magisk service.d)
config/cachalote.patch.yml    camada de patch do perfil (o que desactivamos e inserimos)
tools/extract-kernel.cjs      app.asar → árvore JS do kernel (com trim)
tools/patch-android.cjs       os 6 patches Android
docs/knowledge-android.md     40+ armadilhas do Android, com sintoma/causa/solução
research/                     relatórios de investigação (superfície SDK/ACP, Bun vs Node)
```
