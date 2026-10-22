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
GRADLE="$WS/.work/tools/gradle-8.13/bin/gradle"
KS="$WS/.work/cachalote-release.jks"
# A palavra-passe do keystore NÃO tem omissão no código: lê-se de CACHALOTE_STORE_PASS ou, se não
# estiver definida, de `.work/keystore.pass` (modo 600, fora do repositório). Antes disto o valor por
# omissão era `cachalote` — escrito aqui, logo público, e o mesmo que abria o ficheiro.
PASS="${CACHALOTE_STORE_PASS:-}"
if [ -z "$PASS" ]; then
  if [ -r "$WS/.work/keystore.pass" ]; then
    PASS="$(cat "$WS/.work/keystore.pass")"
  else
    echo "ERRO: falta a palavra-passe do keystore de release." >&2
    echo "      Define CACHALOTE_STORE_PASS ou escreve-a em .work/keystore.pass (chmod 600)." >&2
    exit 1
  fi
fi
ALIAS="${CACHALOTE_KEY_ALIAS:-cachalote}"

# O JDK vem da regra comum (tools/lib-jdk.sh): um build-release com o JBR no 25 morria com
# «IllegalArgumentException: 25.0.3», que não diz que o problema é o JDK (knowledge 138).
. "$WS/tools/lib-jdk.sh"
JAVA_HOME="$(cachalote_jdk "$WS")" || { echo "ERRO: não encontrei um JDK 17-21. Define JAVA_HOME." >&2; exit 1; }
cachalote_exigir_jdk "$JAVA_HOME" || exit 1
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
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
