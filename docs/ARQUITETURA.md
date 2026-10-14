# Arquitectura do Cachalote

Para quem chega de fora e quer perceber **como as peças encaixam** antes de mexer no código. Para
usar a app, ver [`COMO-USAR.md`](COMO-USAR.md); para o plano e o histórico, [`PLANO.md`](../PLANO.md)
e [`STATUS.md`](../STATUS.md).

---

## 1. A ideia em três linhas

O **DeepSeek Harness** (DSH) é um agente que fala por stdio. O Cachalote põe-no a correr **dentro
de um telemóvel** e dá-lhe um **cliente Android nativo**: uma app em Compose que fala o protocolo
ACP do motor e lhe entrega olhos e dedos no telefone (ecrã, toques, teclado, notificações, ecrã
virtual) — sempre com o dono a decidir.

```
┌─ app (Kotlin/Compose) ────────────┐        ┌─ daemon (root ou shell) ────────┐
│  UI: conversa, sessões, poderes   │  TCP   │  bridge.cjs  (ponte)            │
│  capacidades android_*            │◄──────►│    ├─ canal de controlo         │
│  ecrã virtual (ImageReader)       │ 39731  │    ├─ canal de capacidades       │
│  serviço em primeiro plano        │ /39733 │    └─ stdio ─► motor DSH         │
└───────────────────────────────────┘  token └─────────────────────────────────┘
                    ▲                                  ▲
                    │ plugin do kernel                 │ ferramentas do agente
                    └── android_see, android_vscreen, bash…
```

## 2. Porque é que o motor corre **fora** da app (D2)

Três razões, todas aprendidas à força:

1. **`exec` proibido.** Com `targetSdk` moderno, o Android só deixa executar binários no
   `nativeLibraryDir` da app; o motor precisa de correr `node` e processos filhos.
2. **Sobreviver à app.** Um turno longo não pode morrer porque o utilizador mudou de app.
3. **Privilégio separado.** O motor é a parte perigosa; a app é a parte que mostra e decide.

Daí o **daemon**: um processo Node (`daemon/bridge.cjs`) com o motor como filho, que fala stdio com
ele e TCP com a app.

## 3. Os três motores (e o que muda entre eles)

| Modo | Onde corre | Como | Limite |
|---|---|---|---|
| **root** | `/data/adb/cachalote` | Magisk; `bash` como root com guarda de caminhos | o confinamento é **declarativo** (ver §7) |
| **Shizuku** | `/data/local/tmp/cachalote` | `Shizuku.newProcess` por reflexão; corre como **uid shell** | sem root; `/data/local/tmp` não tem socket unix (SELinux) |
| **embutido** | dentro da app | motor como filho da app, `libnode.so` de `jniLibs` | o sandbox do Android é o limite real |

O **mesmo código** de daemon e de app serve os três; o que muda é a base, o porto, o token e quem
arranca o processo. O modo embutido descarrega o kernel (~160 MB) em vez de o trazer na APK
(440 MB → 42 MB).

## 4. A ponte (`daemon/bridge.cjs`)

Um só processo, um só lugar de cliente ACP, **dois papéis** na mesma ligação TCP:

| Canal | Quem fala | Para quê |
|---|---|---|
| **controlo** (`cachalote.*`) | a app e o CLI | estado, credenciais, reiniciar motor, `host.call`, `vscreen.*` |
| **ACP** (`session/*`) | a app | o protocolo do motor, repassado para o stdio dele |
| **capacidades** (motivo inverso) | o daemon → app | o agente pede uma capacidade e a app executa no Android |

Portas: **39731** (root) e **39733** (Shizuku), em `127.0.0.1`. O primeiro frame de cada ligação é
`cachalote.auth` com um token de 128 bits lido de `home/app.token` (0600); sem ele, a ligação é
fechada. A comparação é `timingSafeEqual` — e o token é **relido do ficheiro quando muda**, para uma
reinstalação do daemon não deixar a app presa em `token inválido`.

## 5. Do motor para o Android: as capacidades

O agente não toca no Android directamente. O plugin **`dsh-tool-cachalote`** (dentro da árvore do
kernel) expõe ferramentas que pedem capacidades à app pela ponte:

```
android_status · android_notify · android_screen · android_tap · android_type · android_key
android_see (com frames/intervalMs) · android_vscreen (start/status/frame/stop/launch/tap/swipe/key/text)
bash (local, com guarda de caminhos)
```

Cada capacidade tem um **interruptor** em *Poderes* (por omissão só as notificações) e cada pedido
fica no **Registo**. O `attachmentRef`/`attachmentRefs` que o plugin devolve é como as imagens
entram no contexto do modelo — e é por isso que `android_see` e o ecrã virtual conseguem "ver".

## 6. Segredos: onde vivem

| Segredo | Onde | Quem o vê |
|---|---|---|
| Chave do provedor (`sk-…`) | `home/.env` (0600) no daemon | só o daemon; a app **nunca** a guarda (e não vai nos backups) |
| Token do canal | `home/app.token` (0600) | daemon e app |
| Chave de assinatura da release | `.work/cachalote-release.jks` (fora do repo) | só quem constrói a APK |

## 7. O que trava o agente (e o que não trava)

- **Trava:** a **guarda de caminhos** do `bash` (fora do workspace, pede autorização no telefone) e
  os interruptores das capacidades.
- **Não trava:** com **root**, o Android não impõe o sandbox do DSH — não há Landlock. Quem trava é a
  autorização que o dono aprova, não o sistema operativo. Isto está escrito na app, no separador
  *Poderes*, porque é uma decisão consciente (ver D10 no `PLANO.md`).
- No modo **embutido**, aí sim, o limite é o sandbox da app (sem root).

## 8. O ecrã virtual (vscreen)

Reimplementado do zero com APIs do AOSP (**zero LGPL**): `DisplayManager.createVirtualDisplay` +
`ImageReader` na app, e injecção de toques/teclas pelo **daemon** (`input -d <id>`), que só aceita
os display que a própria app confirma serem dela. Vantagem: **não precisa do serviço de
acessibilidade** — a imagem vem do display e o toque do `input`.

Vive **dentro do processo da app**: o serviço em primeiro plano mantém-no vivo mesmo com a
interface fechada, mas fechar o processo (ou o sistema matá-lo) leva-o com ele.

## 9. Onde está o quê

| Caminho | O que é |
|---|---|
| `app/` | app Android (Kotlin/Compose): UI, capacidades, ecrã virtual, serviço, testes (JVM e aparelho) |
| `daemon/` | `bridge.cjs` (ponte), `cachalote` (CLI: start/stop/doctor/ctl), `install.sh`, `set-key.sh` |
| `kernel-plugins/` | plugin das ferramentas `android_*` |
| `config/` | camada de patch do perfil do kernel |
| `tools/` | pipeline: kernel, runtime, patches, APK, testes, verificações |
| `docs/` | guias de utilizador, base de conhecimento (armadilhas), arquitectura |

## 10. Como se verifica

| Suite | Onde corre | O que cobre |
|---|---|---|
| `test/` (JVM, Robolectric) | CI, sem aparelho | i18n (pt-PT e inglês), interface (sessões, capacidades, vscreen, licenças), backups, avisos |
| `androidTest/` | aparelho | notificações (fim de turno) e i18n de recursos |
| `tools/*-test.sh` | aparelho | UI ponta a ponta, capacidades, reconexão, ecrã virtual, pressão de memória |
| CI | GitHub | schema do plugin, âncoras dos patches, sintaxe, `i18n-check`, `secret-scan`, APK de um clone, testes de interface |

O `./tools/secret-scan.sh` recusa segredos na **árvore e no histórico** — o repositório vai ser
público e tirar um segredo depois obriga a reescrever tudo.
