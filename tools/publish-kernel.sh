#!/bin/bash
# publish-kernel.sh — publica o zip do kernel para a app o poder descarregar (o caminho de
# distribuição: a APK fica leve em vez de carregar 389 MB de kernel).
#
#   ./tools/publish-kernel.sh --repo <owner>/<repo> [--tag kernel-<versao>] [--dir <subpasta>]
#   ./tools/publish-kernel.sh --url https://exemplo/cachalote/   # só gera os metadados
#
# Como funciona:
#   1. gera/usa .work/kernel.zip e calcula sha256
#   2. (--repo) cria um *release* no GitHub com o zip como asset, via `gh`
#   3. escreve app/app/src/main/assets/kernel.remote.json com url + sha256 + bytes
#
# ⚠️ O repositório do Cachalote é **privado**: um asset de release num repo privado não é
# descarregável pela app sem token. Usa um repositório **público** (pode ser só para o kernel),
# uma *release* pública, ou qualquer hospedagem estática (S3, Cloudflare R2, um servidor teu).
# A app só precisa de um URL que responda a um GET simples.
set -euo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
ZIP="$WS/.work/kernel.zip"
REPO=""
TAG=""
URL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --tag) TAG="$2"; shift 2 ;;
    --url) URL="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

[ -f "$ZIP" ] || { echo "ERRO: falta $ZIP (corre tools/prepare-kernel-zip.sh)"; exit 1; }
SHA="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
BYTES="$(wc -c < "$ZIP" | tr -d ' ')"
[ -n "$TAG" ] || TAG="kernel-$(printf '%s' "$SHA" | cut -c1-12)"

if [ -n "$REPO" ]; then
  command -v gh >/dev/null || { echo "ERRO: preciso do gh (GitHub CLI) para publicar"; exit 1; }
  echo "== a publicar $ZIP ($(du -h "$ZIP" | cut -f1)) no release $TAG de $REPO =="
  if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    gh release upload "$TAG" "$ZIP" --repo "$REPO" --clobber
  else
    gh release create "$TAG" "$ZIP" --repo "$REPO" \
      --title "Kernel Cachalote $TAG" \
      --notes "Kernel do DSH para a app Cachalote. sha256: $SHA"
  fi
  URL="https://github.com/$REPO/releases/download/$TAG/kernel.zip"
fi

[ -n "$URL" ] || { echo "ERRO: dá --repo <owner>/<repo> ou --url <URL>"; exit 1; }
ASSETS="$WS/app/app/src/main/assets"
mkdir -p "$ASSETS"
printf '{\n  "url": "%s",\n  "sha256": "%s",\n  "bytes": %s\n}\n' "$URL" "$SHA" "$BYTES" > "$ASSETS/kernel.remote.json"
echo "== metadados escritos: $ASSETS/kernel.remote.json =="
cat "$ASSETS/kernel.remote.json"
echo
echo "A seguir: reconstruir a app SEM o kernel embutido (não corras --bundle) e instalá-la."
echo "Nota: se o URL não for público, a app não o consegue descarregar."
