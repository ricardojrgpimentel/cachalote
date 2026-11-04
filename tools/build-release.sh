#!/bin/bash
# build-release.sh — compila a APK de release com a chave de assinatura existente.
#
#   ./tools/build-release.sh
#   CACHALOTE_NOVA_CHAVE=1 ./tools/build-release.sh   # (só) para criar uma identidade nova
#
# A chave **não** é gerada por omissão: se faltar, o script pára e diz como a repor. Uma chave nova
# em silêncio quebra as actualizações de quem já tem a app (ver o bloco de erro).
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
  # **Não se gera uma chave nova em silêncio.** Uma chave nova tem a mesma DN (CN=Cachalote) e um
  # certificado diferente: a APK compila, instala-se num telefone limpo — e quem já tem a app deixa
  # de a poder actualizar, porque o Android recusa assinaturas diferentes. O erro só aparece no
  # telefone do utilizador, semanas depois. Já houve uma chave gerada assim (a de debug, noutra
  # máquina); a de release não volta a acontecer por descuido.
  if [ "${CACHALOTE_NOVA_CHAVE:-0}" != "1" ]; then
    cat >&2 <<'FIM'
ERRO: falta a chave de assinatura de release em .work/cachalote-release.jks.

Não a gero sozinho de propósito: uma chave nova tem o mesmo DN (CN=Cachalote) mas um
certificado diferente. A APK sairia assinada, instalar-se-ia num telefone limpo, e
quem já tem a app deixava de conseguir actualizá-la.

Para a repor a partir da nota do Bitwarden:
  ./tools/backup-signing-key.sh --de-texto <nota.txt>
e confirma que é a mesma identidade (certificado):
  881b04b0c38746eda8c61da319b99f65bb39364c01884862857a1f29895931a9

Se o que queres mesmo é uma identidade NOVA (e aceitas que a app instalada deixa de
receber actualizações), pede-lho explicitamente:
  CACHALOTE_NOVA_CHAVE=1 ./tools/build-release.sh
FIM
    exit 1
  fi
  echo "== a gerar uma chave NOVA (CACHALOTE_NOVA_CHAVE=1) em $KS =="
  keytool -genkeypair -v -keystore "$KS" -alias "$ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Cachalote, O=Cachalote, C=PT" >/dev/null
  chmod 600 "$KS"
  echo "   (guarda este ficheiro e a password — sem eles não consegues actualizar a app)"
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
