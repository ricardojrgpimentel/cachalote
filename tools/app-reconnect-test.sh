#!/bin/bash
# app-reconnect-test.sh — regressão do "ligação aos solavancos" (STATUS §21): com o motor a
# reiniciar, um pedido feito na janela falhava com "a ligação ao motor caiu". Agora a app
# repete pedidos que a ponte recusou por o motor estar em baixo (nada foi executado, logo
# repetir é seguro) e reconecta sozinha no modo embutido.
#
# O que faz: escreve um prompt, **mata o motor** e envia logo a seguir — ou seja, apanha a
# janela de propósito, em vez de esperar que ela aconteça.
#
#   ./tools/app-reconnect-test.sh
set -uo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
# O dispositivo vem de --device/CACHALOTE_DEV ou é autodetectado (nunca fixo no repo).
. "$WS/tools/lib-device.sh"
DEV="$(cachalote_escolher_dispositivo)" || exit 1
PKG="dev.ricardopimentel.cachalote"
ADB=(adb -s "$DEV")
UI="$WS/.work/ui-reconnect.xml"
FALHAS=0
ok()   { echo "   OK    $1"; }
fail() { echo "   FALHA $1"; FALHAS=$((FALHAS + 1)); }

dump() { "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "${ADB[@]}" exec-out cat /sdcard/ui.xml > "$UI" 2>/dev/null; }
coords() { python3 "$WS/tools/ui.py" "$UI" find-exact "$1" 2>/dev/null || python3 "$WS/tools/ui.py" "$UI" find "$1" 2>/dev/null; }
tap() {
  local c
  for _ in 1 2 3; do
    dump
    c="$(coords "$1")" && break
    "${ADB[@]}" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
    sleep 2
  done
  [ -n "${c:-}" ] || { echo "   (não encontrei '$1')"; return 1; }
  set -- $c
  "${ADB[@]}" shell input tap "$1" "$2" >/dev/null 2>&1
  sleep 1.5
}
texto_do_ecra() { dump; python3 - "$UI" <<'PY'
import sys, xml.etree.ElementTree as ET
t = ET.parse(sys.argv[1])
for n in t.getroot().iter('node'):
    txt = (n.get('text') or '')
    if txt.strip():
        print(txt)
PY
}

echo "== 0. a app e o motor =="
"${ADB[@]}" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
sleep 4
MOTOR="$("${ADB[@]}" shell "su -c 'pgrep -f expose-internals | head -1'" | tr -d '\r')"
[ -n "$MOTOR" ] && ok "motor a correr (pid $MOTOR)" || { fail "não encontrei o motor"; echo "resumo: $FALHAS falhas"; exit 1; }

echo "== 1. preparar a conversa =="
tap "Conversa" || true
tap "Nova sessão" || true
sleep 2
tap "Escreve uma tarefa" || true
"${ADB[@]}" shell "input text 'responde apenas: ok-reconexao'" >/dev/null 2>&1
sleep 1
if "${ADB[@]}" shell "dumpsys input_method | grep -q mInputShown=true" 2>/dev/null; then
  "${ADB[@]}" shell input keyevent 4 >/dev/null 2>&1
  "${ADB[@]}" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
  sleep 2
fi
dump
if texto_do_ecra | grep -q "ok-reconexao"; then ok "prompt escrito no campo"; else fail "o prompt não ficou no campo"; fi

echo "== 2. matar o motor e enviar imediatamente =="
# As coordenadas do "Enviar" são calculadas ANTES de matar o motor: um `uiautomator dump`
# leva 1–2 s e isso deixava passar a janela (foi o que aconteceu à primeira).
dump
ENVIAR="$(coords "Enviar")"
[ -n "$ENVIAR" ] || { fail "não encontrei o botão Enviar"; echo "resumo: $FALHAS falhas"; exit 1; }
"${ADB[@]}" shell "su -c 'pkill -f expose-internals'" >/dev/null 2>&1
set -- $ENVIAR
"${ADB[@]}" shell input tap "$1" "$2" >/dev/null 2>&1
echo "   (motor morto e Enviar tocado em $1,$2 sem dump pelo meio)"

# Prova de que o caminho do retry foi mesmo exercitado (não é só "passou por sorte").
RETRY_LOG=0
( for _ in $(seq 1 20); do
    if "${ADB[@]}" logcat -d 2>/dev/null | grep -q "cachalote-reconexao"; then echo retry-visto; break; fi
    sleep 2
  done ) > .work/retry.log &
WATCH=$!

echo "== 3. a resposta chega? (é isto que falhava) =="
CHEGOU=0
for i in $(seq 1 30); do
  sleep 5
  T="$(texto_do_ecra)"
  if echo "$T" | grep -qE "ok-reconexao"; then
    if echo "$T" | grep -qiE "a ligação ao motor caiu|motor não está a correr"; then
      fail "apareceu o erro transitório no ecrã (ao fim de $((i*5))s)"
    else
      CHEGOU=1; ok "o prompt foi aceite durante o reinício (ao fim de $((i*5))s)"; break
    fi
  fi
done
[ "$CHEGOU" = 1 ] || fail "o prompt não foi aceite nem em 150s"
wait "$WATCH" 2>/dev/null || true
if grep -q retry-visto .work/retry.log; then
  ok "o retry transitório foi exercitado (log 'cachalote-reconexao')"
  RETRY_LOG=1
else
  echo "   (nota: o motor voltou antes de o pedido ser recusado — a janela não chegou a ser apanhada)"
fi

echo "== 4. o motor está de volta e são os mesmos dados =="
NOVO="$("${ADB[@]}" shell "su -c 'pgrep -f expose-internals | head -1'" | tr -d '\r')"
[ -n "$NOVO" ] && [ "$NOVO" != "$MOTOR" ] && ok "motor novo (pid $NOVO)" || fail "o motor não voltou (pid ${NOVO:-nenhum})"
HS="$("${ADB[@]}" shell "su -c 'CACHALOTE_DEV=$DEV /data/adb/cachalote/bin/cachalote status 2>/dev/null | grep -A1 handshake | tail -1'" | tr -d '\r')"
echo "$HS" | grep -q "OK deepseek-harness-acp" && ok "handshake do daemon: OK" || fail "handshake: ${HS:-?}"

echo
if [ "$FALHAS" = 0 ]; then echo "== resumo: tudo OK =="; else echo "== resumo: $FALHAS falhas =="; fi
exit "$FALHAS"
