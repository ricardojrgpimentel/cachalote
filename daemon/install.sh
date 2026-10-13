#!/bin/bash
# install.sh — instala/actualiza o daemon cachalote no dispositivo.
#
#   ./daemon/install.sh                      # /data/adb/cachalote (root, recomendado)
#   ./daemon/install.sh --base /data/local/tmp/cachalote --no-root
#   ./daemon/install.sh --rebuild            # re-extrai o kernel do app.asar e reaplica os patches
#   ./daemon/install.sh --autostart          # + script no Magisk service.d (arranque no boot)
#
# O que faz:
#   1. garante a árvore do kernel local (extract-kernel + patch-android)
#   2. encena tudo em /data/local/tmp/cachalote-stage via adb push
#   3. instala em $BASE (com su, se for root) e arranca o daemon
set -euo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
BASE="/data/adb/cachalote"
# Porto do canal TCP. O daemon root usa 39731; um daemon noutro sítio (ex.: /data/local/tmp
# para o Shizuku) usa 39733 para poderem coexistir.
PORT=""
STAGE="/data/local/tmp/cachalote-stage"
DEV="${CACHALOTE_DEV:-R58M55JNP6X}"
APP="/Applications/DeepSeek Harness.app"
ASAR="$APP/Contents/Resources/app.asar"
ELECTRON="$APP/Contents/MacOS/DeepSeek Harness"
RUNTIME_SRC="$WS/.work/node-android/root/data/data/com.termux/files/usr"
KERNEL="$WS/.work/f0/kernel"
WITH_ROOT=1
AUTOSTART=0
REBUILD=0

while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --device) DEV="$2"; shift 2 ;;
    --no-root) WITH_ROOT=0; shift ;;
    --autostart) AUTOSTART=1; shift ;;
    --rebuild) REBUILD=1; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done
ADB=(adb -s "$DEV")
say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
SH() { if [ "$WITH_ROOT" = 1 ]; then "${ADB[@]}" shell "su -c \"$1\""; else "${ADB[@]}" shell "$1"; fi; }

say "1/6 árvore do kernel"
if [ "$REBUILD" = 1 ] || [ ! -f "$KERNEL/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js" ]; then
  rm -rf "$KERNEL"; mkdir -p "$KERNEL"
  ELECTRON_RUN_AS_NODE=1 "$ELECTRON" "$WS/tools/extract-kernel.cjs" --asar "$ASAR" --out "$KERNEL" --trim | grep -E "ficheiros|tamanho"
else
  echo "a reutilizar $KERNEL (usa --rebuild para re-extrair)"
fi
node "$WS/tools/patch-android.cjs" --tree "$KERNEL"

say "2/6 runtime Node"
[ -x "$RUNTIME_SRC/bin/node" ] || { echo "ERRO: falta $RUNTIME_SRC/bin/node (corre .work/fetch-node-android.sh)"; exit 1; }
echo "node: $("$RUNTIME_SRC/bin/node" -v 2>/dev/null || echo '(binário Android; não corre no Mac)')"

say "3/6 encenar em $STAGE"
"${ADB[@]}" shell "rm -rf $STAGE && mkdir -p $STAGE"
tar -cf "$WS/.work/kernel.tar" -C "$KERNEL" dsh
"${ADB[@]}" push "$WS/.work/kernel.tar" "$STAGE/kernel.tar" >/dev/null
"${ADB[@]}" shell "mkdir -p $STAGE/runtime/bin $STAGE/runtime/lib $STAGE/bin"
"${ADB[@]}" push "$RUNTIME_SRC/bin/node" "$STAGE/runtime/bin/node" >/dev/null
for f in "$RUNTIME_SRC"/lib/*.so*; do [ -f "$f" ] && "${ADB[@]}" push "$f" "$STAGE/runtime/lib/" >/dev/null; done
printf '#!/system/bin/sh\nexec /system/bin/sh "$@"\n' > "$WS/.work/bash-shim"
"${ADB[@]}" push "$WS/.work/bash-shim" "$STAGE/bin/bash" >/dev/null
"${ADB[@]}" push "$WS/daemon/bridge.cjs" "$STAGE/bridge.cjs" >/dev/null
"${ADB[@]}" push "$WS/daemon/cachalote" "$STAGE/cachalote" >/dev/null
"${ADB[@]}" push "$WS/daemon/client-smoke.cjs" "$STAGE/client-smoke.cjs" >/dev/null
"${ADB[@]}" push "$WS/config/cachalote.patch.yml" "$STAGE/cachalote.patch.yml" >/dev/null
echo "encenado"

[ -n "$PORT" ] || { if [ "$BASE" = "/data/adb/cachalote" ]; then PORT=39731; else PORT=39733; fi; }
say "4/6 instalar em $BASE (porto $PORT)"
SH "set -e
  mkdir -p '$BASE/home/ws' '$BASE/run' '$BASE/logs' '$BASE/tmp' '$BASE/bin' '$BASE/runtime/bin' '$BASE/runtime/lib'
  rm -rf '$BASE/dsh' '$BASE/kernel'
  mkdir -p '$BASE/kernel' && cd '$STAGE' && tar -xf kernel.tar -C '$BASE/kernel'
  cp -f '$STAGE/runtime/bin/node' '$BASE/runtime/bin/node'
  cp -f '$STAGE/runtime/lib/'*.so* '$BASE/runtime/lib/'
  cp -f '$STAGE/bin/bash' '$BASE/bin/bash'
  cp -f '$STAGE/bridge.cjs' '$BASE/bridge.cjs'
  cp -f '$STAGE/cachalote' '$BASE/bin/cachalote'
  cp -f '$STAGE/client-smoke.cjs' '$BASE/bin/client-smoke.cjs'
  [ -f '$BASE/home/.env' ] || : > '$BASE/home/.env'
  cp -f '$STAGE/cachalote.patch.yml' '$BASE/home/cordis.patch.yml'
  # O CLI e a ponte têm o base por omissão dentro do ficheiro: reescrevê-lo para o base
  # escolhido, senão um --base alternativo (ex.: /data/local/tmp para o Shizuku) arranca o
  # daemon do sítio errado.
  sed -i \"s|/data/adb/cachalote|$BASE|g\" '$BASE/bin/cachalote' '$BASE/bridge.cjs' '$BASE/home/cordis.patch.yml' '$BASE/bin/client-smoke.cjs' 2>/dev/null || true
  chmod 755 '$BASE/bin/cachalote' '$BASE/bin/bash' '$BASE/runtime/bin/node'
  chmod 600 '$BASE/home/.env'
  chmod -R a+rX '$BASE/kernel' '$BASE/runtime/lib'
  chmod -R u+w '$BASE/home'
  printf '%s\n' '$PORT' > '$BASE/home/port'
  chmod 644 '$BASE/home/port'
  echo 'instalado em $BASE (porto $PORT)'"
SH "du -sh '$BASE'; ls '$BASE' '$BASE/bin'"

say "5/6 arrancar"
SH "'$BASE/bin/cachalote' start || true"
SH "'$BASE/bin/cachalote' status || true"

if [ "$AUTOSTART" = 1 ]; then
  say "6/6 arranque automático (Magisk service.d)"
  "${ADB[@]}" push "$WS/daemon/cachalote.service.sh" "$STAGE/cachalote.service.sh" >/dev/null
  SH "mkdir -p /data/adb/service.d && cp -f '$STAGE/cachalote.service.sh' /data/adb/service.d/cachalote.sh && chmod 755 /data/adb/service.d/cachalote.sh && ls -l /data/adb/service.d/cachalote.sh"
else
  say "6/6 arranque automático: não pedido (--autostart para instalar no Magisk service.d)"
fi

say "pronto"
echo "controlo:  adb shell su -c '$BASE/bin/cachalote status'"
echo "chave:     ./daemon/set-key.sh --base $BASE"
