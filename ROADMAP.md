# Roadmap — reparação dos achados da passagem visual

Origem: [`REVISAO-VISUAL.md`](REVISAO-VISUAL.md) (passagem de 2026-10-15, instalação de zero).
Provas: [`design/revisao/`](design/revisao).

Cada ponto tem: **o que é**, **onde**, **o plano**, e **como se prova que ficou resolvido**. Só se
fecha com a verificação feita no aparelho.

Legenda: ⬜ por fazer · 🔄 a decorrer · ✅ feito e verificado · ⏸️ adiado (com motivo)

**Estado: os 20 pontos originais e os três do Bloco D, fechados e verificados no S10+, com a
verificação final a passar (9/9 + 9/9 + 62 testes).**

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

### E1 · O motor precisa de um computador — e não devia 🔄 (Shizuku feito; root por verificar)

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
| `bin/bash` | não é preciso: o patch ao `dsh-bash-local` cai no `/system/bin/sh` via `CACHALOTE_SHELL` |
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

**Modo root — bloqueado no Magisk, não no código.** O `MotorSelfInstall` trata dos dois destinos e
o teste foi feito: o Magisk **recusa** o `su` da app — `su: request rejected (10179)`, cinco
tentativas seguidas —, enquanto o mesmo comando pelo `adb shell` passa. É uma política do Magisk
para o uid da app (uma decisão anterior, «negar», que ele guarda e aplica em silêncio, sem voltar a
perguntar), e não um defeito da app: a faixa de erro do app diz exatamente isso
(«su saiu com 13: Permission denied»).

Para o verificar é preciso, **no Magisk**, autorizar o Cachalote (Superuser → Cachalote →
permitir). Fica registado porque é o único passo que falta e não depende do código.

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
