#!/bin/bash
# app-capabilities-test.sh — verifica as capacidades Android do agente (F3-B/C/D).
#
#   ./tools/app-capabilities-test.sh            # rápido: acessibilidade + status + notificação
#   ./tools/app-capabilities-test.sh --full     # + interacção real (abre a calculadora e toca)
#
# Requer o daemon instalado e a chave já configurada (o agente tem de responder). Corre contra o
# daemon **do modo em que a app está** (root em /data/adb ou Shizuku em /data/local/tmp — ver
# lib-device.sh); no modo embutido não há daemon, e o teste di-lo em vez de falhar.
set -uo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
# O dispositivo vem de --device/CACHALOTE_DEV ou é autodetectado (nunca fixo no repo).
. "$WS/tools/lib-device.sh"
DEV="$(cachalote_escolher_dispositivo)" || exit 1
PKG="dev.ricardopimentel.cachalote"
ACT="$PKG/.MainActivity"
SVC="$PKG/$PKG.CachaloteAccessibilityService"
UI="$WS/.work/ui.xml"
FULL=0
[ "${1:-}" = "--full" ] && FULL=1

ADB=(adb -s "$DEV")

# Ver lib-device.sh: a base depende do modo em que a app está.
BASE="$(cachalote_base "$DEV")"
MODO="$(cachalote_modo "$DEV")"
SE_ROOT=0
cachalote_tem_root "$DEV" && SE_ROOT=1
# Corre um comando no telefone como root quando há `su` (a base é root:600), senão como shell.
sh_do() { if [ "$SE_ROOT" = 1 ]; then "${ADB[@]}" shell "su -c '$1'"; else "${ADB[@]}" shell "$1"; fi; }
# As conversas que nascem no ensaio são apagadas no fim (lib-device.sh) — este teste cria duas
# (o estado das capacidades e a notificação) e até 2026-10-22 deixava-as na lista do utilizador.
SESSOES_ANTES="$(cachalote_sessoes "$DEV" "$BASE" "$SE_ROOT" 2>/dev/null || true)"
if [ "$MODO" = "embedded" ] || [ -z "$BASE" ]; then
  echo "nota: a app está no modo embutido — não há daemon para inspeccionar (os passos de ficheiro"
  echo "      da sessão ficam de fora); as capacidades testam-se na mesma."
fi
ok=0; falhas=()
pass() { echo "   OK    $1"; ok=$((ok+1)); }
fail() { echo "   FALHA $1"; falhas+=("$1"); }
check() { [ "$1" = 0 ] && pass "$2" || fail "$2"; }

L() { "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "${ADB[@]}" exec-out cat /sdcard/ui.xml > "$UI" 2>/dev/null; }
T() {
  L; local c; c="$(python3 "$WS/tools/ui.py" "$UI" find "$1" 2>/dev/null)" || return 1
  set -- $c; "${ADB[@]}" shell input tap "$1" "$2" >/dev/null 2>&1; sleep 1.6
}
long_text() { python3 - "$UI" <<'PY'
import sys, xml.etree.ElementTree as ET
try: t=ET.parse(sys.argv[1])
except Exception: print(""); raise SystemExit
best=""
for n in t.getroot().iter('node'):
    txt=n.get('text') or ''
    if len(txt)>len(best): best=txt
print(best)
PY
}
ask() { # ask "<prompt>" — escreve no ecrã da conversa e envia
  T "Conversa" || true
  L; python3 "$WS/tools/ui.py" "$UI" find "Escreve uma tarefa" >/dev/null 2>&1 || T "Nova sessão"
  T "Escreve uma tarefa" || true
  "${ADB[@]}" shell "input text '$1'" >/dev/null 2>&1
  sleep 1; T "Enviar" || true
}

echo "== 0. app, serviço em primeiro plano e acessibilidade =="
"${ADB[@]}" shell "am start -n $ACT" >/dev/null 2>&1
sleep 5
"${ADB[@]}" shell "dumpsys activity services $PKG" 2>/dev/null | grep -q "isForeground=true" \
  && check 0 "serviço de agente em primeiro plano" || check 1 "serviço de agente em primeiro plano"

if "${ADB[@]}" shell "dumpsys accessibility | grep -q 'Bound services:{Service\\[label=Cachalote'" 2>/dev/null; then
  check 0 "serviço de acessibilidade ligado"
else
  echo "   (a ligar o serviço de acessibilidade via adb…)"
  "${ADB[@]}" shell "settings put secure enabled_accessibility_services $SVC; settings put secure accessibility_enabled 1"
  sleep 3
  "${ADB[@]}" shell "dumpsys accessibility | grep -q 'Bound services:{Service\\[label=Cachalote'" 2>/dev/null \
    && check 0 "serviço de acessibilidade ligado (activado agora)" || check 1 "serviço de acessibilidade ligado"
fi

echo "== 1. autorizar todas as capacidades na app (Ajustes -> Capacidades) =="
# A navegação mudou: já não há separador «Poderes» (ver STATUS §60). As capacidades vivem em
# Ajustes, no menu de secções. E a lista é longa: o «Autorizar todas» pode ficar abaixo da dobra,
# por isso insistimos com um scroll entre tentativas.
FOUND=1
ir_para_capacidades() {
  "${ADB[@]}" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1; sleep 2
  # A conversa aberta **não tem barra de abas** (é o desenho): sem sair dela, o toque em «Ajustes»
  # caía no vazio e o passo «interruptores visíveis» falhava (visto no S10+ em 2026-10-21, logo a
  # seguir ao passo 3, que deixa a conversa aberta). Sai-se até a barra voltar.
  local i
  for i in 1 2 3 4; do
    L
    if python3 "$WS/tools/ui.py" "$UI" find-exact "Conversa" >/dev/null 2>&1; then break; fi
    "${ADB[@]}" shell input keyevent 4 >/dev/null 2>&1
    sleep 1
  done
  T "Ajustes" || true; sleep 1
  local i
  for i in 1 2 3; do
    L
    python3 "$WS/tools/ui.py" "$UI" exists "módulos" && break
    "${ADB[@]}" shell input keyevent 4 >/dev/null 2>&1; sleep 1
  done
  # Correspondência exacta: o `find` é substring e «Capacidades» também está em
  # «(no separador Capacidades)» e no subtítulo da linha.
  local c
  c="$(python3 "$WS/tools/ui.py" "$UI" find-exact "Capacidades autorizadas" 2>/dev/null)" || true
  if [ -n "$c" ]; then set -- $c; "${ADB[@]}" shell input tap "$1" "$2" >/dev/null 2>&1; fi
  sleep 2
}
for _ in 1 2 3; do
  ir_para_capacidades
  T "Autorizar todas" || true
  sleep 1
  L
  # O rótulo da capacidade na UI é «Ver o ecrã» (o id `screenshot` não aparece no ecrã), por isso
  # o teste procura o botão da secção — que é o que diz «estamos nas Capacidades» (2026-10-21).
  if python3 "$WS/tools/ui.py" "$UI" find "Autorizar todas" >/dev/null 2>&1; then FOUND=0; break; fi
  "${ADB[@]}" shell input swipe 720 2000 720 1100 200 >/dev/null 2>&1
  sleep 1
done
check "$FOUND" "interruptores visíveis"

echo "== 2. o agente pergunta o estado das capacidades (canal reverso) =="
ask "usa a ferramenta android_status e responde apenas com o JSON que ela devolve, sem comentarios"
RESP=""
for i in $(seq 1 20); do
  sleep 6; L; RESP="$(long_text)"
  echo "$RESP" | grep -q '"accessibility"' && break
done
echo "$RESP" | grep -q '"accessibility": true' && check 0 "android_status com acessibilidade ligada" || {
  check 1 "android_status com acessibilidade ligada"
  echo "      (obtido: $(echo "$RESP" | head -c 160))"
}

echo "== 3. notificação (android_notify) =="
ask "usa a ferramenta android_notify para avisar que o teste de capacidades passou"
sleep 20
"${ADB[@]}" shell "dumpsys notification --noredact 2>/dev/null | grep -q 'cachalote_agent'" \
  && check 0 "notificação publicada pela app" || check 1 "notificação publicada pela app"

if [ "$FULL" = 1 ]; then
  echo "== 4. interacção real: abrir a calculadora, ler o ecrã e tocar =="
  # Limpar o estado da calculadora para o teste ser honesto (senão o 7 podia ser herdado).
  "${ADB[@]}" shell "am force-stop com.android.calculator2" >/dev/null 2>&1
  ask "Faz isto: 1) abre a calculadora com o bash (am start -n com.android.calculator2/.Calculator). 2) android_screen. 3) android_tap com text 7. 4) android_screen outra vez e diz se o visor mostra 7."
  sleep 75
  "${ADB[@]}" shell uiautomator dump /sdcard/calc.xml >/dev/null 2>&1
  "${ADB[@]}" exec-out cat /sdcard/calc.xml > "$WS/.work/calc.xml" 2>/dev/null
  if python3 - "$WS/.work/calc.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
t=ET.parse(sys.argv[1])
for n in t.getroot().iter('node'):
    if 'formula' in (n.get('resource-id') or '') and (n.get('text') or '') == '7':
        raise SystemExit(0)
raise SystemExit(1)
PY
  then check 0 "a calculadora mostra 7 (o agente tocou no botão)"; else fail "a calculadora mostra 7 (o agente tocou no botão)"; fi
fi

if [ "$FULL" = 1 ]; then
  # Este é o passo que o STATUS §20.1 deixou por verificar: em §18 provou-se o caminho
  # "imagem → anexo → modelo" a enviar a imagem à mão, mas nunca com o AGENTE a chamar a
  # ferramenta (as tentativas morriam na guarda de caminhos do bash e na automação da UI).
  echo "== 5. visão real: o agente chama android_see e lê os pixais =="
  # Põe-se um número no visor SEM o dizer ao modelo: só se acerta vendo a imagem.
  # O ecrã fica na calculadora durante todo o turno (o cliente ACP não mexe na UI), para a
  # captura não apanhar uma transição de janela — foi o que estragou a 1.ª tentativa desta
  # verificação: a captura saiu a meio da animação, com o visor (alinhado à direita) cortado.
  SEE_NUM=512
  TEX() { L; local c; c="$(python3 "$WS/tools/ui.py" "$UI" find-exact "$1" 2>/dev/null)" || return 1; set -- $c; "${ADB[@]}" shell input tap "$1" "$2" >/dev/null 2>&1; sleep 0.5; }

  "${ADB[@]}" shell "am force-stop com.android.calculator2" >/dev/null 2>&1
  "${ADB[@]}" shell "am start -n com.android.calculator2/.Calculator" >/dev/null 2>&1
  sleep 4
  for d in 5 1 2; do TEX "$d" || echo "   (não encontrei o botão '$d')"; done
  sleep 2
  L
  if python3 - "$UI" "$SEE_NUM" <<'PY'
import sys, xml.etree.ElementTree as ET
want = sys.argv[2]
t = ET.parse(sys.argv[1])
for n in t.getroot().iter('node'):
    if 'formula' in (n.get('resource-id') or '') and (n.get('text') or '') == want:
        raise SystemExit(0)
raise SystemExit(1)
PY
  then pass "o visor da calculadora mostra $SEE_NUM (o prompt não o diz)"
  else fail "o visor da calculadora mostra $SEE_NUM (o prompt não o diz)"; fi

  # Conduzir o agente pelo ecrã da app (é a app que tem as capacidades e o lugar de cliente
  # ACP; um cliente ACP externo desaloja-a e o android_see falha com "a app não está ligada").
  # O prompt escreve-se com a app à frente; o número tem de estar no ecrã quando a captura
  # acontecer, por isso o toque em "Enviar" e o trazer da calculadora para a frente vão na
  # MESMA invocação de shell: dois round-trips adb separados atrasam a transição o suficiente
  # para a captura sair a meio da animação (a janela deslizante corta o visor à direita).
  "${ADB[@]}" shell "am start -n $ACT" >/dev/null 2>&1
  sleep 3
  T "Conversa" || true
  L; python3 "$WS/tools/ui.py" "$UI" find "Escreve uma tarefa" >/dev/null 2>&1 || T "Nova sessão"
  T "Escreve uma tarefa" || true
  "${ADB[@]}" shell "input text 'usa a ferramenta android_see para VER o ecra e responde apenas com o numero que esta no visor. Nao uses o bash nem o android_screen nem outra ferramenta'" >/dev/null 2>&1
  sleep 1
  L
  EC="$(python3 "$WS/tools/ui.py" "$UI" find-exact "Enviar" 2>/dev/null)"
  if [ -n "$EC" ]; then
    set -- $EC
    "${ADB[@]}" shell "input tap $1 $2; am start -n com.android.calculator2/.Calculator" >/dev/null 2>&1
    pass "prompt enviado (toque + calculadora para a frente na mesma invocação)"
  else
    fail "botão Enviar encontrado"
  fi
  sleep 50

  # Verificação no registo da sessão do motor: é autoritativo e não depende do stream da app.
  SFILE=""
  [ -n "$BASE" ] && SFILE="$(sh_do "ls -t $BASE/home/sessions/*/*/session.v4.jsonl.zstd 2>/dev/null | head -1" | tr -d '\r')"
  if [ -n "$SFILE" ] && [ "$SE_ROOT" = 1 ]; then
    "${ADB[@]}" exec-out "su -c 'cat $SFILE'" > "$WS/.work/see-session.zst" 2>/dev/null
  else
    : > "$WS/.work/see-session.zst"
  fi
  if [ -z "$SFILE" ] || [ "$SE_ROOT" = 0 ]; then
    # Não é falha: no embutido não há daemon, e sem root não se lê o ficheiro da sessão.
    echo "   SALTADO registo da sessão (modo $MODO, sem ficheiro a ler)"
  elif zstd -dc "$WS/.work/see-session.zst" > "$WS/.work/see-session.jsonl" 2>/dev/null; then
    if python3 - "$WS/.work/see-session.jsonl" "$SEE_NUM" <<'PY'
import json, sys
path, want = sys.argv[1], sys.argv[2]
# A app RETOMA a mesma sessão entre corridas (a destilada do app-ui-test fica no mesmo
# registo), por isso há que olhar só para o ÚLTIMO turno — senão apanhamos ferramentas e
# erros de corridas anteriores.
turns, cur = [], None
for line in open(path, encoding="utf-8", errors="replace"):
    line = line.strip()
    if not line:
        continue
    try:
        o = json.loads(line)
    except Exception:
        continue
    t, d = o.get("type"), o.get("data") or {}
    if t == "turn/start":
        cur = {"tools": [], "texts": [], "images": 0, "err": None}
        turns.append(cur)
        continue
    if cur is None:
        continue
    if t == "tool/call":
        cur["tools"].append(d.get("name"))
    elif t == "tool/result":
        for c in ((d.get("message") or {}).get("content") or []):
            if c.get("type") == "image" and c.get("attachment"):
                cur["images"] += 1
    elif t == "assistant/message":
        for c in ((d.get("message") or {}).get("content") or []):
            if c.get("type") == "text" and c.get("text"):
                cur["texts"].append(c["text"])
    elif t == "turn/end":
        r = d.get("reason") or {}
        if r.get("kind") == "error":
            cur["err"] = (r.get("error") or {}).get("message")
if not turns:
    print("   FALHA não encontrei nenhum turno no registo da sessão")
    raise SystemExit(1)
last = turns[-1]
tools, images, err = last["tools"], last["images"], last["err"]
final = " ".join(last["texts"]).strip()
print(f"   turnos no registo: {len(turns)} (o teste olha para o último)")
print(f"   ferramentas: {', '.join(str(x) for x in tools) or '(nenhuma)'}")
print(f"   blocos de imagem no resultado: {images}")
print(f"   resposta final: {final[:200] or '(nenhuma)'}")
if err:
    print(f"   turno terminou com erro: {err}")
problems = []
if not tools or any(t != "android_see" for t in tools):
    problems.append(f"android_see devia ser a única ferramenta (chamadas: {tools})")
if images < 1:
    problems.append("o resultado não trouxe bloco de imagem com anexo")
if err:
    problems.append(f"o turno terminou com erro: {err}")
if want not in final:
    problems.append(f"a resposta final não contém {want}")
for p in problems:
    print("   FALHA " + p)
raise SystemExit(1 if problems else 0)
PY
    then pass "o agente chamou android_see e o modelo leu $SEE_NUM da imagem"
    else fail "o agente chamou android_see e o modelo leu $SEE_NUM da imagem"; fi
  else
    fail "registo da sessão legível (zstd)"
  fi
fi

echo
echo "== 4. apagar as conversas que este ensaio criou =="
APAGADAS=0
if [ -n "$BASE" ]; then
  # Em `if` para o `set -e` não matar o script quando ainda sobra alguma (o helper devolve 1).
  if APAGADAS="$(cachalote_limpar_sessoes_do_ensaio "$DEV" "$BASE" "$SE_ROOT" "$SESSOES_ANTES")"; then
    check 0 "as conversas do ensaio foram apagadas ($APAGADAS)"
  else
    check 1 "ficaram conversas do ensaio na lista"
  fi
else
  echo "   (modo embutido: não há daemon para apagar sessões)"
fi
echo
echo "== resumo: $ok passos OK, ${#falhas[@]} falhas =="
for f in "${falhas[@]:-}"; do [ -n "$f" ] && echo "   - $f"; done
[ "${#falhas[@]}" -eq 0 ] || exit 1
