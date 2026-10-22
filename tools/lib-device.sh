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


# ---------------------------------------------------------------- conversas dos testes
#
# Os ensaios de dispositivo criam conversas novas na app e, até 2026-10-22, deixavam-nas lá: quem
# corresse `app-ui-test.sh` ou `app-capabilities-test.sh` ficava com o lixo na lista (e a lista
# ainda mostrava conversas apagadas, por causa da cache do projecto). Agora os dois scripts tiram
# uma fotografia às sessões antes, e apagam as que nasceram no ensaio recorrendo ao **daemon**
# (`cachalote.sessions.delete`), que também limpa a cache — apagar a pasta à mão deixava a lista
# a mentir.
#
#   ANTES="$(cachalote_sessoes "$DEV" "$BASE" "$SE_ROOT")"
#   ...
#   for id in $(cachalote_sessoes "$DEV" "$BASE" "$SE_ROOT"); do
#     grep -qx "$id" <<<"$ANTES" || cachalote_apagar_sessao "$DEV" "$BASE" "$SE_ROOT" "$id"
#   done
#
cachalote_sessoes() { # $1=serial $2=base $3=tem_root(0/1)
  local cmd="ls -1 $2/home/sessions/*/ 2>/dev/null"
  if [ "${3:-0}" = 1 ]; then
    adb -s "${1:?serial}" shell "su -c '$cmd'"
  else
    adb -s "${1:?serial}" shell "$cmd"
  fi | tr -d '\r' | grep -E ^[0-9a-f]{8}-[0-9a-f]{4}- | sort -u
}

cachalote_apagar_sessao() { # $1=serial $2=base $3=tem_root $4=id
  local f push="/data/local/tmp/cachalote-del.json"
  f="$(mktemp)"
  printf '{"sessionId":"%s"}' "$4" > "$f" || { rm -f "$f"; return 1; }
  adb -s "$1" push "$f" "$push" >/dev/null 2>&1 || { rm -f "$f"; return 1; }
  rm -f "$f"
  # O `ctl` aceita `@ficheiro` com os parâmetros: evita o inferno de aspas do `su -c` (mesmo truque
  # do tools/vscreen-tool-test.sh).
  local cmd="cp $push $2/tmp/cachalote-del.json && $2/bin/cachalote ctl cachalote.sessions.delete @$2/tmp/cachalote-del.json; rm -f $2/tmp/cachalote-del.json $push"
  if [ "${3:-0}" = 1 ]; then
    adb -s "$1" shell "su -c '$cmd'" >/dev/null 2>&1
  else
    adb -s "$1" shell "$cmd" >/dev/null 2>&1
  fi
}

# Apaga as conversas que nasceram durante o ensaio e confirma que não sobrou nenhuma.
#
# Uma passagem só não chegava: o motor pode criar a sessão (ou escrever o `session.v4.jsonl`)
# depois de o ensaio a listar — em Shizuku aconteceu ficar uma para trás. Insiste até três vezes,
# sempre com a mesma regra: só se apaga o que **não** estava na fotografia inicial.
#
#   cachalote_limpar_sessoes_do_ensaio "$DEV" "$BASE" "$SE_ROOT" "$SESSOES_ANTES"
#   → imprime quantas apagou; devolve 1 se ainda sobrar alguma.
cachalote_limpar_sessoes_do_ensaio() { # $1=serial $2=base $3=root $4="ids iniciais (linhas)"
  local serial="$1" base="$2" root="$3" antes="$4"
  local tentativa id apagadas=0 sobram
  for tentativa in 1 2 3; do
    for id in $(cachalote_sessoes "$serial" "$base" "$root" 2>/dev/null || true); do
      if ! grep -qx "$id" <<<"$antes"; then
        cachalote_apagar_sessao "$serial" "$base" "$root" "$id" && apagadas=$((apagadas + 1))
      fi
    done
    sobram=0
    for id in $(cachalote_sessoes "$serial" "$base" "$root" 2>/dev/null || true); do
      grep -qx "$id" <<<"$antes" || sobram=$((sobram + 1))
    done
    [ "$sobram" = 0 ] && break
    sleep 1
  done
  echo "$apagadas"
  [ "${sobram:-0}" = 0 ]
}
