# Readiness — instalação limpa nos três modos

Ensaio feito em **2026-10-19**, no **Samsung Galaxy S10+ (SM-G975F, LineageOS 23.2 / Android 16,
Magisk 30.7)**, com a APK de debug construída nesse dia. O objectivo era responder a uma pergunta
concreta: **quem instala a app de fresco e segue o assistente, fica com a app a funcionar?** — nos
três modos: root, Shizuku e embutido (sem root nem Shizuku).

## Veredicto

| Modo | Veredicto | O que é preciso do lado do utilizador |
|---|---|---|
| **Root** | ✅ **funciona** — motor instalado pela app, wizard completo, turno real com `uid=0(root)` | Autorizar o Cachalote no **diálogo do Magisk** (9 s) e ligar a **acessibilidade** |
| **Shizuku** | ✅ **funciona** — motor instalado pela app, wizard completo, turno real | Ter o **Shizuku a correr** e autorizar o Cachalote; acessibilidade |
| **Embutido** | ✅ **funciona sem root nenhum** — kernel extraído pela app, turno real com `uid=10182(u0_a182)`, `android_screen` a ler o ecrã | Acessibilidade (e a chave do provedor) |

**Resposta curta:** sim — nos três modos a app fica a funcionar depois de uma instalação limpa e do
assistente. O caminho **mais frágil é o root**, por causa do diálogo temporizado do Magisk; o mais
**previsível é o embutido**, que não pede nada ao sistema além da acessibilidade.

## O que foi feito em cada modo

Cada modo foi testado com o **motor apagado** (ou nunca instalado) e a app desinstalada/reinstalada,
para o assistente correr do passo 1 ao 6. No fim, um turno real com o modelo:

| Passo do assistente | Root | Shizuku | Embutido |
|---|---|---|---|
| 1. Boas-vindas | ✅ | ✅ | ✅ |
| 2. Escolha do modo | ✅ (root escolhido por omissão) | ✅ (Shizuku marcado RECOMENDADO) | ✅ |
| 3. Ligar o motor | ✅ instala em `/data/adb` (~600 MB), token e motor ✓ | ✅ instala em `/data/local/tmp`, token e motor ✓ | ✅ kernel extraído na app, motor a correr — **sem cartão root e sem `su`** |
| 4. Acessibilidade | ✅ pelo botão → Definições → Cachalote → Permitir | ✅ (repetido) | ✅ (repetido) |
| 5. Chave do provedor | ✅ grava e mostra ✓ | ✅ grava; o ✓ **só passou a aparecer com a correcção abaixo** | ✅ grava na app e mostra ✓ |
| 6. Poderes | ✅ (o pedido de notificações **só passou a aparecer com a correcção abaixo**) | ✅ | ✅ |
| Turno real | ✅ `uid=0(root) gid=0(root) … u:r:magisk:s0` → [42-root-bash.png](design/revisao/42-root-bash.png) | ✅ `uid=0(root)` (o servidor Shizuku deste telefone corre como root) | ✅ `uid=10182(u0_a182) … u:r:untrusted_app` → [43-embutido-sem-root.png](design/revisao/43-embutido-sem-root.png) |
| Extra | — | — | ✅ `android_screen` leu a árvore do ecrã depois de a acessibilidade estar ligada |

## O que estava partido numa instalação limpa — e foi corrigido

Estes defeitos só aparecem a quem vem de fora, com a app e os motores limpos. Foram todos
corrigidos nesta ronda:

| # | Defeito | Efeito para quem instala | Correção |
|---|---|---|---|
| 1 | O passo 2 dizia **«este modo precisa de um computador»** e os erros de motor por instalar mandavam instalar a partir do PC | O utilizador concluía que não conseguia sem computador — a parede que o E1 tinha derrubado | Textos passaram a dizer que **a app instala o motor no telefone** e que o Magisk/Shizuku só dão o acesso |
| 2 | No modo embutido, o passo 3 perguntava pelo daemon **root** (corria `su`) e mostrava o cartão **«Instalar o motor» root** | Quem escolhia «sem nada» via um pedido de root e um cartão para instalar o que não queria | `daemonInstalled` no embutido passou a significar **kernel extraído**; o passo 3 deixou de correr `su` e ganhou cartão «Preparar o kernel» + «Ligar o motor» |
| 3 | O botão **«Autorizar as essenciais»** ligava a capacidade `notify` mas **não pedia a permissão de notificações do Android** | A UI dizia que estava ligado e `android_notify` falhava; `POST_NOTIFICATIONS: granted=false` | O atalho passou a pedir a permissão (o diálogo apareceu no ensaio) |
| 4 | No modo Shizuku, o estado da chave **não trazia o comprimento**; o passo 5 testava `keyLength > 0` | A chave estava gravada (o daemon dizia `present: true`) e o passo ficava **✗** para sempre | O passo 5 usa `keyPresent`; o `keyStatus` do Shizuku passou a ler o comprimento do ficheiro (a tela da Chave mostra `presente · 35 caracteres`) |
| 5 | No modo embutido, o passo 5 dizia **«Guardar no daemon»** e «enviada ao daemon» | Linguagem de um daemon que não existe nesse modo | Textos próprios: «Guardar na app» e «fica no armazenamento privado da app, modo 600» |
| 6 | O ecrã **Resumo** mostrava «Ligar daemon» e «Obter token (root)» mesmo em Shizuku/embutido | Botões que não se aplicam ao modo escolhido | Embutido esconde-os; Shizuku usa os rótulos «(Shizuku)» |

Já tinham sido corrigidos na ronda do E1 (e foram re-verificados aqui, com instalação limpa):
**shim `bin/bash`** no auto-instalador (era `spawn bash ENOENT` em todos os comandos) e **arranque no
boot** (`service.d/cachalote.sh`, que o `sendFile(..., 0)` impedia de escrever).

## O que fica aberto (por prioridade)

| Prioridade | Problema | Porque importa |
|---|---|---|
| **P0** | **O Magisk nega em silêncio ao fim de 9 s.** O diálogo «Solicitação de SuperUsuário» tem contagem decrescente; se ninguém tocar, o uid fica `policy=1` (negar) e o install falha com «su saiu com 13: Permission denied» — a app não explica que é preciso ir ao Magisk | É o primeiro obstáculo de quem escolhe root e não está à espera do diálogo. A app devia detectar a negação e mostrar um cartão «autoriza o Cachalote no Magisk → Superuser» (ou abrir essa página) |
| **P0** | **Os dois daemons usam o mesmo porto (39731).** Ao trocar root ↔ Shizuku sem parar o outro, a app liga-se ao daemon errado e o sintoma é «token inválido» | A troca de modo é uma função da app; hoje exige parar o daemon antigo à mão. Ou se usa o 39733 para o Shizuku (como a documentação já diz), ou o `switchMode` pára o outro |
| **P1** | **O modo escolhido por omissão é root**, mesmo quando o Shizuku está disponível e marcado RECOMENDADO; num telefone sem root o utilizador tem de mudar | Incoerência entre o cartão recomendado e o escolhido; um toque em «Continuar» leva ao caminho errado |
| **P1** | **A extracção do kernel embutido começa ao escolher o modo no passo 2**, mas o progresso só se vê no passo 3; o «Continuar» não fica desactivado | Em telemóveis lentos, o passo 2 parece pendurado durante 1–2 min |
| **P1** | **`force-stop` desliga a acessibilidade.** O Android desactiva o serviço quando a app é forçada a parar; a app avisa no transcript («a acessibilidade desligou-se»), mas não oferece um botão para religar | Acontece a quem «fecha» a app pelas definições; o agente perde olhos e dedos até o dono voltar às Definições |
| **P2** | **Conversas por modo.** Ao trocar de modo, a lista muda (cada motor tem o seu `$DSH_HOME`); quem tinha conversas no Shizuku não as vê no root | É por desenho, mas não está explicado no momento da troca; um aviso «as conversas deste modo são as que estão em …» evitava sustos |
| **P2** | **Reinstalar a app muda o uid** e o Magisk perde a política; o primeiro pedido de root volta a aparecer (e ao fim de 9 s pode ser negado) | É consequência do P0; a app podia detectar e explicar |

## Notas de método (para se poder repetir)

- A chave do provedor foi **escrita por stdin** nos motores depois do assistente (com uma chave de
  ensaio no passo 5), para a chave real não passar por `adb shell input text` nem ficar no histórico.
- O diálogo do Magisk foi observado; para continuar o ensaio, a política foi escrita com
  `magisk --sqlite` (o mesmo que o [`install-app.sh`](tools/install-app.sh#L76) faz) — equivale a
  tocar em **PERMITIR**.
- A acessibilidade foi ligada pelo caminho real (Definições → Acessibilidade → Cachalote →
  Permitir) no ensaio do root; nos seguintes foi ligada por `settings put` para não repetir os
  toques. Não apareceu o bloqueio de «definições restritas» nesta versão do Android.
- No ensaio do embutido, o `su` da app foi **negado** (`policy=1`) e o modo funcionou na mesma —
  é a prova de que não depende de root.
- Os motores originais do telefone (com as conversas) foram **guardados e repostos** no fim; a app
  ficou em Shizuku, como estava.

## Ficheiros tocados nesta ronda

- [`Assistente.kt`](app/app/src/main/java/dev/ricardopimentel/cachalote/Assistente.kt) — passo 2 sem
  «computador», passo 3 do embutido, passo 5 (chave) e passo 6 (notificações).
- [`DaemonController.kt`](app/app/src/main/java/dev/ricardopimentel/cachalote/DaemonController.kt) —
  `daemonInstalled` do embutido e comprimento da chave no Shizuku.
- [`MainViewModel.kt`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainViewModel.kt) —
  `refreshStatus` sem `su` no embutido, `agentInfo` após preparar/ligar.
- [`MainActivity.kt`](app/app/src/main/java/dev/ricardopimentel/cachalote/MainActivity.kt) — botões
  do Resumo por modo.
- Strings nos dois idiomas; `i18n-check` verde (467 chaves, 432 usadas).

**Testes:** `testDebugUnitTest` ✅ (67), `session-meta-test` ✅, `i18n-check` ✅. O ensaio de
dispositivo foi manual (uiautomator + capturas), não acrescentado aos scripts de CI — os scripts
`app-ui-test.sh` continuam a assumir o daemon root em `/data/adb`.
