# Como usar o Cachalote

O **Cachalote** põe o agente do **DeepSeek Harness** a viver dentro do teu telemóvel Android: ele
lê o ecrã, toca, escreve, corre comandos, vê imagens e — se quiseres — trabalha numa **segunda
tela** sem mexer na tua.

Este guia é para quem quer **usar** a app. Para perceber como foi feita, ver o
[`README.md`](../README.md) e a [`docs/knowledge-android.md`](knowledge-android.md).

> **Versão em inglês:** [`HOW-TO-USE.md`](HOW-TO-USE.md).

---

## 1. O que precisas

| | |
|---|---|
| Telemóvel | Android 10 ou mais recente (testado num Samsung S10+ com LineageOS 23 / Android 16) |
| Espaço | ~250 MB para a app e o motor |
| Uma de três | **root** (Magisk), **Shizuku**, ou **nada** (modo embutido) |
| Chave | uma chave da API da DeepSeek (`sk-…`) — é introduzida na app e fica só no telemóvel |

**Qual escolher?** Com root, o agente é mais capaz (corre comandos como root, com uma guarda de
caminhos e a tua autorização). Com Shizuku, corre como *shell*. Sem nada, o motor vive **dentro da
app** e o sandbox do Android é o limite — mais seguro, menos capaz.

## 2. Instalar

1. Instala a APK de **release** ([releases do projecto](https://github.com/ricardojrgpimentel/cachalote/releases)).
2. Abre a app. No modo embutido, ela descarrega o motor na primeira utilização (~160 MB) e verifica
   o `sha256` — se não bater certo, recusa.
3. Em **Estado**, segue a lista de **Primeiros passos**: o daemon, o token do canal, a chave e a
   acessibilidade. Cada linha diz o que falta e tem o botão que resolve.

## 3. Configurar

- **Chave do provedor** (separador *Chave*): escreve a tua chave `sk-…` e toca em **Guardar no
  daemon**. Ela é escrita em `home/.env` com modo `600` (só o root lê). **A app não a guarda** — e
  por isso é que ela também **não** vai nos backups.
- **Acessibilidade** (separador *Poderes* → *Abrir Definições → Acessibilidade*): é o que dá ao
  agente olhos e dedos no ecrã real. Sem ela, só podes enviar notificações.
- **Capacidades autorizadas**: cada capacidade (ver o ecrã, tocar, escrever, teclas, capturas,
  ecrã virtual) tem um interruptor. Por omissão **só as notificações** estão ligadas: és tu que
  decides o resto. Cada pedido do agente fica no **Registo**, ao lado.
- **Áreas permitidas do bash**: fora destas pastas o agente tem de pedir autorização **no
  telefone**, e tu vês o comando exacto antes de aprovar.

## 4. Usar

Escreve o que queres em *Conversa* e toca em **Enviar**. Podes anexar uma imagem, ditar por voz, e
ver o raciocínio do agente enquanto ele trabalha. As conversas ficam em **Sessões** (o ✓ marca a
activa; podes retomá-la ou fechá-la).

Duas coisas que mudam a experiência:

- **Turnos longos:** podes sair da app. Quando o agente acabar, aparece uma notificação (e só
  aparece se a app não estiver à frente — não é para incomodar).
- **Autorizações:** quando o agente precisa de sair do workspace, a app abre um diálogo com o
  comando exacto. Nada acontece sem tu veres.
- **Telemetria do turno:** no fim de cada turno, a conversa mostra os passos, as ferramentas e o
  tempo que ele levou (`turno 3 · 1 passo · 1,8 s`), e o separador **Resumo** tem o cartão «Turnos e
  tempo» com os últimos seis. Vem do registo da sessão — o motor não manda estes números.
- **Procurar conversas:** a lista tem uma busca que procura no título e na prévia, sem precisares de
  acentos («correcao» encontra «correção»).

## 5. Ecrã virtual: uma segunda tela

No separador *Poderes*, cartão **Ecrã virtual**. Ao iniciar, o agente fica com uma tela só para
ele: abre lá apps, vê-as por captura e toca-as — **sem mexer no teu ecrã**, e **sem precisar da
acessibilidade**. Com o interruptor **Ao vivo** vês o que ele está a fazer.

É útil para: deixar o agente a trabalhar enquanto usas o telefone; testar uma app sem a tirar do
sítio; e evitar que as capturas apanhem as tuas notificações ou conversas.

Nota: o ecrã virtual vive **dentro da app**. Se fechares a app, ele desaparece.

## 6. Ferramentas externas (MCP)

Se tens um servidor MCP (por exemplo, uma ferramenta da tua empresa), podes ligá-lo no cartão
**Servidores MCP**, em JSON:

```json
[{"name":"ping","command":"/data/adb/cachalote/runtime/bin/node",
  "args":["/data/local/tmp/meu-servidor.mjs"],
  "env":[{"name":"LD_LIBRARY_PATH","value":"/data/adb/cachalote/runtime/lib"}]}]
```

O campo aceita servidores `stdio` (comando **absoluto**) e HTTP (`{"name":"x","type":"http","url":"…"}`).
Atenção ao `env`: se o comando usar o Node do daemon, precisa do `LD_LIBRARY_PATH`, senão morre no
*linker* e o agente responde que não tem a ferramenta — sem erro nenhum no registo.

## 7. Backup, licenças e o resto

- **Backup das definições**: exporta modo, capacidades, áreas do bash, URL do kernel e MCP para um
  ficheiro. **Não** leva a chave nem o token do canal (de propósito).
- **Licenças de terceiros**: os textos integrais do que a app redistribui, dentro da app.
- **Idioma**: inglês e português europeu, seguindo o idioma do sistema.

## 8. Quando algo corre mal

Abre o terminal (ou o `adb`) e corre:

```sh
su -c '/data/adb/cachalote/bin/cachalote doctor'
```

Ele verifica a cadeia toda — ficheiros, motor, chave, daemon, `oom_score_adj`, watchdog, portas,
acessibilidade, notificações, disco e registos — e diz o que está mal. Se houver problemas, sai com
código 1.

Outras coisas úteis:

| Situação | O que fazer |
|---|---|
| "a ligação ao motor caiu" | o agente volta a ligar sozinho; se insistir, **Reiniciar motor** em *Estado* |
| A app não liga ao daemon | confirma em *Estado* se o daemon e o token estão bem; o `doctor` diz qual das peças falha |
| A acessibilidade desligou-se sozinha | acontece a cada reinstalação da app: volta a ligá-la em Definições |
| Quero ver o que o agente fez | separador *Poderes* → **Registo** (últimos pedidos) e os registos em `/data/adb/cachalote/logs/` |

## 9. Privacidade e segurança (o que deves saber)

- **A chave fica no telemóvel.** A app nunca a guarda nem a copia; quem a escreve é o daemon.
- **O agente pede autorização** para o que sai do workspace, e tudo fica registado.
- **Com root, o confinamento é declarativo.** O motor corre como root e o Android não impõe o
  *sandbox* do DSH: quem trava um comando fora do workspace é a autorização que aprovas, não o
  sistema operativo. Está escrito na app, no separador *Poderes*.
- **O que sai do telefone:** os prompts e as imagens que anexas vão para a API da DeepSeek (é assim
  que o modelo responde). Mais nada sai por iniciativa da app.
- **O ecrã virtual ajuda aqui**: as capturas dele não apanham o que está no teu ecrã.

## 10. Limitações honestas

- O agente **não** substitui um utilizador atento: aprova com cabeça, sobretudo em modo root.
- O **fecho de sessões** só funciona na sessão activa (é o que o motor permite).
- Os **títulos das sessões** são derivados do teu primeiro prompt (o motor ainda não expõe títulos
  próprios).
- O vscreen vive dentro da app: se a fechares, perde-se.
