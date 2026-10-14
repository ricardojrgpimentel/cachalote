#!/bin/bash
# build-release.sh — gera (se preciso) a chave de assinatura e compila a APK de release.
#
#   ./tools/build-release.sh
#
# A chave fica em .work/cachalote-release.jks (NÃO versionada). Para distribuir, guarda-a
# em segurança: sem ela não consegues actualizar a app instalada (assinaturas diferentes
# não se sobrepõem).
set -euo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
JH="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
GRADLE="$WS/.work/tools/gradle-8.13/bin/gradle"
KS="$WS/.work/cachalote-release.jks"
PASS="${CACHALOTE_STORE_PASS:-cachalote}"
ALIAS="${CACHALOTE_KEY_ALIAS:-cachalote}"

export JAVA_HOME="$JH"
export PATH="$JH/bin:$PATH"
export GRADLE_USER_HOME="$WS/.work/gradle-home"
export ANDROID_USER_HOME="$WS/.work/android-home"
export CACHALOTE_KEYSTORE="$KS"
export CACHALOTE_STORE_PASS="$PASS"
export CACHALOTE_KEY_ALIAS="$ALIAS"
export CACHALOTE_KEY_PASS="$PASS"

if [ ! -f "$KS" ]; then
  echo "== a gerar a chave de assinatura em $KS =="
  keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Cachalote, O=Cachalote, C=PT" >/dev/null
  chmod 600 "$KS"
  echo "   (guarda este ficheiro e a password '$PASS' — sem eles não consegues actualizar a app)"
fi

echo "== a compilar release =="
( cd "$WS/app" && "$GRADLE" --no-daemon --console=plain assembleRelease | tail -6 )

APK="$WS/app/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "ERRO: APK não encontrada"; exit 1; }
echo
echo "== resultado =="
ls -lh "$APK"
APKSIGNER="$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/apksigner 2>/dev/null | tail -1)"
[ -n "$APKSIGNER" ] && "$APKSIGNER" verify --print-certs "$APK" | head -4
echo "sha256: $(shasum -a 256 "$APK" | cut -d' ' -f1)"
echo
echo "instalar:  adb -s \"\${CACHALOTE_DEV:-<serial>}\" install -r \"$APK\""
