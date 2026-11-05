#!/bin/bash
# lib-e2e.sh — utilitários comuns aos testes end-to-end da app (adb + uiautomator).
#
# Os testes que usam esta biblioteca **mexem no telefone**: trazem a app para a frente, escrevem
# no campo da conversa, abrem secções dos Ajustes e, nos testes do motor, chegam a pará-lo. Por
# isso é que o runner tira uma fotografia das conversas antes e apaga só as que nasceram no ensaio,
# e tem um trap que volta a ligar o motor se o teste morrer a meio.
#
# Pressupõe que `tools/lib-device.sh` já foi carregada e que `DEV`, `ADB`, `BASE`, `MODO` e
# `SE_ROOT` estão definidos pelo runner.
#
# A UI é procurada por texto (pt-PT, a língua do telefone do autor): o teste não depende de
# coordenadas fixas nem da resolução — as coordenadas saem sempre do `uiautomator dump`.

# ---------------------------------------------------------------- estado do ensaio
E2E_PKG="${CACHALOTE_PKG:-dev.ricardopimentel.cachalote}"
E2E_ACT="$E2E_PKG/.MainActivity"
E2E_OK=0
E2E_FALHAS=()
E2E_SALTADOS=0
E2E_MOTOR_PARADO=0
E2E_W=0
E2E_H=0

e2e_init() { # $1 = nome do cenário
  E2E_NOME="${1:?nome do cenário}"
  E2E_DIR="$WS/.work/e2e/$(date +%Y%m%d-%H%M%S)-$E2E_NOME"
  mkdir -p "$E2E_DIR" || true
  E2E_UI="$E2E_DIR/ui.xml"
  E2E_OK=0
  E2E_FALHAS=()
  E2E_SALTADOS=0
  E2E_MOTOR_PARADO=0
  E2E_W=0
  E2E_H=0
  E2E_CRASH0="$(e2e_crash_count)"
  unset E2E_SESSOES_ANTES 2>/dev/null || true
}

e2e_ok()   { echo "   OK     $1"; E2E_OK=$((E2E_OK + 1)); }
e2e_fail() { echo "   FALHA  $1"; E2E_FALHAS+=("$1"); }
e2e_skip() { echo "   SALTADO $1"; E2E_SALTADOS=$((E2E_SALTADOS + 1)); }

e2e_resumo() {
  local f
  echo
  echo "== $E2E_NOME: $E2E_OK ok, ${#E2E_FALHAS[@]} falhas, $E2E_SALTADOS saltados =="
  if [ "${#E2E_FALHAS[@]}" -gt 0 ]; then
    for f in "${E2E_FALHAS[@]}"; do echo "   - $f"; done
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------- comandos no telefone
# O macOS não traz `timeout`: um `uiautomator dump` que fica preso não pode prender o ensaio
# inteiro. O `perl alarm` não chegava — o `adb` filho sobrevivia ao sinal e o dump seguinte
# ficava à espera dele. Aqui o comando corre em segundo plano e é morto a sério (TERM, depois
# KILL) quando o tempo acaba.
e2e_t() {
  local segundos="$1"
  shift
  local pid matador rc
  "$@" &
  pid=$!
  (
    sleep "$segundos"
    kill -TERM "$pid" 2>/dev/null || exit 0
    sleep 1
    kill -KILL "$pid" 2>/dev/null || true
  ) >/dev/null 2>&1 &
  matador=$!
  wait "$pid"
  rc=$?
  kill -TERM "$matador" 2>/dev/null || true
  wait "$matador" 2>/dev/null || true
  return "$rc"
}

e2e_sh() { # $1 = comando de shell no telefone
  e2e_t 40 "${ADB[@]}" shell "$1" 2>/dev/null | tr -d '\r'
}

e2e_su() { # $1 = comando de shell como root
  e2e_t 60 "${ADB[@]}" shell "su -c '$1'" 2>/dev/null | tr -d '\r'
}

e2e_limpar() { # $1 = texto; tira espaços das pontas
  printf '%s' "${1:-}" | tr -d ' \r\n'
}

# ---------------------------------------------------------------- ecrã
# Um `uiautomator` que ficou preso do dump anterior segura a ligação de automação e faz **todos**
# os dumps seguintes esperarem para sempre. Mata-se antes de tentar outra vez: é processo do
# `shell`, não da app, e morre sem efeito nenhum na app.
e2e_matar_uiautomator() {
  local pids p
  pids="$(e2e_sh "ps -A | grep uiautomator | awk '{print \$2}'")"
  for p in $pids; do
    case "$p" in ''|*[!0-9]*) continue ;; esac
    e2e_sh "kill -9 $p" >/dev/null 2>&1 || true
  done
}

# O `uiautomator dump /dev/tty` escreve o XML e, no fim, uma linha «UI hierchary dumped to: …».
# Corta-se no fecho do `<hierarchy>` e valida-se o que ficou.
e2e_xml_valido() {
  python3 - "$1" <<'PYEOF'
import sys, xml.etree.ElementTree as ET
p = sys.argv[1]
try:
    b = open(p, "rb").read()
except OSError:
    raise SystemExit(1)
fim = b.find(b"</hierarchy>")
if fim < 0:
    raise SystemExit(1)
b = b[:fim + len(b"</hierarchy>")]
try:
    open(p, "wb").write(b)
    ET.fromstring(b)
except Exception:
    raise SystemExit(1)
raise SystemExit(0)
PYEOF
}

e2e_dump() {
  local i
  for i in 1 2 3; do
    # `/dev/tty` escreve o XML no stdout: um só `adb`, sem o `cat` à parte — e, se o dump for
    # morto, o pipe fecha e o uiautomator do telefone morre com ele (SIGPIPE), em vez de ficar
    # a segurar a ligação de automação e a prender o dump seguinte.
    e2e_matar_uiautomator
    e2e_t 15 "${ADB[@]}" exec-out uiautomator dump /dev/tty > "$E2E_UI" 2>/dev/null || true
    if [ -s "$E2E_UI" ] && e2e_xml_valido "$E2E_UI"; then
      return 0
    fi
    e2e_matar_uiautomator
    sleep 0.4
  done
  echo "   (não consegui ler a árvore da UI)" >&2
  return 1
}

e2e_screenshot() { # $1 = nome; guarda uma captura no directório do ensaio
  e2e_t 20 "${ADB[@]}" exec-out screencap -p > "$E2E_DIR/${1:-ecra}.png" 2>/dev/null || true
}

e2e_coords() { python3 "$WS/tools/ui.py" "$E2E_UI" find "$1" 2>/dev/null; }
e2e_coords_exact() { python3 "$WS/tools/ui.py" "$E2E_UI" find-exact "$1" 2>/dev/null; }
e2e_has() { python3 "$WS/tools/ui.py" "$E2E_UI" exists "$1" 2>/dev/null; }

# A mesma pesquisa, mas devolve a ocorrência **mais abaixo** no ecrã. É para a lista de conversas:
# o termo da busca aparece no campo (em cima) e na conversa (em baixo); tocar no campo não abre nada.
e2e_coords_ultimo() {
  python3 - "$E2E_UI" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
needle = sys.argv[2].lower()
melhor = None
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    raise SystemExit(1)
for n in root.iter("node"):
    h = ((n.get("text") or "") + " " + (n.get("content-desc") or "")).lower()
    if needle not in h:
        continue
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds") or "")
    if not m:
        continue
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    if melhor is None or cy > melhor[1]:
        melhor = (cx, cy)
if melhor:
    print(*melhor)
    raise SystemExit(0)
raise SystemExit(1)
PY
}

e2e_crash_count() {
  local n
  n="$(e2e_sh "logcat -b crash -d -t 400" | grep -c "$E2E_PKG" || true)"
  echo "${n:-0}"
}

e2e_verificar_crash() {
  local n
  n="$(e2e_crash_count)"
  if [ "${n:-0}" -le "${E2E_CRASH0:-0}" ]; then
    e2e_ok "sem crashes novos no logcat"
  else
    e2e_fail "o logcat ganhou $((n - E2E_CRASH0)) linha(s) de crash da app"
    e2e_screenshot "crash"
  fi
}

e2e_count() {
  python3 - "$E2E_UI" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
needle = sys.argv[2].lower()
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    print(0)
    raise SystemExit(0)
print(sum(1 for n in root.iter("node")
          if needle in (((n.get("text") or "") + " " + (n.get("content-desc") or "")).lower())))
PY
}

e2e_tamanho() {
  [ "${E2E_W:-0}" -gt 0 ] && [ "${E2E_H:-0}" -gt 0 ] && return 0
  local s
  s="$(e2e_sh "wm size")"
  E2E_W="$(printf '%s\n' "$s" | sed -n 's/.*: *\([0-9][0-9]*\)x[0-9][0-9]*.*/\1/p' | tail -1)"
  E2E_H="$(printf '%s\n' "$s" | sed -n 's/.*: *[0-9][0-9]*x\([0-9][0-9]*\).*/\1/p' | tail -1)"
  case "${E2E_W:-}" in ''|*[!0-9]*) E2E_W=1080 ;; esac
  case "${E2E_H:-}" in ''|*[!0-9]*) E2E_H=1920 ;; esac
}

e2e_scroll() {
  e2e_tamanho
  e2e_sh "input swipe $((E2E_W / 2)) $((E2E_H * 62 / 100)) $((E2E_W / 2)) $((E2E_H * 35 / 100)) 250" >/dev/null 2>&1 || true
  sleep 0.5
}

# ---------------------------------------------------------------- app e navegação
e2e_foreground() {
  local foco
  foco="$(e2e_sh "dumpsys window | grep -m1 mCurrentFocus")"
  case "$foco" in
    *"$E2E_PKG"*) return 0 ;;
  esac
  e2e_sh "am start -n $E2E_ACT" >/dev/null 2>&1 || true
  sleep 2
  return 1
}

e2e_hide_kb() {
  if e2e_sh "dumpsys input_method" | grep -q "mInputShown=true"; then
    # O BACK só se carrega quando o teclado está mesmo aberto: sem ele, o BACK fechava a app.
    e2e_sh "input keyevent 4" >/dev/null 2>&1 || true
    sleep 0.6
    e2e_foreground || true
  fi
}

e2e_back() {
  e2e_hide_kb
  e2e_sh "input keyevent 4" >/dev/null 2>&1 || true
  sleep 1
}

e2e_tap() { # $1 = texto (substring); rola se não encontrar
  local alvo="$1" c i
  for i in 1 2 3; do
    e2e_dump || true
    c="$(e2e_coords "$alvo")" && break
    e2e_foreground || true
    e2e_scroll
  done
  if [ -z "${c:-}" ]; then
    echo "   (não encontrei «${alvo}» no ecrã)" >&2
    return 1
  fi
  set -- $c
  e2e_sh "input tap $1 $2" >/dev/null 2>&1 || true
  sleep 1.2
}

e2e_tap_exact() { # $1 = texto exacto; rola se não encontrar
  local alvo="$1" c i
  for i in 1 2 3; do
    e2e_dump || true
    c="$(e2e_coords_exact "$alvo")" && break
    c="$(e2e_coords "$alvo")" && break
    e2e_foreground || true
    e2e_scroll
  done
  if [ -z "${c:-}" ]; then
    echo "   (não encontrei «${alvo}» exacto no ecrã)" >&2
    return 1
  fi
  set -- $c
  e2e_sh "input tap $1 $2" >/dev/null 2>&1 || true
  sleep 1.2
}

e2e_wait_for() { # $1 = texto; $2 = segundos
  local alvo="$1" segundos="${2:-20}" i
  for ((i = 0; i < segundos; i++)); do
    e2e_dump || true
    e2e_has "$alvo" && return 0
    sleep 1
  done
  return 1
}

e2e_wait_count_ge() { # $1 = texto; $2 = mínimo; $3 = segundos
  local alvo="$1" minimo="$2" segundos="${3:-60}" i c
  for ((i = 0; i < segundos; i++)); do
    e2e_dump || true
    c="$(e2e_count "$alvo")"
    [ "${c:-0}" -ge "$minimo" ] && return 0
    sleep 1
  done
  return 1
}

e2e_ver_has() { # $1 = texto; $2 = descrição
  local alvo="$1" desc="${2:-$1}"
  if e2e_dump && e2e_has "$alvo"; then e2e_ok "$desc"; else e2e_fail "$desc"; fi
}

e2e_ver_no() { # $1 = texto; $2 = descrição
  local alvo="$1" desc="${2:-$1}"
  if e2e_dump && ! e2e_has "$alvo"; then e2e_ok "$desc"; else e2e_fail "$desc"; fi
}

e2e_ver_igual() { # $1 = obtido; $2 = esperado; $3 = descrição
  if [ "$1" = "$2" ]; then
    e2e_ok "$3"
  else
    e2e_fail "$3 (obtido: «$1», esperado: «$2»)"
    return 1
  fi
}

e2e_cold_start() {
  e2e_sh "am force-stop $E2E_PKG" >/dev/null 2>&1 || true
  sleep 1
  e2e_sh "am start -n $E2E_ACT" >/dev/null 2>&1 || true
  sleep 3
  e2e_wait_for "Cachalote" 25 || return 1
  # Numa instalação nova o assistente ocupa o ecrã todo. Este ensaio não é o do assistente:
  # salta-se para o resto poder correr (o READINESS é que cobre o primeiro arranque).
  if e2e_has "Já tenho tudo configurado"; then
    e2e_tap "Já tenho tudo configurado" >/dev/null 2>&1 || true
    sleep 2
  fi
  return 0
}

e2e_tab() { # $1 = Conversa | Resumo | Ajustes
  local nome="$1" c i
  e2e_foreground || true
  e2e_hide_kb
  for i in 1 2 3; do
    e2e_dump || true
    c="$(e2e_coords_exact "$nome")" && break
    e2e_hide_kb
    sleep 0.5
  done
  if [ -z "${c:-}" ]; then
    echo "   (separador «${nome}» não encontrado)" >&2
    return 1
  fi
  set -- $c
  e2e_sh "input tap $1 $2" >/dev/null 2>&1 || true
  sleep 1.5
}

e2e_ir_lista() {
  local i
  e2e_foreground || true
  for i in 1 2 3; do
    e2e_dump || break
    e2e_has "Voltar às conversas" || break
    e2e_tap "Voltar às conversas" >/dev/null 2>&1 || e2e_back
    sleep 0.5
  done
  e2e_tab "Conversa" >/dev/null 2>&1 || true
  for i in 1 2 3; do
    e2e_dump || true
    if e2e_has "procurar nas conversas" || e2e_has "Nova sessão" || e2e_has "Ainda não há conversas"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# A lista de conversas não se recarrega sozinha quando se fecha uma conversa (o
# `MainViewModel.fecharConversa` só muda a flag; o `LaunchedEffect(tab)` é que relê). Aqui a troca
# de aba é deliberada: é também o que um utilizador faria para ver a conversa nova na lista.
e2e_refresh_lista() {
  e2e_tab "Resumo" >/dev/null 2>&1 || true
  sleep 1
  e2e_tab "Conversa" >/dev/null 2>&1 || true
  sleep 1.2
}

e2e_abrir_secao() { # $1 = nome exacto da linha do menu dos Ajustes
  local nome="$1" c i
  e2e_tab "Ajustes" >/dev/null 2>&1 || return 1
  for i in 1 2 3; do
    e2e_dump || true
    e2e_has "módulos configurados" && break
    e2e_tap "← Ajustes" >/dev/null 2>&1 || e2e_back
    sleep 0.5
  done
  c=""
  for i in 1 2 3; do
    e2e_dump || true
    c="$(e2e_coords_exact "$nome")" || c="$(e2e_coords "$nome")" || true
    [ -n "${c:-}" ] && break
    e2e_scroll
  done
  if [ -z "${c:-}" ]; then
    echo "   (secção «${nome}» não encontrada)" >&2
    return 1
  fi
  set -- $c
  e2e_sh "input tap $1 $2" >/dev/null 2>&1 || true
  sleep 1.5
  e2e_wait_for "← Ajustes" 15
}

e2e_novo_chat() {
  e2e_ir_lista || return 1
  e2e_tap_exact "Nova" >/dev/null 2>&1 || e2e_tap "Nova conversa" >/dev/null 2>&1 || e2e_tap "Nova sessão" >/dev/null 2>&1 || return 1
  e2e_wait_for "Voltar às conversas" 25
}

e2e_escrever() { # $1 = texto; o campo tem de estar visível
  local texto="${1// /%s}"
  e2e_tap "Escreve uma tarefa" >/dev/null 2>&1 || e2e_tap "tarefa" >/dev/null 2>&1 || return 1
  e2e_sh "input text '$texto'" >/dev/null 2>&1 || return 1
  sleep 0.8
}

e2e_enviar() { # $1 = prompt
  e2e_escrever "$1" || return 1
  e2e_hide_kb
  e2e_dump || true
  e2e_tap_exact "Enviar" >/dev/null 2>&1 || e2e_tap "Enviar" >/dev/null 2>&1 || return 1
  sleep 1
}

# ---------------------------------------------------------------- aprovações
e2e_esperar_aprovacao() {
  local i
  for ((i = 0; i < 45; i++)); do
    e2e_dump || true
    e2e_has "O AGENTE QUER CORRER" && return 0
    sleep 1
  done
  return 1
}

# Responde a **todas** as folhas de autorização que aparecerem até ao fim do turno. Uma única
# operação fora da área pode pedir duas coisas: a guarda de caminhos e, depois, o escalonamento do
# sandbox (foi o que se viu no S10+ em 2026-11-04). Parar na primeira deixava o turno pendurado.
e2e_aprovar() { # $1 = permitir|recusar; $2 = máximo de folhas
  local modo="$1" maximo="${2:-12}" n=0
  while [ "$n" -lt "$maximo" ]; do
    e2e_dump || break
    if ! e2e_has "O AGENTE QUER CORRER"; then
      # A folha seguinte pode aparecer um instante depois de a anterior fechar.
      sleep 1
      e2e_dump || true
      e2e_has "O AGENTE QUER CORRER" || break
    fi
    # A folha sobe com animação; tocar logo a seguir ao dump podia cair no sítio antigo.
    sleep 0.6
    if [ "$modo" = "recusar" ]; then
      e2e_tap_exact "Recusar" >/dev/null 2>&1 || { e2e_fail "não encontrei a opção Recusar"; break; }
    else
      e2e_tap_exact "Permitir uma vez" >/dev/null 2>&1 || { e2e_fail "não encontrei a opção Permitir uma vez"; break; }
    fi
    e2e_tap_exact "Confirmar" >/dev/null 2>&1 || { e2e_fail "não encontrei o botão Confirmar"; break; }
    n=$((n + 1))
    sleep 1.5
  done
  # Um agente teimoso pode pedir mais folhas do que o limite (viu-se em 2026-11-04: depois de
  # recusar, tentou ler o caminho e pedir o sandbox). Deixar a última folha aberta pendurava o
  # turno e o ensaio ficava à espera de um «turno» que nunca chegava. Fecha-se o turno.
  e2e_dump || true
  if e2e_has "O AGENTE QUER CORRER"; then
    echo "   (ainda havia pedidos depois de $n respostas — a recusar o turno)" >&2
    e2e_tap_exact "Recusar o turno" >/dev/null 2>&1 || true
    sleep 1
  fi
  [ "$n" -gt 0 ]
}

# ---------------------------------------------------------------- conversas e dados
e2e_sessoes_antes() {
  # Só se apaga o que nascer depois desta fotografia (ver lib-device.sh). Sem BASE (embutido) não
  # há daemon para apagar; o snapshot fica vazio e a limpeza não faz nada.
  if [ -n "${BASE:-}" ]; then
    E2E_SESSOES_ANTES="$(cachalote_sessoes "$DEV" "$BASE" "$SE_ROOT" 2>/dev/null || true)"
  else
    E2E_SESSOES_ANTES=""
  fi
}

e2e_limpar_sessoes() {
  local apagadas
  if [ -z "${E2E_SESSOES_ANTES+x}" ] || [ -z "${BASE:-}" ]; then
    return 0
  fi
  if apagadas="$(cachalote_limpar_sessoes_do_ensaio "$DEV" "$BASE" "$SE_ROOT" "$E2E_SESSOES_ANTES")"; then
    e2e_ok "conversas do ensaio apagadas ($apagadas)"
  else
    e2e_fail "ficaram conversas do ensaio na lista"
    e2e_screenshot "conversas-por-apagar"
  fi
  unset E2E_SESSOES_ANTES 2>/dev/null || true
}

e2e_limpar_busca() {
  e2e_dump || true
  if e2e_has "limpar a busca"; then
    e2e_tap "limpar a busca" >/dev/null 2>&1 || true
    e2e_hide_kb
    sleep 0.6
  fi
}

e2e_contagem_conversas() {
  e2e_dump || true
  python3 - "$E2E_UI" <<'PY'
import re, sys, xml.etree.ElementTree as ET
total = -1
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    print(total)
    raise SystemExit(0)
for n in root.iter("node"):
    t = (n.get("text") or "").strip()
    m = re.match(r"^(\d+)\s+de\s+(\d+)\s+conversas?$", t)
    if m:
        total = int(m.group(2))
        continue
    m = re.match(r"^(\d+)\s+conversas?$", t)
    if m:
        total = int(m.group(1))
print(total)
PY
}

e2e_abrir_conversa_pelo_marcador() { # $1 = marcador
  local marca="$1" c
  e2e_ir_lista || return 1
  e2e_refresh_lista
  e2e_limpar_busca
  e2e_tap "procurar nas conversas" >/dev/null 2>&1 || return 1
  e2e_sh "input text '$marca'" >/dev/null 2>&1 || return 1
  e2e_hide_kb
  e2e_wait_for "1 de" 12 || true
  c="$(e2e_coords_ultimo "$marca")" || { echo "   (não achei a conversa «${marca}»)" >&2; return 1; }
  set -- $c
  e2e_sh "input tap $1 $2" >/dev/null 2>&1 || true
  sleep 1.5
  e2e_wait_for "Voltar às conversas" 20
}

e2e_apagar_conversa_pelo_marcador() { # $1 = marcador
  local marca="$1" c
  e2e_ir_lista || return 1
  e2e_refresh_lista
  e2e_limpar_busca
  e2e_tap "procurar nas conversas" >/dev/null 2>&1 || return 1
  e2e_sh "input text '$marca'" >/dev/null 2>&1 || return 1
  e2e_hide_kb
  e2e_wait_for "1 de" 12 || true
  c="$(e2e_coords_ultimo "$marca")" || { echo "   (não achei a conversa «${marca}»)" >&2; return 1; }
  set -- $c
  # O toque longo é o caminho de sempre (o deslize também existe, mas o toque longo continua).
  e2e_sh "input swipe $1 $2 $1 $2 900" >/dev/null 2>&1 || true
  sleep 1.2
  e2e_dump || true
  e2e_has "Apagar conversa" || { echo "   (o diálogo de apagar não apareceu)" >&2; return 1; }
  e2e_tap_exact "Apagar" >/dev/null 2>&1 || return 1
  sleep 2
  return 0
}

# ---------------------------------------------------------------- pré-condições
e2e_precisa_daemon() {
  if [ "${MODO:-}" = "embedded" ] || [ -z "${BASE:-}" ]; then
    echo "   (a app está no modo embutido: não há daemon para este ensaio)"
    return 1
  fi
  if ! e2e_su "test -x $BASE/bin/cachalote && echo sim" | grep -q sim; then
    echo "   (não há daemon instalado em $BASE)"
    return 1
  fi
  return 0
}

e2e_precisa_modelo() {
  local n
  e2e_precisa_daemon || return 1
  if [ "${SE_ROOT:-0}" != "1" ]; then
    echo "   (sem \`su\` não consigo ler a chave em $BASE/home/.env)"
    return 1
  fi
  n="$(e2e_su "grep -c '^DEEPSEEK_API_KEY=.' $BASE/home/.env" | tr -d ' \r\n')"
  if [ "${n:-0}" -lt 1 ]; then
    echo "   (não há chave do provedor no daemon — os turnos reais ficam de fora)"
    return 1
  fi
  if ! e2e_su "$BASE/bin/cachalote status" | grep -q "OK deepseek-harness-acp"; then
    echo "   (o motor não responde ao handshake — os turnos reais ficam de fora)"
    return 1
  fi
  return 0
}

e2e_precisa_daemon_root() {
  [ "${MODO:-}" = "daemon" ] || { echo "   (este ensaio precisa do modo root)"; return 1; }
  [ "${SE_ROOT:-0}" = "1" ] || { echo "   (este ensaio precisa de \`su\`)"; return 1; }
  return 0
}

# ---------------------------------------------------------------- emergência (trap do runner)
e2e_repor_motor() {
  if [ "${E2E_MOTOR_PARADO:-0}" = "1" ] && [ -n "${BASE:-}" ]; then
    echo "== a repor o motor (o ensaio parou-o e não o voltou a ligar) =="
    e2e_su "$BASE/bin/cachalote start" >/dev/null 2>&1 || true
    sleep 5
  fi
}

e2e_limpar_emergencia() {
  if [ -n "${E2E_SESSOES_ANTES+x}" ] && [ -n "${BASE:-}" ]; then
    cachalote_limpar_sessoes_do_ensaio "$DEV" "$BASE" "$SE_ROOT" "$E2E_SESSOES_ANTES" >/dev/null 2>&1 || true
  fi
}
