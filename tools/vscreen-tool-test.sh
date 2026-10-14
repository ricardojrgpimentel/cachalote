#!/bin/bash
# vscreen-tool-test.sh — testa as acções do ecrã virtual na ferramenta do plugin, a sério.
#
#   ./tools/vscreen-tool-test.sh
#
# Faz no telefone: inicia o ecrã virtual, empurra o plugin e um pequeno cliente que chama a
# ferramenta `android_vscreen` **directamente** (o mesmo código que o agente chama) contra o
# daemon, e confirma:
#   - recusa injectar num display que não seja o ecrã virtual do Cachalote;
#   - recusa um componente que não seja `pacote/Actividade`;
#   - lança uma app, tira fotograma, toca e manda uma tecla (por nome, `KEYCODE_BACK`).
#
# Existe porque a automação de UI (uiautomator) falha por tudo e por nada: aqui não se toca no ecrã.
set -uo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
. "$WS/tools/lib-device.sh"
DEV="$(cachalote_escolher_dispositivo)" || exit 1
BASE="${CACHALOTE_BASE:-/data/adb/cachalote}"
PKG="${CACHALOTE_PKG:-dev.ricardopimentel.cachalote}"
ADB=(adb -s "$DEV")
sh_() { "${ADB[@]}" shell "su -c '$1'" 2>/dev/null | tr -d '\r'; }

echo "== a preparar o telefone =="
# A app tem de estar a correr: é ela que tem o ecrã virtual e responde ao daemon.
"${ADB[@]}" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
for _ in $(seq 1 10); do
  sleep 3
  case "$(sh_ "$BASE/bin/cachalote ctl cachalote.host.status")" in
    *'"appConnected":true'*) break ;;
  esac
done

# Os parâmetros vão num ficheiro: dentro de `su -c '…'` as aspas do JSON seriam o caos.
echo '{"capability":"vscreen_start","params":{}}' > "$WS/.work/vs-start.json"
"${ADB[@]}" push "$WS/.work/vs-start.json" /data/local/tmp/vs-start.json >/dev/null 2>&1
DID="$(sh_ "$BASE/bin/cachalote ctl cachalote.host.call @/data/local/tmp/vs-start.json" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("displayId","-1"))' 2>/dev/null)"
[ "$DID" != "-1" ] || { echo "ERRO: não consegui criar o ecrã virtual (a app responde?)"; exit 1; }
echo "   ecrã virtual: display $DID"

"${ADB[@]}" shell "rm -rf /data/local/tmp/vstest" >/dev/null 2>&1
"${ADB[@]}" shell "mkdir -p /data/local/tmp/vstest" >/dev/null 2>&1
"${ADB[@]}" push "$WS/tools/vscreen-tool-test.mjs" /data/local/tmp/vstest/ >/dev/null 2>&1 || {
  echo "ERRO: falhou o push do cliente de teste"; exit 1; }

PLUG="$BASE/kernel/dsh/node_modules/@deepseek-ai/dsh-tool-cachalote/lib/index.js"
echo "== a chamar a ferramenta android_vscreen =="
sh_ "cd /data/local/tmp/vstest && DSH_HOME=$BASE/home LD_LIBRARY_PATH=$BASE/runtime/lib $BASE/runtime/bin/node vscreen-tool-test.mjs $PLUG $DID"
SAIDA=$?
echo '{"capability":"vscreen_stop","params":{}}' > "$WS/.work/vs-stop.json"
"${ADB[@]}" push "$WS/.work/vs-stop.json" /data/local/tmp/vs-stop.json >/dev/null 2>&1
sh_ "$BASE/bin/cachalote ctl cachalote.host.call @/data/local/tmp/vs-stop.json" >/dev/null 2>&1
exit $SAIDA
