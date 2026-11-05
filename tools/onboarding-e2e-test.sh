#!/bin/bash
# onboarding-e2e-test.sh — o primeiro arranque, sem AI e sem daemon.
#
#   ./tools/onboarding-e2e-test.sh --device emulator-5554
#
# Faz `pm clear` na app de debug, abre o assistente, confirma que o primeiro «Continuar» avança
# apesar de o kernel estar a preparar, que o passo 2 mostra os três modos, e que «Saltar» leva à
# lista. Não espera pelos 211 MB do kernel: o objectivo é o percurso de entrada, não o download.
set -uo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
. "$WS/tools/lib-device.sh"
. "$WS/tools/lib-e2e.sh"

PKG="${CACHALOTE_PKG:-dev.ricardopimentel.cachalote}"
DEV=""
PEDIDO_DEV=""
FALHAS=0
OK=0

while [ $# -gt 0 ]; do
  case "$1" in
    --device) PEDIDO_DEV="$2"; shift 2 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done
DEV="$(cachalote_escolher_dispositivo "$PEDIDO_DEV")" || exit 1
case "$DEV" in
  emulator-*) ;;
  *) echo "ERRO: este ensaio só corre num emulador (recebi $DEV)." >&2; exit 2 ;;
esac
ADB=(adb -s "$DEV")
E2E_PKG="$PKG"
E2E_ACT="$PKG/.MainActivity"

ok()   { echo "   OK     $1"; OK=$((OK + 1)); }
fail() { echo "   FALHA  $1"; FALHAS=$((FALHAS + 1)); }

limpar() { "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true; }
trap limpar EXIT

if ! "${ADB[@]}" shell "run-as $PKG id" >/dev/null 2>&1; then
  echo "ERRO: a app instalada não é debug (run-as falhou)." >&2
  exit 2
fi

echo "== primeiro arranque =="
"${ADB[@]}" shell pm clear "$PKG" >/dev/null
"${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null
e2e_init onboarding
for _ in $(seq 1 20); do e2e_dump && break; sleep 1; done

EN=0; e2e_has "Welcome to Cachalote" && EN=1
t() { if [ "$EN" = 1 ]; then echo "$2"; else echo "$1"; fi; }

if e2e_wait_for "$(t "Bem-vindo ao Cachalote" "Welcome to Cachalote")" 20; then
  ok "o assistente abriu no passo 1"
else
  fail "o assistente abriu no passo 1"
fi
if e2e_has "$(t "PASSO 1 DE 6" "STEP 1 OF 6")"; then ok "o cabeçalho diz passo 1 de 6"; else fail "o cabeçalho diz passo 1 de 6"; fi

# O «Continuar» do passo 1 tem de avançar mesmo com o kernel a preparar (o bug da 0.3.2).
if e2e_tap "$(t "Continuar" "Continue")" >/dev/null 2>&1; then ok "o Continuar do passo 1 aceitou o toque"; else fail "o Continuar do passo 1 aceitou o toque"; fi
if e2e_wait_for "$(t "PASSO 2 DE 6" "STEP 2 OF 6")" 15; then ok "chegou ao passo 2"; else fail "chegou ao passo 2"; fi
if e2e_has "$(t "Com root" "With root")" && e2e_has "$(t "Com Shizuku" "With Shizuku")" && e2e_has "$(t "Sem nada" "Nothing")"; then
  ok "os três modos estão à vista"
else
  fail "os três modos estão à vista"
fi

if e2e_tap_exact "$(t "Saltar" "Skip")" >/dev/null 2>&1; then ok "o Saltar aceitou o toque"; else fail "o Saltar aceitou o toque"; fi
if e2e_wait_for "$(t "Ainda não há conversas" "No conversations yet")" 15; then
  ok "o Saltar levou à lista"
else
  fail "o Saltar levou à lista"
fi

echo
echo "== onboarding e2e: $OK ok, $FALHAS falhas =="
[ "$FALHAS" -eq 0 ]
