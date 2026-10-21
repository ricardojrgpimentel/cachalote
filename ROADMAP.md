# Roadmap — reparação dos achados da passagem visual

Origem: [`REVISAO-VISUAL.md`](REVISAO-VISUAL.md) (passagem de 2026-10-15, instalação de zero).
Provas: [`design/revisao/`](design/revisao).

Cada ponto tem: **o que é**, **onde**, **o plano**, e **como se prova que ficou resolvido**. Só se
fecha com a verificação feita no aparelho.

Legenda: ⬜ por fazer · 🔄 a decorrer · ✅ feito e verificado · ⏸️ adiado (com motivo)

**Estado: os 20 pontos originais, os três do Bloco D, o E1 (Shizuku **e** root, os dois instalados
pela app), o F1 e o Bloco G (os pendentes do ensaio de instalação limpa) fechados e verificados no
S10+, com a verificação final a passar (9/9 + 9/9; 76 testes de JVM).**

Toda a reparação foi verificada no aparelho, com capturas antes/depois em
[`design/revisao/`](design/revisao) (as provas das correcções são as de número **21 a 25**).

---

## Bloco A — P0: interface quebrada

### A1 · `D1` — Botões que colapsam a zero e desaparecem ✅
**Causa:** `Row(horizontalArrangement = Arrangement.spacedBy(...))` com filhos **sem `weight`**. A
`Row` mede os filhos sem peso com a largura que sobra; quando a soma excede a largura, os últimos
recebem um limite quase nulo (ou zero).

| Sítio | O que se viu |
|---|---|
| `Assistente.kt:307` (passo 3, 3 botões) | PT @457 dp: `Atualizar` → 22 × 513 px; EN @360 dp: `Refresh` **desaparece** |
| `UiParts.kt:130` (Fila, conversa) | PT @457 dp: 3 linhas com `s` órfão; EN @360 dp: botão **vazio** 114 × 1072 px |
| `MainActivity.kt:1264` (Motor, 2.ª linha) | EN @360 dp: **13 px** de altura |
| `MainActivity.kt:1072` (Backup: exportar/importar) | risco a 360 dp |
| `MainActivity.kt:646` (autorizar/negar todas) | risco a 360 dp |

**Plano:** dar `weight` aos botões destas linhas (ou passar a `FlowRow`, que quebra em vez de
espremer) e encurtar os rótulos mais longos nos recursos.

**Prova:** capturas a 457 dp e 360 dp, PT e EN, com todos os botões visíveis e legíveis; inspecção da
árvore a confirmar que nenhum botão tem largura 0 nem altura de uma linha.

---

### A2 · `D2` — «Modo completo (interface web do DSH)» = ecrã preto ✅
**Três causas somadas** em [`WebModeActivity.kt`](app/app/src/main/java/dev/ricardopimentel/cachalote/WebModeActivity.kt):
1. `MaterialTheme { }` **sem** o esquema do projecto → esquema claro (texto quase preto) sobre o fundo
   de janela preto do `Theme.Cachalote`;
2. sem `WindowInsets` → controlos em `y 26..178`, debaixo da barra de estado, e o SystemUI engole os
   toques;
3. `AndroidView(Modifier.fillMaxSize())` dentro de uma `Column` → sobrepõe-se à linha de controlos.

**Plano:** usar o `Tema` da app, aplicar `windowInsetsPadding`/`safeDrawing` e mudar o `AndroidView`
para `weight(1f)`.

**Prova:** o ecrã deixa de ser preto (amostragem de píxeis), a barra de estado não tapa nada, e os
três botões respondem ao toque.

---

### A3 · `D3` — Contraste 1,30:1 no cartão escolhido ✅
`#BEFFE7` (o `primary` do tema) sobre `#38EFC2` (`primaryContainer`) — o `TextButton` dentro do
cartão ficava com a cor por omissão em vez de `onPrimaryContainer`.

**Plano:** `ButtonDefaults.textButtonColors(...)` com `onPrimaryContainer` no botão do cartão
escolhido.

**Prova:** repetir a medição — o contraste do `escolhido`/`chosen` tem de passar 4,5:1.

---

### A4 · `F6` — Sessão antiga deixa a app «desligada» para sempre ✅
**O achado mais grave.** Depois de o motor reiniciar com a app a segurar uma sessão:
cabeçalho «desligado», `chave: a ligação ao motor caiu`, `retomar: a ligação ao motor caiu`, e um
ciclo `auth`→`initialize`→`session/resume`(**erro**) a cada 1–3 s. O erro é
`session is already active`, que `isSessionAlreadyActive` já tolera — mas a ligação cai a cada volta.

Duas causas:
1. **dois caminhos de reconexão em paralelo** — o laço do ViewModel (`MainViewModel.kt:236`) e o
   `reconnector` invocado de dentro de `call()` (`CachaloteClient.kt:125`) — e cada `connectTcp`
   começa por `disconnect()`, pelo que um derruba a ligação do outro;
2. **nunca largar a sessão que o motor já não retoma**: só `switchMode` limpa `sessionId`.

**Plano:** uma só porta de reconexão (mutex/serialização), e largar o `sessionId` quando o motor
recusa retomá-lo — com mensagem no transcript, como já acontece no caminho de envio.

**Prova:** reiniciar o motor com a app aberta e confirmar que o cabeçalho volta a «ligado» sozinho;
abrir uma conversa antiga não pode dar «a ligação ao motor caiu».

---

## Bloco B — P1: texto e i18n

### B1 · `D4` — Markdown literal (`**não**`) ✅
`backup_texto` nos dois idiomas. **Plano:** tirar os asteriscos do recurso.

### B2 · `D5` — JSON de exemplo do MCP sem aspas ✅
As aspas **não escapadas** em `strings.xml` são removidas pelo Android (delimitam espaço preservado).
**Plano:** `\"` em `mcp_exemplo` (e `placeholder_mcp`), e apagar a chave morta se não voltar a ser
usada.

### B3 · `D6` — `1 problema(s) e 4 aviso(s)` ✅
**Plano:** `<plurals>` para problemas/avisos e tirar a linha duplicada do Resumo.

### B4 · `D7` — Registo de auditoria em milissegundos Unix ✅
**Plano:** hora legível e resultado (autorizado/recusado).

### B5 · `D12` — Frases de estado no código, não nos recursos ✅
Mais de 60 frases no `MainViewModel.kt` + as do `MainActivity.kt` (Primeiros passos, ecrã virtual,
serviço de agente, etiquetas `tu`/`agente`/`erro`, diálogo de voz) e, no sentido inverso, `Model:` e
`Reasoning effort:` fixos em inglês.
**Plano:** passar tudo a `strings.xml` nos dois idiomas; o `Assistente.kt:305` deixa de comparar
estado com o texto `"(por obter)"` (passa a um booleano).

### B6 · `D8` — Nome do modelo cortado a 28 caracteres ✅
`MainActivity.kt:1527` `take(28)`. **Plano:** cortar com reticências, sem partir identificadores.

### B7 · `D9` — Chip «Reasoning effort» cortado na borda ✅
**Plano:** a linha de chips quebra (`FlowRow`) em vez de sair do ecrã.

### B8 · `D10` — Token comprido sem ponto de quebra ✅
O JSON do MCP (159/190 caracteres sem espaços). **Plano:** mostrar em bloco monoespaçado com scroll
horizontal.

### B9 · `D11` — Elipses nos detalhes dos Ajustes ✅
1 a 6 linhas cortadas conforme o tamanho. **Plano:** 2 linhas com reticências e encurtar os textos
que não cabem.

---

## Bloco C — funcional e acabamento

### C1 · `F1` — Integridade do kernel embutido (intermitente) ✅
Falhou uma vez na primeira utilização; o artefacto está correcto (sha256 verificado em três sítios).
**Plano:** serializar a cópia, apagar o `.part` em caso de falha e tentar uma segunda vez antes de
mostrar erro.

### C2 · `F2` — A faixa de erro empurra o ecrã inteiro (228 px) ✅
**Plano:** a faixa flutua sobre o conteúdo, sem deslocar os alvos de toque.

### C3 · `F3` — Erros do motor crus, em inglês ✅ (em grande parte — ver nota)
**Plano:** traduzir os casos conhecidos e conter o texto técnico.

### C4 · `F4` — «Iniciar» do ecrã virtual activo sem autorização ✅
**Plano:** o botão leva ao sítio onde se autoriza, em vez de só dar erro.

### C5 · `F5` — Comandos de exemplo com pacote fixo ✅
`com.android.calculator2` pode não existir. **Plano:** usar o pacote real quando conhecido, com
alternativa genérica.

### C6 · Acessibilidade ✅
`✕` sem `contentDescription` (única forma de remover da fila); opções do painel de autorização sem
semântica de rádio; alvos abaixo de 48 dp.

### C7 · Observações de desenho ✅
Revisão com o autor: aviso de root repetido em 9 sítios, títulos duplicados, dois convites para «nova
conversa», cartão do Daemon com JSON cru, placeholders que parecem valores, «8 módulos
configurados», texto a 10 sp, rodapé com palavra órfã, duplicação no passo 5 do assistente.
As que eram claramente defeito foram corrigidas: o aviso de root deixou de se repetir em todas as
secções (fica no menu, com o cartão PERIGO), os títulos duplicados saíram, o FAB só aparece quando há
conversas, o JSON cru do daemon passou a «Detalhes técnicos» fechado, o «8 módulos» passou a contar a
lista, e o rodapé do assistente e a duplicação do passo 5 foram encurtados.

---

## Bloco D — aparecidos depois (2026-10-16)

### D1 · Modo embutido pode ficar preso em silêncio ✅
A app ficou em modo embutido com o cabeçalho em «desligado», um `libnode` novo a cada poucos
segundos e **nada no ecrã** a dizer porquê. Havia um motor embutido do dia anterior ainda vivo (e
dois bridges do daemon). Ao voltar a testar, ligou à primeira — é um **estado preso**, não uma falha
sistemática, mas o utilizador não tinha como o saber.

**Feito:** o `engine-embedded.log` passou a ser escrito **a cada linha** (com `readText()` só era
escrito no fim do processo, ou seja, nunca enquanto ele estivesse vivo — que é quando faz falta) e o
laço de reconexão deixou de engolir a causa: ao fim de três tentativas seguidas mostra «o motor (…)
não está a responder: …» na faixa, e limpa quando a ligação volta.

**Causa encontrada (2026-10-16):** o motor embutido ficava **órfão** — a app era morta pelo
sistema, o `libnode` continuava a correr com o uid da app a segurar o `$DSH_HOME`, e a app nova não
tinha como lhe falar: arrancava outro a cada poucos segundos sem ligar a nenhum. O `start` passou a
procurar e terminar os motores embutidos órfãos antes de arrancar, confirmando pelo
`/proc/<pid>/status` que o uid é mesmo o nosso.

### D2 · Os processos do daemon acumulam-se ✅
No telefone ficaram **dois `bridge.cjs`** a correr ao mesmo tempo (e os motores de cada um). O
`cachalote start` arranca outro sem ver que já lá está um, e o `watchdog` levanta o seu. Não é desta
passagem, mas é a razão pela qual os processos se acumulam entre reinícios — e foi o que tornou o
§D1 confuso de diagnosticar.

### D3 · O markdown do agente aparece em bruto ✅
O agente responde com markdown e ele sai literal: `## Resultado`, `**negrito**`, `| a | b |` e
`|---|---|`. Numa resposta longa lê-se mal. O design diz que as mensagens do agente são «plain text,
no bubble», portanto isto nunca foi tratado — mas o modelo escreve markdown.

**Feito:** um analisador próprio (`Markdown.kt`, sem dependências) e um renderizador — títulos,
ênfase, código inline e em bloco, listas, citações e **tabelas**. Só o agente passa por aqui; o texto
do dono e os erros ficam como estão. Seis testes de JVM.

---

## Bloco E — a maior lacuna por resolver (2026-10-16)

### E1 · O motor precisa de um computador — e não devia ✅ (Shizuku e root, os dois pela app)

**O que se passa.** Escolher root ou Shizuku no assistente não chega: o motor (o *daemon*) tem de
ser instalado numa pasta de sistema, e a app **não o sabe fazer**. Hoje isso é um script no
computador (`daemon/install.sh`). Quem instala a app e só tem o telefone fica limitado ao modo
embutido. O autor disse-o assim: *«o utilizador vai instalar a app e usar, não vai estar a ligar ao
pc para correr o que quer que seja»*. É uma parede real e não se defende.

**A dependência é histórica, não técnica.** Fui ver o que o `install.sh` empurra e comparar com o
que a APK já leva dentro:

| O que é preciso | Quem tem |
|---|---|
| O kernel do DSH (366 MB) | **a APK** — `assets/kernel.zip`, 212 MB, o mesmo artefacto |
| O Node (173 MB) | **a APK** — `libnode.so`, 43 MB + 9 bibliotecas na farm; é o mesmo Node que o modo embutido executa |
| `bridge.cjs`, `session-meta.cjs`, `cachalote`, `cachalote.patch.yml` | **a APK** (falta pôr como assets — são ~60 KB) |
| `bin/bash` | **não**: o daemon cria um shim `bash` → `/system/bin/sh` em `$BASE/bin` (é o que o `PATH` do motor põe à frente) — o auto-instalador da app **não o criava**, e o agente falhava tudo com `spawn bash ENOENT`; corrigido a 2026-10-19 |
| O script do Magisk `service.d` | **a APK** (é um ficheiro de 637 bytes) |

Ou seja: **a app já tem 100% do que é preciso para correr o motor**. O que falta é a canalização
para o pôr fora do seu sandbox. Não é arquitetura — é uma funcionalidade por fazer.

**Como se faz.** Os dois modos têm caminhos diferentes, e ambos são viáveis:

- **Shizuku (sem root, sem computador).** O destino é `/data/local/tmp/cachalote`, que é gravável
  pelo uid *shell* — que é exatamente o que o Shizuku dá à app. A app extrai o kernel e o
  `libnode.so` para o seu sandbox, e envia-os por *stdin* para um `sh -c 'tar xf -'` do lado do
  Shizuku (o `toybox tar` existe em qualquer Android atual). Não precisa de root nem de PC.
- **Root (com o `su` que o utilizador já autoriza).** Mesmo princípio, com destino
  `/data/adb/cachalote` e um `su -c` a mover as pastas, mais o script em `/data/adb/service.d/`
  para o arranque no boot. O `su` é pedido **uma vez**, no assistente, com contexto.

**O que isto obriga a resolver.**
- Um ecrã de progresso honesto: são ~540 MB a copiar (extrair o kernel + as bibliotecas), entre um
  e três minutos no S10+. Não pode ser um botão que parece pendurado.
- O `libnode.so` tem de ser **copiado** para o destino e corrido de lá: executar um ficheiro de
  dentro de `/data/app/.../lib/arm64` a partir do contexto do `su`/shell é o tipo de coisa que o
  SELinux recusa. Copiado para `/data/local/tmp`, corre — é o que já acontece hoje.
- Integridade: verificar o `sha256` do `kernel.zip` (a app já tem `kernel.sha256` nos assets) e
  apagar instalações parciais, como já se faz no modo embutido.
- Espaço: 540 MB duplicados (o kernel do modo embutido + o do daemon). Aceitável, mas vale a pena
  ver se os dois podem partilhar a mesma cópia.
- Uma saída à mão: quem quiser (ou precisar) continua a poder usar o `daemon/install.sh`.

**Feito (2026-10-16) — modo Shizuku.** A app instala o motor sozinha: o assistente, passo 3, tem
um cartão «Instalar o motor neste telefone» que envia o kernel pelos bytes do stdin de um processo
do Shizuku, manda extrair lá com o `/usr/bin/unzip` do telefone, copia o `libnode.so` e as
bibliotecas para `runtime/`, põe o perfil `home/cordis.patch.yml` (sem ele o agente **não tem as
ferramentas `android_*`** — o instalador do computador fazia-o e eu só o vi porque corri o
`doctor`), corrige os caminhos `/data/adb/cachalote` → base e escreve a marca de origem do kernel.

Verificado no S10+ com o telefone limpo e o daemon apagado: assistente → Shizuku → instalar →
daemon, token e motor os três a ✓, em menos de um minuto. E o agente a correr no motor que a app
instalou, a chamar `android_status` e a responder «32 ferramentas».

Três armadilhas que só apareceram no aparelho, e que valem para o modo root: o
`Process.waitFor(tempo)` do Android não serve para processos do Shizuku (atira
`IllegalArgumentException`, não `IllegalThreadStateException`), o código de saída deles não é de
confiança (os comandos passaram a dizer eles próprios que correram bem), e a extracção tem de ir
destacada, com ficheiro-marca.

**Modo root — fechado (2026-10-19).** O bloqueio era o Magisk e caiu. A tabela de políticas tinha
o uid **10179** (Cachalote) em `policy=1` (**negar**), uma decisão anterior que ele guarda e aplica
em silêncio, sem voltar a perguntar. Passou a `policy=2` (permitir) pela mesma via que o
[`install-app.sh`](tools/install-app.sh#L76) já usava — desta vez não tinha corrido porque a APK
foi instalada directamente com `adb install`. Provado **em nome da app**, não do adb:
`run-as dev.ricardopimentel.cachalote su -c id` → `uid=0(root)`.

Com o `su` a passar, a instalação limpa (apagar `/data/adb/cachalote` e carregar em «Instalar o
motor agora») revelou **dois bugs do auto-instalador**, ambos corrigidos:

| Bug | Efeito | Correção |
|---|---|---|
| Não criava `$BASE/bin/bash` | todos os comandos do agente falhavam com `spawn bash ENOENT` — o `install.sh` do PC criava o shim, o da app não | `MotorSelfInstall` escreve o shim (`#!/system/bin/sh` + `exec /system/bin/sh "$@"`) e dá-lhe 755 |
| `RootShell.sendFile(..., 0)` no arranque no boot | o `0` era lido como «timeout imediato»; o `runCatching` engolia o erro e `/data/adb/service.d` ficava **vazio** | o call passou a 60 s e o `sendFile` trata `<= 0` como 60 s, não como zero |

Verificação, com a instalação feita **de raiz pela app**: `bin/bash` e `service.d/cachalote.sh` com
755; chave migrada do daemon Shizuku; `doctor` **3 avisos, nenhum problema** (com watchdog a
vigiar); e um turno real num motor root — o agente correu `id` e respondeu
`uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0` →
[`design/revisao/42-root-bash.png`](design/revisao/42-root-bash.png).

**Continua fora:** o `doctor` avisa que a acessibilidade e as notificações estão desligadas (são
definições do dono) e que 2 entradas do motor não activaram (`plugin-exemplo`, que falha o import e
já vinha assim). Nenhum destes impede o motor root.

---

## Bloco F — a maior lacuna que sobrava (2026-10-19)

### F1 · Abrir uma conversa antiga mostrava só «sessão X retomada» ✅

**O que era.** O ACP não tem `session/load` (está na lista do que o `dsh-acp` recusa), e a app não
lia o histórico: tocar numa conversa antiga abria-a vazia, com uma linha de sistema. Foi o passo
seguinte apontado em [`STATUS.md`](STATUS.md) §69.6 — e o que mais se notava no uso diário.

**Feito.** O daemon lê o ficheiro da sessão (o mesmo leitor de frames zstd dos metadados) e devolve
os eventos na forma em que eles chegaram em direto; a app enche o transcript ao retomar. Um
`cachalote.sessions.history` novo no `bridge.cjs`, um [`Historico.kt`](app/app/src/main/java/dev/ricardopimentel/cachalote/Historico.kt)
do lado da app. O que o motor injecta (`runtime-context`, `system-prompt`) fica de fora, o estado
das ferramentas vem do resultado, e o corte (300 eventos / 512 KB) diz que cortou.

**Prova:** a conversa antiga do S10+ aberta pelo Shizuku, com o pedido, os dois pensamentos, o
cartão `Android capabilities` **CONCLUÍDA** e a resposta em markdown →
[`design/revisao/41-historico-da-conversa.png`](design/revisao/41-historico-da-conversa.png).
`session-meta-test.cjs` (16 asserções novas) e 5 testes de JVM (`HistoricoTest`); 67 testes no total.

**De caminho, um bug antigo:** o `lerSessao` ficava com o **primeiro** `session/title`, que é o de
recurso («usa aa ferramenta…»); o do provedor vem depois e é o bom («Contagem total de ferramentas
Android»). Passou a preferir o do provedor — via-se na lista e no cabeçalho.

**O que continua fora:** o modo embutido não tem daemon que leia o ficheiro (é zstd e o Kotlin não
o descomprime), e a app di-lo em vez de fingir. Um daemon já instalado só ganha o método novo
quando o motor for reinstalado pela app (`motor-bridge.cjs` e `motor-session-meta.cjs` são assets).

---

## Bloco G — o que a instalação limpa deixou aberto (2026-10-19 → 2026-10-21)

Origem: [`READINESS.md`](READINESS.md) §«O que fica aberto» e os §§69.6/70.4 do [`STATUS.md`](STATUS.md).
O ensaio de instalação limpa nos três modos fechou o E1, mas deixou sete defeitos que só aparecem a
quem vem de fora — e **nenhum deles estava no roadmap**. Este bloco fecha-os, cada um com a sua
prova. O G1 estava a acontecer ao vivo no S10+ quando este bloco foi aberto (2026-10-21, 14 min
depois de um reboot: app em Shizuku a dizer «token inválido» com o daemon root a ouvir no mesmo
porto).

| # | O que era | Gravidade |
|---|---|---|
| G1 | Os dois daemons no mesmo porto (39731): a app em Shizuku ligava-se ao daemon root | **P0** — app inutilizável sem intervenção |
| G2 | O Magisk nega em silêncio ao fim de 9 s e a app não explicava o que fazer | **P0** — primeiro obstáculo de quem escolhe root |
| G3 | O modo por omissão era root mesmo com o Shizuku marcado RECOMENDADO | P1 — leva ao caminho errado |
| G4 | A extracção do kernel começava no passo 2 e o progresso só se via no 3; «Continuar» não bloqueava | P1 — 1–2 min a parecer pendurado |
| G5 | A acessibilidade caía (force-stop) e não havia botão para religar | P1 — o agente perde olhos e dedos |
| G6 | As conversas são por modo, e a troca não o dizia | P2 — susto, não falha |
| G7 | `status`/`doctor` diziam «motor a correr» para a base errada e contavam linhas como entradas | P2 — diagnóstico que mente |

### G1 · Os dois daemons no mesmo porto ✅

**O que era.** Com o telefone reiniciado, o daemon root sobe pelo `service.d` do Magisk e fica com o
39731. O daemon do Shizuku não sobe no boot (por desenho) — e a app, em modo Shizuku, ligava-se ao
**daemon root** e recebia «token inválido». A faixa dizia «o motor (Shizuku) não está a responder: o
motor devolveu um erro: token inválido», que não aponta para nada disto.

**As três causas, todas no código:**

| Causa | Onde |
|---|---|
| O instalador da app **não escrevia** `home/port` (o `install.sh` do PC escrevia) | `MotorSelfInstall` — o `mkdir` não incluía o ficheiro |
| Sem `home/port`, o bridge usa 39731 para **qualquer** base | `bridge.cjs` (o porto por omissão) |
| O `switchMode` não parava o daemon do modo anterior, e a app não sabia distinguir «o meu daemon está parado» de «o porto é de outro» | `MainViewModel.switchMode`, `DaemonController.connect` |

**Plano.** Uma só regra do porto, escrita onde nasce: `home/port` é escrito pelo instalador da app
e **garantido pelo `cachalote start`** (migra instalações antigas); o porto vem da base (39731 para
`/data/adb`, 39733 para o resto). A app lê-o (`DaemonController.effectivePort`), o `switchMode` pára
o daemon do modo que se está a deixar, e quando o TCP recusa a ligação a app **arranca o daemon
deste modo** e tenta outra vez, em vez de desistir com um erro de token.

**Feito, e quatro defeitos que só apareceram ao pôr isto a andar.**

| Defeito | Sintoma | Correção |
|---|---|---|
| Dois `start` ao mesmo tempo (o laço de reconexão e o fim da instalação) | dois bridges: um com o porto, outro com o motor e o pidfile — e a app ligava-se ao antigo | cadeado `run/start.lock` no CLI (com o pid do dono e remoção de cadeados órfãos) + `Mutex` no `DaemonController` |
| O bridge que perdia o porto (`EADDRINUSE`) **continuava vivo** | um motor a mais por cada `start` perdido | o bridge sai (`shutdown`) quando não consegue ouvir: quem já está no porto é que manda |
| Um `stop` a meio de um `start` apagava o pidfile do recém-chegado | `status` dizia «PARADO» com o daemon à escuta | `stop` entra no mesmo cadeado; `alive()` também reconhece um bridge da base sem pidfile |
| `workspace` era o do root em todos os modos | no Shizuku, `session/new` com `cwd=/data/adb/...` — falhava num telefone sem root (o ensaio de 2026-10-19 não o apanhou porque ali o Shizuku corria como root) | `DaemonController.workspace` passa a depender do modo |

**Prova (S10+, 2026-10-21, 14 min depois de um reboot).** O daemon root subiu sozinho no 39731 e a app,
em Shizuku, ficou **ligada** no 39733 (`ss` mostra os dois portos; `ps` mostra os dois motores, cada um
com o seu `bin.js`); `home/port` = `39733` foi escrito **pelo instalador da app** (a base foi apagada e
reinstalada pelo botão «Instalar o motor agora»); e um turno **real** no motor que a app instalou —
`id -u` → `0` → [`design/revisao/44-turno-shizuku-39733.png`](design/revisao/44-turno-shizuku-39733.png).
Ao trocar root ← Shizuku pela interface, o daemon do modo que se deixa pára.

### G2 · «su saiu com 13: Permission denied» não dizia o que fazer ✅

**O que era.** O diálogo do Magisk tem contagem decrescente; sem toque, o uid fica em `policy=1`
(negar) e o Magisk **não volta a perguntar**. A app mostrava o código de saída do `su` e mais nada.

**Plano.** `RootShell` passa a distinguir **concedido / negado / ausente** (o `hasRoot` respondia só
sim/não); quando é *negado*, a secção **Motor** e o passo 3 do assistente mostram um cartão que diz
que o Magisk recusou e leva ao sítio certo (abrir o Magisk, e um «tentar outra vez»).

**Prova.** Com o uid da app em `policy=1` no Magisk (`magisk --sqlite`), a app mostra o cartão →
[`design/revisao/45-magisk-recusou.png`](design/revisao/45-magisk-recusou.png); reposta a `policy=2`,
«Já autorizei — tentar outra vez» faz o cartão desaparecer e o `su` volta a responder.

### G3 · O modo por omissão ignorava o que o telefone tem ✅

**O que era.** `Prefs.mode` nasce em `"daemon"` (root), e o passo 2 do assistente marca o Shizuku
como RECOMENDADO sem o escolher. Num telefone sem root, o primeiro «Continuar» levava ao caminho
errado.

**Plano.** Uma sugestão explícita quando o assistente abre: **Shizuku se estiver a correr** (não pede
root a ninguém), senão root se houver Magisk/`su`, senão embutido. A escolha do utilizador passa a
ficar marcada (`modoEscolhido`) e deixa de ser reescrita.

**Prova.** Com o Shizuku a correr, o assistente (sem escolha anterior) abre no passo 2 com
**«✓ Com Shizuku (sem root)»**, RECOMENDADO e escolhido →
[`design/revisao/48-passo2-shizuku-escolhido.png`](design/revisao/48-passo2-shizuku-escolhido.png). O
cartão RECOMENDADO e o modo escolhido passam a seguir a mesma regra (`sugerirModo` e `EscolhaDoModo`).

### G4 · O passo 2 parecia pendurado ✅

**O que era.** Escolher o embutido no passo 2 dispara a extracção do kernel (1–2 min no S10+), mas o
progresso vivia numa linha do passo 3 — e o «Continuar» deixava avançar sem nada a dizer o que
faltava.

**Plano.** O estado da preparação passa a ser do modelo (`kernelPreparando`, `kernelFracao`) e
mostra-se **no passo onde começou**, com barra; o «Continuar» fica desactivado enquanto decorre.

**Prova.** Com o kernel apagado, o passo 2 mostra a barra **no topo** (antes estava por baixo dos três
cartões — quem escolhia «Sem nada» só via o botão cinzento) e o «Continuar» desactivado →
[`design/revisao/46-passo2-kernel-a-preparar.png`](design/revisao/46-passo2-kernel-a-preparar.png); ao
fim, os dois voltam ao normal.

### G5 · A acessibilidade caía e não havia como a religar sem sair da conversa ✅

**O que era.** Um `force-stop` (ou o sistema) desliga o serviço de acessibilidade. A app escrevia
uma linha no transcript e mandava uma notificação, mas o botão para religar só existia em
Ajustes → Acessibilidade — três toques longe do sítio onde o erro aparece.

**Plano.** Uma faixa fina por cima do campo de escrita, **só quando a acessibilidade já esteve
ligada alguma vez** (para não incomodar quem não a usa), com o botão que abre as definições.

**Prova.** Um `force-stop` (o Android desliga o serviço) e a app reaberta: a conversa mostra a faixa
«a acessibilidade está desligada: o agente não vê nem toca no ecrã» com «Ligar» →
[`design/revisao/47-faixa-acessibilidade.png`](design/revisao/47-faixa-acessibilidade.png); o botão
abre `Settings$AccessibilitySettingsActivity` (confirmado no `mCurrentFocus`); religar faz a faixa
desaparecer.

### G6 · Trocar de modo trocava a lista de conversas sem aviso ✅

**O que era.** Cada motor tem o seu `$DSH_HOME`, logo as suas conversas. Trocar de modo limpava a
lista e o transcript sem uma palavra — parecia que se tinham perdido.

**Plano.** Ao trocar, uma linha de sistema diz onde ficam as conversas **deste** modo e que as do
outro continuam no outro.

**Prova.** A linha aparece **no sítio onde se troca** (Motor), sempre visível — «as conversas deste
modo: /data/local/tmp/cachalote/home/ws» →
[`design/revisao/49-conversas-deste-modo.png`](design/revisao/49-conversas-deste-modo.png) — e vai
também para o transcript. As três conversas do modo Shizuku (restauradas do ensaio anterior) continuam
na lista após a troca.

### G7 · O diagnóstico do daemon mentia sobre o motor ✅

**O que era.** `cachalote status` e o `doctor` procuravam **qualquer** `@deepseek-ai/dsh/lib/bin.js`
(`pgrep -f`), portanto a base parada dizia «motor: a correr» quando o motor que ali corria era o da
outra base. E o `doctor` contava **linhas** «did not activate» do log e chamava-lhes «8 entradas»,
quando é sempre a mesma entrada (`plugin-exemplo`) a falhar de cada vez que o motor arranca.

**Plano.** O motor procura-se **pelo caminho do kernel desta base**; as entradas contam-se por
**nome distinto** no último arranque, não por linha do log.

**De caminho, mais dois que mentiam do mesmo modo:** o `doctor` perguntava `pgrep -f "cachalote
watchdog"` (casava com o watchdog do **root**) e contava **todos** os `ERRO` do `daemon.log` desde
sempre — a base do Shizuku dizia «watchdog a vigiar» sem ter nenhum, e arrastava erros de ontem. Passou
a procurar `$BASE/bin/cachalote watchdog` e a ler só a cauda (300 linhas).

**Prova.** Com o daemon Shizuku parado e o root a correr, `status` na base do Shizuku diz «motor:
parado»; com ele a correr, «motor: a correr». O `doctor` passou de «8 entradas» a «1 entrada» — e a
essa última foi ao sítio: era um *insert* de exemplo no perfil a apontar para um plugin que não vai na
instalação (ver [`STATUS.md`](STATUS.md) §73), e hoje o `doctor` diz «motor sem entradas por activar».

### Verificação do Bloco G (S10+, 2026-10-21)

| Verificação | Resultado |
|---|---|
| Dois daemons, dois portos | `ss` mostra o root no **39731** e o Shizuku no **39733**; um motor por base (`ps`) |
| Instalação pela app, de raiz | base apagada → «Instalar o motor agora» → `home/port` = `39733` (644), token ✓, motor ✓ |
| Turno real no motor instalado | `id -u` → `0` → [prova](design/revisao/44-turno-shizuku-39733.png) |
| Reinício do daemon com a app aberta | a app volta a «ligado» sozinha (o `start` é disparado pela ligação que falha) |
| Troca de modo | pára o daemon do modo que se deixa; Shizuku → root → Shizuku sem bridges a mais |
| `doctor` na base parada | «motor: parado» (antes «a correr», por causa do motor do root) |
| `doctor` entradas / watchdog | «1 entrada» e «watchdog não está a correr» (a verdade da base do Shizuku) |
| Testes de JVM | ✅ **76**, 0 falhas (5 `PortosTest` + 4 `FalhasDeLigacaoTest` novos) |
| `tools/i18n-check.sh` | ✅ 485 chaves nos dois idiomas, 450 usadas, nenhuma forma brasileira |
| `tools/session-meta-test.cjs` / `plugin-schema-test.sh` / `secret-scan.sh` | ✅ tudo bem (8 ferramentas; nada na árvore nem no histórico) |

**O que fica fora, e porquê:** no modo Shizuku o **watchdog** não corre (não há `service.d` sem root) — o
`doctor` avisa e é a verdade; a linha de telemetria do turno continua bloqueada no motor (§69.6).

---

## Verificação final — feita (2026-10-16)

| Verificação | Resultado |
|---|---|
| `tools/app-ui-test.sh` | ✅ **9 passos, 0 falhas** (e a chave real é guardada e reposta) |
| `tools/app-capabilities-test.sh --full` | ✅ **9 passos, 0 falhas** — inclui o agente a ler `512` do visor da calculadora pelos próprios olhos |
| `tools/plugin-schema-test.sh` | ✅ o plugin carrega com as 8 ferramentas |
| Testes de JVM | ✅ **62**, 0 falhas |
| `tools/i18n-check.sh` | ✅ coerente nos dois idiomas |
| `tools/secret-scan.sh` | ✅ nada na árvore nem no histórico |

Os dois testes de UI **navegavam na app de há duas versões** (procuravam os separadores «Estado»,
«Chave» e «Poderes», que já não existem) e falhavam nos passos da navegação. Foram postos a par do
menu de secções — ver o commit `acabamentos`.

## Verificação final (o que a fechava)

1. `./tools/bootstrap.sh` de raiz outra vez — tem de continuar a sair 0.
2. Percorrer **todos** os ecrãs outra vez, nos dois idiomas, a 457 dp, a 360 dp e com fonte 1,3×.
3. Um turno **real** com o modelo (a rede já está de pé) — cartões de ferramenta, telemetria e o
   painel de autorização, que a passagem anterior não conseguiu testar por falta de rede.
4. `./tools/app-ui-test.sh`, `./tools/app-capabilities-test.sh --full`,
   `./tools/plugin-schema-test.sh`, testes de JVM e o `i18n-check.sh`.

---

## Notas de ambiente

- **A rede caiu por causa do modem, não da app** (2026-10-15): o SIM era lido (ICCID/IMSI, DIGI PT)
  mas o telefone ficava `OUT_OF_SERVICE` sem se fixar em célula (`mChannelNumber=-1`) e o SIM em
  `NOT_READY`. O ciclo de modo de avião e o `cmd phone restart-modem`
  (`NV_RESET_CONFIG error 6`) não repuseram; **um reboot repôs** (`IN_SERVICE`, `LOADED`, ping OK).
  O módulo Magisk `StevenBlock` é só um bloqueador de hosts — não toca no rádio.
- O reboot confirmou, pela primeira vez, que o **autostart do daemon no Magisk funciona** (daemon de
  pé sem intervenção).

---

## Registo da verificação (2026-10-15, S10+)

| Ponto | Como se provou | Resultado |
|---|---|---|
| A1 | Fila de tarefas a 457 dp e a 360 dp; passo 3 do assistente a 457 dp | `Limpar concluídas` passa para a 2.ª linha (457 dp) e fica legível (360 dp, onde antes era um comprimido vazio de 114×1072 px); `Atualizar` deixa de empilhar letras → [prova a 360 dp](design/revisao/21-corrigido-fila-360dp.png) · [prova do assistente](design/revisao/22-corrigido-assistente-passo3.png) |
| A2 | Abrir *Ajustes → Motor → Modo completo* sem e com motor web | Deixa de ser preto: mostra a explicação e os três botões; com o motor web ligado mostra a interface do DSH em `127.0.0.1:3080` → [prova](design/revisao/23-corrigido-modo-completo.png) |
| A3 | Medição de píxeis na mesma região do cartão escolhido | 1,30:1 → **4,55:1** (passa AA) |
| A4 | Reiniciar o daemon com a app a segurar sessão | Cabeçalho volta a «ligado» sozinho; **0** `auth` e **0** `resume` em 15 s (antes: um ciclo a cada 1–3 s) |
| B1 | Ecrã *Backup e licenças*, nos dois idiomas | `**não**` desapareceu |
| B2 | Ecrã *Servidores MCP* | O exemplo mostra as aspas e é JSON válido |
| B3 | Ecrã *Resumo* | «3 avisos, nenhum problema» + linha `base: …` (a duplicada saiu) |
| B4 | *Capacidades → Registo* após uma acção | `15/10 16:03:36  vscreen_start  {"width":720,…}` — e os `status {}` deixaram de poluir |
| B5 | Percorrer os ecrãs com a app em inglês | «First steps», «daemon installed», «provider key», «accessibility on», «(not tested)», «3 warnings, no problems»; e o chip passa a «Modelo:» em português → [prova](design/revisao/25-corrigido-first-steps-em-ingles.png) |
| B6 | Chip do modelo | Nome completo (ou elipse no fim), sem cortar a meio |
| B7 | Linha dos chips a 360 dp | «Model…» e «Reasoning effort: High» em duas linhas, os dois visíveis |
| B8 | Cartão do MCP | O exemplo fica numa linha com scroll horizontal |
| B9 | *Ajustes* a 457 dp | As duas linhas que eram cortadas aparecem inteiras → [prova](design/revisao/24-corrigido-ajustes-duas-linhas.png) |
| C2 | Provocar um erro com a secção aberta | A faixa flutua no topo com «Fechar»; «← Ajustes» fica na mesma posição (y 405→407) |
| C3 | Provocar um erro de JSON no MCP | «servidores MCP: o motor devolveu um erro: …» — o enquadramento é da app (traduzível); o detalhe técnico fica contido |
| C4 | Desautorizar `vscreen_start` e abrir o Ecrã virtual | Aparece o aviso e o botão «Autorizar», que leva mesmo à secção Capacidades |
| C5 | Lista de comandos do Ecrã virtual | Deixou de fixar `com.android.calculator2` |
| C6 | `uiautomator` no botão de remover da fila | Passa a ter rótulo («Remover esta tarefa da fila») |
| i18n | `./tools/i18n-check.sh` | 339 chaves nos dois idiomas, 329 usadas e todas definidas, nenhuma forma brasileira |

### C1 — como ficou provado

Apagou-se `files/kernel.zip` **e** `files/kernel/` e mudou-se para o modo embutido **três vezes
seguidas**: o kernel foi copiado do asset (221 997 674 bytes, o tamanho exato), a verificação de
integridade passou e a app chegou a «ligado» nas três. A falha intermitente original não voltou a
aparecer; a segunda tentativa com o `.part` apagado pelo meio cobre o caso.

### C3 — o que ainda não está traduzido

As mensagens que o **próprio motor** devolve (por exemplo o erro do parser JSON, ou o
`no API key for provider route …` que já é traduzido) ficam em inglês técnico quando não são um dos
casos conhecidos. A app passou a enquadrá-las («o motor devolveu um erro: …») e a traduzir os casos
comuns (sem chave, sem rede, chave recusada, ligação caída, motor em baixo). Traduzir o texto
arbitrário do motor não é possível sem o interpretar; contê-lo é o que se pode fazer.
