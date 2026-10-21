#!/bin/bash
# lib-device.sh — escolher o dispositivo Android, sem fixar nenhum em concreto.
#
# Antes isto era um serial fixo por omissão em nove scripts: o identificador do aparelho do autor
# ficava gravado no repositório (e não servia a mais ninguém).
# Agora:
#   1. $CACHALOTE_DEV, se estiver definido;
#   2. o único dispositivo `device` ligado por adb;
#   3. se houver vários, erro a dizer quais são (para se escolher com --device ou CACHALOTE_DEV).
#
# Uso num script:
#   . "$(dirname "$0")/lib-device.sh"
#   DEV="$(cachalote_escolher_dispositivo "${1:-}")"      # opcional: o que veio em --device
#
# Ver docs/knowledge-android.md (item 95).

cachalote_listar_dispositivos() {
  adb devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { print $1 }'
}

cachalote_escolher_dispositivo() {
  local pedido="${1:-}"
  if [ -n "$pedido" ]; then echo "$pedido"; return 0; fi
  if [ -n "${CACHALOTE_DEV:-}" ]; then echo "$CACHALOTE_DEV"; return 0; fi
  local lista
  lista="$(cachalote_listar_dispositivos)"
  local n
  n="$(printf '%s\n' "$lista" | grep -c . || true)"
  if [ "$n" = "1" ]; then echo "$lista"; return 0; fi
  if [ "$n" = "0" ]; then
    echo "erro: nenhum dispositivo ligado (adb devices vazio). Liga por USB e autoriza a depuração, ou define CACHALOTE_DEV." >&2
    return 1
  fi
  echo "erro: há $n dispositivos ligados; escolhe um com --device ou CACHALOTE_DEV:" >&2
  printf '  %s\n' $lista >&2
  return 1
}

# ---------------------------------------------------------------- o daemon em uso
#
# A app pode estar em **root** (base `/data/adb/cachalote`) ou em **Shizuku**
# (`/data/local/tmp/cachalote`). Os testes de dispositivo têm de olhar para a base **do modo em que
# a app está**: assumir `/data/adb` media (ou falhava contra) o outro daemon, e numa app em Shizuku
# o teste dizia «não há daemon instalado» com o motor a correr — foi o que ficou aberto no READINESS
# de 2026-10-19 e se fechou em 2026-10-21.
#
#   BASE="$(cachalote_base "$DEV")"
#   MODO="$(cachalote_modo "$DEV")"          # daemon | shizuku | embedded
#   cachalote_tem_root "$DEV" && ...         # há `su` utilizável (a base é root:600)
#
CACHALOTE_PKG="${CACHALOTE_PKG:-dev.ricardopimentel.cachalote}"

cachalote_modo() { # $1 = serial
  adb -s "${1:?serial}" shell "run-as $CACHALOTE_PKG cat shared_prefs/cachalote.xml" 2>/dev/null \
    | tr -d '\r' \
    | sed -n 's/.*<string name="mode">\([a-z]*\)<\/string>.*/\1/p' \
    | head -1
}

cachalote_base() { # $1 = serial; vazio quando o modo é embutido (não há daemon)
  case "$(cachalote_modo "$1")" in
    shizuku) echo "/data/local/tmp/cachalote" ;;
    embedded) echo "" ;;
    *) echo "/data/adb/cachalote" ;;
  esac
}

cachalote_tem_root() { # $1 = serial
  adb -s "${1:?serial}" shell "su -c 'id -u'" 2>/dev/null | tr -d '\r' | grep -qx 0
}

