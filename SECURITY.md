# Segurança

## Como reportar

**Não abras uma issue pública** para uma vulnerabilidade. Usa o
[*Report a vulnerability*](https://github.com/ricardojrgpimentel/cachalote/security/advisories/new)
do GitHub (separador **Security → Advisories**): a conversa fica privada até haver correcção.

Diz o que consegues: versão (a app mostra-a em `Ajustes`), modo do motor (root, Shizuku ou embutido),
o que fizeste e o que aconteceu. Se puderes, um `adb logcat` filtrado por `cachalote` ajuda.

## Versões suportadas

| Versão | Suporte |
|---|---|
| 0.3.x | ✅ correcções de segurança |
| < 0.3 | ❌ actualiza |

## O modelo de segurança, em curto

- **A chave do provedor fica no telemóvel.** A app nunca a guarda: quem a escreve é o daemon, em
  `home/.env` com modo `600`. Ela não vai nos backups nem fica em `argv`.
- **Cada capacidade Android é uma decisão tua.** Notificações, ver o ecrã, tocar, escrever, teclas,
  capturas e ecrã virtual têm interruptores; o agente pede e tu decides, e fica no registo.
- **O que sai do telefone:** os prompts e as imagens que anexas vão para a API do provedor. O resto
  (conversas, chave, ficheiros) fica no aparelho.
- **No modo root, o confinamento é declarativo.** O motor corre como root e o Android não impõe
  sandbox: o que existe é a guarda de caminhos do `bash` (aprovação no telefone para tudo o que fica
  fora das áreas permitidas) e o registo. Está explicado em [`STATUS.md`](STATUS.md) §12 e §16 e em
  [`docs/ARQUITETURA.md`](docs/ARQUITETURA.md). Se isso não for aceitável para o teu caso, usa o modo
  embutido — aí o limite é o sandbox do Android.
- **Sem telemetria.** A app não envia dados para nós; não há servidor nosso.

## Segredos no repositório

O repositório é auditado antes de cada publicação: `.env`, credenciais, keystores e tokens não entram
(ver [`PUBLICAR.md`](PUBLICAR.md)) e o `tools/secret-scan.sh` corre na CI e à mão. Se encontrares um
segredo commitado, reporta-o como vulnerabilidade — não o copies para uma issue.
