#!/bin/bash
# install-app.sh — compila (se preciso) e instala a app Cachalote no dispositivo,
# e pré-autoriza o root no Magisk para que o ecrã da chave funcione sem prompt.
#
#   ./tools/install-app.sh              # build + install + pré-autoriza root + abre a app
#   ./tools/install-app.sh --no-build   # instala o APK que já existe
#   ./tools/install-app.sh --no-root    # não mexe nas políticas do Magisk (emulador/sem root)
#   ./tools/install-app.sh --device emulator-5554
#   ./tools/install-app.sh --release    # assina com a chave de release (actualiza uma app já
#                                       # instalada por release sem perder preferências)
set -euo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
# O dispositivo vem de --device/CACHALOTE_DEV ou é autodetectado (nunca fixo no repo).
. "$WS/tools/lib-device.sh"
DEV="$(cachalote_escolher_dispositivo)" || exit 1
PKG="dev.ricardopimentel.cachalote"
GRADLE="$WS/.work/tools/gradle-8.13/bin/gradle"
# O Gradle precisa de escrever no seu home e no home do Android; manter tudo dentro
# do workspace evita a política de ficheiros do harness (ver knowledge-android #47).
export GRADLE_USER_HOME="$WS/.work/gradle-home"
export ANDROID_USER_HOME="$WS/.work/android-home"
# O JDK: respeitar o que vier do ambiente (o bootstrap detecta-o e passa-o) e só procurar
# sozinho se não vier nada. O caminho do Android Studio é o **do macOS** e estava fixo aqui —
# no Linux sobrepunha-se ao JAVA_HOME do bootstrap e o build morria sem explicação.
# Um JDK 21 posto à mão em .work/jdk21 ganha: o Kotlin que vem no AGP **não sabe ler a versão**
# de um JDK >= 24 e o build morre com «IllegalArgumentException: 25.0.3», que não aponta para nada
# (aconteceu em 2026-10-21, com o JBR do Android Studio já no 25).
if [ -x "$WS/.work/jdk21/Contents/Home/bin/java" ]; then
  JAVA_HOME="$WS/.work/jdk21/Contents/Home"
fi
# Os JDKs que os SDKs trazem vêm primeiro: um JDK do sistema demasiado novo (26) faz o AGP
# falhar com um erro que não aponta para a causa.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  for c in "${ANDROID_HOME:-}/jbr" "${ANDROID_HOME:-}/jdk-"* "$HOME/Android/Sdk/jbr" \
           "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk; do
    [ -x "$c/bin/java" ] && { JAVA_HOME="$c"; break; }
  done
fi
export JAVA_HOME
# A versão do JDK, em número: o Kotlin do AGP não suporta >= 24 e falha de forma ilegível.
JDK_MAJOR="$(sed -n 's/.*JAVA_VERSION="\([0-9]*\).*/\1/p' "$JAVA_HOME/release" 2>/dev/null | head -1)"
if [ -n "$JDK_MAJOR" ] && [ "$JDK_MAJOR" -ge 24 ] 2>/dev/null; then
  echo "ERRO: $JAVA_HOME é JDK $JDK_MAJOR — o Kotlin do AGP não o suporta (falha com «IllegalArgumentException: $JDK_MAJOR…»)." >&2
  echo "      Usa JDK 17-21: export JAVA_HOME=/caminho/para/jdk-21, ou põe um em .work/jdk21." >&2
  exit 1
fi
ADB=(adb -s "$DEV")
BUILD=1; ROOT=1; VARIANT="debug"

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0; shift ;;
    --no-root) ROOT=0; shift ;;
    # Uma app instalada por release **não** aceita `install -r` de uma APK assinada com a chave
    # de debug: o Android recusa com INSTALL_FAILED_UPDATE_INCOMPATIBLE e substituí-la obrigaria
    # a desinstalar (perdendo token, capacidades autorizadas e auditoria). Com --release
    # actualiza-se em cima, mantendo tudo.
    --release) VARIANT="release"; shift ;;
    --device) DEV="$2"; ADB=(adb -s "$DEV"); shift 2 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

APK="$WS/app/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
if [ "$BUILD" = 1 ]; then
  echo "== a compilar ($VARIANT) =="
  if [ "$VARIANT" = "release" ]; then
    export CACHALOTE_KEYSTORE="${CACHALOTE_KEYSTORE:-$WS/.work/cachalote-release.jks}"
    [ -f "$CACHALOTE_KEYSTORE" ] || { echo "ERRO: falta a chave de release em $CACHALOTE_KEYSTORE (corre tools/build-release.sh)"; exit 1; }
    TASK="assembleRelease"
  else
    TASK="assembleDebug"
  fi
  ( cd "$WS/app" && JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" "$GRADLE" --no-daemon --console=plain "$TASK" | tail -12 )
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
