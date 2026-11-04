# Changelog

Formato: as versões são `MAJOR.MINOR.PATCH`; cada entrada diz o que mudou **para quem usa** e
aponta para a secção do [`STATUS.md`](STATUS.md) onde está a prova.

## Não publicado

### Menos legendas, mais acções à vista

- **Apagar uma conversa** passou a ser um **deslize para a esquerda** que descobre um fundo vermelho
  com o caixote (com o mesmo diálogo de confirmação). Saiu o «Toca sem largar numa conversa para a
  apagar» — uma legenda que só existia porque a acção não se via; o toque longo continua a funcionar.
- Saíram as legendas que apontavam para botões da mesma linha — «(toca em Obter token)»,
  «(sem dados — toca em Atualizar)», «ainda não verifiquei — toca em Verificar», «toca em «Iniciar»»,
  «Toca em «Ligar web»». Fica o **estado**; o botão ao lado diz o resto.
- A regra passou a ser um teste: `tools/i18n-check.sh` falha o build se voltar a aparecer uma legenda
  a explicar a disposição (§89).
- **Menu dos Ajustes sem subtítulos que repetem o título**: «Chave do provedor» passou a «nunca sai do
  telefone», «Capacidades autorizadas» e «Backup e licenças» ficaram de uma linha só, e «Repetir a
  configuração inicial» passou a dizer o que **não** perde («não apaga conversas, chave nem
  capacidades»). Agora há um teste que falha quando um subtítulo só repete o título (§90).

### O cabeçalho da conversa deixou de ser uma parede de texto

- Antes, por cima da conversa, liam-se **duas** linhas densas em monospace e a segunda repetia o
  contexto: `root · deepseek-official: DeepSeek-V4-Flash · contexto: 8 772 / 1 000 000 · 1%` e, logo
  abaixo, `2 ferramentas · 0 a correr · contexto: … · turno 2 · 3 passos · 12 s`. A 360 dp a primeira
  acabava em «co…» e a segunda partia-se a meio de um número (§88).
- Agora o cabeçalho diz o mesmo em pedaços que se leem de relance: o **chip do modo** (`ROOT`), o
  **modelo curto** (`V4-Flash`, sem o fornecedor nem a marca — o nome completo continua no menu) e o
  **contexto como medidor** (`ctx 1%` + barra fina), em vez de dois números grandes.
- A linha de telemetria ficou só com os números do turno (`turno 5 · 1 passo · 1,8 s`), numa linha,
  com elipse em vez de embrulho; as ferramentas só aparecem enquanto correm (antes misturava a
  contagem da sessão com os números do turno).
- O medidor de contexto passou a **sobreviver ao arranque** (fica no instantâneo da sessão): antes só
  aparecia depois de um turno, e a app reaberta ficava sem ele.
- O **cabeçalho da lista** de conversas usa o mesmo medidor (`ctx 1%` + barra): era a última linha
  com os dois números grandes, e assim os dois cabeçalhos dizem a mesma coisa da mesma maneira.

## 0.3.1 — 2026-11-04

A versão em que o cabeçalho deixa de piscar «ligado/desligado» — e em que se percebeu porquê.

### O estado do motor deixou de oscilar

- **Era um ciclo, não um pisca-pisca.** Ao abrir a app com uma sessão já activa no motor, a app
  voltava a pedir `session/resume`, o motor respondia `session is already active`, a ligação caía e a
  app reconectava — de 1 em 1 segundo, indefinidamente (971 autenticações no log do daemon). Agora a
  app **lembra-se** de que a sessão está activa e não volta a pedi-la: repõe o modelo e o esforço do
  que está guardado e a ligação fica quieta (§87).
- **Um laço de ligação por processo.** O `MainActivity` passou a `singleTask` e a notificação abre a
  instância que já existe (`SINGLE_TOP | CLEAR_TOP`). Havia casos com **duas** instâncias na mesma
  tarefa — e cada uma com o seu laço, a expulsar a outra no daemon (que só aceita um cliente ACP).
- **O cabeçalho passou a ter três estados**: `ligado` · `a ligar…` · `desligado`, e só diz
  «desligado» ao fim de 3 tentativas seguidas. Um solavanco de 200 ms já não muda o que se lê.

### Ecrã virtual com qualquer app

- O ecrã virtual já abria qualquer app — o que faltava era saber o **nome da actividade de arranque**
  de cada uma. A ferramenta `android_vscreen` ganhou a acção **`apps`** (lista as apps que se podem
  abrir) e o **`launch`** passa a aceitar só o pacote (o sistema resolve a actividade). O agente
  deixou de adivinhar: abriu o Brave no ecrã virtual, navegou para um site e devolveu o fotograma
  (§85).

## 0.3.0 — 2026-10-22

A primeira versão em que o motor pode ser **desligado a sério**, a conversa mostra o que o turno
custou, e a busca e as licenças servem para alguma coisa.

### Motor e arranque

- **«Parar tudo»** no ecrã do motor: pára o daemon root, o do Shizuku, o motor embutido e o serviço
  da app, e **desliga o arranque no boot** — senão o próximo reboot trazia tudo de volta (§78).
- **Notificação do serviço** com a acção **«Parar o motor»**: dá para parar sem abrir a app (§78).
- **Interruptor «Arrancar com o telefone»** (root): com o interruptor em baixo o script **sai** do
  `service.d` do Magisk. Renomear para `.off` não chegava — o Magisk corre todos os ficheiros da
  pasta, e foi assim que o motor voltou sozinho depois de um «parar» (§79).
- O **watchdog** deixou de ressuscitar o que o dono mandou parar: o `stop` cala-o primeiro, e o
  `restart` não (perder a vigilância até ao próximo arranque era pior) (§81).

### Ecrã do motor (Ajustes → Motor)

- Passou a **blocos** com título, uma frase a dizer o que é, e as acções dentro de cada bloco com
  uma linha a explicar o efeito — «Desligar o motor», «Reiniciar o motor», «Serviço da app —
  ligado», «Atualizar estado», «Abrir a interface web», «Parar tudo» (§83).
- O **token do canal** só aparece quando falta; os cartões do modo embutido («Testar execução»,
  «Kernel por download») só aparecem no modo embutido — era o que fazia a lista parecer não acabar.
- O **modo do motor** mostra as três opções com uma frase cada, em vez de três chips sem contexto.

### Conversa

- **Telemetria do turno**: depois de cada turno, a conversa mostra os passos, as ferramentas e a
  duração (`turno 3 · 1 passo · 1,8 s`), e o **Resumo** tem o cartão «Turnos e tempo» com os últimos
  seis. Os números vêm do registo da sessão, porque o protocolo ACP não os envia (§82.1).
- **Apagar uma conversa** leva também os metadados dela (a cache do projeto) — uma conversa apagada
  deixou de ficar com o título em disco (§80.3).
- **Busca nas conversas**: procura no título e na prévia, sem maiúsculas nem acentos («correcao»
  encontra «correção»), com «N de M» e «nenhuma conversa com …» (§82.2).

### Ajustes

- **Licenças de terceiros** em painel que sobe de baixo, com o texto integral, **copiar** e **abrir a
  página** de origem (§82.3).

### Instalação e build

- Um JDK 21 é escolhido e um JDK ≥ 24 é **recusado com uma mensagem útil** em `install-app.sh`,
  `bootstrap.sh` e `build-release.sh` (o Kotlin do AGP não lê o 25) — `tools/lib-jdk.sh` (§80.2).
- `gradle test` passa a correr só a variante de debug: as classes Compose falhavam na release por
  falta da actividade de teste no manifesto (§80.1).
- O `build-release.sh` voltou a construir e a assinar a APK; o build deixou de cuspir o aviso do
  daemon do Kotlin (§80.2/§84).

### Verificado

- S10+ (LineageOS 23.2 / Android 16) nos **três modos**: turno real em cada um; os dois ensaios de
  dispositivo (`app-ui-test.sh` 10/10, `app-capabilities-test.sh` 6/6) em root **e** em Shizuku;
  interruptor do arranque provado com reboots; «parar tudo» com o watchdog real a correr.
- Emulador: testes de instrumentação 9/9 **também no telefone** (o teste da notificação passou a
  esperar pelo sistema).
- JVM: **87 testes, 0 falhas**; `i18n-check`, `session-meta`, `plugin-schema` e `secret-scan` verdes.
