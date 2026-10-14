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
