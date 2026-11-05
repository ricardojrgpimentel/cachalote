#!/bin/bash
# app-e2e-test.sh — testes end-to-end da app, a correr contra o telefone ligado por USB.
#
#   ./tools/app-e2e-test.sh                 # todos os cenários que o telefone permitir
#   ./tools/app-e2e-test.sh --list          # o que existe, sem tocar no telefone
#   ./tools/app-e2e-test.sh --only conversa # um cenário (ou vários: --only conversa,aprovacao)
#   ./tools/app-e2e-test.sh --device SERIAL
#
# O que é preciso:
#   · a app instalada e o telefone autorizado em `adb devices`;
#   · para «conversa» e «aprovacao»: uma chave do provedor válida no daemon e rede (são turnos
#     reais do modelo; sem chave o cenário é SALTADO, não falha);
#   · para «motor» e «aprovacao»: modo root com `su` utilizável.
#
# O que o ensaio **faz** ao telefone (e como se limpa):
#   · traz a app para a frente e faz `am force-stop` no arranque frio (o Android desliga o serviço
#     de acessibilidade; a app avisa e tem o botão «Ligar»);
#   · cria conversas e apaga só as que nasceram no ensaio (fotografia em lib-device.sh);
#   · no «motor» chega a desligá-lo — um trap volta a ligá-lo mesmo que o teste morra a meio;
#   · não mexe na chave, no modo, nas capacidades nem no arranque com o telefone.
#
# As asserções são de UI (uiautomator) e de estado (daemon/ficheiros); os textos estão em pt-PT,
# como os restantes ensaios de dispositivo do repositório.
set -uo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
. "$WS/tools/lib-device.sh"
. "$WS/tools/lib-e2e.sh"

DEV=""
ONLY=""
PEDIDO_DEV=""

while [ $# -gt 0 ]; do
  case "$1" in
    --only)
      [ $# -ge 2 ] || { echo "falta o nome do cenário depois de --only" >&2; exit 2; }
      ONLY="$2"; shift 2 ;;
    --device)
      [ $# -ge 2 ] || { echo "falta o serial depois de --device" >&2; exit 2; }
      PEDIDO_DEV="$2"; shift 2 ;;
    --list)
      cat <<'TXT'
cenários:
  navegacao   arranque frio, abas, as 8 secções dos Ajustes e a busca (sem modelo)
  conversa    turno real, telemetria, lista, busca, fila, reinício da app e apagar (precisa de chave/rede)
  aprovacao   a guarda de caminhos: recusar não corre, autorizar corre — e fica no registo (idem)
  motor       reiniciar, desligar e ligar o motor pela UI, com a app a reconectar (precisa de root)
TXT
      exit 0
      ;;
    -h|--help)
      sed -n '2,23p' "$0"
      exit 0
      ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

# Validar --only antes de tocar no telefone: um nome mal escrito não pode passar por «0 testes».
if [ -n "$ONLY" ]; then
  for alvo in ${ONLY//,/ }; do
    case " navegacao conversa aprovacao motor " in
      *" $alvo "*) ;;
      *) echo "cenário desconhecido: $alvo (vê --list)" >&2; exit 2 ;;
    esac
  done
fi

DEV="$(cachalote_escolher_dispositivo "$PEDIDO_DEV")" || exit 1
ADB=(adb -s "$DEV")
BASE="$(cachalote_base "$DEV")"
MODO="$(cachalote_modo "$DEV")"
SE_ROOT=0
cachalote_tem_root "$DEV" && SE_ROOT=1

# Um trap só para o que não pode ficar pendurado: o motor parado e conversas de teste. Os cenários
# limpam-se normalmente no fim; isto é a rede de segurança para um Ctrl-C a meio.
e2e_fim() {
  e2e_repor_motor
  e2e_limpar_emergencia
}
trap e2e_fim EXIT

TOTAL_OK=0
TOTAL_FALHAS=0
TOTAL_SALTADOS=0

deve_correr() { # $1 = nome do cenário
  [ -z "$ONLY" ] && return 0
  case ",$ONLY," in
    *",$1,"*) return 0 ;;
  esac
  return 1
}

correr() { # $1 = nome, $2 = função
  local nome="$1" fn="$2"
  deve_correr "$nome" || return 0
  echo
  echo "############################################################################"
  echo "# $nome"
  echo "############################################################################"
  "$fn"
  TOTAL_OK=$((TOTAL_OK + E2E_OK))
  TOTAL_FALHAS=$((TOTAL_FALHAS + ${#E2E_FALHAS[@]}))
  TOTAL_SALTADOS=$((TOTAL_SALTADOS + E2E_SALTADOS))
}

# ---------------------------------------------------------------- cenários

# Sem modelo: prova que a app abre, que as três abas existem, que as oito secções dos Ajustes
# abrem e voltam ao menu, e que a busca filtra e limpa. É o ensaio que pode correr sempre.
cenario_navegacao() {
  e2e_init navegacao
  local secoes par nome marca i achou
  e2e_cold_start || e2e_fail "arranque frio da app"
  e2e_ver_has "Cachalote" "o ecrã principal abriu"
  e2e_ver_has "Conversa" "a aba Conversa existe"
  e2e_ver_has "Resumo" "a aba Resumo existe"
  e2e_ver_has "Ajustes" "a aba Ajustes existe"

  e2e_tab "Resumo" >/dev/null 2>&1 || e2e_fail "abrir o Resumo"
  e2e_ver_has "TURNOS E TEMPO" "o Resumo mostra os turnos"
  # O `LaunchedEffect(tab)` recarrega a lista ao chegar à aba; a primeira leitura pode apanhar
  # o ecrã ainda a carregar — espera-se pela busca (ou pelo estado vazio, que é diferente de
  # «ainda não sei»).
  e2e_tab "Conversa" >/dev/null 2>&1 || e2e_fail "voltar à Conversa"
  if e2e_wait_for "procurar nas conversas" 12; then
    e2e_ok "a lista de conversas tem busca"
  elif e2e_has "Ainda não há conversas"; then
    e2e_skip "lista vazia — não há busca para testar"
  else
    e2e_fail "a lista de conversas tem busca"
  fi

  # As oito linhas do menu, com um texto que só existe na secção respectiva. A ordem é a do menu.
  secoes=(
    "Motor|O MOTOR"
    "Chave do provedor|DEEPSEEK_API_KEY"
    "Capacidades autorizadas|Autorizar todas"
    "Acessibilidade|serviço"
    "Áreas permitidas do bash|Gravar e reiniciar"
    "Servidores MCP|Gravar MCP"
    "Ecrã virtual|DISPLAY_ID"
    "Backup e licenças|Exportar definições"
  )
  for par in "${secoes[@]}"; do
    nome="${par%%|*}"
    marca="${par#*|}"
    if e2e_abrir_secao "$nome"; then
      e2e_ok "abriu «${nome}»"
    else
      e2e_fail "abrir «${nome}»"
      continue
    fi
    e2e_ver_has "$marca" "«${nome}» mostra o conteúdo"
    e2e_back
    e2e_wait_for "módulos configurados" 10 || true
    if e2e_has "módulos configurados"; then
      e2e_ok "o voltar de «${nome}» regressa ao menu"
    else
      e2e_fail "o voltar de «${nome}» regressa ao menu"
      e2e_abrir_secao "Motor" >/dev/null 2>&1 || true
      e2e_back
    fi
  done

  # A busca: um termo que não existe tem de dar zero resultados e a limpeza tem de devolver a lista.
  e2e_tab "Conversa" >/dev/null 2>&1 || e2e_fail "voltar à lista para a busca"
  if e2e_wait_for "procurar nas conversas" 12; then
    e2e_tap "procurar nas conversas" >/dev/null 2>&1 || e2e_fail "tocar na busca"
    e2e_sh "input text 'zzz-e2e-sem-resultado'" >/dev/null 2>&1 || true
    e2e_hide_kb
    achou=1
    for i in 1 2 3 4 5 6 7 8; do
      e2e_dump || true
      if e2e_has "nenhuma conversa" || e2e_has "0 de"; then achou=0; break; fi
      sleep 1
    done
    e2e_ver_igual "$achou" 0 "a busca sem resultados mostra o estado vazio"
    e2e_limpar_busca
    e2e_ver_no "nenhuma conversa" "limpar a busca devolve a lista"
  else
    e2e_skip "a lista não mostrou a busca — nada para filtrar"
  fi

  e2e_verificar_crash
  e2e_resumo
}

# Um turno real + o que a app faz à volta dele: telemetria, prévia na lista, busca, fila,
# persistência depois de um force-stop e apagar a conversa.
cenario_conversa() {
  e2e_init conversa
  if ! e2e_precisa_modelo; then
    e2e_skip "um turno real precisa de chave e do motor a responder"
    e2e_resumo
    return 0
  fi
  e2e_sessoes_antes
  local marca="conv-$(date +%H%M%S)" antes depois fim i c
  e2e_cold_start || e2e_fail "arranque frio"
  e2e_ir_lista || e2e_fail "lista de conversas"
  antes="$(e2e_contagem_conversas)"
  echo "   (conversas antes: $antes)"

  e2e_novo_chat || e2e_fail "criar uma conversa nova"
  e2e_ver_has "Voltar às conversas" "a conversa abriu"
  e2e_enviar "Responde apenas com $marca e nao uses ferramentas." || e2e_fail "enviar o prompt"

  achou=1
  for i in $(seq 1 90); do
    e2e_dump || true
    c="$(e2e_count "$marca")"
    if [ "${c:-0}" -ge 2 ] && e2e_has "turno 1"; then achou=0; break; fi
    sleep 1
  done
  e2e_ver_igual "$achou" 0 "o agente respondeu com o marcador e o turno terminou"
  e2e_ver_has "AGENTE" "a resposta do agente está no transcript"
  e2e_ver_has "turno 1" "a telemetria do turno aparece na conversa"

  # Fila: escrever e juntar não chama o modelo; só prova que a tira e o estado mudam.
  e2e_escrever "tarefa fila $marca" || e2e_fail "escrever uma tarefa na fila"
  e2e_hide_kb
  e2e_tap "Juntar à fila" >/dev/null 2>&1 || e2e_fail "juntar a tarefa à fila"
  e2e_ver_has "1 na fila" "a fila mostra uma tarefa"
  e2e_tap "Remover esta tarefa da fila" >/dev/null 2>&1 || e2e_fail "remover a tarefa da fila"
  e2e_ver_no "1 na fila" "a fila voltou a ficar vazia"

  # De volta à lista: a conversa nova tem de aparecer **sem trocar de aba**. Era aqui que a app
  # falhava: `fecharConversa()` só mudava a flag e a lista só era relida pelo `LaunchedEffect(tab)`.
  e2e_ir_lista || e2e_fail "voltar à lista"
  e2e_wait_for "$marca" 4 || true
  e2e_ver_has "$marca" "a conversa nova aparece na lista sem trocar de aba"
  e2e_refresh_lista
  e2e_ver_has "$marca" "a conversa nova continua na lista depois de refrescar"
  depois="$(e2e_contagem_conversas)"
  e2e_ver_igual "$depois" "$((antes + 1))" "a lista tem mais uma conversa"

  # Busca pela marca: filtra para uma e limpa.
  e2e_tap "procurar nas conversas" >/dev/null 2>&1 || e2e_fail "tocar na busca"
  e2e_sh "input text '$marca'" >/dev/null 2>&1 || true
  e2e_hide_kb
  e2e_wait_for "1 de" 12 || true
  e2e_ver_has "1 de" "a busca filtra até uma conversa"
  e2e_limpar_busca

  # Persistência: force-stop, abrir de novo, a conversa e o histórico continuam lá.
  e2e_cold_start || e2e_fail "segundo arranque frio"
  e2e_ir_lista || e2e_fail "lista depois do reinício"
  e2e_refresh_lista
  e2e_ver_has "$marca" "a conversa sobreviveu ao reinício da app"
  if e2e_abrir_conversa_pelo_marcador "$marca"; then
    e2e_ok "a conversa reabriu depois do reinício"
  else
    e2e_fail "a conversa reabriu depois do reinício"
  fi
  e2e_ver_has "turno 1" "o histórico mostra o turno anterior"
  c="$(e2e_count "$marca")"
  if [ "${c:-0}" -ge 2 ]; then
    e2e_ok "o histórico tem o pedido e a resposta"
  else
    e2e_fail "o histórico tem o pedido e a resposta (ocorrências: ${c:-0})"
  fi

  # O Resumo lê a telemetria do registo da sessão (o ACP não a envia).
  e2e_back
  e2e_tab "Resumo" >/dev/null 2>&1 || e2e_fail "abrir o Resumo"
  e2e_wait_for "TURNOS E TEMPO" 15 || true
  e2e_ver_has "TURNOS E TEMPO" "o Resumo mostra os turnos"
  e2e_ver_has "turno 1" "o Resumo mostra o turno desta conversa"

  # Apagar pela lista: toque longo, confirmar, e a lista volta ao que era.
  if e2e_apagar_conversa_pelo_marcador "$marca"; then
    e2e_ok "apagar a conversa pelo diálogo"
  else
    e2e_fail "apagar a conversa pelo diálogo"
  fi
  e2e_refresh_lista
  e2e_limpar_busca
  e2e_ver_no "$marca" "a conversa apagada desapareceu da lista"
  fim="$(e2e_contagem_conversas)"
  e2e_ver_igual "$fim" "$antes" "a lista voltou à contagem inicial"

  e2e_limpar_sessoes
  e2e_verificar_crash
  e2e_resumo
}

# A guarda de caminhos: um comando fora das áreas permitidas tem de pedir autorização. Recusar
# não pode criar o ficheiro; autorizar (as duas folhas: caminho e escalonamento do sandbox) cria.
cenario_aprovacao() {
  e2e_init aprovacao
  if ! e2e_precisa_modelo || ! e2e_precisa_daemon_root; then
    e2e_skip "a guarda de caminhos precisa de chave, rede e root"
    e2e_resumo
    return 0
  fi
  e2e_sessoes_antes
  local marca="aprov-$(date +%H%M%S)"
  local arq="/sdcard/Download/cachalote-e2e-$marca.txt"
  local i achou tem
  e2e_su "rm -f $arq" >/dev/null 2>&1 || true
  e2e_cold_start || e2e_fail "arranque frio"
  e2e_ir_lista || e2e_fail "lista de conversas"
  e2e_novo_chat || e2e_fail "criar a conversa do ensaio"
  e2e_enviar "touch $arq" || e2e_fail "enviar o comando fora da área"

  if e2e_esperar_aprovacao; then
    e2e_ok "o pedido de autorização apareceu"
  else
    e2e_fail "o pedido de autorização apareceu"
  fi
  e2e_ver_has "Fora da área permitida" "o painel diz que o caminho está fora da área"
  e2e_ver_has "$marca" "o painel mostra o caminho exacto"

  # 1) Recusar. Podem aparecer mais do que uma folha (o agente tenta alternativas): recusa-se
  #    todas até o turno acabar. O ficheiro não pode existir no fim.
  e2e_aprovar recusar 12 || e2e_fail "responder «Recusar» ao pedido"
  e2e_wait_for "turno 1" 30 || true
  tem="$(e2e_limpar "$(e2e_su "test -f $arq && echo existe || echo ausente")")"
  e2e_ver_igual "$tem" "ausente" "recusar não criou o ficheiro fora da área"

  # 2) Autorizar. A mesma operação volta a pedir; a primeira folha é a guarda de caminhos e a
  #    segunda é o escalonamento do sandbox. `e2e_aprovar` responde às duas.
  e2e_enviar "touch $arq" || e2e_fail "enviar outra vez o comando fora da área"
  if e2e_esperar_aprovacao; then
    e2e_ok "o segundo pedido de autorização apareceu"
  else
    e2e_fail "o segundo pedido de autorização apareceu"
  fi
  e2e_aprovar permitir 10 || e2e_fail "responder «Permitir uma vez» ao pedido"
  tem=1
  for i in $(seq 1 20); do
    if [ "$(e2e_limpar "$(e2e_su "test -f $arq && echo existe || echo ausente")")" = "existe" ]; then
      tem=0
      break
    fi
    sleep 1
  done
  e2e_ver_igual "$tem" 0 "autorizar criou o ficheiro fora da área"

  # O pedido fica no registo de auditoria da app (é a promessa do painel: «este pedido fica no
  # registo do dispositivo»).
  e2e_ir_lista || true
  if e2e_abrir_secao "Capacidades autorizadas"; then
    achou=1
    for i in 1 2 3 4 5 6; do
      e2e_dump || true
      if e2e_has "$marca"; then achou=0; break; fi
      e2e_scroll
    done
    e2e_ver_igual "$achou" 0 "o pedido ficou no registo de auditoria"
  else
    e2e_fail "abrir as Capacidades para ler o registo"
  fi

  e2e_su "rm -f $arq" >/dev/null 2>&1 || true
  e2e_limpar_sessoes
  e2e_verificar_crash
  e2e_resumo
}

# O motor pela UI: reiniciar (o processo muda e a app volta a ligar-se), desligar (a app mostra
# «Ligar o motor») e ligar outra vez. O trap do runner garante que o motor nunca fica parado.
cenario_motor() {
  e2e_init motor
  if ! e2e_precisa_daemon_root; then
    e2e_skip "o ensaio do motor precisa do modo root e de su"
    e2e_resumo
    return 0
  fi
  local pid1 pid2 i
  e2e_cold_start || e2e_fail "arranque frio"
  e2e_abrir_secao "Motor" >/dev/null 2>&1 || e2e_fail "abrir Ajustes → Motor"
  e2e_wait_for "O MOTOR" 15 || true
  e2e_ver_has "O MOTOR" "a secção do motor abriu"

  pid1="$(e2e_limpar "$(e2e_su "pgrep -f expose-internals | head -1")")"
  e2e_ver_igual "$([ -n "$pid1" ] && echo sim || echo nao)" "sim" "o motor está a correr antes do ensaio"

  e2e_tap_exact "Reiniciar motor" >/dev/null 2>&1 || e2e_fail "tocar em Reiniciar motor"
  pid2=""
  for i in $(seq 1 30); do
    pid2="$(e2e_limpar "$(e2e_su "pgrep -f expose-internals | head -1")")"
    if [ -n "$pid2" ] && [ "$pid2" != "$pid1" ]; then break; fi
    sleep 1
  done
  e2e_ver_igual "$([ -n "$pid2" ] && [ "$pid2" != "$pid1" ] && echo novo || echo igual)" "novo" "reiniciar trocou o processo do motor"
  e2e_wait_for "motor DSH no telefone · ligado" 30 || true
  e2e_ver_has "motor DSH no telefone · ligado" "a app voltou a ligar-se depois de reiniciar"
  if e2e_su "$BASE/bin/cachalote status" | grep -q "OK deepseek-harness-acp"; then
    e2e_ok "o handshake do daemon continua bom"
  else
    e2e_fail "o handshake do daemon continua bom"
  fi

  # Desligar. Marca-se antes de tocar: se o ensaio morrer aqui, o trap liga-o outra vez.
  E2E_MOTOR_PARADO=1
  e2e_tap_exact "Desligar" >/dev/null 2>&1 || e2e_fail "tocar em Desligar o motor"
  # A prova de ponta a ponta é a app: quando perde o motor, o botão passa a «Ligar o motor».
  # Só depois se confirma o daemon — o contrário já deu um falso negativo (o status foi lido
  # enquanto o cliente ainda se estava a desligar).
  e2e_wait_for "Ligar o motor" 30 || true
  e2e_ver_has "Ligar o motor" "a app percebeu que o motor parou e oferece Ligar"
  local parado=1 estado
  for i in $(seq 1 15); do
    estado="$(e2e_su "$BASE/bin/cachalote status")"
    case "$estado" in
      *"daemon : PARADO"*) parado=0; break ;;
    esac
    sleep 1
  done
  if [ "$parado" = 0 ]; then
    e2e_ok "o daemon ficou parado"
  else
    e2e_fail "o daemon ficou parado (o status diz: $(printf '%s' "$estado" | grep -m1 '^daemon' || echo '?'))"
  fi

  e2e_tap_exact "Ligar" >/dev/null 2>&1 || e2e_fail "tocar em Ligar o motor"
  E2E_MOTOR_PARADO=0
  for i in $(seq 1 40); do
    if e2e_su "$BASE/bin/cachalote status" | grep -q "daemon : a correr"; then break; fi
    sleep 1
  done
  if e2e_su "$BASE/bin/cachalote status" | grep -q "OK deepseek-harness-acp"; then
    e2e_ok "o motor voltou e responde ao handshake"
  else
    e2e_fail "o motor voltou e responde ao handshake"
  fi
  e2e_wait_for "motor DSH no telefone · ligado" 30 || true
  e2e_ver_has "motor DSH no telefone · ligado" "a app voltou a mostrar o motor ligado"

  e2e_ir_lista >/dev/null 2>&1 || true
  e2e_verificar_crash
  e2e_resumo
}

# ---------------------------------------------------------------- arranque
echo "== dispositivo: $DEV · modo: ${MODO:-?} · root: $([ "$SE_ROOT" = 1 ] && echo sim || echo nao) =="
echo "== app: $E2E_PKG =="

correr navegacao cenario_navegacao
correr conversa cenario_conversa
correr aprovacao cenario_aprovacao
correr motor cenario_motor

echo
echo "============================================================================"
echo "== TOTAL: $TOTAL_OK ok, $TOTAL_FALHAS falhas, $TOTAL_SALTADOS saltados =="
echo "============================================================================"
[ "$TOTAL_FALHAS" -eq 0 ]
