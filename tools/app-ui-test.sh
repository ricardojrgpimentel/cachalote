#!/bin/bash
# app-ui-test.sh — testa a app instalada por adb (uiautomator + input), sem tocar no ecrã.
#
# Valida o percurso completo: estado do daemon → obter token → colar a chave no ecrã da app →
# guardar no daemon → conferir no ficheiro → remover → conversa.
#
#   ./tools/app-ui-test.sh [--keep-key] [--device SERIAL]
#
# Corre contra o daemon **do modo em que a app está** (root em /data/adb ou Shizuku em
# /data/local/tmp — ver lib-device.sh). Antes assumia /data/adb e, com a app em Shizuku, dizia «não
# há daemon instalado» com o motor a correr (READINESS, 2026-10-19).
#
# Usa uma chave de TESTE (sk-dummy-…) e limpa-a no fim, salvo --keep-key.
set -uo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
# O dispositivo vem de --device/CACHALOTE_DEV ou é autodetectado (nunca fixo no repo).
. "$WS/tools/lib-device.sh"
DEV="$(cachalote_escolher_dispositivo)" || exit 1
PKG="dev.ricardopimentel.cachalote"
ACT="$PKG/.MainActivity"
DUMMY="sk-dummy-app-ui-1234567890"
KEEP=0
VAR="DEEPSEEK_API_KEY"
UI="$WS/.work/ui.xml"

while [ $# -gt 0 ]; do
  case "$1" in
    --keep-key) KEEP=1; shift ;;
    --device) DEV="$2"; shift 2 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done
ADB=(adb -s "$DEV")

# O daemon **em uso**: a app pode estar em root (/data/adb) ou Shizuku (/data/local/tmp). Sem isto
# o teste media o outro daemon — ou dizia que não havia nenhum. `sh_do` corre como root quando há
# `su` (a base é root:600) e como shell quando não há, para o teste não mentir sobre o que vê.
BASE="$(cachalote_base "$DEV")"
MODO="$(cachalote_modo "$DEV")"
SE_ROOT=0
cachalote_tem_root "$DEV" && SE_ROOT=1
sh_do() { if [ "$SE_ROOT" = 1 ]; then "${ADB[@]}" shell "su -c '$1'"; else "${ADB[@]}" shell "$1"; fi; }
# As conversas que nascem no ensaio são apagadas no fim (lib-device.sh): sem isto, cada corrida
# deixava uma conversa de teste na lista do utilizador.
SESSOES_ANTES="$(cachalote_sessoes "$DEV" "$BASE" "$SE_ROOT" 2>/dev/null || true)"
ok=0; falhas=()

pass() { echo "   OK    $1"; ok=$((ok+1)); }
fail() { echo "   FALHA $1"; falhas+=("$1"); }
check() { if [ "$1" = 0 ]; then pass "$2"; else fail "$2"; fi; }

SHOTN=0
dump() {
  SHOTN=$((SHOTN+1))
  "${ADB[@]}" exec-out screencap -p > "$WS/.work/ui-test-$SHOTN.png" 2>/dev/null
  "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "${ADB[@]}" exec-out cat /sdcard/ui.xml > "$UI" 2>/dev/null
  [ -s "$UI" ]
}
# BACK só quando há teclado: sem ele, o BACK fecha a app (foi o que fazia o teste falhar).
# Depois do BACK confirmamos que a app continua à frente: se o `dumpsys` estava desactualizado
# e já não havia teclado, o BACK fechou a app e temos de a trazer de volta.
hide_kb() {
  if "${ADB[@]}" shell "dumpsys input_method | grep -q 'mInputShown=true'" 2>/dev/null; then
    "${ADB[@]}" shell input keyevent 4 >/dev/null 2>&1
    sleep 0.6
    foreground_app || true
  fi
}

coords() { python3 "$WS/tools/ui.py" "$UI" find "$1" 2>/dev/null; }
# Traz a app para a frente: sem isto os toques caem noutra app (calculadora, definições…).
foreground_app() {
  local focus
  focus="$("${ADB[@]}" shell "dumpsys window | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r')"
  case "$focus" in
    *"$PKG"*) return 0 ;;
    *) "${ADB[@]}" shell "am start -n $ACT" >/dev/null 2>&1; sleep 3; return 1 ;;
  esac
}
# Corresponde ao texto EXACTO: evita apanhar dicas como "(no separador Chave)" em vez do
# separador verdadeiro da barra de navegação.
tap_exact() {
  local c attempt
  for attempt in 1 2 3; do
    dump
    c="$(python3 "$WS/tools/ui.py" "$UI" find-exact "$1" 2>/dev/null)" && break
    foreground_app || true
    "${ADB[@]}" shell input swipe 720 2000 720 1100 200 >/dev/null 2>&1
    sleep 1
  done
  [ -n "${c:-}" ] || { echo "   (não encontrei '$1' exacto)"; return 1; }
  set -- $c
  "${ADB[@]}" shell input tap "$1" "$2" >/dev/null 2>&1
  sleep 1.2
}

tap_text() {
  local c attempt
  for attempt in 1 2 3; do
    dump
    c="$(coords "$1")" && break
    foreground_app || true
    # Os ecrãs têm crescido: se o alvo não está visível, rolar e tentar outra vez.
    "${ADB[@]}" shell input swipe 720 2000 720 1100 200 >/dev/null 2>&1
    sleep 1
  done
  [ -n "${c:-}" ] || { echo "   (não encontrei '$1' no ecrã)"; return 1; }
  set -- $c
  "${ADB[@]}" shell input tap "$1" "$2" >/dev/null 2>&1
  sleep 1.2
}
has() { python3 "$WS/tools/ui.py" "$UI" exists "$1" 2>/dev/null; }

# Vai a uma secção dos Ajustes.
#
# Antes isto procurava os separadores «Estado» e «Chave», que **já não existem**: a barra de baixo
# passou a ter Conversa/Resumo/Ajustes e o resto virou um menu de secções (ver STATUS §60 e §69).
# O teste andava à procura de um ecrã que já não havia — e falhava nos passos 2, 3 e 4.
ir_para_secao() {
  foreground_app || true
  sleep 1
  hide_kb
  # Garantir que estamos nos Ajustes (a barra de baixo tem o rótulo exacto).
  tap_exact "Ajustes" >/dev/null 2>&1 || true
  sleep 1
  # Se estivermos dentro de outra secção, o BACK volta ao menu (há BackHandler).
  local i
  for i in 1 2 3; do
    dump
    has "módulos" && break
    "${ADB[@]}" shell input keyevent 4 >/dev/null 2>&1
    sleep 1
  done
  # Correspondência EXACTA: o `find` é substring e sem isto «Motor» casava com o cabeçalho
  # «motor DSH no telefone · ligado» e o toque caía no vazio (era o que fazia o passo 3 falhar).
  tap_exact "$1" || true
  sleep 1
}

# ⚠ SEGURANÇA: este teste grava (e remove) uma chave de teste no daemon. Se já existir
# uma chave real, guardamo-la e repomo-la no fim — nunca deixar o utilizador sem chave.
HAD_KEY=0
if [ "$SE_ROOT" = 1 ] && sh_do "grep -q \"^$VAR=\" $BASE/home/.env" 2>/dev/null; then
  HAD_KEY=1
  sh_do "cp -f $BASE/home/.env $BASE/home/.env.bak && chmod 600 $BASE/home/.env.bak"
  echo "(chave existente guardada em $BASE/home/.env.bak — será reposta no fim)"
fi
restore_key() {
  if [ "$HAD_KEY" = 1 ]; then
    echo "== a repor a chave que já existia =="
    sh_do "cp -f $BASE/home/.env.bak $BASE/home/.env; chmod 600 $BASE/home/.env; rm -f $BASE/home/.env.bak; $BASE/bin/cachalote restart >/dev/null 2>&1"
    N=$(sh_do "grep -c \"^$VAR=\" $BASE/home/.env" 2>/dev/null | tr -d '\r\n ')
    [ "${N:-0}" = "1" ] && echo "   OK  chave reposta e motor reiniciado" || echo "   FALHA  não consegui repor a chave (ver $BASE/home/.env.bak)"
  fi
}
trap restore_key EXIT

# Este teste precisa do **daemon instalado** (é ele que traz o motor, o token e o .env). Numa
# instalação só com a app — o caso de quem quer ver o percurso de um utilizador — não há daemon, e
# falhar em cascata não diz nada a ninguém. Diz-se o que falta e sai-se.
if [ "$MODO" = "embedded" ] || [ -z "$BASE" ]; then
  echo "SALTADO: a app está no modo embutido — este teste é do daemon (root ou Shizuku)."
  echo "         No embutido não há daemon, token nem .env: usa tools/app-capabilities-test.sh."
  exit 0
fi
if ! sh_do "test -x $BASE/bin/cachalote && echo sim" 2>/dev/null | grep -q sim; then
  echo "SALTADO: não há daemon instalado em $BASE (modo $MODO)."
  echo "         Este teste corre contra o motor; instala-o na app (Assistente → Ligar o motor)."
  exit 0
fi
if [ "$SE_ROOT" = 0 ]; then
  # O .env é root:600. Sem root conseguimos testar a app, mas não o ficheiro — e este teste
  # **escreve** chaves nele. Mais vale dizer que não dá do que falhar a meio.
  echo "SALTADO: sem \`su\` neste telefone, não consigo ler nem guardar $BASE/home/.env."
  echo "         Este teste escreve e repõe a chave no .env do daemon; precisa de root."
  exit 0
fi

echo "== 1. app instalada e aberta =="
"${ADB[@]}" shell "am force-stop $PKG; am start -n $ACT" >/dev/null 2>&1
sleep 4
dump && has "Cachalote" && check 0 "ecrã principal abriu" || check 1 "ecrã principal abriu"

# Numa instalação nova aparece o assistente, e ele **ocupa o ecrã todo**: a barra de baixo não
# existe e nenhuma secção dos Ajustes está acessível. Salta-se para o teste poder correr (é o que
# um utilizador faria depois de configurar).
dump
if has "Já tenho tudo configurado"; then
  tap_text "Já tenho tudo configurado" || true
  sleep 2
  echo "   (assistente saltado)"
fi

echo "== 2. estado do daemon =="
ir_para_secao "Motor"
tap_text "Atualizar" || true
sleep 2; dump
has "daemon" && check 0 "estado do daemon visível" || check 1 "estado do daemon visível"
python3 "$WS/tools/ui.py" "$UI" find "daemon" >/dev/null 2>&1 && echo "   (info: $(python3 "$WS/tools/ui.py" "$UI" list | grep -i '"daemon"' | head -1 | cut -c1-90))"

echo "== 3. obter token (root) =="
# Duas causas para a falha intermitente deste passo:
#  (a) a app pode reabrir noutro separador — o índice do separador é `rememberSaveable` e o
#      estado guardado sobrevive à recriação da actividade, e rolar nunca chega ao botão;
#  (b) logo a seguir ao arranque o botão pode ainda estar abaixo da dobra.
# Por isso: garantir o separador Estado e só depois procurar o botão, verificando o resultado.
ensure_token_tab() { ir_para_secao "Motor"; }
ensure_token_tab
# Procurar o estado em TODA a secção, rolando — o cartão «Acesso» fica abaixo da dobra.
procurar_token() {
  local i
  for i in 1 2 3 4; do
    dump
    has "obtido" && return 0
    "${ADB[@]}" shell input swipe 720 2000 720 1200 200 >/dev/null 2>&1
    sleep 1
  done
  dump
  has "obtido"
}
# Se o token **já** está obtido, o botão nem aparece (o «Primeiros passos» só mostra o que falta):
# procurar primeiro e só tocar se ainda não estiver. Era isto que fazia o passo falhar com o token
# obtido — andava à procura de um botão que já não existia.
if ! procurar_token; then
  ensure_token_tab
  tap_text "Obter token" || true
  sleep 5
  procurar_token || true
fi
has "obtido" && check 0 "token obtido" || check 1 "token obtido"

echo "== 4. ecrã da chave: colar e guardar =="
# O teclado pode estar aberto (separador Conversa) e tapa a barra de baixo.
hide_kb; sleep 1
foreground_app || true
hide_kb; sleep 1
# Ir à secção da chave e CONFIRMAR que lá estamos (antes, um toque falhado deixava o teste a
# escrever no vazio e a apagar a chave do utilizador sem se aperceber).
for attempt in 1 2 3; do
  ir_para_secao "Chave do provedor"
  dump
  python3 "$WS/tools/ui.py" "$UI" exists "DEEPSEEK_API_KEY" && break
  foreground_app || true
done
dump
python3 "$WS/tools/ui.py" "$UI" exists "DEEPSEEK_API_KEY" \
  && echo "   (no ecrã da chave)" || { echo "   ERRO: não cheguei ao ecrã da chave — a abortar sem tocar na chave"; restore_key; exit 1; }
sleep 1; dump
# O campo é um OutlinedTextField com password visual: o texto aparece como pontos. Como o
# teclado e o layout mudam, verificamos o estado e repetimos se necessário.
env_has_dummy() {
  sh_do "grep -q \"^DEEPSEEK_API_KEY=$DUMMY\" $BASE/home/.env" >/dev/null 2>&1
}
typed_ok() {
  dump
  python3 "$WS/tools/ui.py" "$UI" list >/dev/null 2>&1 || return 1
  grep -q "EditText" "$UI" 2>/dev/null && python3 - "$UI" <<'PYTYPED'
import sys, xml.etree.ElementTree as ET
t = ET.parse(sys.argv[1])
for n in t.getroot().iter('node'):
    if (n.get('class') or '').endswith('EditText') and (n.get('text') or '').strip():
        raise SystemExit(0)
raise SystemExit(1)
PYTYPED
}
saved=1
for attempt in 1 2 3; do
  tap_text "DEEPSEEK_API_KEY" || { echo "   (ecrã actual: $(python3 "$WS/tools/ui.py" "$UI" list | head -4 | tr '\n' ' ' | cut -c1-140))"; continue; }
  "${ADB[@]}" shell input text "$DUMMY" >/dev/null 2>&1
  sleep 1; hide_kb
  if ! typed_ok; then echo "   (o campo ficou vazio — a repetir)"; continue; fi
  tap_text "Guardar no daemon" || true
  for i in $(seq 1 8); do
    sleep 2
    if env_has_dummy; then saved=0; break; fi
  done
  [ "$saved" = 0 ] && break
done
dump
echo "   (o daemon reinicia o motor ao gravar a chave; a dar-lhe tempo)"
for i in $(seq 1 12); do
  OKH="$(sh_do "$BASE/bin/cachalote status" 2>/dev/null | grep -c 'OK deepseek-harness-acp' || true)"
  [ "${OKH:-0}" -ge 1 ] && break
  sleep 3
done

echo "== 5. confirmar no daemon (ficheiro home/.env) =="
if env_has_dummy; then
  check 0 "o daemon gravou a chave de teste no .env"
else
  check 1 "o daemon gravou a chave de teste no .env (obtido: ${ENV_OUT:0:40}…)"
fi
PERM="$(sh_do "stat -c %a $BASE/home/.env" 2>/dev/null | tr -d '\r')"
[ "$PERM" = "600" ] && check 0 "modo do .env é 600" || check 1 "modo do .env é 600 (obtido: $PERM)"
dump; has "presente" && check 0 "a app mostra a chave como presente" || check 1 "a app mostra a chave como presente"

echo "== 6. conversa: nova sessão + prompt (sem chave válida → erro esperado do modelo) =="
tap_exact "Conversa" || true
sleep 1; dump
tap_text "Nova sessão" || true
sleep 6; dump
has "sessão" && check 0 "sessão criada pela app" || check 1 "sessão criada pela app"

echo "   → a enviar um prompt pelo ecrã da app"
tap_text "Escreve uma tarefa" || tap_text "tarefa" || true
"${ADB[@]}" shell input text "diz%sapenas:%sok" >/dev/null 2>&1
sleep 1; hide_kb; dump
tap_text "Enviar" || true
sleep 12; dump
if has "Authentication Fails" || has "api key"; then
  check 0 "o prompt chegou à API (resposta de autenticação visível no transcript)"
elif has "ok"; then
  check 0 "o agente respondeu no transcript"
else
  check 1 "resposta do agente visível no transcript"
  echo "   (últimos textos: $(python3 "$WS/tools/ui.py" "$UI" list | tail -4 | tr '\n' ' ' | cut -c1-160))"
fi

echo "== 7. limpeza =="
if [ "$KEEP" = 0 ]; then
  RESTA=1
  # Este passo falhava de vez em quando com "remover chave: a ligação ao motor caiu": o toque
  # chegava ao botão (vê-se na captura de ecrã), mas a app estava sem ligação ao daemon.
  # A causa era o `hide_kb` que aqui estava: se o `dumpsys input_method` diz que o teclado
  # está aberto quando já não está, o BACK fecha a APP (é o aviso que está no topo deste
  # ficheiro), e a app passava a reconectar em ciclo. Não se escreve nada neste passo, logo
  # não há teclado nenhum para esconder — o `hide_kb` sai daqui.
  # (A chave real do utilizador está a salvo em .env.bak e é reposta pelo trap no fim.)
  for attempt in 1 2 3; do
    ir_para_secao "Chave do provedor"
    sleep 1; dump
    tap_text "Remover chave" || true
    for i in $(seq 1 6); do
      sleep 2
      RESTA="$(sh_do "grep -c DEEPSEEK_API_KEY $BASE/home/.env 2>/dev/null" 2>/dev/null | tr -d '\r\n ')"
      RESTA="${RESTA:-0}"
      [ "$RESTA" = "0" ] && break 2
    done
    echo "   (a chave de teste ainda lá está — a repetir a remoção)"
  done
  if [ "$RESTA" = "0" ]; then
    check 0 "chave de teste removida (o .env ficou vazio, modo 600)"
  else
    check 1 "chave de teste removida (linhas: $RESTA)"
    # Diagnóstico: a app mostra o motivo num cartão vermelho (ex.: "a ligação ao motor caiu").
    dump
    echo "   (o que a app diz: $(python3 "$WS/tools/ui.py" "$UI" list | grep -iE 'remover|caiu|erro|falh' | head -3 | tr '\n' ' ' | cut -c1-200))"
  fi
else
  echo "   (--keep-key: a chave de teste ficou no dispositivo)"
fi

echo "== 8. apagar as conversas que este ensaio criou =="
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
