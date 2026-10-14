#!/bin/bash
# make-logo-png.sh — do vector (design/logo/marca.svg) para o PNG que a fábrica de ícones usa.
#
#   ./tools/make-logo-png.sh && python3 tools/make-icon.py --logo design/logo/marca.png
#
# Existe porque a marca tem de ser **uma só**: o ícone do lançador e a marca dentro da app saíram,
# numa primeira versão, de dois desenhos diferentes (o ecrã do ícone e a folha de marca) e via-se.
# Agora o SVG é a fonte única e este script deriva o raster dele.
#
# Duas manhas do ImageMagick, ambas necessárias:
#   · `currentColor` não é resolvido fora de CSS → fixa-se o teal do sistema;
#   · o `<line>` com `stroke` não é desenhado → troca-se por um rectângulo arredondado equivalente.
set -uo pipefail
WS="$(cd "$(dirname "$0")/.." && pwd)"
SVG="$WS/design/logo/marca.svg"
TMP="$WS/.work/logo-raster"
PY="${CACHALOTE_PY:-/Users/ricardopimentel/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3}"
mkdir -p "$TMP"

[ -f "$SVG" ] || { echo "erro: falta $SVG"; exit 1; }
command -v magick >/dev/null || { echo "erro: preciso do ImageMagick (magick)"; exit 1; }

sed -e 's/currentColor/#38efc2/g' \
    -e 's|<line x1="14" y1="24" x2="86" y2="24" stroke="#38efc2" stroke-width="5.5" stroke-linecap="round"/>|<rect x="14" y="21.25" width="72" height="5.5" rx="2.75" fill="#38efc2"/>|' \
    "$SVG" > "$TMP/marca-raster.svg"
magick -background none -density 600 "$TMP/marca-raster.svg" -resize 1024x1024 "$TMP/marca-1024.png" || {
  echo "erro: falhou a rasterização"; exit 1; }

"$PY" - "$WS" "$TMP/marca-1024.png" <<'PY'
import sys, pathlib
from PIL import Image
raiz, origem = pathlib.Path(sys.argv[1]), sys.argv[2]
m = Image.open(origem).convert("RGBA")
caixa = m.getbbox()
if not caixa:
    raise SystemExit("erro: o raster saiu vazio")
marca = m.crop(caixa)
marca.save(raiz / "design/logo/marca.png")
lado = int(max(marca.size) * 1.25)
q = Image.new("RGBA", (lado, lado), (0, 0, 0, 0))
q.paste(marca, ((lado - marca.width) // 2, (lado - marca.height) // 2), marca)
q.resize((512, 512), Image.LANCZOS).save(raiz / "design/logo/marca-512.png")
print(f"marca.png: {marca.size} · marca-512.png: 512x512")
PY
echo "pronto. a seguir: python3 tools/make-icon.py --logo design/logo/marca.png --background 0C141D"
