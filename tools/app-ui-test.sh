#!/bin/bash
# app-ui-test.sh — testa a app instalada por adb (uiautomator + input), sem tocar no ecrã.
#
# Valida o percurso completo: estado do daemon → obter token (root) → colar a chave
# no ecrã da app → guardar no daemon → conferir no ficheiro → remover → conversa.
#
#   ./tools/app-ui-test.sh [--keep-key] [--device SERIAL]
#
# Usa uma chave de TESTE (sk-dummy-…) e limpa-a no fim, salvo --keep-key.
set -uo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
DEV="${CACHALOTE_DEV:-R58M55JNP6X}"
PKG="dev.ricardopimentel.cachalote"
ACT="$PKG/.MainActivity"
BASE="/data/adb/cachalote"
DUMMY="sk-dummy-app-ui-1234567890"
KEEP=0
VAR="DEEPSEEK_API_KEY"
UI="$WS/.work/ui.xml"

while [ $# -gt 0 ]; do
  case "$1" in
    --keep-key) KEEP=1; shift ;;
    --device) DEV="$2"; shift 2 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done
ADB=(adb -s "$DEV")
ok=0; falhas=()

pass() { echo "   OK    $1"; ok=$((ok+1)); }
fail() { echo "   FALHA $1"; falhas+=("$1"); }
check() { if [ "$1" = 0 ]; then pass "$2"; else fail "$2"; fi; }

SHOTN=0
dump() {
  SHOTN=$((SHOTN+1))
  "${ADB[@]}" exec-out screencap -p > "$WS/.work/ui-test-$SHOTN.png" 2>/dev/null
  "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "${ADB[@]}" exec-out cat /sdcard/ui.xml > "$UI" 2>/dev/null
  [ -s "$UI" ]
}
hide_kb() { "${ADB[@]}" shell input keyevent 4 >/dev/null 2>&1; sleep 0.6; }

coords() { python3 "$WS/tools/ui.py" "$UI" find "$1" 2>/dev/null; }
tap_text() {
  local c; c="$(coords "$1")" || { echo "   (não encontrei '$1' no ecrã)"; return 1; }
  set -- $c
  "${ADB[@]}" shell input tap "$1" "$2" >/dev/null 2>&1
  sleep 1.2
}
has() { python3 "$WS/tools/ui.py" "$UI" exists "$1" 2>/dev/null; }

# ⚠ SEGURANÇA: este teste grava (e remove) uma chave de teste no daemon. Se já existir
# uma chave real, guardamo-la e repomo-la no fim — nunca deixar o utilizador sem chave.
HAD_KEY=0
if "${ADB[@]}" shell "su -c 'grep -q \"^$VAR=\" $BASE/home/.env'" 2>/dev/null; then
  HAD_KEY=1
  "${ADB[@]}" shell "su -c 'cp -f $BASE/home/.env $BASE/home/.env.bak && chmod 600 $BASE/home/.env.bak'"
  echo "(chave existente guardada em $BASE/home/.env.bak — será reposta no fim)"
fi
restore_key() {
  if [ "$HAD_KEY" = 1 ]; then
    echo "== a repor a chave que já existia =="
    "${ADB[@]}" shell "su -c 'cp -f $BASE/home/.env.bak $BASE/home/.env; chmod 600 $BASE/home/.env; rm -f $BASE/home/.env.bak; $BASE/bin/cachalote restart >/dev/null 2>&1'"
    N=$("${ADB[@]}" shell "su -c 'grep -c \"^$VAR=\" $BASE/home/.env'" 2>/dev/null | tr -d '\r\n ')
    [ "${N:-0}" = "1" ] && echo "   OK  chave reposta e motor reiniciado" || echo "   FALHA  não consegui repor a chave (ver $BASE/home/.env.bak)"
  fi
}
trap restore_key EXIT

echo "== 1. app instalada e aberta =="
"${ADB[@]}" shell "am force-stop $PKG; am start -n $ACT" >/dev/null 2>&1
sleep 4
dump && has "Cachalote" && check 0 "ecrã principal abriu" || check 1 "ecrã principal abriu"

echo "== 2. estado do daemon =="
tap_text "Atualizar" || true
sleep 2; dump
has "daemon" && check 0 "estado do daemon visível" || check 1 "estado do daemon visível"
python3 "$WS/tools/ui.py" "$UI" find "daemon" >/dev/null 2>&1 && echo "   (info: $(python3 "$WS/tools/ui.py" "$UI" list | grep -i '"daemon"' | head -1 | cut -c1-90))"

echo "== 3. obter token (root) =="
tap_text "Obter token" || true
sleep 5; dump
has "obtido" && check 0 "token obtido" || check 1 "token obtido"

echo "== 4. ecrã da chave: colar e guardar =="
tap_text "Chave" || true
sleep 1; dump
tap_text "DEEPSEEK_API_KEY" || true
"${ADB[@]}" shell input text "$DUMMY" >/dev/null 2>&1
sleep 1; hide_kb; dump
tap_text "Guardar no daemon" || true
sleep 3; dump
echo "   (o daemon reinicia o motor ao gravar a chave; a dar-lhe tempo)"
for i in $(seq 1 12); do
  OKH="$("${ADB[@]}" shell "su -c '$BASE/bin/cachalote status'" 2>/dev/null | grep -c 'OK deepseek-harness-acp' || true)"
  [ "${OKH:-0}" -ge 1 ] && break
  sleep 3
done

echo "== 5. confirmar no daemon (ficheiro home/.env) =="
ENV_OUT="$("${ADB[@]}" shell "su -c 'cat $BASE/home/.env'" 2>/dev/null | tr -d '\r')"
if echo "$ENV_OUT" | grep -q "^DEEPSEEK_API_KEY=$DUMMY$"; then
  check 0 "o daemon gravou a chave de teste no .env"
else
  check 1 "o daemon gravou a chave de teste no .env (obtido: ${ENV_OUT:0:40}…)"
fi
PERM="$("${ADB[@]}" shell "su -c 'stat -c %a $BASE/home/.env'" 2>/dev/null | tr -d '\r')"
[ "$PERM" = "600" ] && check 0 "modo do .env é 600" || check 1 "modo do .env é 600 (obtido: $PERM)"
dump; has "presente" && check 0 "a app mostra a chave como presente" || check 1 "a app mostra a chave como presente"

echo "== 6. conversa: nova sessão + prompt (sem chave válida → erro esperado do modelo) =="
tap_text "Conversa" || true
sleep 1; dump
tap_text "Nova sessão" || true
sleep 6; dump
has "sessão" && check 0 "sessão criada pela app" || check 1 "sessão criada pela app"

echo "   → a enviar um prompt pelo ecrã da app"
tap_text "Escreve uma tarefa" || tap_text "tarefa" || true
"${ADB[@]}" shell input text "diz%sapenas:%sok" >/dev/null 2>&1
sleep 1; hide_kb; dump
tap_text "Enviar" || true
sleep 12; dump
if has "Authentication Fails" || has "api key"; then
  check 0 "o prompt chegou à API (resposta de autenticação visível no transcript)"
elif has "ok"; then
  check 0 "o agente respondeu no transcript"
else
  check 1 "resposta do agente visível no transcript"
  echo "   (últimos textos: $(python3 "$WS/tools/ui.py" "$UI" list | tail -4 | tr '\n' ' ' | cut -c1-160))"
fi

echo "== 7. limpeza =="
if [ "$KEEP" = 0 ]; then
  tap_text "Chave" || true
  sleep 1; dump
  tap_text "Remover chave" || true
  sleep 2
  RESTA="$("${ADB[@]}" shell "su -c 'grep -c DEEPSEEK_API_KEY $BASE/home/.env 2>/dev/null'" 2>/dev/null | tr -d '\r\n ')"
  RESTA="${RESTA:-0}"
  [ "$RESTA" = "0" ] && check 0 "chave de teste removida (o .env ficou vazio, modo 600)" || check 1 "chave de teste removida (linhas: $RESTA)"
else
  echo "   (--keep-key: a chave de teste ficou no dispositivo)"
fi

echo
echo "== resumo: $ok passos OK, ${#falhas[@]} falhas =="
for f in "${falhas[@]:-}"; do [ -n "$f" ] && echo "   - $f"; done
[ "${#falhas[@]}" -eq 0 ] || exit 1
