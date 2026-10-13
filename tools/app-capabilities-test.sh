#!/bin/bash
# app-capabilities-test.sh — verifica as capacidades Android do agente (F3-B/C/D).
#
#   ./tools/app-capabilities-test.sh            # rápido: acessibilidade + status + notificação
#   ./tools/app-capabilities-test.sh --full     # + interacção real (abre a calculadora e toca)
#
# Requer o daemon instalado e a chave já configurada (o agente tem de responder).
set -uo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
DEV="${CACHALOTE_DEV:-R58M55JNP6X}"
PKG="dev.ricardopimentel.cachalote"
ACT="$PKG/.MainActivity"
SVC="$PKG/$PKG.CachaloteAccessibilityService"
BASE="/data/adb/cachalote"
UI="$WS/.work/ui.xml"
FULL=0
[ "${1:-}" = "--full" ] && FULL=1

ADB=(adb -s "$DEV")
ok=0; falhas=()
pass() { echo "   OK    $1"; ok=$((ok+1)); }
fail() { echo "   FALHA $1"; falhas+=("$1"); }
check() { [ "$1" = 0 ] && pass "$2" || fail "$2"; }

L() { "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "${ADB[@]}" exec-out cat /sdcard/ui.xml > "$UI" 2>/dev/null; }
T() {
  L; local c; c="$(python3 "$WS/tools/ui.py" "$UI" find "$1" 2>/dev/null)" || return 1
  set -- $c; "${ADB[@]}" shell input tap "$1" "$2" >/dev/null 2>&1; sleep 1.6
}
long_text() { python3 - "$UI" <<'PY'
import sys, xml.etree.ElementTree as ET
try: t=ET.parse(sys.argv[1])
except Exception: print(""); raise SystemExit
best=""
for n in t.getroot().iter('node'):
    txt=n.get('text') or ''
    if len(txt)>len(best): best=txt
print(best)
PY
}
ask() { # ask "<prompt>" — escreve no ecrã da conversa e envia
  T "Conversa" || true
  L; python3 "$WS/tools/ui.py" "$UI" find "Escreve uma tarefa" >/dev/null 2>&1 || T "Nova sessão"
  T "Escreve uma tarefa" || true
  "${ADB[@]}" shell "input text '$1'" >/dev/null 2>&1
  sleep 1; T "Enviar" || true
}

echo "== 0. app, serviço em primeiro plano e acessibilidade =="
"${ADB[@]}" shell "am start -n $ACT" >/dev/null 2>&1
sleep 5
"${ADB[@]}" shell "dumpsys activity services $PKG" 2>/dev/null | grep -q "isForeground=true" \
  && check 0 "serviço de agente em primeiro plano" || check 1 "serviço de agente em primeiro plano"

if "${ADB[@]}" shell "dumpsys accessibility | grep -q 'Bound services:{Service\\[label=Cachalote'" 2>/dev/null; then
  check 0 "serviço de acessibilidade ligado"
else
  echo "   (a ligar o serviço de acessibilidade via adb…)"
  "${ADB[@]}" shell "settings put secure enabled_accessibility_services $SVC; settings put secure accessibility_enabled 1"
  sleep 3
  "${ADB[@]}" shell "dumpsys accessibility | grep -q 'Bound services:{Service\\[label=Cachalote'" 2>/dev/null \
    && check 0 "serviço de acessibilidade ligado (activado agora)" || check 1 "serviço de acessibilidade ligado"
fi

echo "== 1. autorizar todas as capacidades na app (separador Poderes) =="
T "Poderes" || true
T "Autorizar todas" || true
sleep 1
L
python3 "$WS/tools/ui.py" "$UI" find "screenshot" >/dev/null 2>&1 && check 0 "interruptores visíveis" || check 1 "interruptores visíveis"

echo "== 2. o agente pergunta o estado das capacidades (canal reverso) =="
ask "usa a ferramenta android_status e responde apenas com o JSON que ela devolve, sem comentarios"
RESP=""
for i in $(seq 1 20); do
  sleep 6; L; RESP="$(long_text)"
  echo "$RESP" | grep -q '"accessibility"' && break
done
echo "$RESP" | grep -q '"accessibility": true' && check 0 "android_status com acessibilidade ligada" || {
  check 1 "android_status com acessibilidade ligada"
  echo "      (obtido: $(echo "$RESP" | head -c 160))"
}

echo "== 3. notificação (android_notify) =="
ask "usa a ferramenta android_notify para avisar que o teste de capacidades passou"
sleep 20
"${ADB[@]}" shell "dumpsys notification --noredact 2>/dev/null | grep -q 'cachalote_agent'" \
  && check 0 "notificação publicada pela app" || check 1 "notificação publicada pela app"

if [ "$FULL" = 1 ]; then
  echo "== 4. interacção real: abrir a calculadora, ler o ecrã e tocar =="
  # Limpar o estado da calculadora para o teste ser honesto (senão o 7 podia ser herdado).
  "${ADB[@]}" shell "am force-stop com.android.calculator2" >/dev/null 2>&1
  ask "Faz isto: 1) abre a calculadora com o bash (am start -n com.android.calculator2/.Calculator). 2) android_screen. 3) android_tap com text 7. 4) android_screen outra vez e diz se o visor mostra 7."
  sleep 75
  "${ADB[@]}" shell uiautomator dump /sdcard/calc.xml >/dev/null 2>&1
  "${ADB[@]}" exec-out cat /sdcard/calc.xml > "$WS/.work/calc.xml" 2>/dev/null
  if python3 - "$WS/.work/calc.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
t=ET.parse(sys.argv[1])
for n in t.getroot().iter('node'):
    if 'formula' in (n.get('resource-id') or '') and (n.get('text') or '') == '7':
        raise SystemExit(0)
raise SystemExit(1)
PY
  then check 0 "a calculadora mostra 7 (o agente tocou no botão)"; else fail "a calculadora mostra 7 (o agente tocou no botão)"; fi
fi

echo
echo "== resumo: $ok passos OK, ${#falhas[@]} falhas =="
for f in "${falhas[@]:-}"; do [ -n "$f" ] && echo "   - $f"; done
[ "${#falhas[@]}" -eq 0 ] || exit 1
