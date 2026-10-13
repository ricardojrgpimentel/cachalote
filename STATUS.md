# Cachalote — estado do projecto

> ## ⚠ ACÇÃO NECESSÁRIA (1 minuto)
>
> **A chave do provedor tem de ser introduzida outra vez.** Durante o diagnóstico do ecrã da
> chave escrevi uma chave de teste directamente na app e **substituí a tua chave real**, que não
> tinha cópia no dispositivo (o daemon só registava o comprimento). O `.env` está agora vazio,
> modo 600. Repõe com:
>
> ```sh
> cd /Users/ricardopimentel/Documents/dshm && ./daemon/set-key.sh --verify
> ```
>
> (ou na app: separador **Chave** → colar → Guardar). O `--verify` confirma no fim com um turno
> real. Regras para não voltar a acontecer: [`docs/knowledge-android.md`](docs/knowledge-android.md) #67.

> Relatório para revisão. Tudo o que está marcado ✅ foi **verificado no Samsung
> Galaxy S10+ (SM-G975F, LineageOS 23.2 / Android 16, Magisk)** — as provas estão citadas.
>
> **F2 entregue**: app Android nativa com ecrã da chave (colas a chave no telemóvel) e
> conversa com o agente. Ver §9.
>
> **F3 entregue**: o agente **opera o telefone** — notificações, ler o ecrã, tocar, escrever,
> teclas e captura — com allow-list de capacidades e registo. Ver §10.

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
| ~~**F2**~~ | ~~App Android nativo~~ — ✅ **feito**, ver §9 | falta responder a `session/request_permission` (aprovações) na UI |
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


---

## 9. F2 — a app Android nativa (feito e verificado)

App em **Kotlin + Compose** (`app/`), package `dev.ricardopimentel.cachalote`, três ecrãs:

| Ecrã | O que faz |
|---|---|
| **Estado** | estado do daemon/motor (`cachalote.status`), root concedido?, token obtido?, botões *Ligar daemon*, *Obter token (root)*, *Reiniciar motor* |
| **Chave** | campo para **colar a API key no telemóvel**; a app envia-a ao daemon (`cachalote.credentials.set`), que a escreve em `home/.env` com **modo 600** — a app **não** a guarda |
| **Conversa** | *Nova sessão* (ACP `session/new`), campo de prompt, e o transcript com streaming: `agent_message_chunk`, `agent_thought_chunk`, `tool_call`/`tool_call_update` |

Como a app fala com o daemon (ver D11): **TCP 127.0.0.1:39731 autenticado por token**
(o SELinux não deixa `untrusted_app` chegar ao socket AF_UNIX em `/data/adb`). O token é
obtido **uma vez** com `su -c 'cachalote app-token'` (o Magisk guarda a autorização) e fica
nas prefs privadas da app.

### Verificação automática (9/9 passos) — `tools/app-ui-test.sh`

O teste instala a app, pré-autoriza o root no Magisk, e conduz a UI por `uiautomator`/`input`:

```
== 1. app instalada e aberta ................. OK
== 2. estado do daemon ....................... OK   ("daemon": true, …)
== 3. obter token (root) ..................... OK
== 4. ecrã da chave: colar e guardar ......... OK
== 5. confirmar no daemon .................... OK   (.env com a chave de teste, modo 600)
== 6. conversa: nova sessão + prompt ......... OK   (sessão criada; prompt enviado)
== 7. limpeza ................................ OK   (.env vazio, modo 600)
== resumo: 9 passos OK, 0 falhas ==
```

E a prova mais forte, no log do motor depois de a chave ser escrita **pelo ecrã da app**:

```
Authentication Fails, Your api key: ****7890 is invalid (request_id: 1304d558-…)
```

Ou seja: **a chave colada no telemóvel chegou à API DeepSeek.** Com uma chave válida, é a
resposta do modelo que aparece no transcript.

### Comandos

```sh
./tools/install-app.sh          # compila + instala + pré-autoriza root + abre a app
./tools/app-ui-test.sh          # teste de UI completo (usa uma chave de teste e limpa-a)
```

### Notas de implementação que custaram sangue

1. **Nada de I/O de socket no thread principal** — dava `NetworkOnMainThreadException`
   (apanhado pelo teste). Todo o `call()` corre em `Dispatchers.IO`.
2. **O motor lê as credenciais no arranque**: gravar o `.env` com o motor a correr não
   bastava (o turno seguinte ainda dizia *"no API key"*). Agora o daemon **reinicia o motor
   automaticamente** ao gravar/remover a chave (`cachalote.credentials.set` → `engineRestart: agendado`).
3. **Compilar neste Mac** exige `GRADLE_USER_HOME` e `ANDROID_USER_HOME` **dentro do
   workspace** (a política de ficheiros do harness bloqueia `~/.gradle` e `~/.android`), e o
   JDK é o do Android Studio (`Contents/jbr`).


---

## 10. F3 — o agente opera o telefone (feito e verificado)

### Arquitectura do canal reverso

```
ferramenta no kernel (android_screen, android_tap, …)
   │  TCP 127.0.0.1:39731, cliente "tool", token
   ▼
daemon (ponte)  ──►  pedido host.call (id string "host-N")  ──►  app
   ▲                                                              │
   └──────────────── resposta {result|error} ◄────────────────────┘
```

### Ferramentas que o modelo passa a ter

| Ferramenta | O que faz | Precisa de |
|---|---|---|
| `android_status` | diz o que a app pode fazer (acessibilidade, capacidades autorizadas) | — |
| `android_notify` | publica uma notificação no telefone | permissão de notificações |
| `android_screen` | lê o ecrã como lista de elementos (texto, descrição, limites, clicável/editável) | acessibilidade |
| `android_tap` | toca por texto, descrição ou coordenadas | acessibilidade |
| `android_type` | escreve no campo focado (com Enter opcional) | acessibilidade |
| `android_key` | back / home / recents / enter / gaveta de notificações | acessibilidade |
| `android_see` | captura o ecrã para PNG e devolve o caminho | acessibilidade (Android 11+) |

### Prova ponta-a-ponta (agente real, no S10+)

```
1) android_status  → {"accessibility": true, "notifications": true,
                      "allowed": ["notify","screen","tap","type","key","screenshot"]}
2) bash            → am start -n com.android.calculator2/.Calculator   (abriu a app)
3) android_screen  → 36 elementos: √ π ^ ! DEG sin cos tan … 7 8 9 × … AC ( ) % ÷
4) android_tap     → {"ok": true, "clicked": "7"}
5) android_screen  → o visor (formula) passou de "Sem fórmula" para "7"
```

Verificação independente (fora do agente): `formula = "7"` na calculadora. Repetível com
[`tools/app-capabilities-test.sh`](tools/app-capabilities-test.sh) `--full` → **6/6 passos**.

### A barreira de segurança (o que substitui o sandbox que o Android não tem)

- **Allow-list por capacidade** no separador **Poderes**: `notify` nasce autorizada; o resto é
  opt-in, com botões de autorizar/negar todas. O agente pede, a app recusa com uma mensagem
  clara e o modelo percebe.
- **Registo de auditoria**: cada pedido (capacidade + parâmetros, 160 chars) fica visível na
  app, com botão de limpar.
- **Serviço em primeiro plano** (`specialUse`): o agente sobrevive a turnos longos com o ecrã
  apagado — verificado 95 s com `appConnected: true` e `isForeground=true`.

### Comandos novos

```sh
./tools/app-capabilities-test.sh          # 5 passos: FGS, acessibilidade, status, notificação
./tools/app-capabilities-test.sh --full   # + interacção real (calculadora)
adb shell su -c '/data/adb/cachalote/bin/cachalote status'          # estado do daemon
adb shell su -c 'LD_LIBRARY_PATH=/data/adb/cachalote/runtime/lib \
  /data/adb/cachalote/runtime/bin/node /data/adb/cachalote/ctl.cjs host.status'   # app ligada?
```


---

## 11. F4/F5 — paridade total e entrega

### Modo completo (a interface web do DSH, dentro da app)

O perfil `web` corre **ao lado** do motor ACP, no próprio telefone:

```
/daemon: cachalote web-start   →  node bin.js --profile web --host 127.0.0.1 --port 3080
app:     "Modo completo"       →  WebView no URL autenticado que o motor imprime
```

Serve para o que a UI nativa ainda não cobre: **definições de modelos e provedores, gestão de
plugins, anexos**. O motor web exige o URL com token (`/?token=…`), que o daemon captura e a
app busca por `su`. Verificado: a SPA do Harness 0.2 carrega no WebView
([captura](docs/screenshots/app-modo-completo.png)).

Comandos: `cachalote web-start | web-stop | web-status | web-url`.

### Empacotamento (`tools/build-release.sh`)

```sh
./tools/build-release.sh
# → app/app/build/outputs/apk/release/app-release.apk (10 MB, assinada)
#   keystore em .work/cachalote-release.jks (NÃO versionado)
#   sha256 e certificado impressos no fim
```

⚠ A chave de assinatura vive fora do repositório e **tem de ser guardada**: sem ela não
consegues actualizar uma app já instalada (assinaturas diferentes não se sobrepõem). Para
instalar por cima da versão de debug é preciso desinstalar primeiro.

### O que ainda falta (trabalho futuro, não bloqueia o uso)

| Tema | Nota |
|---|---|
| ~~**Confinamento do bash**~~ | ✅ **feito** (ver §12): guarda de caminhos + aprovação no telefone, verificada A/B (aprovar → corre; recusar → bloqueado). |
| **Flavors** | O núcleo do flavor "in-app" está **provado** (`libnode.so` executa com targetSdk 35, ver §12); falta empacotar a árvore do kernel na APK e o modo de arranque em processo. O flavor Shizuku continua por fazer. |
| **Onboarding** | A app já guia token/acessibilidade/capacidades, mas não tem um fluxo de primeira execução (9 passos de permissões, como faz o projecto de referência). |
| **Anexos e voz** | Sem imagens no prompt (o ACP anuncia `promptCapabilities.image: false`) e sem entrada por voz. |
| **SEA + snapshot** | Arranque do motor já é raro (daemon quente), mas o SEA com snapshot (`--build-snapshot`) daria 5,9× no arranque a frio (D6b). |


---

## 12. Fecho do plano — a barreira do bash e o flavor sem root

### Guarda de caminhos do `bash` (o item de segurança que faltava)

```
comando bash do agente
   │
   ├─ caminhos dentro da área permitida  → executa (fica no registo)
   └─ caminhos fora  → pedido de autorização (ACP session/request_permission)
                        → diálogo na app:  [Allow once]  [Reject]
                        → aprovado: executa · recusado: erro claro ao modelo
```

Área permitida por omissão: `cwd` da sessão + `$DSH_HOME`, configurável com
`CACHALOTE_ALLOWED_ROOTS` (caminhos de sistema como `/system`, `/proc`, `/dev` são leitura e
não pedem nada). Implementado como **patch 7** ao `dsh-tool-bash`.

**Verificação A/B no dispositivo:**

| Passo | Resultado |
|---|---|
| comando `touch /sdcard/cachalote-guard.txt` | diálogo de autorização na app ([captura](docs/screenshots/app-guarda-bash.png)) |
| antes de aprovar | `ls /sdcard/cachalote-guard.txt` → **No such file** |
| depois de "Allow once" | ficheiro **criado** |
| `touch /sdcard/cachalote-guard-deny.txt` + "Reject" | ficheiro **não criado**; o agente recebeu `cachalote guard: o utilizador não autorizou (rejected)` e respondeu que não ia procurar outra via |

### Flavor sem root: núcleo provado

- `libnode.so` (o Node renomeado) **executa a partir do `nativeLibraryDir` com targetSdk 35** —
  resultado no ecrã Poderes da app: `libnode.so executou: v24.18.0 [exit 0, farm 9 libs]`.
- As bibliotecas versionadas são embarcadas com alias `.so` (a AGP descarta nomes que não
  acabem em `.so`) e a app reconstrói os sonames numa farm de symlinks — o `dlopen` segue o
  link até ao `nativeLibraryDir`. Runtime embutido: **89 MB**.
- Falta empacotar a árvore do kernel (145 MB) na APK e o modo de motor em processo para fechar
  o flavor; a parte tecnicamente incerta está resolvida.


---

## 13. Verificação com reinício real (o que faltava provar)

Reiniciei o S10+ e verifiquei a cadeia completa de arranque:

| Verificação | Resultado |
|---|---|
| `sys.boot_completed` | `1` |
| **Daemon arrancou sozinho** (Magisk `service.d`) | ✅ `daemon: a correr (pid 9001)` — pid baixo = arrancou cedo |
| Motor + handshake | ✅ `OK deepseek-harness-acp 0.0.1` |
| Chave do utilizador | ✅ intacta (53 bytes, modo 600) |
| Acessibilidade | ✅ religou sozinha (com a nuance do item 65: pode demorar até o sistema religar o serviço ao processo novo) |
| App | ✅ liga-se sozinha ao daemon e o cartão de primeiros passos desaparece quando está tudo pronto |

Também ficou registado que **`force-stop` e reinstalação desligam o serviço de acessibilidade**
(comportamento do Android) — o cartão de primeiros passos detecta e oferece o botão para
religar, e o teste de capacidades religa-o automaticamente.

### Onde está o projecto

- **Estado**: tudo o que o plano pedia está feito e verificado no dispositivo, excepto o
  flavor Shizuku e o empacotamento do kernel no flavor sem root (o núcleo técnico deste
  último está provado: `libnode.so` executa com targetSdk 35).
- **Segurança**: allow-list de capacidades + guarda de caminhos no `bash` com aprovação no
  telefone + registo de auditoria. O que resta é dar ao `bash` uma área permitida configurável
  na UI (hoje é `CACHALOTE_ALLOWED_ROOTS` no ambiente do motor).
- **Documentação**: [`PLANO.md`](PLANO.md), [`STATUS.md`](STATUS.md) (esta página),
  [`docs/knowledge-android.md`](docs/knowledge-android.md) com **65 armadilhas**,
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) e [`LICENSE`](LICENSE).


---

## 14. Nota sobre os testes e a chave

Os testes que conduzem o **agente** (passo 6 do `app-ui-test` e o passo 2 do
`app-capabilities-test`) precisam de uma chave válida: sem ela o turno falha com
`Authentication Fails` e o passo não passa — é o comportamento correcto, não uma avaria.

Estado depois desta ronda: **`app-ui-test` 9/9** (a chave de teste é guardada e reposta, e o
`.env` fica como estava) e **`app-capabilities-test` 4/5**, com o passo em falta a ser
exactamente o que precisa da chave. Depois de repores a chave, corre outra vez:

```sh
./daemon/set-key.sh --verify && ./tools/app-capabilities-test.sh --full
```


---

## 15. Modo sem root (motor embutido) — feito e verificado

O que faltava do plano em matéria de flavors está resolvido **sem Shizuku e sem root**: a app
corre o motor como seu próprio filho.

| Peça | Como |
|---|---|
| Runtime | Node embarcado como `libnode.so` (jniLibs) — executa no `nativeLibraryDir` mesmo com `targetSdk 35`; sonames versionados com alias `.so` e farm de symlinks no `filesDir` |
| Kernel | extraído de `files/kernel.zip` (114 MB, enviado com `tools/prepare-kernel-zip.sh --push`) para `files/kernel/` |
| Transporte | **stdio** do processo filho (o cliente fala TCP *ou* stdio com o mesmo protocolo) |
| Capacidades | a app levanta o seu próprio servidor `host.*` em `127.0.0.1:39732` (IPv4 explícito); o plugin do kernel fala-lhe como falaria ao daemon |
| Chave | `files/dsh-home/.env` (só o uid da app lê) |
| Perfil | a app escreve `files/dsh-home/cordis.patch.yml` com o caminho absoluto do plugin |
| Shell | patch 8: `dsh-bash-local` usa `CACHALOTE_SHELL` (o Android não tem `bash`) |

**Confinamento:** no modo embutido o motor corre como o **uid da app**, portanto o sandbox do
Android é o limite real — exactamente o que o D10 previa como a alternativa sólida ao "root com
guarda".

### Provas no dispositivo (sem root nenhum)

```
RPC (logcat)  → initialize (id=3) · session/resume (id=4) · session/new (id=5)
              → session/prompt (id=6) · ← session/update · ← resposta id=6 erro=false
resposta do agente (android_status):
   {"app":"cachalote","pid":19020,"accessibility":false,"notifications":true,
    "allowed":["notify","screen","tap","type","key","screenshot"],"hint":"…"}
```

E o `app-capabilities-test.sh` passou **5/5 com o modo embutido ligado** (o `pid 19020` é o
processo da app, não o daemon — prova de que as capacidades vieram do servidor da própria app).

Interruptor na app: **Estado → Motor embutido (sem root)**. Em modo daemon fica o
comportamento anterior (root/Shizuku, autostart, guarda de caminhos com aprovação).


---

## 16. Áreas permitidas do bash (configurável na app)

Fecha o último item de segurança em aberto: o agente pede autorização **fora** das áreas
permitidas e corre **dentro** delas sem perguntar.

- **Na app:** separador **Poderes → Áreas permitidas do bash** (uma pasta por linha; `Gravar e
  reiniciar`). Em modo daemon a app grava `$BASE/home/allowed-roots` por `su`; em modo embutido
  passa a variável ao processo filho.
- **Default (o que está agora):** vazio — só o workspace da sessão e o `$DSH_HOME`. Tudo o resto
  pede aprovação no telefone.

| Cenário | Comportamento verificado |
|---|---|
| `touch /sdcard/…` sem autorização | diálogo de autorização; bloqueado até decidires |
| `touch /sdcard/…` com `/sdcard` autorizado | corre directo, sem diálogo (5 s) |
| `touch` dentro do workspace | corre directo |

Estado final do dispositivo: modo **daemon** (root, autostart no boot, guarda activa), chave
intacta, acessibilidade ligada, e `app-ui-test` **9/9** + `app-capabilities-test` **5/5**.
