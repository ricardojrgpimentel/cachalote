#!/bin/bash
# snapshot-experiment.sh — receita completa do snapshot V8 para o motor (D6b), tal como ficou.
#
#   ./tools/snapshot-experiment.sh --device emulator-5554 [--base /data/local/tmp/cachalote]
#
# Constrói o snapshot no DISPOSITIVO (o blob é específico de arch+sistema+versão de Node, por
# isso não se constrói no computador) e mede-o com o `engine-startup-bench.cjs`.
#
# ATENÇÃO — o resultado medido é este: o snapshot constrói, restaura e o motor responde, mas o
# ganho é **nulo** (mediana 892 ms com snapshot vs 913 ms sem, num emulador x86_64). Este
# script existe para não se repetir a exploração e para quem quiser continuar ter o caminho
# exacto, com os quatro obstáculos já resolvidos. Ver STATUS §23.8.
#
# Os obstáculos que custaram tempo (todos medidos, nenhum é óbvio):
#
#   1. O construtor do Node corre o entry num executor MÍNIMO (`minimalRunCjs`): é um **script**
#      CJS, sem `module`, sem `exports` e **sem `require` de ficheiros de utilizador** — só
#      módulos internos. Um entry ESM é recusado ("Cannot use import statement outside a
#      module"); um `import()` dinâmico é recusado ("Error: Not supported"); exigir outro
#      ficheiro é recusado ("Cannot find module './dep.cjs'", mesmo num caso mínimo, mesmo por
#      caminho absoluto).
#      ⇒ o entry tem de SER o bundle inteiro, em script puro.
#
#   2. O `bin.js` tem `if (import.meta.main) await runCli();` — um `await` de topo, que o
#      esbuild recusa em saída CJS/IIFE ("Top-level await is currently not supported"). O
#      auto-arranque sai e passa a ser a cola a chamar `runCli()`.
#
#   3. A saída `--format=cjs` do esbuild usa `module.exports`, que no executor mínimo não
#      existe ("ReferenceError: module is not defined"). ⇒ `--format=iife --global-name=…`.
#
#   4. O `@deepseek-ai/dsh-app-boot` faz `new URL("../package.json", import.meta.url)`; o
#      `import.meta.url` resolve para nada num bundle ⇒ `TypeError: Invalid URL`.
#      ⇒ `--define:import.meta.url` a apontar para o sítio onde o bundle vai viver.
#
#   5. Restaurar o snapshot com `--profile acp` faz o Node recusar a opção
#      ("bad option: --profile"). ⇒ separador `--` antes dos argumentos do CLI.
#
# E o resultado, que é o que interessa a quem continuar: **não ganhou nada**. O arranque a
# frio medido foi 892 ms (snapshot) contra 913 ms (sem), quando o D6b projectava 31 ms contra
# 187 ms. Antes de investir mais nisto, vale a pena perceber PORQUE — as hipóteses ficam
# escritas no §23.8, e nenhuma foi verificada.
set -euo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
DEV="${CACHALOTE_DEV:-emulator-5554}"
BASE="/data/local/tmp/cachalote"
RUNS=5

while [ $# -gt 0 ]; do
  case "$1" in
    --device) DEV="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "opção desconhecida: $1" >&2; exit 2 ;;
  esac
done

ADB=(adb -s "$DEV")
ESBUILD="$WS/.work/tools/node_modules/.bin/esbuild"
K="$WS/.work/f0/kernel/dsh/node_modules/@deepseek-ai"
SRC="$K/dsh/lib/bin.js"
PATCHED="$K/dsh/lib/bin-snapshot-src.js"
OUT="$WS/.work/bundle/snap-iife.js"
D="$BASE/kernel/dsh/node_modules/@deepseek-ai/dsh/lib"
URL="file://$D/snap-iife.js"

[ -x "$ESBUILD" ] || { echo "ERRO: falta o esbuild. Instala com:"; echo "  npm install --cache .work/npm-cache --prefix .work/tools esbuild"; exit 1; }
[ -f "$SRC" ] || { echo "ERRO: falta a árvore do kernel em $K (corre tools/bootstrap.sh --skip-daemon --skip-app)"; exit 1; }

say() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }

say "1/5 bundle IIFE (script puro, sem module/exports)"
cp "$SRC" "$PATCHED"
# O auto-arranque é o `await` de topo que impede a saída em script; a cola chama o CLI.
sed -i 's|^if (import.meta.main) await runCli();|// auto-arranque removido (variante para snapshot)|' "$PATCHED"
mkdir -p "$WS/.work/bundle"
"$ESBUILD" "$PATCHED" --bundle --platform=node --target=node24 \
  --format=iife --global-name=CachaloteKernel \
  --define:import.meta.url="\"$URL\"" \
  --outfile="$OUT" --log-limit=4
rm -f "$PATCHED"

say "2/5 cola do snapshot (mesmo ficheiro: o entry não pode exigir outros)"
cat >> "$OUT" <<'EOF'

/* cola do snapshot (D6b). O executor do construtor não tem `module`/`exports`/`require` de
   ficheiros, por isso isto vive no mesmo ficheiro que o bundle. */
const __v8 = require("node:v8");
if (__v8.startupSnapshot.isBuildingSnapshot()) {
  __v8.startupSnapshot.setDeserializeMainFunction(() => { CachaloteKernel.runCli(); });
} else {
  CachaloteKernel.runCli();
}
EOF
ls -lh "$OUT"

say "3/5 enviar para o dispositivo"
"${ADB[@]}" push "$OUT" "$D/snap-iife.js" >/dev/null

say "4/5 construir o snapshot NO dispositivo (o blob não é portátil)"
"${ADB[@]}" shell "cd $D && DSH_HOME=$BASE/home HOME=$BASE TMPDIR=$BASE/tmp LD_LIBRARY_PATH=$BASE/runtime/lib $BASE/runtime/bin/node --build-snapshot --snapshot-blob=$BASE/tmp/snap.blob snap-iife.js" 2>&1 \
  | grep -viE "^\(node|^To request|^It may|^\(Use" || true
"${ADB[@]}" shell "ls -lh $BASE/tmp/snap.blob"

say "5/5 medir (baseline sem snapshot: ~913 ms neste emulador)"
"${ADB[@]}" push "$WS/tools/engine-startup-bench.cjs" "$BASE/bin/engine-startup-bench.cjs" >/dev/null
"${ADB[@]}" shell "CACHALOTE_BASE=$BASE RUNS=$RUNS TIMEOUT_MS=45000 CACHALOTE_CMDLINE='--snapshot-blob=$BASE/tmp/snap.blob --expose-internals -- --profile acp' LD_LIBRARY_PATH=$BASE/runtime/lib $BASE/runtime/bin/node $BASE/bin/engine-startup-bench.cjs"
