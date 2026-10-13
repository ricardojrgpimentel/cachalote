#!/bin/bash
# set-key.sh — põe a API key do provedor no daemon do telemóvel.
#
#   ./daemon/set-key.sh                        # pede a chave no terminal (não aparece no ecrã)
#   ./daemon/set-key.sh --check                # mostra o estado, com o valor mascarado
#   ./daemon/set-key.sh --verify               # (depois de gravar) pede um turno real ao agente
#   ./daemon/set-key.sh --remove               # apaga a chave
#   ./daemon/set-key.sh --from-file ~/k.txt    # lê a chave de um ficheiro
#   ./daemon/set-key.sh --from-file ~/k.txt --delete-source
#   ./daemon/set-key.sh --base /data/adb/cachalote       # layout root (por omissão)
#   ./daemon/set-key.sh --no-root --base /data/local/tmp/cachalote
#   ./daemon/set-key.sh --var OPENCODE_GO_API_KEY        # outro provedor
#
# Segurança: a chave NUNCA vai em argumentos (não aparece no `ps`), nunca é ecoada,
# e o ficheiro de destino fica com modo 600. O valor viaja por stdin.
#
# Nota de arquitectura: gravar o ficheiro com o motor a correr não chega — o motor lê
# as credenciais no arranque. O script reinicia-o no fim e espera pelo handshake.
set -euo pipefail

BASE="${CACHALOTE_BASE:-/data/adb/cachalote}"
VAR="DEEPSEEK_API_KEY"
DEV="${CACHALOTE_DEV:-R58M55JNP6X}"
MODE="set"
KEYFILE=""
DELETE_SOURCE=0
ROOT="auto"       # auto | 1 | 0
VERIFY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --check) MODE="check"; shift ;;
    --remove) MODE="remove"; shift ;;
    --verify) VERIFY=1; shift ;;
    --from-file) KEYFILE="$2"; MODE="set"; shift 2 ;;
    --delete-source) DELETE_SOURCE=1; shift ;;
    --base) BASE="$2"; shift 2 ;;
    --var) VAR="$2"; shift 2 ;;
    --device) DEV="$2"; shift 2 ;;
    --root) ROOT=1; shift ;;
    --no-root) ROOT=0; shift ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

# /data/adb é drwx------ root: sem su não se lá chega. Detectar automaticamente.
if [ "$ROOT" = "auto" ]; then
  case "$BASE" in /data/adb*) ROOT=1 ;; *) ROOT=0 ;; esac
fi

ADB=(adb -s "$DEV")
ENVFILE="$BASE/home/.env"
TMPFILE="/data/local/tmp/.cachalote-key.$$"
ROOTFLAG="--no-root"; [ "$ROOT" = 1 ] && ROOTFLAG="--root"

SH() { # corre como root ou como shell, conforme o layout
  if [ "$ROOT" = 1 ]; then "${ADB[@]}" shell "su -c $(printf '%q' "$1")"; else "${ADB[@]}" shell "$1"; fi
}

echo "== cachalote · chave do provedor =="
echo "   dispositivo : $("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')  ($DEV)"
echo "   destino     : $ENVFILE   (variável: $VAR; $([ "$ROOT" = 1 ] && echo root || echo shell))"
echo

if [ "$MODE" = "check" ]; then
  SH "if [ -f $ENVFILE ]; then ls -l $ENVFILE; echo '--- variáveis definidas (valores mascarados) ---'; sed -E 's/^(.*=.{0,4}).*/\\1****/' $ENVFILE; else echo 'sem $ENVFILE'; fi"
  exit 0
fi

if [ "$MODE" = "remove" ]; then
  SH "if [ -f $ENVFILE ]; then grep -v '^$VAR=' $ENVFILE > $ENVFILE.tmp 2>/dev/null || true; mv $ENVFILE.tmp $ENVFILE; chmod 600 $ENVFILE; fi; ls -l $ENVFILE 2>/dev/null || echo 'sem $ENVFILE'"
  echo "chave removida de $ENVFILE"
  exit 0
fi

# ---------------------------------------------------------------- obter a chave
if [ -n "$KEYFILE" ]; then
  [ -f "$KEYFILE" ] || { echo "ERRO: não encontrei $KEYFILE" >&2; exit 1; }
  KEY="$(tr -d '\r\n' < "$KEYFILE")"
else
  if [ ! -t 0 ]; then
    echo "ERRO: sem terminal interactivo. Usa --from-file <ficheiro> ou corre isto no teu terminal." >&2
    exit 1
  fi
  printf 'Cola a API key (não aparece no ecrã) e prime Enter: '
  IFS= read -r -s KEY
  printf '\n'
fi
KEY="${KEY%$'\r'}"
KEY="$(printf '%s' "$KEY" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
[ -n "$KEY" ] || { echo "ERRO: chave vazia." >&2; exit 1; }
[ "${#KEY}" -lt 16 ] && echo "AVISO: a chave tem só ${#KEY} caracteres."

# ---------------------------------------------------------------- gravar
# Passo 1: valor por stdin para /data/local/tmp (escrevível pelo shell; nunca em argv).
printf '%s=%s\n' "$VAR" "$KEY" | "${ADB[@]}" shell "cat > $TMPFILE && chmod 600 $TMPFILE" >/dev/null
# Passo 2: como root, substituir só a nossa linha no .env, sempre com modo 600.
SH "umask 077; mkdir -p $BASE/home; touch $ENVFILE; grep -v '^$VAR=' $ENVFILE > $TMPFILE.env 2>/dev/null || true; cat $TMPFILE >> $TMPFILE.env; mv $TMPFILE.env $ENVFILE; chmod 600 $ENVFILE; rm -f $TMPFILE; ls -l $ENVFILE"
unset KEY

if [ "$DELETE_SOURCE" = 1 ] && [ -n "$KEYFILE" ]; then
  rm -f "$KEYFILE" && echo "ficheiro de origem apagado: $KEYFILE"
fi

# ---------------------------------------------------------------- motor
echo
echo "a reiniciar o motor (ele lê as credenciais no arranque)..."
SH "$BASE/bin/cachalote restart >/dev/null 2>&1 || true"
for _ in $(seq 1 20); do
  if SH "$BASE/bin/cachalote status" 2>/dev/null | grep -q "OK deepseek-harness-acp"; then
    echo "motor pronto."
    break
  fi
  sleep 3
done

# ---------------------------------------------------------------- verificação
if [ "$VERIFY" = 1 ]; then
  echo
  echo "== a pedir um turno real ao agente (a chave não é mostrada) =="
  OUT="$(SH "LD_LIBRARY_PATH=$BASE/runtime/lib $BASE/runtime/bin/node $BASE/bin/client-smoke.cjs $BASE/run/cachalote.sock --prompt 'diz apenas: ok' --timeout 120" 2>&1 || true)"
  if echo "$OUT" | grep -q "agent_message_chunk"; then
    echo "   OK  o agente respondeu — o modelo está a funcionar com esta chave"
  elif echo "$OUT" | grep -qi "Authentication Fails\|no API key\|invalid"; then
    echo "   FALHA  a chave foi gravada mas a API recusou-a:"
    echo "$OUT" | grep -io "Authentication Fails.*\|no API key[^\"]*" | head -2 | sed 's/^/      /'
  else
    echo "   ?  resposta inesperada; fim do output:"
    echo "$OUT" | tail -6 | sed 's/^/      /'
  fi
fi

echo
echo "estado final:"
"$0" --check --base "$BASE" --device "$DEV" --var "$VAR" "$ROOTFLAG"
