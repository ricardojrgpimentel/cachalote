#!/bin/bash
# mock-e2e-test.sh — ensaio E2E contra o daemon ACP falso, sem AI, chave ou rede.
#
#   ./tools/mock-e2e-test.sh --device emulator-5554
#
# Requer um **emulador** (não toca no telefone) e a app de **debug** instalada, porque escreve as
# preferências da app para a apontar ao mock (porta 39732, token de teste). O trap repõe as
# preferências originais no fim, mesmo a falhar.
#
# O mock corre no computador e chega ao telefone por `adb reverse`. Não substitui o daemon real:
# é um contrato guionado para percorrer a UI (turno, ferramenta, aprovação e erro) sempre igual.
set -uo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
. "$WS/tools/lib-device.sh"
PORT=39732
TOKEN="mock-$(printf 'a%.0s' $(seq 1 64))"
PKG="${CACHALOTE_PKG:-dev.ricardopimentel.cachalote}"
ADB=()
DEV=""
PEDIDO_DEV=""
MOCK_PID=""
DIR="$WS/.work/mock-e2e"
BACKUP="$DIR/prefs-original.xml"
PREF_FILE="shared_prefs/cachalote.xml"
FALHAS=0
OK=0
INICIADO=0

while [ $# -gt 0 ]; do
  case "$1" in
    --device) PEDIDO_DEV="$2"; shift 2 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done
DEV="$(cachalote_escolher_dispositivo "$PEDIDO_DEV")" || exit 1
case "$DEV" in
  emulator-*) ;;
  *) echo "ERRO: este ensaio só corre num emulador (recebi $DEV); não quero mexer nas preferências do telefone." >&2; exit 2 ;;
esac
ADB=(adb -s "$DEV")
mkdir -p "$DIR"

ok()   { echo "   OK     $1"; OK=$((OK + 1)); }
fail() { echo "   FALHA  $1"; FALHAS=$((FALHAS + 1)); }
check() { if [ "$1" = 0 ]; then ok "$2"; else fail "$2"; fi; }

limpar() {
  "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  "${ADB[@]}" reverse --remove "tcp:$PORT" >/dev/null 2>&1 || true
  if [ -n "$MOCK_PID" ]; then kill "$MOCK_PID" 2>/dev/null || true; wait "$MOCK_PID" 2>/dev/null || true; fi
  if [ -f "$BACKUP" ]; then
    "${ADB[@]}" push "$BACKUP" /data/local/tmp/mock-prefs-orig.xml >/dev/null 2>&1 || true
    "${ADB[@]}" shell "run-as $PKG cp /data/local/tmp/mock-prefs-orig.xml $PREF_FILE" >/dev/null 2>&1 || true
  fi
  if [ "$INICIADO" = 1 ]; then
    "${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  fi
}
trap limpar EXIT

# O mock escreve as respostas guionadas; a UI é lida por uiautomator.
. "$WS/tools/lib-e2e.sh"
E2E_PKG="$PKG"
E2E_ACT="$PKG/.MainActivity"

echo "== preparar a app de debug ($DEV) =="
if ! "${ADB[@]}" shell "run-as $PKG id" >/dev/null 2>&1; then
  echo "ERRO: a app instalada não é debug (run-as falhou). Instala a APK de debug neste emulador." >&2
  exit 2
fi
"${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
"${ADB[@]}" exec-out "run-as $PKG cat $PREF_FILE" > "$BACKUP" 2>/dev/null || true
[ -s "$BACKUP" ] || printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map></map>' > "$BACKUP"
# Estado limpo: sem o instantâneo da sessão anterior, a lista não pode abrir na conversa antiga.
# As preferências originais ficam em $BACKUP e voltam no trap.
"${ADB[@]}" shell pm clear "$PKG" >/dev/null 2>&1 || true

python3 - "$BACKUP" "$DIR/prefs-mock.xml" "$PORT" "$TOKEN" <<'PY'
import sys, xml.etree.ElementTree as ET
orig, dst, porta, token = sys.argv[1:5]
t = ET.parse(orig); root = t.getroot()
def rm(n):
    for e in list(root):
        if e.get("name") == n: root.remove(e)
def s(n, v):
    rm(n); e = ET.SubElement(root, "string", {"name": n}); e.text = v
def i(n, v):
    rm(n); ET.SubElement(root, "int", {"name": n, "value": str(v)})
def b(n, v):
    rm(n); ET.SubElement(root, "boolean", {"name": n, "value": "true" if v else "false"})
s("mode", "daemon"); b("wizardConcluido", True); b("modoEscolhido", True)
b("motorParado", False); b("a11yJaEsteveLigada", False)
# O ecrã virtual é local da app; o ensaio autoriza as quatro capacidades para o poder iniciar.
for cap in ("vscreen_start", "vscreen_status", "vscreen_frame", "vscreen_stop"):
    b("cap_" + cap, True)
i("port", int(porta)); s("token", token)
t.write(dst, encoding="utf-8", xml_declaration=True)
PY

"${ADB[@]}" push "$DIR/prefs-mock.xml" /data/local/tmp/mock-prefs.xml >/dev/null
"${ADB[@]}" shell "run-as $PKG mkdir -p shared_prefs; run-as $PKG cp /data/local/tmp/mock-prefs.xml $PREF_FILE" >/dev/null
"${ADB[@]}" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null

echo "== mock ACP =="
node "$WS/tools/mock-acp-daemon.cjs" --port "$PORT" --token "$TOKEN" --verbose > "$DIR/mock.log" 2>&1 &
MOCK_PID=$!
for _ in $(seq 1 20); do
  python3 - "$PORT" <<'PY' >/dev/null 2>&1 && break
import socket, sys
s = socket.create_connection(("127.0.0.1", int(sys.argv[1])), 1); s.close()
PY
  sleep 0.2
done
if ! kill -0 "$MOCK_PID" 2>/dev/null; then echo "ERRO: o mock não arrancou (ver $DIR/mock.log)"; exit 1; fi
ok "mock à escuta em $PORT"

INICIADO=1
"${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null
e2e_init mock
for _ in $(seq 1 20); do e2e_dump && break; sleep 1; done

# A UI do emulador pode estar em inglês ou pt-PT; os textos da app mudam, os do mock não.
EN=0; e2e_has "Chat" && EN=1
t() { if [ "$EN" = 1 ]; then echo "$2"; else echo "$1"; fi; }
escrever() {
  e2e_tap "$INPUT" >/dev/null 2>&1 || return 1
  e2e_sh "input text '${1// /%s}'" >/dev/null 2>&1 || return 1
  sleep 0.8
}
CHAT="$(t "Conversa" "Chat")"
NOVA="$(t "Nova conversa" "New conversation")"
INPUT="$(t "Escreve uma tarefa" "Write a task")"
ENVIAR="$(t "Enviar" "Send")"
VOLTAR="$(t "Voltar às conversas" "Back to the conversations")"
PERMITIR="$(t "Permitir uma vez" "Allow once")"
RECUSAR="$(t "Recusar" "Reject")"
CONFIRMAR="$(t "Confirmar" "Confirm")"
MAIS="$(t "Mais opções" "More options")"
ESFORCO="$(t "Esforço de raciocínio" "Reasoning effort")"
VOLTAR_LISTA="$(t "Voltar às conversas" "Back to the conversations")"
APAGAR="$(t "Apagar" "Delete")"
APAGAR_CONV="$(t "Apagar conversa" "Delete conversation")"
JUNTAR="$(t "Juntar à fila" "Add to queue")"
CORRER="$(t "Correr a fila" "Run the queue")"

echo "== ligação e lista =="
if e2e_wait_for "$(t "motor DSH no telefone · ligado" "DSH engine on the phone · connected")" 20; then
  ok "a app ligou-se ao mock"
else
  fail "a app ligou-se ao mock"
fi
if e2e_wait_for "$(t "Ainda não há conversas" "No conversations yet")" 10; then
  ok "a lista começa vazia (vem do mock)"
else
  fail "a lista começa vazia (vem do mock)"
fi

echo "== conversa guionada =="
e2e_tap "$NOVA" >/dev/null 2>&1 || fail "criar conversa"
e2e_wait_for "mock-1" 15 || true
e2e_ver_has "mock-1" "o mock criou a sessão"
escrever "ola mock" || fail "escrever o prompt"
e2e_hide_kb
e2e_tap_exact "$ENVIAR" >/dev/null 2>&1 || e2e_tap "$ENVIAR" >/dev/null 2>&1 || fail "enviar"
if e2e_wait_for "Olá do mock" 20; then ok "a resposta guionada apareceu"; else fail "a resposta guionada apareceu"; fi
e2e_ver_has "bash" "o cartão da ferramenta apareceu"
e2e_ver_has "$(t "turno 1" "turn 1")" "a telemetria do turno apareceu"
e2e_ver_has "ctx" "o consumo de contexto apareceu"

echo "== escolha de esforço (configOptions) =="
e2e_tap "$MAIS" >/dev/null 2>&1 || fail "abrir o menu da conversa"
e2e_tap_exact "$ESFORCO" >/dev/null 2>&1 || e2e_tap "$ESFORCO" >/dev/null 2>&1 || fail "abrir o esforço"
if e2e_wait_for "High" 15; then ok "o picker mostra as opções do mock"; else fail "o picker mostra as opções do mock"; fi
e2e_tap_exact "High" >/dev/null 2>&1 || e2e_tap_exact "Alto" >/dev/null 2>&1 || fail "escolher High"
if e2e_wait_for "reasoning_effort = high" 10; then ok "a escolha foi aplicada (e o mock a recebeu)"; else fail "a escolha foi aplicada (e o mock a recebeu)"; fi

echo "== aprovação guionada =="
escrever "aprova mock" || fail "escrever o segundo prompt"
e2e_hide_kb
e2e_tap_exact "$ENVIAR" >/dev/null 2>&1 || e2e_tap "$ENVIAR" >/dev/null 2>&1 || fail "enviar o segundo prompt"
if e2e_wait_for "$(t "Autorização" "Authorisation")" 20; then ok "o painel de autorização apareceu"; else fail "o painel de autorização apareceu"; fi
e2e_tap_exact "$RECUSAR" >/dev/null 2>&1 || e2e_tap "$RECUSAR" >/dev/null 2>&1 || fail "escolher Recusar"
e2e_tap_exact "$CONFIRMAR" >/dev/null 2>&1 || fail "confirmar a recusa"
# O teclado pode ter ficado aberto e a resposta nasce abaixo da dobra; sem o fechar, o
# uiautomator só vê o cartão da ferramenta e o ensaio diz que a app não respondeu.
e2e_hide_kb
sleep 1
# O teclado pode tapar a parte de baixo: rola-se **dentro da conversa** (acima do teclado),
# não no sítio onde o `e2e_scroll` genérico passa.
e2e_sh "input swipe $((E2E_W / 2)) $((E2E_H * 38 / 100)) $((E2E_W / 2)) $((E2E_H * 16 / 100)) 250" >/dev/null 2>&1 || true
sleep 1
if e2e_wait_for "Pedido recusado pelo mock." 20; then
  ok "o mock recebeu a recusa"
else
  fail "o mock recebeu a recusa"
  e2e_screenshot "falha-aprovacao"
  cp "$E2E_UI" "$DIR/falha-aprovacao.xml" 2>/dev/null || true
fi
# A app tem de dizer que recusou — antes dizia sempre «autorizado», mesmo com «Recusar».
if e2e_wait_for "pedido recusado" 5; then ok "o transcript diz que o pedido foi recusado"; else fail "o transcript diz que o pedido foi recusado"; fi

echo "== aprovação guionada: permitir =="
escrever "aprova mock" || fail "escrever o prompt de permitir"
e2e_hide_kb
e2e_tap_exact "$ENVIAR" >/dev/null 2>&1 || e2e_tap "$ENVIAR" >/dev/null 2>&1 || fail "enviar o prompt de permitir"
if e2e_wait_for "$(t "Autorização" "Authorisation")" 20; then ok "o segundo painel de autorização apareceu"; else fail "o segundo painel de autorização apareceu"; fi
e2e_tap_exact "$PERMITIR" >/dev/null 2>&1 || e2e_tap "$PERMITIR" >/dev/null 2>&1 || fail "escolher Permitir uma vez"
e2e_tap_exact "$CONFIRMAR" >/dev/null 2>&1 || fail "confirmar a autorização"
e2e_hide_kb
sleep 1
e2e_sh "input swipe $((E2E_W / 2)) $((E2E_H * 38 / 100)) $((E2E_W / 2)) $((E2E_H * 16 / 100)) 250" >/dev/null 2>&1 || true
sleep 1
if e2e_wait_for "Pedido autorizado pelo mock." 20; then ok "o mock recebeu a autorização"; else fail "o mock recebeu a autorização"; fi
if e2e_wait_for "autorizado" 5; then ok "o transcript diz que o pedido foi autorizado"; else fail "o transcript diz que o pedido foi autorizado"; fi

echo "== fila a correr =="
escrever "tarefa um" || fail "escrever a primeira tarefa"
e2e_hide_kb
e2e_tap "$JUNTAR" >/dev/null 2>&1 || fail "juntar a primeira tarefa"
escrever "tarefa dois" || fail "escrever a segunda tarefa"
e2e_hide_kb
e2e_tap "$JUNTAR" >/dev/null 2>&1 || fail "juntar a segunda tarefa"
if e2e_wait_for "$(t "2 na fila" "2 in the queue")" 10; then ok "a fila tem duas tarefas"; else fail "a fila tem duas tarefas"; fi
e2e_tap "$CORRER" >/dev/null 2>&1 || fail "correr a fila"
if e2e_wait_for "tarefa dois" 25; then ok "a fila correu até à segunda tarefa"; else fail "a fila correu até à segunda tarefa"; fi
if e2e_wait_for "$(t "fila vazia" "queue empty")" 15; then ok "as tarefas ficaram concluídas"; else fail "as tarefas ficaram concluídas"; fi

echo "== erro guionado =="
escrever "erro mock" || fail "escrever o terceiro prompt"
e2e_hide_kb
e2e_tap_exact "$ENVIAR" >/dev/null 2>&1 || e2e_tap "$ENVIAR" >/dev/null 2>&1 || fail "enviar o terceiro prompt"
if e2e_wait_for "erro simulado pelo mock" 15; then ok "o erro do mock chegou à UI"; else fail "o erro do mock chegou à UI"; fi

echo "== histórico e apagar =="
e2e_tap "$VOLTAR_LISTA" >/dev/null 2>&1 || e2e_tap_exact "←" >/dev/null 2>&1 || fail "voltar à lista"
e2e_wait_for "ola mock" 10 || true
e2e_ver_has "ola mock" "a conversa aparece na lista"
e2e_tap_exact "ola mock" >/dev/null 2>&1 || e2e_tap "ola mock" >/dev/null 2>&1 || fail "reabrir a conversa"
if e2e_wait_for "tarefa dois" 15; then ok "o histórico abriu no último turno (fila)"; else fail "o histórico abriu no último turno (fila)"; fi
if e2e_wait_for "Olá do mock" 10; then ok "o histórico inclui a resposta do mock"; else fail "o histórico inclui a resposta do mock"; fi

e2e_tap "$VOLTAR_LISTA" >/dev/null 2>&1 || fail "voltar à lista para apagar"
e2e_wait_for "ola mock" 10 || true
C="$(e2e_coords "ola mock")" || C=""
if [ -n "$C" ]; then
  set -- $C
  e2e_sh "input swipe $1 $2 $1 $2 900" >/dev/null 2>&1 || true
  sleep 1
  if e2e_wait_for "$APAGAR_CONV" 10; then ok "o diálogo de apagar apareceu"; else fail "o diálogo de apagar apareceu"; fi
  e2e_tap_exact "$APAGAR" >/dev/null 2>&1 || fail "confirmar o apagar"
  if e2e_wait_for "$(t "Ainda não há conversas" "No conversations yet")" 15; then
    ok "a conversa desapareceu (mock apagou)"
  else
    fail "a conversa desapareceu (mock apagou)"
  fi
else
  fail "encontrar a conversa para apagar"
fi

echo "== ecrã virtual =="
e2e_tap_exact "$(t "Ajustes" "Settings")" >/dev/null 2>&1 || e2e_tap "$(t "Ajustes" "Settings")" >/dev/null 2>&1 || fail "abrir os Ajustes"
e2e_tap_exact "$(t "Ecrã virtual" "Virtual screen")" >/dev/null 2>&1 || e2e_tap "$(t "Ecrã virtual" "Virtual screen")" >/dev/null 2>&1 || fail "abrir o Ecrã virtual"
e2e_tap_exact "$(t "Iniciar" "Start")" >/dev/null 2>&1 || fail "iniciar o ecrã virtual"
if e2e_wait_for "display_id=" 20; then ok "o ecrã virtual iniciou com um display"; else fail "o ecrã virtual iniciou com um display"; fi
e2e_tap_exact "$(t "Parar" "Stop")" >/dev/null 2>&1 || fail "parar o ecrã virtual"
if e2e_wait_for "$(t "parado" "stopped")" 10; then ok "o ecrã virtual parou"; else fail "o ecrã virtual parou"; fi

echo
echo "== mock e2e: $OK ok, $FALHAS falhas =="
[ "$FALHAS" -eq 0 ]
