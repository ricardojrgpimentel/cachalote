#!/bin/bash
# lib-jdk.sh — escolher o JDK do build, num sítio só.
#
# O Kotlin que vem no AGP **não sabe ler a versão** de um JDK >= 24: o build morre com
# `IllegalArgumentException: 25.0.3` e o Gradle mostra só a mensagem da excepção, sem dizer que o
# problema é o JDK (aconteceu a 2026-10-21, com o JBR do Android Studio já no 25 — knowledge 138).
#
# Uso:
#   . "$WS/tools/lib-jdk.sh"
#   JAVA_HOME="$(cachalote_jdk "$WS")" || { echo "sem JDK" >&2; exit 1; }
#   cachalote_exigir_jdk "$JAVA_HOME" || exit 1
#   export JAVA_HOME

# Devolve o melhor JDK que houver, por ordem: `.work/jdk21` (posto à mão), o `JAVA_HOME` do
# utilizador, e depois os que os SDKs de Android trazem (JBR do Android Studio, jdk-* da montra,
# openjdk do sistema). Os do SDK vêm antes de um `/usr/lib/jvm` genérico porque numa máquina
# actualizada esse pode ser o 26.
cachalote_jdk() {
  local ws="$1" c
  if [ -x "$ws/.work/jdk21/Contents/Home/bin/java" ]; then
    echo "$ws/.work/jdk21/Contents/Home"; return 0
  fi
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    echo "$JAVA_HOME"; return 0
  fi
  for c in "${ANDROID_HOME:-}/jbr" "${ANDROID_HOME:-}/jdk-"* \
           "$HOME/Library/Android/sdk/jbr" \
           "$HOME/Android/Sdk/jbr" \
           "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk; do
    [ -x "$c/bin/java" ] && { echo "$c"; return 0; }
  done
  return 1
}

# Recusa um JDK >= 24 com uma mensagem que diz o que fazer (o erro do Gradle não diz).
cachalote_exigir_jdk() {
  local j="$1" major
  [ -n "$j" ] || return 0
  major="$(sed -n 's/.*JAVA_VERSION="\([0-9]*\).*/\1/p' "$j/release" 2>/dev/null | head -1)"
  if [ -n "$major" ] && [ "$major" -ge 24 ] 2>/dev/null; then
    echo "ERRO: $j é JDK $major — o Kotlin do AGP não o suporta (falha com «IllegalArgumentException: $major…»)." >&2
    echo "      Usa JDK 17-21: export JAVA_HOME=/caminho/para/jdk-21, ou põe um em .work/jdk21." >&2
    return 1
  fi
  return 0
}
