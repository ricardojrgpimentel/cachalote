#!/bin/bash
# prepare-kernel-zip.sh — empacota a árvore do kernel (já patcheada) num zip que a app
# consegue extrair sozinha (o Android não traz `tar`).
#
#   ./tools/prepare-kernel-zip.sh            # gera .work/kernel.zip
#   ./tools/prepare-kernel-zip.sh --push     # + envia para o armazenamento privado da app
#   ./tools/prepare-kernel-zip.sh --bundle   # + mete o zip na APK (assets/) com sha256
#   ./tools/prepare-kernel-zip.sh --remote-url URL
#                                            # + grava assets/kernel.remote.json (url + sha256):
#                                            #   é o caminho de distribuição (a app descarrega e
#                                            #   verifica em vez de carregar 389 MB de kernel)
#
# O zip tem a raiz `dsh/` (como o tar do daemon). Sem compressão: a extracção fica muito mais
# rápida. ATENÇÃO ao tamanho: a árvore do `app.asar` do desktop dava ~110 MB, mas a que vem do
# **npm** dá ~389 MB (com ela embutida a APK vai a ~440 MB). Para distribuir, o caminho é o
# download do kernel na primeira execução (o `--push` serve para desenvolvimento), não o asset.
# Ver STATUS §22.6.
set -euo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
# O dispositivo só é necessário para --push (empacotar/bundle não precisa de telemóvel).
. "$WS/tools/lib-device.sh"
PKG="dev.ricardopimentel.cachalote"
SRC="$WS/.work/f0/kernel"
OUT="$WS/.work/kernel.zip"

[ -d "$SRC/dsh/node_modules/@deepseek-ai/dsh" ] || { echo "ERRO: falta a árvore patcheada em $SRC (corre tools/patch-android.cjs)"; exit 1; }
echo "== a empacotar $SRC -> $OUT =="
rm -f "$OUT"
( cd "$SRC" && zip -q -0 -r "$OUT" dsh -x "*.DS_Store" )
ls -lh "$OUT"

if [ "${1:-}" = "--remote-url" ]; then
  URL="${2:?falta o URL}"
  SHA="$(shasum -a 256 "$OUT" | awk '{print $1}')"
  BYTES="$(wc -c < "$OUT" | tr -d ' ')"
  ASSETS="$WS/app/app/src/main/assets"
  mkdir -p "$ASSETS"
  printf '{\n  "url": "%s",\n  "sha256": "%s",\n  "bytes": %s\n}\n' "$URL" "$SHA" "$BYTES" > "$ASSETS/kernel.remote.json"
  echo "== kernel publicado: $URL ($(du -h "$OUT" | cut -f1), sha256=${SHA:0:16}…) =="
fi

if [ "${1:-}" = "--bundle" ]; then
  # O modo embutido passa a ser auto-suficiente: a app copia o kernel do asset na primeira
  # execução (com verificação de integridade) em vez de exigir `adb push`.
  ASSETS="$WS/app/app/src/main/assets"
  mkdir -p "$ASSETS"
  cp -f "$OUT" "$ASSETS/kernel.zip"
  SHA="$(shasum -a 256 "$OUT" | awk '{print $1}')"
  printf '%s  kernel.zip\n' "$SHA" > "$ASSETS/kernel.sha256"
  echo "== embutido na APK: assets/kernel.zip ($(du -h "$OUT" | cut -f1)) sha256=${SHA:0:16}… =="
fi

if [ "${1:-}" = "--push" ]; then
  DEV="$(cachalote_escolher_dispositivo "${DEV:-}")" || exit 1
  echo "== a enviar para o armazenamento privado da app =="
  adb -s "$DEV" push "$OUT" /data/local/tmp/kernel.zip >/dev/null
  adb -s "$DEV" shell "su -c 'cp /data/local/tmp/kernel.zip /data/data/$PKG/files/kernel.zip && chown \$(stat -c %u:%g /data/data/$PKG/files) /data/data/$PKG/files/kernel.zip && ls -l /data/data/$PKG/files/kernel.zip'"
fi
