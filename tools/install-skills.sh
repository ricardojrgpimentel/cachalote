#!/bin/bash
# install-skills.sh — instala skills (formato Agent Skills) no motor do Cachalote.
#
#   ./tools/install-skills.sh                 # as skills do Stitch (Google), conjunto de design
#   ./tools/install-skills.sh --repo <url>    # outro repositório com plugins/*/skills/*
#   ./tools/install-skills.sh --list          # só mostra o que está instalado
#
# O kernel procura skills em `$DSH_HOME/skills` (e em `<projecto>/.dsh/skills`). O sistema já vem
# montado do perfil base (`dsh-skill`, `dsh-skill-filesystem`, `dsh-tool-skill`); o que faltava era
# haver skills lá dentro. Cada skill é uma pasta com `SKILL.md` (formato aberto Agent Skills).
set -uo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
. "$WS/tools/lib-device.sh"
DEV="$(cachalote_escolher_dispositivo)" || exit 1
BASE="${CACHALOTE_BASE:-/data/adb/cachalote}"
ADB=(adb -s "$DEV")
REPO="https://github.com/google-labs-code/stitch-skills"
# Conjuntos úteis para DESIGN num telemóvel. Os de build (react-*, shadcn, remotion) ficam de fora:
# geram web/React e o Cachalote é Compose.
GRUPOS="plugins/stitch-design/skills plugins/stitch-utilities/skills"

case "${1:-}" in
  --list)
    echo "== skills instaladas em $BASE/home/skills =="
    "${ADB[@]}" shell "su -c 'ls $BASE/home/skills 2>/dev/null || echo \"  (nenhuma)\"'"
    exit 0 ;;
  --repo) REPO="${2:?falta o URL}"; GRUPOS="plugins/*/skills" ;;
esac

TMP="$WS/.work/skills-src"
echo "== a buscar $REPO =="
rm -rf "$TMP"
git clone --depth 1 -q "$REPO" "$TMP" || { echo "ERRO: falhou o clone"; exit 1; }

echo "== a instalar =="
"${ADB[@]}" shell "rm -rf /data/local/tmp/skills && mkdir -p /data/local/tmp/skills" >/dev/null 2>&1
# shellcheck disable=SC2086
for grupo in $GRUPOS; do
  for skill in "$TMP"/$grupo/*/; do
    [ -f "$skill/SKILL.md" ] || continue
    nome="$(basename "$skill")"
    # O nome no frontmatter tem de ser kebab-case em alguns agentes; o do Stitch é `stitch::x`.
    # Aqui mantém-se como está (o nosso kernel aceita), mas registamos a tradução no README local.
    "${ADB[@]}" push "$skill" "/data/local/tmp/skills/$nome" >/dev/null 2>&1
    echo "  $nome"
  done
done
"${ADB[@]}" shell "su -c 'mkdir -p $BASE/home/skills && cp -r /data/local/tmp/skills/* $BASE/home/skills/ && chmod -R a+rX $BASE/home/skills && ls $BASE/home/skills | wc -l'"

echo "== a reiniciar o motor para ele descobrir as skills =="
"${ADB[@]}" shell "su -c '$BASE/bin/cachalote restart'" >/dev/null 2>&1
sleep 12
"${ADB[@]}" shell "su -c 'grep -i -c skill $BASE/logs/engine.log'" | tr -d '\r' | sed 's/^/  menções a skill no registo: /'
