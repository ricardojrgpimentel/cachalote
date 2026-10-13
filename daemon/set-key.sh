#!/bin/bash
# cachalote-set-key — introduz a API key do provedor no telemóvel, sem a mostrar
# a ninguém (nem a este agente, nem ao histórico do shell, nem ao `ps` do Android).
#
#   ./daemon/set-key.sh                 # pede a chave e grava-a no dispositivo
#   ./daemon/set-key.sh --check         # verifica o que lá está (sem mostrar a chave)
#   ./daemon/set-key.sh --remove        # apaga a chave do dispositivo
#   ./daemon/set-key.sh --base /data/adb/cachalote      # layout de F1 (root)
#   ./daemon/set-key.sh --var OPENCODE_GO_API_KEY       # outro provedor
#
# Como funciona: a chave é escrita em <base>/home/.env, que o motor lê como
# camada de credenciais (precedência: ambiente herdado > .credentials.yaml >
# cwd/.env > $DSH_HOME/.env). O ficheiro fica com modo 600.
#
# Porque assim: o motor corre noutro processo e nunca precisa de saber a chave
# para além do próprio ficheiro; a app (F2) vai escrever o mesmo ficheiro a
# partir de um ecrã de definições, com a chave guardada no Keystore.
set -euo pipefail

BASE="${CACHALOTE_BASE:-/data/adb/cachalote}"
VAR="DEEPSEEK_API_KEY"
DEV="${CACHALOTE_DEV:-}"
MODE="set"

while [ $# -gt 0 ]; do
  case "$1" in
    --check) MODE="check"; shift ;;
    --remove) MODE="remove"; shift ;;
    --base) BASE="$2"; shift 2 ;;
    --var) VAR="$2"; shift 2 ;;
    --device) DEV="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

ADB=(adb)
[ -n "$DEV" ] && ADB=(adb -s "$DEV")
ENVFILE="$BASE/home/.env"

echo "== cachalote · chave do provedor =="
echo "   dispositivo : $("${ADB[@]}" get-state 2>/dev/null || echo '?')  $("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
echo "   ficheiro    : $ENVFILE   (variável: $VAR)"
echo

if [ "$MODE" = "check" ]; then
  "${ADB[@]}" shell "if [ -f $ENVFILE ]; then ls -l $ENVFILE; echo '--- variáveis definidas (valores mascarados) ---'; sed -E 's/^(.*=.{0,4}).*/\1****/' $ENVFILE; else echo 'sem $ENVFILE'; fi"
  exit 0
fi

if [ "$MODE" = "remove" ]; then
  "${ADB[@]}" shell "rm -f $ENVFILE" && echo "removido: $ENVFILE"
  exit 0
fi

# ---- introdução da chave ----
if [ ! -t 0 ]; then
  echo "ERRO: precisa de um terminal interactivo (para não passar a chave por argumentos)." >&2
  exit 1
fi

printf 'Cola a API key (não aparece no ecrã) e prime Enter: '
KEY=""
IFS= read -r -s KEY
printf '\n'
KEY="${KEY%$'\r'}"          # limpar CR de colagens
KEY="$(printf '%s' "$KEY" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"

if [ -z "$KEY" ]; then echo "nada introduzido — a sair." >&2; exit 1; fi
case "$KEY" in
  *[[:space:]]*) echo "AVISO: a chave contém espaços; confirma que colaste só a chave." >&2 ;;
esac
if [ "${#KEY}" -lt 16 ]; then echo "AVISO: só ${#KEY} caracteres — parece curta." >&2; fi

printf 'Confirma a chave (cola outra vez): '
KEY2=""
IFS= read -r -s KEY2
printf '\n'
if [ "$KEY" != "${KEY2%$'\r'}" ]; then echo "ERRO: as duas introduções não coincidem. Nada foi alterado." >&2; exit 1; fi

# O valor vai por stdin (nunca em argv, nunca em ficheiro local, nunca ecoado).
printf "%s=%s\n" "$VAR" "$KEY" | "${ADB[@]}" shell "umask 077; mkdir -p '$BASE/home'; touch '$ENVFILE'; chmod 600 '$ENVFILE'; grep -v '^$VAR=' '$ENVFILE' > '$ENVFILE.tmp' 2>/dev/null || true; cat '$ENVFILE.tmp' > '$ENVFILE' 2>/dev/null || true; rm -f '$ENVFILE.tmp'; cat >> '$ENVFILE'; chmod 600 '$ENVFILE'"

echo
echo "gravado ($VAR, ${#KEY} caracteres) em $ENVFILE com modo 600."
echo "verificação:"
"${ADB[@]}" shell "ls -l $ENVFILE; grep -c '^$VAR=' $ENVFILE | sed 's/^/   linhas com $VAR: /'"
echo
echo "a partir daqui o motor encontra a chave sozinho. Para confirmar:"
echo "   ./daemon/set-key.sh --check"
