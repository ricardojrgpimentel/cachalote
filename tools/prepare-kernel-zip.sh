#!/bin/bash
# prepare-kernel-zip.sh — empacota a árvore do kernel (já patcheada) num zip que a app
# consegue extrair sozinha (o Android não traz `tar`).
#
#   ./tools/prepare-kernel-zip.sh            # gera .work/kernel.zip
#   ./tools/prepare-kernel-zip.sh --push     # + envia para o armazenamento privado da app
#   ./tools/prepare-kernel-zip.sh --bundle   # + mete o zip na APK (assets/) com sha256
#
# O zip tem a raiz `dsh/` (como o tar do daemon). Sem compressão: o kernel já são ficheiros
# JS pequenos e a extracção fica muito mais rápida (e o zip fica ~140 MB).
set -euo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
DEV="${CACHALOTE_DEV:-R58M55JNP6X}"
PKG="dev.ricardopimentel.cachalote"
SRC="$WS/.work/f0/kernel"
OUT="$WS/.work/kernel.zip"

[ -d "$SRC/dsh/node_modules/@deepseek-ai/dsh" ] || { echo "ERRO: falta a árvore patcheada em $SRC (corre tools/patch-android.cjs)"; exit 1; }
echo "== a empacotar $SRC -> $OUT =="
rm -f "$OUT"
( cd "$SRC" && zip -q -0 -r "$OUT" dsh -x "*.DS_Store" )
ls -lh "$OUT"

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
  echo "== a enviar para o armazenamento privado da app =="
  adb -s "$DEV" push "$OUT" /data/local/tmp/kernel.zip >/dev/null
  adb -s "$DEV" shell "su -c 'cp /data/local/tmp/kernel.zip /data/data/$PKG/files/kernel.zip && chown \$(stat -c %u:%g /data/data/$PKG/files) /data/data/$PKG/files/kernel.zip && ls -l /data/data/$PKG/files/kernel.zip'"
fi
