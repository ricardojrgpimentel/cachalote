#!/bin/bash
# bootstrap.sh — de um clone a **daemon a correr + app instalada**, num comando.
#
#   ./tools/bootstrap.sh                     # root (Magisk), kernel do npm, runtime do Termux
#   ./tools/bootstrap.sh --shizuku           # daemon em /data/local/tmp (sem root)
#   ./tools/bootstrap.sh --skip-app          # só o motor/daemon
#   ./tools/bootstrap.sh --dry-run           # mostra o plano e sai
#   ./tools/bootstrap.sh --no-device         # só prepara as árvores (CI, sem telemóvel)
#
# Num emulador (x86_64, sem root), é a conjugação destas opções:
#
#   ./tools/bootstrap.sh --device emulator-5554 --arch x86_64 --shizuku --skip-app
#   JAVA_HOME="$ANDROID_HOME/jdk-21.0.12.1+1" ./tools/bootstrap.sh --device emulator-5554 \
#     --arch x86_64 --shizuku
#
# O que faz, por ordem:
#   1. verifica pré-requisitos (node, npm, python3, adb, JDK, SDK)
#   2. obtém o kernel: `npm install @deepseek-ai/dsh` (ou --dsh-dir) e aplica os nossos patches
#   3. obtém o runtime Node/bionic (Termux) — descarrega, copia ou tira do telemóvel
#   4. instala o daemon (root ou /data/local/tmp) e liga-o
#   5. prepara e instala a app (runtime embutido + kernel no asset) e verifica
#   6. (opcional, --key) pede a chave do provedor
#
# Nada disto precisa do app.asar do desktop: o kernel vem do npm e o runtime do Termux.
set -euo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
DEV="${CACHALOTE_DEV:-}"
DSH_VERSION="${CACHALOTE_DSH_VERSION:-0.2.0-rc.2}"
DSH_DIR=""
RUNTIME_DIR=""
RUNTIME_FROM_DEVICE=0
ARCH="${CACHALOTE_ARCH:-aarch64}"
BASE="/data/adb/cachalote"
SHIZUKU=0
AUTOSTART=1
SKIP_APP=0
SKIP_DAEMON=0
NO_DEVICE=0
DO_KEY=0
DRY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --device) DEV="$2"; shift 2 ;;
    --dsh-version) DSH_VERSION="$2"; shift 2 ;;
    --dsh-dir) DSH_DIR="$2"; shift 2 ;;
    --runtime-dir) RUNTIME_DIR="$2"; shift 2 ;;
    --runtime-from-device) RUNTIME_FROM_DEVICE=1; shift ;;
    --arch) ARCH="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --shizuku) SHIZUKU=1; BASE="/data/local/tmp/cachalote"; AUTOSTART=0; shift ;;
    --no-autostart) AUTOSTART=0; shift ;;
    --skip-app) SKIP_APP=1; shift ;;
    --skip-daemon) SKIP_DAEMON=1; shift ;;
    # Sem telemóvel: só kernel + runtime (é o que a CI precisa de provar).
    --no-device) NO_DEVICE=1; SKIP_DAEMON=1; SKIP_APP=1; shift ;;
    --key) DO_KEY=1; shift ;;
    --dry-run) DRY=1; shift ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

case "$ARCH" in
  aarch64) RUNTIME_SUFFIX="" ;;
  x86_64|arm|i686) RUNTIME_SUFFIX="-$ARCH" ;;
  *) echo "ERRO: --arch '$ARCH' desconhecida (aarch64, arm, x86_64, i686)" >&2; exit 2 ;;
esac
RUNTIME_OUT="$WS/.work/node-android$RUNTIME_SUFFIX"
RUNTIME_TREE="$RUNTIME_OUT/root/data/data/com.termux/files/usr"
# O instalador do daemon e o preparador do runtime embutido têm este caminho por omissão;
# esta variável é o que lhes permite usar a árvore da arquitectura certa.
export CACHALOTE_RUNTIME_SRC="$RUNTIME_TREE"
KERNEL="$WS/.work/f0/kernel"
GRADLE="$WS/.work/tools/gradle-8.13/bin/gradle"

say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "ERRO: falta '$1' ($2)"; exit 1; }; }

# ---------------------------------------------------------------- JDK e SDK
detect_java() {
  local sdk="${1:-${ANDROID_HOME:-}}" c
  # Um JDK 21 posto à mão em .work/jdk21 ganha (ver install-app.sh: o AGP quebra com >= 24).
  if [ -x "$WS/.work/jdk21/Contents/Home/bin/java" ]; then echo "$WS/.work/jdk21/Contents/Home"; return; fi
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then echo "$JAVA_HOME"; return; fi
  # Os JDKs que os SDKs de Android trazem (Android Studio, montra) são os que o AGP espera —
  # vêm PRIMEIRO. Um `/usr/lib/jvm/*/bin/java` genérico apanha o JDK do sistema, que numa
  # máquina actualizada pode ser o 26; o AGP 8.x não o suporta e o build morre com um erro
  # que não diz nada de útil ("What went wrong: 26.0.2.1").
  for c in "$sdk"/jbr "$sdk"/jdk-* \
           "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "$HOME/Android/Sdk/jbr" \
           /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk; do
    [ -x "$c/bin/java" ] && { echo "$c"; return; }
  done
  return 1
}
detect_sdk() {
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then echo "$ANDROID_HOME"; return; fi
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
                   "$HOME/.local/share/montra-android"; do
    [ -d "$candidate" ] && { echo "$candidate"; return; }
  done
}

if [ "$DRY" = 1 ]; then
  say "plano (--dry-run)"
  cat <<EOF
dispositivo : ${DEV:-(autodetectar)}
arquitectura: $ARCH -> $RUNTIME_TREE
kernel      : ${DSH_DIR:-npm install @deepseek-ai/dsh@$DSH_VERSION} -> $KERNEL
patches     : tools/patch-android.cjs
runtime     : $( [ -x "$RUNTIME_TREE/bin/node" ] && echo "reutilizar $RUNTIME_TREE" || { [ "$RUNTIME_FROM_DEVICE" = 1 ] && echo "tirar do Termux do telemóvel" || { [ -n "$RUNTIME_DIR" ] && echo "copiar de $RUNTIME_DIR" || echo "descarregar do repositório do Termux (tools/fetch-runtime.sh)"; }; } )
daemon      : $( [ "$SKIP_DAEMON" = 1 ] && echo "não pedido" || echo "daemon/install.sh --base $BASE$( [ "$SHIZUKU" = 1 ] && echo " --no-root" )$( [ "$AUTOSTART" = 1 ] && echo " --autostart" )" )
app         : $( [ "$SKIP_APP" = 1 ] && echo "não pedida" || echo "prepare-embed.sh + prepare-kernel-zip.sh --bundle + install-app.sh" )
chave       : $( [ "$DO_KEY" = 1 ] && echo "pedida no fim (daemon/set-key.sh --verify)" || echo "não mexer" )
EOF
  exit 0
fi

say "1/6 pré-requisitos"
need node "Node.js no computador (só para as ferramentas)"
need npm "npm (para instalar o kernel)"
need python3 "Python 3 (pipelines e testes)"
[ "$NO_DEVICE" = 1 ] || need adb "Android platform-tools"
[ "$SKIP_APP" = 1 ] || { need zip "zip (empacotar o kernel)"; need unzip "unzip (extrair o Gradle)"; }
SDK="$(detect_sdk || true)"
JAVA="$(detect_java "$SDK" || true)"
if [ "$SKIP_APP" != 1 ]; then
  [ -n "$JAVA" ] || { echo "ERRO: não encontrei um JDK 17+. Define JAVA_HOME (o JBR do Android Studio serve)."; exit 1; }
  [ -n "$SDK" ] || { echo "ERRO: não encontrei o Android SDK. Define ANDROID_HOME."; exit 1; }
  echo "java: $JAVA"; echo "sdk : $SDK"
fi

if [ "$NO_DEVICE" = 1 ]; then
  echo "dispositivo: (nenhum — --no-device)"
  DEV="(sem dispositivo)"
else
  if [ -z "$DEV" ]; then
    DEV="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
    [ -n "$DEV" ] || { echo "ERRO: nenhum telemóvel ligado (adb devices vazio). Liga por USB e autoriza a depuração."; exit 1; }
  fi
  echo "dispositivo: $DEV"
fi

say "2/6 kernel do DSH (npm) + patches"
if [ ! -f "$KERNEL/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js" ] || [ ! -d "$KERNEL/dsh/node_modules/@deepseek-ai/dsh-tool-cachalote" ]; then
  if [ -n "$DSH_DIR" ]; then
    SRC_MODULES="$DSH_DIR"
    echo "a usar node_modules de $DSH_DIR"
  else
    echo "a instalar @deepseek-ai/dsh@$DSH_VERSION em .work/dsh-install (pode demorar)"
    rm -rf "$WS/.work/dsh-install"
    # Cache próprio: não depende (nem estraga) o cache npm do utilizador, que pode ter
    # ficheiros de root de execuções antigas com sudo.
    npm install --silent --no-audit --no-fund --cache "$WS/.work/npm-cache" \
      --prefix "$WS/.work/dsh-install" "@deepseek-ai/dsh@$DSH_VERSION" >/dev/null
    SRC_MODULES="$WS/.work/dsh-install/node_modules"
  fi
  [ -d "$SRC_MODULES/@deepseek-ai/dsh" ] || { echo "ERRO: $SRC_MODULES não tem @deepseek-ai/dsh"; exit 1; }
  mkdir -p "$KERNEL"
  node "$WS/tools/extract-kernel.cjs" --node-modules "$SRC_MODULES" --out "$KERNEL" --trim
else
  echo "a reutilizar $KERNEL (apaga-o para re-extrair)"
fi
node "$WS/tools/patch-android.cjs" --tree "$KERNEL"

say "3/6 runtime Node $ARCH (bionic)"
if [ -x "$RUNTIME_TREE/bin/node" ]; then
  echo "a reutilizar $RUNTIME_TREE"
else
  if [ "$RUNTIME_FROM_DEVICE" = 1 ]; then
    "$WS/tools/fetch-runtime.sh" --out "$RUNTIME_OUT"
  elif [ -n "$RUNTIME_DIR" ]; then
    "$WS/tools/fetch-runtime.sh" --dir "$RUNTIME_DIR" --out "$RUNTIME_OUT"
  else
    "$WS/tools/fetch-runtime.sh" --arch "$ARCH" --out "$RUNTIME_OUT"
  fi
fi
sha256sum_or_shasum() { shasum -a 256 "$1" 2>/dev/null | cut -c1-16 || sha256sum "$1" | cut -c1-16; }
echo "node ($ARCH): $(sha256sum_or_shasum "$RUNTIME_TREE/bin/node")…"

if [ "$SKIP_DAEMON" = 1 ]; then
  say "4/6 daemon (não pedido)"
else
  say "4/6 daemon no telemóvel ($BASE)"
  args=(--device "$DEV" --base "$BASE")
  [ "$SHIZUKU" = 1 ] && args+=(--no-root)
  [ "$AUTOSTART" = 1 ] && args+=(--autostart)
  "$WS/daemon/install.sh" "${args[@]}"
fi

if [ "$SKIP_APP" = 1 ]; then
  say "5/6 app (não pedida)"
else
  say "5/6 app: runtime embutido, kernel no asset e build"
  "$WS/tools/prepare-embed.sh" --arch "$ARCH"
  "$WS/tools/prepare-kernel-zip.sh" --bundle
  if [ ! -x "$GRADLE" ]; then
    echo "a descarregar o Gradle 8.13 para .work/tools"
    mkdir -p "$WS/.work/tools"
    curl -fsSL -o "$WS/.work/gradle.zip" "https://services.gradle.org/distributions/gradle-8.13-bin.zip"
    ( cd "$WS/.work/tools" && unzip -q "$WS/.work/gradle.zip" )
    rm -f "$WS/.work/gradle.zip"
  fi
  # Passar o dispositivo explicitamente: o `install-app.sh` tem um por omissão (o telefone) e,
  # sem isto, um bootstrap para o emulador instalava a app no telefone.
  app_args=(--device "$DEV")
  [ "$SHIZUKU" = 1 ] && app_args+=(--no-root)
  CACHALOTE_DEV="$DEV" JAVA_HOME="$JAVA" ANDROID_HOME="$SDK" "$WS/tools/install-app.sh" "${app_args[@]}"
fi

if [ "$DO_KEY" = 1 ]; then
  say "6/6 chave do provedor"
  "$WS/daemon/set-key.sh" --base "$BASE" --verify
else
  say "6/6 chave do provedor (não pedida)"
  echo "quando quiseres: ./daemon/set-key.sh --base $BASE --verify"
fi

say "pronto"
if [ "$SHIZUKU" = 1 ]; then
  STATUS="adb -s $DEV shell '$BASE/bin/cachalote status'"
else
  STATUS="adb -s $DEV shell \"su -c '$BASE/bin/cachalote status'\""
fi
cat <<EOF
arquitectura: $ARCH
runtime   : $RUNTIME_TREE
daemon    : $STATUS
testes    : ./tools/app-ui-test.sh          (9 passos, guarda e repõe a tua chave)
            ./tools/app-capabilities-test.sh --full
EOF
