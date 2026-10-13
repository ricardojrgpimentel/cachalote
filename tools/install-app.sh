#!/bin/bash
# install-app.sh — compila (se preciso) e instala a app Cachalote no dispositivo,
# e pré-autoriza o root no Magisk para que o ecrã da chave funcione sem prompt.
#
#   ./tools/install-app.sh              # build + install + pré-autoriza root + abre a app
#   ./tools/install-app.sh --no-build   # instala o APK que já existe
#   ./tools/install-app.sh --no-root    # não mexe nas políticas do Magisk
set -euo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
DEV="${CACHALOTE_DEV:-R58M55JNP6X}"
PKG="dev.ricardopimentel.cachalote"
GRADLE="$WS/.work/tools/gradle-8.13/bin/gradle"
# O Gradle precisa de escrever no seu home e no home do Android; manter tudo dentro
# do workspace evita a política de ficheiros do harness (ver knowledge-android #47).
export GRADLE_USER_HOME="$WS/.work/gradle-home"
export ANDROID_USER_HOME="$WS/.work/android-home"
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
APK="$WS/app/app/build/outputs/apk/debug/app-debug.apk"
ADB=(adb -s "$DEV")
BUILD=1; ROOT=1

for a in "$@"; do
  case "$a" in
    --no-build) BUILD=0 ;;
    --no-root) ROOT=0 ;;
  esac
done

if [ "$BUILD" = 1 ]; then
  echo "== a compilar =="
  ( cd "$WS/app" && JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" "$GRADLE" --no-daemon --console=plain assembleDebug | tail -12 )
fi
[ -f "$APK" ] || { echo "ERRO: APK não existe em $APK"; exit 1; }
ls -lh "$APK"

echo "== a instalar =="
"${ADB[@]}" install -r "$APK" | tail -3

if [ "$ROOT" = 1 ]; then
  echo "== pré-autorizar root no Magisk (para o ecrã da chave) =="
  UID_APP="$("${ADB[@]}" shell "cmd package list packages -U | grep $PKG" | tr -d '\r' | sed 's/.*uid://')"
  echo "uid da app: $UID_APP"
  if [ -n "$UID_APP" ]; then
    "${ADB[@]}" shell "su -c 'magisk --sqlite \"REPLACE INTO policies (uid,policy,until,logging,notification) VALUES ($UID_APP,2,0,1,1)\"'" 2>&1 | tail -2 || true
  fi
fi

echo "== a abrir =="
"${ADB[@]}" shell "am start -n $PKG/.MainActivity" | tail -2
sleep 3
echo "pronto. Para ver o ecrã: adb exec-out screencap -p > /tmp/cachalote-app.png"
