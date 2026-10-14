# Cachalote

**O DeepSeek Harness a correr dentro de um telemóvel Android** — com uma app nativa que fala o
protocolo do motor, aprovações no telefone e três motores possíveis (root, Shizuku ou dentro da
própria app).

[![CI](https://github.com/ricardojrgpimentel/cachalote/actions/workflows/ci.yml/badge.svg)](https://github.com/ricardojrgpimentel/cachalote/actions/workflows/ci.yml)

> Estado: **funcional e verificado num Samsung Galaxy S10+ (LineageOS 23.2 / Android 16)**.
> O plano completo está em [`PLANO.md`](PLANO.md) e o que já foi verificado, com provas, em
> [`STATUS.md`](STATUS.md).

## O que isto faz

Uma app Android que corre o [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh)
(DSH) *no telemóvel*, sem computador:

- **Conversa nativa** (Compose) sobre o protocolo ACP do motor — streaming de texto, pensamento e
  chamadas de ferramentas; escolha de modelo e de esforço de raciocínio.
- **Aprovações no telefone**: quando o agente quer tocar fora da área permitida, aparece um
  diálogo no ecrã para autorizar ou recusar.
- **Capacidades Android** dadas ao agente por um plugin nosso: notificações, ver o ecrã, tocar,
  escrever, teclas e capturas — através de um serviço de acessibilidade.
- **Ecrã virtual** (reimplementado com APIs do AOSP, sem LGPL): uma segundo ecrã onde o agente
  abre apps, vê e toca **sem mexer no teu ecrã** — com pré-visualização na app. Não precisa do
  serviço de acessibilidade.
- **Chave do provedor introduzida na app** e nunca copiada de outro sítio.

## Três motores, um interruptor

| Motor | Onde corre | O `bash` do agente corre como | Arranca no boot | Para quem |
|---|---|---|---|---|
| **root** | daemon em `/data/adb/cachalote` | root, **com guarda de caminhos + aprovação** | ✅ Magisk `service.d` | telefone com root |
| **Shizuku** | daemon em `/data/local/tmp/cachalote` | o uid do Shizuku (0 ou 2000) | ❌ | sem root, com Shizuku |
| **embutido** | dentro da app (`libnode.so` + kernel extraído) | uid da app — sandbox do Android | ✅ com a app | qualquer telefone, sem nada instalado |

Troca-se em **Estado → Motor**. O modo embutido é o mais seguro (o limite é o próprio sandbox do
Android); o modo root é o mais poderoso, e é aí que a guarda de caminhos faz o trabalho que o
Android não faz (não há Landlock no Android, o `sandboxMode` do DSH é declarativo).

## Segurança, sem promessas a mais

- O motor **não** está confinado pelo Android no modo root: o que existe é (a) allow-list de
  capacidades, (b) **guarda de caminhos** no `bash` que pede autorização no telefone para tudo o
  que fica fora das áreas permitidas, (c) registo de auditoria, (d) áreas permitidas
  configuráveis na app. Está tudo descrito em [`STATUS.md`](STATUS.md) §12 e §16.
- A chave do provedor é escrita com modo `600` pelo daemon e nunca passa por `argv`.
- Nada de segredos no repositório (`.env`, `.credentials.yaml` e keystores estão no `.gitignore`).

## Estrutura

```
app/                 app Android (Kotlin + Compose): cliente ACP, motor embutido, capacidades
daemon/              daemon Node que supervisiona o motor (root ou Shizuku) + CLI `cachalote`
kernel-plugins/      plugin do kernel que expõe as capacidades Android ao agente
tools/               pipeline: extrair kernel, aplicar patches, empacotar, instalar, testar
config/              camada de patch do perfil do DSH (o que liga/desliga no motor)
docs/                base de conhecimento Android (88 armadilhas) e capturas de ecrã
PLANO.md / STATUS.md plano, decisões, riscos e o que está verificado (com provas)
```

## Começar

De um clone a **daemon a correr + app instalada**, num comando:

```sh
./tools/bootstrap.sh                 # root (Magisk)
./tools/bootstrap.sh --shizuku       # sem root: daemon em /data/local/tmp via Shizuku
./tools/bootstrap.sh --dry-run       # só mostra o plano
./tools/bootstrap.sh --skip-app      # só o motor/daemon
```

O bootstrap verifica pré-requisitos, instala o kernel do **npm**
(`@deepseek-ai/dsh`), obtém o runtime Node **arm64/bionic** dos pacotes do
[Termux](https://termux.dev) (os binários oficiais para Linux são glibc e não correm no
Android), aplica os nossos patches, instala o daemon e constrói/instala a app.

Depois:

```sh
./daemon/set-key.sh --verify              # chave do provedor (nunca em argv nem no histórico)
./tools/app-ui-test.sh                    # 9 passos (guarda e repõe a tua chave)
./tools/app-capabilities-test.sh --full   # capacidades Android + interacção real
```

Testes repetíveis (protegem a tua chave: guardam e repõem o `.env`):

```sh
./tools/app-ui-test.sh            # 9 passos: daemon, token, chave, conversa
./tools/app-capabilities-test.sh --full   # capacidades + interacção real + o agente a VER o ecrã
./tools/plugin-schema-test.sh     # sem telemóvel: o plugin do kernel carrega? (regressão)
```

O `plugin-schema-test.sh` existe por uma razão concreta: um `{}` sem `type` num schema de
ferramenta faz o kernel recusar o plugin **inteiro** e o agente fica sem nenhuma ferramenta
`android_*` — em silêncio, com um único `warning` no log. Apanha isso antes de chegar ao
telemóvel. Ver [`STATUS.md`](STATUS.md) §21 e
[`docs/knowledge-android.md`](docs/knowledge-android.md) #82.

### Modo sem root (embutido)

```sh
node tools/patch-android.cjs --tree .work/f0/kernel
./tools/prepare-embed.sh                       # runtime + libs para a APK (ficam na APK)
./tools/prepare-kernel-zip.sh --push           # desenvolvimento: empurra o kernel por adb
# ou, para distribuir (APK leve, o kernel vem por download com verificação de sha256):
./tools/publish-kernel.sh --repo <owner>/<repo-publico>
```

Sem o kernel na APK, a app descarrega-o na primeira utilização do modo embutido — há um botão
**Descarregar kernel** no ecrã Estado e um campo para o URL em **Poderes** (hospedagem própria).

## Créditos e licenças

- [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) — o motor.
- [woaiys3/deepseek-harness-android-app](https://github.com/woaiys3/deepseek-harness-android-app)
  (MIT) — projeto de referência cuja **base de conhecimento Android** usámos; o overlay dele é de
  uma versão anterior do kernel e não é usado.
- Node.js, [Termux](https://termux.dev) (runtime arm64), [Shizuku](https://shizuku.rikka.app)
  (RikkaApps), AndroidX/Jetpack Compose.
- **Nada de código LGPL** (o *virtual display* do Operit ficou de fora) — ver
  [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Cachalote é MIT — ver [`LICENSE`](LICENSE).
