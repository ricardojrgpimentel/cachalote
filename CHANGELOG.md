# Changelog

Formato: as versões são `MAJOR.MINOR.PATCH`; cada entrada diz o que mudou **para quem usa** e
aponta para a secção do [`STATUS.md`](STATUS.md) onde está a prova.

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
