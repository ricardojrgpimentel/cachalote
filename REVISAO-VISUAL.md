# Revisão visual e de integridade — instalação de zero

**Data:** 2026-10-15 · **Aparelho:** Samsung SM-G975F (Galaxy S10+), LineageOS 23.2 / Android 16, root
(Magisk) · **Estado inicial:** app e daemon apagados por completo, artefactos de build locais
removidos · **Regra desta passagem:** só documentar (nenhum ficheiro de código foi alterado).

Esta passagem correu **toda a interface** a partir de uma instalação de raiz, com captura de ecrã e
árvore de UI (`uiautomator`) em cada passo, e medição de píxeis onde o olho não bastava. O que se
segue é o que **não** está bem. O que está bem está resumido no fim (§9), para não dar a impressão
de que só há problemas.

As provas visuais estão em [`design/revisao/`](design/revisao) (18 capturas, numeradas como os
achados abaixo).

---

## 0. Como correu a instalação de zero

```sh
adb uninstall dev.ricardopimentel.cachalote
adb shell su -c 'rm -rf /data/adb/cachalote /data/adb/service.d/cachalote.sh \
                        /data/local/tmp/cachalote /data/local/tmp/cachalote-stage'
rm -rf .work app/build app/app/build app/.gradle app/.kotlin
./tools/bootstrap.sh
```

`bootstrap.sh` **saiu com código 0** e fez tudo o que promete:

| Passo | Resultado |
|---|---|
| 1 pré-requisitos | JDK do Android Studio + SDK encontrados; dispositivo autodetectado |
| 2 kernel do npm | `@deepseek-ai/dsh@0.2.0-rc.2` → 20 566 ficheiros, 205,8 MB; 11 patches aplicados; 0 binários `.node` restantes |
| 3 runtime Node | 7 pacotes do Termux (node 24.18.0 aarch64/bionic), 102 MB |
| 4 daemon | instalado em `/data/adb/cachalote`, a correr, socket 600, `handshake OK deepseek-harness-acp`, autostart em `/data/adb/service.d/cachalote.sh` |
| 5 app | runtime embutido 89 MB, kernel no asset 227 MB, Gradle 8.13 descarregado, `BUILD SUCCESSFUL in 2m 13s`, APK 263 MB instalada |
| 6 chave | não pedida (feita à mão depois) |

**Nota de ambiente (não é defeito do projecto):** o build imprime

```
java.nio.file.FileSystemException: ~/Library/Application Support/kotlin/daemon/kotlin-daemon-client-*.tmp:
  Operation not permitted
Daemon compilation failed: Could not connect to Kotlin compile daemon
```

O sandbox de ficheiros do harness não deixa o daemon do Kotlin escrever fora do workspace; o Gradle
cai para compilação *in-process* e o build passa. Quem correr isto dentro do harness deve esperar
esse ruído (não é fatal, mas assusta).

---

## 1. Defeitos que quebram a interface (P0)

### 1.1 Botões que colapsam a zero — e desaparecem (3 sítios, uma só causa)

**Causa:** `Row(horizontalArrangement = Arrangement.spacedBy(…))` com filhos **sem `weight`**. O
Compose mede os filhos sem peso com a largura que sobra; quando a soma dos mínimos excede a largura,
os últimos recebem um limite quase nulo. Medido no aparelho:

| Sítio | Configuração | Resultado medido |
|---|---|---|
| [`Assistente.kt:307`](app/app/src/main/java/dev/ricardopimentel/cachalote/Assistente.kt#L307) — passo 3 do assistente, 3 botões | PT @ 457 dp | `Atualizar` → TextView de **22 × 513 px** (≈7 × 163 dp): as letras empilham-se na vertical e o botão fica mais alto que o cartão → [prova](design/revisao/02-assistente-passo3-botao-colapsado.png) |
| idem | EN @ 360 dp | `Refresh` **desaparece por completo** da árvore de UI; `Get token (root)` passa a 2 linhas → [prova](design/revisao/03-assistente-passo3-360dp-botao-desaparece.png) |
| [`UiParts.kt:130`](app/app/src/main/java/dev/ricardopimentel/cachalote/UiParts.kt#L130) — `Fila de tarefas` na conversa | PT @ 457 dp | `Limpar concluídas` parte em 3 linhas com o **`s` órfão** → [prova](design/revisao/04-conversa-botoes-e-chips.png) |
| idem | EN @ 360 dp | O botão é um **comprimido vazio de 114 × 1072 px** (≈29 × 268 dp), **sem texto nenhum**; a `FilaCard` cresce tanto que empurra os chips de modelo/esforço para fora do layout (**desaparecem**) e esmaga o transcript → [prova](design/revisao/05-conversa-360dp-botao-fantasma.png) |
| [`MainActivity.kt:1264`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainActivity.kt#L1264) — secção Motor, 4 botões em 2 linhas | EN @ 360 dp | A segunda linha colapsa num **sliver de 598 × 13 px** (≈3 dp de altura): os botões ficam invisíveis → [prova](design/revisao/18-motor-360dp-botao-13px.png) |

O mesmo padrão está em [`MainActivity.kt:1072`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainActivity.kt#L1072)
(Exportar/Importar definições) e
[`MainActivity.kt:646`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainActivity.kt#L646)
(Autorizar/Negar todas) — a 457 dp ainda cabem, a 360 dp não foram testados até ao limite.

**Consequência prática:** a 360 dp (a largura da maioria dos telefones) o utilizador perde o botão
«Atualizar» do assistente, o «Limpar concluídas» da fila e os botões da segunda linha do Motor — sem
qualquer aviso. Não é um problema estético: são funções que deixam de existir.

### 1.2 «Modo completo (interface web do DSH)» = ecrã totalmente preto

[`WebModeActivity`](app/app/src/main/java/dev/ricardopimentel/cachalote/WebModeActivity.kt) desenha um
ecrã **100 % preto**: medição de píxeis em três zonas (0..203, 400..1600 e a linha dos botões) dá
`#000000` em 100 % dos píxeos, enquanto o `uiautomator` vê os controlos com limites normais.

Prova: [ecrã preto](design/revisao/08-modo-completo-ecra-preto.png)

Três causas somam-se, todas visíveis no código:

1. **Tema errado.** O `setContent` embrulha tudo em `MaterialTheme { }` **sem** o esquema do projecto
   ([`WebModeActivity.kt:47`](app/app/src/main/java/dev/ricardopimentel/cachalote/WebModeActivity.kt#L47)).
   Isso dá o esquema **claro** (texto quase preto) sobre o fundo de janela do `Theme.Cachalote`
   (`android:Theme.Material.NoActionBar` → `background_dark`, preto). Preto sobre preto.
2. **Sem insets.** Os controlos ficam em `y 26..178` — **debaixo da barra de estado**. Um toque ali
   não chega à app (é o SystemUI que o apanha): verificado, tocar em «Ligar web» não muda nada.
3. **`AndroidView` com `fillMaxSize()` dentro de uma `Column`** em vez de `weight(1f)`
   ([`WebModeActivity.kt:90-91`](app/app/src/main/java/dev/ricardopimentel/cachalote/WebModeActivity.kt#L90-L91)).

Quem entra neste ecrã não vê nada, não consegue tocar em nada e só sai com o gesto de «voltar» do
sistema. O ecrã está acessível em *Ajustes → Motor → Modo completo (interface web do DSH)*.

### 1.3 Texto praticamente invisível no cartão escolhido do assistente (contraste 1,30:1)

No passo 2 do assistente, o botão do cartão **escolhido** lê-se `escolhido` / `chosen` pintado a
`#BEFFE7` sobre o cartão `#38EFC2`.

| Texto | Cor | Contraste medido |
|---|---|---|
| `escolhido` / `chosen` (TextButton) | `#BEFFE7` sobre `#38EFC2` | **1,30 : 1** |
| Título `Com root (Magisk)` | `#006952` sobre `#38EFC2` | 4,55 : 1 (passa, sem margem) |

O mínimo AA é 4,5 : 1 para texto normal. A causa: o cartão fixa `containerColor = primaryContainer` e
calcula `corTexto`/`corDetalhe`, mas o `TextButton` lá dentro fica com a cor por omissão, que é
`colorScheme.primary` — **um tom mais claro da mesma família** do fundo
([`Assistente.kt:265-292`](app/app/src/main/java/dev/ricardopimentel/cachalote/Assistente.kt#L265)).

Prova: [cartão mint com `escolhido` ilegível](design/revisao/01-assistente-modo-escolhido.png)

---

## 2. Texto desformatado

### 2.1 Markdown literal na app

`A chave do provedor e o token do canal **não** entram no ficheiro.`

Os asteriscos aparecem **tal e qual** no ecrã *Ajustes → Backup e licenças*, nos dois idiomas
(`backup_texto`, [`values/strings.xml:42`](app/app/src/main/res/values/strings.xml#L42) e
[`values-pt-rPT/strings.xml:42`](app/app/src/main/res/values-pt-rPT/strings.xml#L42)).

Prova: [**não** no ecrã](design/revisao/07-backup-markdown-literal.png)

Há uma segunda string com markdown — `` `cachalote doctor` `` em `wizard_pronto_texto` — mas essa
chave **não é usada** por ninguém (ver §3), por isso não chega a aparecer.

### 2.2 O JSON de exemplo do MCP aparece **sem aspas** (e é inválido)

```
[{name:ping,command:/data/adb/cachalote/runtime/bin/node,args:[/data/local/tmp/mcp-ping-server.mjs],env:[{name:LD_LIBRARY_PATH,value:/data/adb/cachalote/runtime/lib}]}]
```

Em Android, uma aspa **não escapada** dentro de `<string>` delimita um troço com espaço preservado e
**é retirada do texto final**; para sair uma aspa é preciso `\"`. O `mcp_exemplo` tem
`"name"` no XML e o utilizador vê `name`. A prova está no **mesmo ecrã**: o `placeholder` logo abaixo
(uma string Kotlin, com `\"`) mostra as aspas corretamente
([`MainActivity.kt:730`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainActivity.kt#L730)) —
enquanto a chave `placeholder_mcp` que existia para isso está morta.

O exemplo que a app convida a copiar é, portanto, **JSON inválido**.

Prova: [exemplo sem aspas, placeholder com aspas](design/revisao/06-mcp-json-sem-aspas.png)

### 2.3 Plurais à mostra: `1 problema(s) e 4 aviso(s)`

O ecrã *Resumo* mostra o sufixo `(s)` literal e **repete a mesma informação na linha seguinte**:

```
1 problema(s) e 4 aviso(s)
1 problema(s) · 4 aviso(s) · /data/adb/cachalote
```

(e, quando não há problemas, `4 aviso(s), nenhum problema`).

Prova: [problema(s)/aviso(s)](design/revisao/10-resumo-problema-s.png)

### 2.4 O registo de auditoria mostra milissegundos Unix

```
1792065911667  status  {}
1792065510850  status  {}
```

*Capacidades autorizadas → Registo*. Nem é legível nem diz se o pedido foi autorizado ou recusado —
para o que o projecto chama «registo de auditoria», é pouco.

Prova: [registo com timestamps](design/revisao/11-registo-timestamps.png)

### 2.5 Nome do modelo cortado a meio, sem reticências

O chip da conversa diz `Model: deepseek-official: DeepSeek-` — porque
[`MainActivity.kt:1527`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainActivity.kt#L1527)
faz `currentLabel.take(28)`. A linha de cabeçalho, dois centímetros acima, mostra o nome completo
(`deepseek-official: DeepSeek-V4-Flash`) — a inconsistência fica à vista.

### 2.6 O chip «Reasoning effort» nasce cortado pela borda do ecrã

Na primeira composição da conversa o segundo chip começa em `x = 1041` e acaba em `x = 1440` (a
largura do ecrã): o valor («High») fica fora. A `Row` tem scroll horizontal (confirmado: depois de
arrastar, o chip aparece inteiro), mas nada o indica.

Provas: [cortado](design/revisao/04-conversa-botoes-e-chips.png)

### 2.7 Token comprido sem ponto de quebra

O JSON de exemplo do MCP tem 159 caracteres (EN) / 190 (PT) **sem um único espaço**: o motor de
texto quebra-o a meio dos identificadores e o cartão cresce para 8–10 linhas estreitas. Não é cortado
— é feio.

---

## 3. Cortes e elipses dependentes do tamanho

As linhas de apoio do menu de Ajustes são `maxLines = 1` + `Ellipsis`
([`UiParts.kt:584`](app/app/src/main/java/dev/ricardopimentel/cachalote/UiParts.kt#L584)). Contei as
que ficam com reticências em quatro configurações:

| Configuração | Linhas cortadas (de 8) |
|---|---|
| **pt-PT @ 457 dp** (o estado real do telefone) | **2** — `estado, modos (…) e dia…`, `exportar definições e ver o software de terceir…` |
| en @ 457 dp | 1 — `status, modes (root, Shizuku, embedded) and …` |
| en @ 360 dp | **4** — `…Shizuku, e…`, `…stored in the …`, `…do on the p…`, `…without a…` |
| en @ 457 dp, fonte 1,3× | **6** — incluindo `…third-part…` e `…the agent …` |

O corte cai **a meio de palavra** (`e…`, `p…`, `a…`), o que lê pior do que uma elipse no fim da frase.
As frases são as mesmas nos dois idiomas, mas o português é mais comprido — o que faz com que o mesmo
menu perca mais informação em português.

Provas: [360 dp](design/revisao/12-ajustes-360dp-elipses.png) ·
[fonte 1,3×](design/revisao/13-ajustes-fonte-130-elipses.png) ·
[en @ 457 dp](design/revisao/17-en-ajustes.png)

---

## 4. i18n: o que continua em português com a app em inglês

Testado com `cmd locale set-app-locales dev.ricardopimentel.cachalote --locales en`. A app muda de
idioma corretamente **nas strings que vêm de `strings.xml`** — e só nessas. Fica em português:

| Onde | O que fica em PT |
|---|---|
| *Settings → Engine → «First steps — 2 left»* | **a lista inteira**: `daemon instalado`, `root concedido`, `token do canal`, `chave do provedor`, `(no separador Chave)`, `acessibilidade ligada`, `Abrir Acessibilidade`, `capacidades autorizadas` — com o título e o botão (`First steps`, `Check`) **traduzidos**, o que torna a mistura mais óbvia → [prova](design/revisao/14-en-first-steps-em-portugues.png) |
| Conversa (transcript) | `sessão 9112b1de retomada`, `sessão …… criada`, `turno falhou: …`, `fila concluída`, `definições exportadas`, `N definições restauradas`, `capacidade X: …`, `servidores MCP: …` → [prova](design/revisao/16-en-sessao-retomada-em-portugues.png) |
| *Summary → Diagnostics* | **todo o `cachalote doctor`**: títulos `FICHEIROS`, `CHAVE DO PROVEDOR`, `CANAL E MOTOR`, `APP E CAPACIDADES (PONTA A PONTA)`, `ESPAÇO E REGISTOS`; itens `ponte (bridge.cjs)`, `a correr (pid …)`, `watchdog a vigiar`, `TCP … à escuta`, `sem DEEPSEEK_API_KEY em home/.env` → [prova](design/revisao/15-en-doctor-em-portugues.png) |
| *Summary*, cabeçalho | `1 problema(s) e 4 aviso(s)` (PT) imediatamente acima do seu gémeo em inglês `1 problem(s) · 4 warning(s)` — **dois idiomas na mesma informação, em duas linhas seguidas** |
| *Settings → Engine* | `(não testado)` |

A origem está concentrada: **mais de 60 frases** escritas à mão no
[`MainViewModel.kt`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainViewModel.kt) e
[`MainActivity.kt`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainActivity.kt), que entram
no `transcript` ou na faixa de erro. Há também o caso inverso — **inglês fixo na UI portuguesa**: os
chips da conversa leem `Model:` e `Reasoning effort:`
([`MainViewModel.kt:1121`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainViewModel.kt#L1121)).

Um caso mais frágil que os outros: [`Assistente.kt:305`](app/app/src/main/java/dev/ricardopimentel/cachalote/Assistente.kt#L305)
decide se o token existe comparando `vm.tokenState != "(por obter)"` — **lógica de estado acoplada a
uma frase visível ao utilizador**. Basta traduzir essa frase para o assistente passar a mentir.

**Teimosia relacionada:** 260 chaves em cada idioma, mas **27 nunca são usadas** (entre elas
`placeholder_mcp`, `motor_root`, `motor_shizuku`, `fila_corrida`, `sobre*`, `wizard_pronto_*`) — são
exatamente as que foram substituídas por literais no código.

---

## 5. Achados funcionais

### 5.1 O kernel embutido falhou uma vez a verificação de integridade

Ao mudar para o modo embutido **pela primeira vez depois da instalação de raiz**, a app mostrou:

> modo: o kernel embutido falhou a verificação de integridade
> (esperado `0c7106ffa8b1ad5f…`, obtido `d2cf2cd627e89b54…`)

O artefacto que viaja na APK **está correto** — verifiquei o sha256 em três sítios:

| Onde | sha256 (16 primeiros) |
|---|---|
| `.work/kernel.zip` | `0c7106ffa8b1ad5f` |
| `assets/kernel.zip` extraído da APK | `0c7106ffa8b1ad5f` |
| `files/kernel.zip` que a app acabou por escrever | `0c7106ffa8b1ad5f` |
| **o que a app mediu na primeira tentativa** | `d2cf2cd627e89b54` |

Ou seja: a comparação falhou sobre uma **cópia** errada, não sobre o ficheiro distribuído. Repeti a
operação duas vezes depois (incluindo apagando `files/kernel.zip` e `files/kernel/` antes), e passou
sempre. É **intermitente** e não consegui reproduzi-lo a pedido; pelo código
([`EmbeddedEngine.kt:133-151`](app/app/src/main/java/dev/ricardopimentel/cachalote/EmbeddedEngine.kt#L133-L151))
a explicação mais provável é uma escrita/leitura concorrente do mesmo `kernel.zip.part`. Fica como
«por confirmar», mas o sintoma é mau: na primeira utilização do modo «sem nada» o utilizador vê um
erro de integridade.

### 5.2 A faixa de erro empurra o ecrã inteiro e engana os toques

A faixa de erro é inserida **acima** do ecrã ativo, num `Column` sem scroll. Medido: ao aparecer, o
botão de voltar da conversa salta de `y 186..262` para `y 414..490` — **228 px**. Enquanto a faixa
está lá, todos os alvos de toque se deslocam; durante esta passagem toquei várias vezes no sítio
errado por causa disso.

### 5.3 Os erros do motor aparecem crus, em inglês, dentro da UI portuguesa

```
turno falhou: Internal error: turn failed: llm-deepseek: no API key for provider route
"deepseek-official"; store DEEPSEEK_API_KEY through the credentials service (the web Models
page writes it), or export DEEPSEEK_API_KEY in the launching environment
```

Fala de conceitos que **não existem nesta app** («the web Models page», «the launching
environment») a quem está a segurar um telefone.

Prova: [erro do motor](design/revisao/09-conversa-erro-do-motor.png)

(A mensagem está correta e o caminho da chave funciona: depois de repor a chave, o mesmo turno falha
apenas com `DeepSeek Messages transport failed` — o telefone está sem rede.)

### 5.4 O botão «Iniciar» do ecrã virtual está ativo mesmo sem autorização

Tocar em «Iniciar» sem a capacidade `vscreen_start` autorizada dá
`ecrã virtual: capacidade 'vscreen_start' não está autorizada na app (separador Capacidades)`. A
guarda funciona e explica-se bem — mas o botão podia levar lá diretamente (ou pedir a autorização),
em vez de deixar o utilizador descobrir sozinho.

### 5.5 Os comandos de exemplo do ecrã virtual apontam para uma app que pode não existir

```
1  am start --display <id> -n com.android.calculator2/.Calculator
2  input -d <id> tap 105 838
3  input -d <id> keyevent KEYCODE_BACK
```

Pacote e coordenadas fixos, iguais em qualquer telefone.

### 5.6 Uma sessão antiga pode deixar a app «desligada» para sempre

**Este foi o achado mais grave da passagem.** Depois de o motor ser reiniciado com a app a segurar
uma sessão, a app ficou neste estado — e **não sai dele sozinha**:

| Sintoma | O que se vê |
|---|---|
| Cabeçalho | `motor DSH no telefone · **desligado**` — para sempre |
| Faixa de erro | `chave: a ligação ao motor caiu` |
| Abrir uma conversa | `retomar: a ligação ao motor caiu`; a conversa não abre |
| Consola | ciclo a cada 1–3 s: `cachalote.auth` (ok) → `initialize` (ok) → `session/resume` (**erro**) → ligação abaixo → recomeça |
| Log do daemon | `{"code":-32602,"message":"Invalid params: session is already active: 3edc8293-…"}` |

O detalhe importante: o erro é **`session is already active`** — precisamente o caso que o código diz
querer tolerar ([`MainViewModel.kt:1110`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainViewModel.kt#L1110)
`isSessionAlreadyActive`). Apesar disso, a ligação cai a cada ciclo e `connected` volta a `false`, por
isso o cabeçalho nunca chega a «ligado».

**Causa provável (por confirmar instrumentando):** há **dois caminhos de reconexão a correr ao mesmo
tempo**:

1. o laço de reconexão do ViewModel
   ([`MainViewModel.kt:236-269`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainViewModel.kt#L236-L269));
2. o `reconnector` invocado de dentro do próprio `call()`, sempre que `streams == null`
   ([`CachaloteClient.kt:125-127`](app/app/src/main/java/dev/ricardopimentel/cachalote/CachaloteClient.kt#L125-L127)).

Como `connectTcp` passa por `connect()`, que **começa por `disconnect()`**
([`CachaloteClient.kt:87`](app/app/src/main/java/dev/ricardopimentel/cachalote/CachaloteClient.kt#L87)),
cada caminho derruba a ligação do outro: no log vê-se `cachalote.auth` **aos pares** (dois clientes a
autenticar-se em simultâneo), e o `readLoop` de um deles termina — o que põe `connected = false` e
alimenta o ciclo.

**Como se sai (testado, um a um):**

| Ação | Resulta? |
|---|---|
| `Reiniciar motor` (Motor) | ❌ não |
| `Obter token (root)` / `Atualizar` | ❌ não |
| Fechar a sessão activa (ícone de power) | ❌ não |
| Abrir a conversa antiga | ❌ não (dá «retomar: a ligação ao motor caiu») |
| **Criar uma conversa nova** | ⚠️ a sessão nova funciona, mas o cabeçalho continua «desligado» |
| **Trocar de motor e voltar** (root → embutido → root) | ✅ **é o único caminho que repõe «ligado»** |

A troca de motor é o único caminho que limpa `sessionId`
([`MainViewModel.kt:325`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainViewModel.kt#L325)) —
o que confirma que o problema está em **nunca largar a sessão que o motor já não aceita retomar**. O
caminho de envio já sabe fazer isto (`unknown session` → abre sessão nova,
[`MainViewModel.kt:708`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainViewModel.kt#L708));
o laço de reconexão não.

**Como cheguei aqui:** não foi preciso nada de exótico — bastou reiniciar o daemon (o que a própria
app faz ao gravar a chave ou pelo botão `Reiniciar motor`). O estado é recuperável, mas quem não
descobrir a troca de motor fica com uma app que diz «desligado» e não abre as conversas.

Provas: [app presa em «desligado»](design/revisao/19-app-desligada-sessao-presa.png) ·
[depois da troca de motor](design/revisao/20-app-recuperada-apos-troca-de-motor.png)

**Efeito secundário observado:** depois de fechar uma sessão, a prévia na lista passa a
`sessão 3edc8293` — o daemon devolve a última mensagem do ficheiro, que passou a ser a mensagem de
sistema do fecho. A lista fica com prévias inúteis.

---

## 6. Observações de design (não são defeitos)

Coisas que não estão «mal», mas que um designer olharia duas vezes:

1. **O aviso de root repete-se em todos os ecrãs.** `aviso_root` aparece no fundo do menu **e** no
   fundo de cada uma das 8 secções — nove cópias do mesmo parágrafo, incluindo em *Backup e licenças*
   e *Servidores MCP*, onde não tem nada que ver.
2. **Títulos duplicados.** O cabeçalho da secção e o primeiro cartão dizem o mesmo: *Motor*/*Engine*,
   *Acessibilidade*/*Accessibility*, *Servidores MCP*/*MCP servers*, *Capacidades autorizadas*,
   *Áreas permitidas do bash*.
3. **Dois convites para a mesma ação.** No estado vazio da conversa há o botão «Nova conversa» ao
   centro **e** o FAB «Nova» em baixo à direita; com conversas, só o FAB.
4. **Ecras quase vazios.** *Acessibilidade* tem um cartão, um botão e ~1 600 px de nada; *Áreas
   permitidas do bash* idem.
5. **JSON de diagnóstico à vista do utilizador.** O cartão *Daemon* (secção Motor) despeja o JSON
   cru, com barras escapadas (`"\/data\/adb\/cachalote"`).
6. **Placeholders que parecem valores gravados.** `/sdcard/Download` (Áreas), `sk-…` (Chave),
   `https://…/kernel.zip` (Motor), o JSON (MCP). Medido: placeholder `#BACAC2` (7,28 : 1) contra
   valor real `#DBE3F0` (12,73 : 1) — só 1,7× de diferença. No ecrã das Áreas parece que já está
   configurado.
7. **«8 módulos configurados».** O ecrã tem 8 módulos **e mais dois cartões** (repetir assistente,
   PERIGO), e os módulos não estão «configurados» — estão disponíveis.
8. **Texto a 10 sp para frases inteiras** (`labelSmall`): o rodapé do assistente, a legenda de
   latência, a linha de comando, o chip «Ao vivo». Num telefone, 10 sp com `letterSpacing` é marginal.
9. **Dois diálogos continuam sem desenho** — Licenças e Modelo/Esforço —, ambos `AlertDialog` de
   Material puro, como o [`design/README.md`](design/README.md) já assume.
10. **Os chips de modelo/esforço quase não servem para nada** quando o motor oferece uma opção de cada
    (foi o caso: um modelo, um esforço). O diálogo *Model* tem uma linha.
11. **Rodapé com palavra órfã.** «Nada disto é obrigatório: podes saltar e configurar depois, nos
    Ajustes.» quebra com `Ajustes.` sozinho na última linha.
12. **Duplicação de texto no passo 5 do assistente:** a chave é explicada **duas vezes** no mesmo
    ecrã («A chave é enviada ao daemon, que a escreve em home/.env com modo 600 — a app não a
    guarda.» e, logo abaixo do botão, «A chave é enviada ao daemon, que a escreve em home/.env com
    modo 600. A app não a guarda.»).
13. **A lista de conversas repete o título na prévia** quando o último evento é a própria mensagem do
    dono (`ola agente` / `ola agente`).

---

## 7. Coerência com o design do Stitch

Comparação com [`design/ecras/`](design/ecras) e [`design/app/`](design/app):

| Ecrã | Veredicto |
|---|---|
| Ajustes (menu) | **Fiel** — linhas de 72 dp, círculo tonal, chevron, separador recuado, título em Space Grotesk. As reticências do §3 são o único desvio |
| Ecrã virtual | **Fiel**, incluindo o monitor, os chips de estado e o bloco de terminal com os três pontos |
| Conversa (lista e sessão) | **Fiel**, mas os chips e o rodapé da fila rompem a grelha em ecrãs estreitos |
| Chave, Resumo, Primeiros passos | **Fiéis** |
| Assistente (passos 1, 2, 4, 5, 6) | Fiel, exceto o contraste do cartão escolhido (§1.3) |
| Assistente (passo 3) | **Quebrado** (§1.1) |
| **Modo completo (web)** | **Sem design nenhum** e sem render (§1.2) |
| Licenças, Modelo/Esforço | Sem design (assumido no README do design) |

---

## 8. Limites desta passagem

- **O telefone não tem rede.** `ping 8.8.8.8` → *Network is unreachable*; `dumpsys connectivity` →
  *Active default network: none*. Isto já era conhecido ([`STATUS.md`](STATUS.md) §59) e não é
  regressão desta passagem, mas fecha portas: **não foi possível testar** um turno completo com o
  modelo, os cartões de ferramenta, o painel de autorização, o envio de imagens ao modelo, nem o
  *download* do kernel. O que se testou desses caminhos é o erro — e o erro é limpo.
- **Modo Shizuku** não testado (Shizuku não está a correr no aparelho).
- **Diálogos do sistema** (permissões, seletor de ficheiros, seletor de imagens) aparecem como o
  Android os desenha; não são avaliados aqui.
- O aparelho corria a **504 dpi (457 dp de largura)**. Testei também a 640 dpi (**360 dp**, a largura
  típica de telefone) e com fonte a 1,3×; foi aí que a maior parte dos defeitos apareceu.
- Repus o estado do aparelho no fim: densidade 504, `font_scale` 1,0, idioma da app pt-PT, chave do
  provedor escrita de volta em `home/.env` (modo 600, idêntica ao backup) e `.credentials.yaml`
  reposto. Ficaram como resíduo: `/sdcard/Download/cachalote-definicoes.json`, duas conversas de
  teste na app (`ola agente`, `teste de registro`) e o registo de auditoria.

---

## 9. O que está bem (para contrapeso)

| Verificação | Resultado |
|---|---|
| `bootstrap.sh` de raiz | ✅ exit 0, daemon a correr, app instalada e aberta |
| Autostart no Magisk | ✅ `/data/adb/service.d/cachalote.sh` |
| Assistente inicial (6 passos) | ✅ percorrido todo, nos dois idiomas |
| Menu de Ajustes + 8 secções + BACK | ✅ abrem todas; o BACK volta ao menu |
| Ecrã da Chave | ✅ gravar/verificar/remover; `home/.env` com modo 600 |
| Troca de motor | ✅ root → embutido → root, com reinício do daemon |
| Backup | ✅ exporta 426 B para `/sdcard/Download` **sem** chave nem token; importa e repõe 14 definições |
| `cachalote doctor` | ✅ «0 problema(s) · 4 aviso(s)» com a chave reposta |
| Testar execução | ✅ `libnode.so executou: v24.18.0 [exit 0, farm 9 libs]` |
| Fila de tarefas | ✅ juntar (1 na fila), remover (✕), listar |
| Ecrã virtual | ✅ inicia (`display 10 · 720×1280 @ 60 Hz`), guarda por capacidade, para, e **liberta o display quando a app fecha** (verificado) |
| Anexar / Falar | ✅ seletor de imagens; erro claro quando não há reconhecedor de voz |
| Lista de conversas | ✅ título, prévia, hora, marca ACTIVA |
| Licenças | ✅ 16 entradas, todas acessíveis por scroll, com texto integral e URL |
| Troca de idioma | ✅ `cmd locale set-app-locales` funciona nas duas direções |
| Recuperação da app presa (§5.6) | ✅ trocar de motor e voltar repõe «ligado» (foi assim que deixei o aparelho) |

---

## 10. Resumo por gravidade

| # | Achado | § | Gravidade |
|---|---|---|---|
| D1 | Botões colapsam a zero e desaparecem (3 sítios) | 1.1 | **P0** |
| D2 | «Modo completo» = ecrã preto, controlos inalcançáveis | 1.2 | **P0** |
| D3 | Texto a 1,30:1 no cartão escolhido do assistente | 1.3 | **P0** |
| **F6** | **Sessão antiga deixa a app «desligada» para sempre** | 5.6 | **P1** |
| D4 | Markdown `**não**` literal | 2.1 | P1 |
| D5 | JSON de exemplo do MCP sem aspas (inválido) | 2.2 | P1 |
| D6 | `problema(s)` / `aviso(s)` à mostra | 2.3 | P1 |
| D7 | Registo de auditoria com milissegundos Unix | 2.4 | P1 |
| D12 | Blocos inteiros em português com a app em inglês | 4 | P1 |
| D8 | Nome do modelo cortado sem reticências | 2.5 | P2 |
| D9 | Chip «Reasoning effort» cortado na borda | 2.6 | P2 |
| D11 | Linhas de apoio dos Ajustes elididas (1 a 6, conforme o tamanho) | 3 | P2 |
| D10 | Token comprido sem ponto de quebra | 2.7 | P2 |
| F1 | Kernel embutido falhou a integridade (uma vez) | 5.1 | P2 (intermitente) |
| F2 | Faixa de erro desloca o ecrã inteiro | 5.2 | P2 |
| F3 | Erros do motor crus, em inglês | 5.3 | P2 |
| F4 | «Iniciar» ativo sem autorização | 5.4 | P3 |
| F5 | Comandos de exemplo com pacote fixo | 5.5 | P3 |

O padrão que domina é um só: **as `Row` sem `weight`**. Resolver isso resolve D1 por inteiro e, no
caso da conversa a 360 dp, devolve também os chips de modelo que hoje desaparecem por arrasto.

Depois há dois problemas de fundo, independentes do desenho: **a app não larga uma sessão que o motor
já não retoma** (§5.6) e **as frases de estado vivem no código em vez de nos recursos** (§4) — a
segunda é a razão pela qual a app parece portuguesa mesmo em inglês.
