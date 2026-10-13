# Cachalote

**O DeepSeek Harness a correr dentro de um telemóvel Android** — com uma app nativa que fala o
protocolo do motor, aprovações no telefone e três motores possíveis (root, Shizuku ou dentro da
própria app).

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
docs/                base de conhecimento Android (79 armadilhas) e capturas de ecrã
PLANO.md / STATUS.md plano, decisões, riscos e o que está verificado (com provas)
```

## Começar

Os passos completos, incluindo o que vem do telemóvel, estão em
[`docs/knowledge-android.md`](docs/knowledge-android.md) e [`STATUS.md`](STATUS.md). Em resumo:

```sh
# 1. kernel: extrair do pacote npm e aplicar os nossos patches
node tools/extract-kernel.cjs --package <dsh.tgz>
node tools/patch-android.cjs --tree .work/f0/kernel

# 2. daemon no telefone (root) — ou --base /data/local/tmp/cachalote --no-root para Shizuku
./daemon/install.sh

# 3. chave do provedor (nunca aparece no histórico nem em argv)
./daemon/set-key.sh --verify

# 4. app
./tools/install-app.sh
```

Testes repetíveis (protegem a tua chave: guardam e repõem o `.env`):

```sh
./tools/app-ui-test.sh            # 9 passos: daemon, token, chave, conversa
./tools/app-capabilities-test.sh --full   # capacidades + interacção real
```

### Modo sem root (embutido)

```sh
node tools/patch-android.cjs --tree .work/f0/kernel
./tools/prepare-embed.sh                       # runtime + libs para a APK
./tools/prepare-kernel-zip.sh --push           # kernel para o armazenamento da app
```

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
