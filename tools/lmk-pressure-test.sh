#!/bin/bash
# lmk-pressure-test.sh — o risco "Node morto por LMK" testado a sério: enche a memória do
# telefone e verifica quem sobrevive.
#
#   ./tools/lmk-pressure-test.sh [GB]        # por omissão 6 GB
#
# O que se espera, pela ordem:
#   1. o **alocador** (oom_score_adj +1000) é o primeiro a morrer — é para isso que serve;
#   2. o **daemon** (-800) e o **motor** (-500) sobrevivem, ou o watchdog levanta-os;
#   3. a app (serviço em primeiro plano) sobrevive;
#   4. no fim, a cadeia funciona outra vez (`cachalote doctor` sem problemas).
#
# O processo que aloca memória corre como root com o `oom_score_adj` no máximo, para o kernel
# matar *ele* e não os nossos. Ver docs/knowledge-android.md (item 98).
set -uo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
. "$WS/tools/lib-device.sh"
DEV="$(cachalote_escolher_dispositivo)" || exit 1
BASE="${CACHALOTE_BASE:-/data/adb/cachalote}"
ADB=(adb -s "$DEV")
GB="${1:-6}"
FALHAS=0
ok()   { echo "   OK    $1"; }
warn() { echo "   (!!)  $1"; }
fail() { echo "   FALHA $1"; FALHAS=$((FALHAS + 1)); }
sh_() { "${ADB[@]}" shell "su -c '$1'" 2>/dev/null | tr -d '\r'; }

pid_de() { sh_ "cat $BASE/run/daemon.pid 2>/dev/null"; }
pid_motor() { sh_ "pgrep -f 'dsh/lib/bin.js' | head -1"; }
pid_app() { "${ADB[@]}" shell "pidof dev.ricardopimentel.cachalote" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
vivo() { [ -n "${1:-}" ] && sh_ "test -d /proc/$1 && echo sim" | grep -q sim; }
adj() { sh_ "cat /proc/${1:-0}/oom_score_adj 2>/dev/null"; }

echo "== 0. ponto de partida =="
D0="$(pid_de)"; M0="$(pid_motor)"; A0="$(pid_app)"
echo "   daemon=$D0 (adj $(adj "$D0")) · motor=$M0 (adj $(adj "$M0")) · app=$A0"
[ -n "$D0" ] && ok "daemon a correr" || fail "daemon não está a correr (corre: cachalote start)"
[ "$(adj "$D0")" -le -500 ] 2>/dev/null && ok "daemon protegido" || warn "daemon sem protecção de memória"

echo "== 1. encher a memória (${GB} GB, o alocador com oom_score_adj=1000) =="
"${ADB[@]}" logcat -c 2>/dev/null || true
# O alocador SEGURA a memória (não sai depois de a obter): sem isto a pressão durava
# milissegundos e não se observava nada.
sh_ "echo 1000 > /proc/self/oom_score_adj; nohup /data/adb/cachalote/runtime/bin/node -e 'const b=[];const cap=${GB}*1024*1024*1024;let n=0;console.log(\"a alocar\");while(n<cap){try{b.push(Buffer.alloc(64*1024*1024));n+=64*1024*1024;console.log(Math.round(n/1048576)+\" MB\");}catch(e){console.log(\"sem memoria: \"+e.message);break}}console.log(\"a segurar 40s\");setTimeout(()=>process.exit(0),40000);' > $BASE/logs/pressure.log 2>&1 & echo alocador=\$!" >/dev/null
sleep 5
ALOC="$(sh_ "pgrep -f 'Buffer.alloc' | head -1")"
echo "   alocador: pid ${ALOC:-?}"

echo "== 2. observar com a memória cheia (30 s) =="
for i in $(seq 1 6); do
  sleep 5
  D="$(pid_de)"; M="$(pid_motor)"; A="$(pid_app)"
  MEM="$("${ADB[@]}" shell 'free -m | sed -n 2p' | tr -d '\r' | awk '{print $3"/"$2" MB usados"}')"
  echo "   $((i*5))s: daemon=$(vivo "$D" && echo vivo || echo morto) motor=$(vivo "$M" && echo vivo || echo morto) app=$(vivo "$A" && echo vivo || echo morto) alocador=$(vivo "$ALOC" && echo vivo || echo morto) · memória: $MEM"
done

echo "== 3. parar a pressão =="
sh_ "pkill -f 'Buffer.alloc'" >/dev/null 2>&1
sleep 3
echo "   memória: $("${ADB[@]}" shell 'free -h | sed -n 2p' | tr -d '\r')"

echo "== 4. o que o kernel matou =="
KILLS="$("${ADB[@]}" logcat -d 2>/dev/null | grep -icE "lowmemorykiller|Killing|lmkd")"
echo "   linhas de low-memory killer no registo: ${KILLS:-0}"
"${ADB[@]}" logcat -d 2>/dev/null | grep -iE "lowmemorykiller|Killing" | tail -3

echo "== 5. a cadeia voltou? =="
D1="$(pid_de)"; M1="$(pid_motor)"; A1="$(pid_app)"
vivo "$D1" && ok "daemon vivo (pid $D1)" || fail "daemon morto e não voltou"
vivo "$M1" && ok "motor vivo (pid $M1)" || fail "motor morto e não voltou"
vivo "$A1" && ok "app viva (pid $A1)" || warn "app não está a correr (pode ter sido fechada pelo sistema)"
if [ "$D1" != "$D0" ]; then
  echo "   (o daemon mudou de pid: $D0 → $D1 — sobreviveu via watchdog)"
fi
SAUDE="$(sh_ "$BASE/bin/cachalote doctor" | tail -1)"
echo "   doctor: ${SAUDE:-sem resposta}"
case "$SAUDE" in
  *"tudo bem"*|*"aviso(s), nenhum problema"*) ok "cadeia saudável depois da pressão" ;;
  *) fail "a cadeia não ficou saudável: ${SAUDE:-?}" ;;
esac

echo
if [ "$FALHAS" = 0 ]; then echo "== resumo: sobreviveu tudo =="; else echo "== resumo: $FALHAS falhas =="; fi
exit "$FALHAS"
