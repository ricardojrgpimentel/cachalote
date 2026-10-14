#!/bin/bash
# plugin-schema-test.sh — regressão do schema do plugin do kernel (cachalote-tools).
#
# Porque é que este teste existe: um `{}` sem `type` num schema de ferramenta faz o
# compilador do kernel recusar a ferramenta — e o plugin INTEIRO não activa. O agente fica
# sem NENHUMA ferramenta android_* e o único sinal é um "1 entry did not activate" no log.
# Foi exactamente isto que aconteceu depois do trabalho de visão de §18: `attachmentRef: {}`
# no output do android_see matou as sete ferramentas em silêncio (ver §21 do STATUS).
#
#   ./tools/plugin-schema-test.sh [caminho-para-dsh-tools]
#
# Descobre o dsh-tools por esta ordem: argumento, $CACHALOTE_DSH_TOOLS, uma árvore em .work
# (a do bootstrap ou a de um kernel já patcheado). Não inventa: se não encontrar, sai com 2.
set -uo pipefail

WS="$(cd "$(dirname "$0")/.." && pwd)"
PLUGIN="$WS/kernel-plugins/cachalote-tools"
WORK="$WS/.work/plugin-schema-test"

find_tools() {
  local c
  for c in "${1:-}" "${CACHALOTE_DSH_TOOLS:-}"; do
    [ -n "$c" ] && [ -d "$c" ] && { echo "$c"; return 0; }
  done
  for c in \
    "$WS/.work/dsh-install/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-tools" \
    "$WS/.work/f0/kernel/node_modules/@deepseek-ai/dsh-tools" \
    "$WS/.work/kernel/node_modules/@deepseek-ai/dsh-tools"; do
    [ -d "$c" ] && { echo "$c"; return 0; }
  done
  c="$(find "$WS/.work" -maxdepth 8 -type d -path '*@deepseek-ai/dsh-tools' 2>/dev/null | head -1)"
  [ -n "$c" ] && { echo "$c"; return 0; }
  return 1
}

TOOLS="$(find_tools "${1:-}")" || {
  echo "não encontrei o dsh-tools do kernel."
  echo "Corre primeiro:  ./tools/bootstrap.sh --skip-daemon --skip-app"
  echo "ou passa o caminho: ./tools/plugin-schema-test.sh /caminho/@deepseek-ai/dsh-tools"
  exit 2
}
echo "dsh-tools: $TOOLS"
grep -m1 '"version"' "$TOOLS/package.json" 2>/dev/null | sed 's/^/           /'

rm -rf "$WORK"
mkdir -p "$WORK/node_modules/@deepseek-ai"
ln -sfn "$TOOLS" "$WORK/node_modules/@deepseek-ai/dsh-tools"
cp -r "$PLUGIN" "$WORK/plugin"

cat > "$WORK/check.mjs" <<'EOF'
// Carrega o plugin contra o `dsh-tools` REAL e exige que todas as ferramentas compilem.
// O `apply` do plugin chama `defineTool` no registo — é aí que o schema é validado.
import { apply, name } from "./plugin/lib/index.js";

const WANT = [
  "android_status", "android_notify", "android_screen", "android_tap",
  "android_type", "android_key", "android_see", "android_vscreen",
];
const reg = [];
try {
  apply({ tools: { register: (t) => reg.push(t) }, get: () => undefined });
} catch (e) {
  console.log(`FALHA: o plugin "${name}" não carregou: ${e.name}: ${e.message}`);
  process.exit(1);
}
const names = reg.map((t) => t?.name).filter(Boolean);
console.log(`ferramentas registadas: ${names.length} → ${names.join(", ")}`);
const missing = WANT.filter((w) => !names.includes(w));
if (missing.length) {
  console.log("FALHA: não registaram: " + missing.join(", "));
  process.exit(1);
}
console.log(`OK: o plugin "${name}" carregou com as ${WANT.length} ferramentas`);
EOF

if node "$WORK/check.mjs"; then
  rm -rf "$WORK"
  exit 0
fi
echo "(árvore de trabalho deixada em $WORK para inspecção)"
exit 1
