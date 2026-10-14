#!/usr/bin/env python3
"""make-icon.py — transforma um logo no ícone da app (Android, todas as densidades).

    ./tools/make-icon.py --logo design/logo/cachalote.png --background 0C141D
    ./tools/make-icon.py --logo logo.png --so-previa      # só mostra o que faria

O que produz, em `app/app/src/main/res/`:

| Ficheiro | Para quê |
|---|---|
| `mipmap-*/ic_launcher.png` | o ícone "clássico", 48/72/96/144/192 px |
| `mipmap-*/ic_launcher_round.png` | variante redonda (launchers antigos) |
| `mipmap-*/ic_launcher_foreground.png` | o logo na tela de 108dp do ícone adaptativo (Android 8+) |
| `mipmap-*/ic_launcher_monochrome.png` | versão de uma cor (ícones temáticos, Android 13+) |
| `mipmap-anydpi-v26/ic_launcher{,_round}.xml` | o ícone adaptativo (fundo + frente) |
| `values/ic_launcher_background.xml` | a cor de fundo, para se mudar num sítio só |

Precisa de Pillow (`import PIL`). No harness, usar o Python que o traz:
`/Users/ricardopimentel/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3`.
"""
import argparse
import pathlib
import sys

try:
    from PIL import Image, ImageDraw
except ImportError:
    print("erro: falta o Pillow. Usa o Python do harness (ver o cabeçalho deste ficheiro).", file=sys.stderr)
    raise SystemExit(2)

RAIZ = pathlib.Path(__file__).resolve().parent.parent
RES = RAIZ / "app/app/src/main/res"

# densidade → (ícone, frente do adaptativo). O adaptativo é 108dp com o logo a ~62%.
DENSIDADES = {"mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216), "xxhdpi": (144, 324), "xxxhdpi": (192, 432)}
LOGO_NO_ADAPTATIVO = 0.62


def normalizar_logo(caminho: pathlib.Path) -> Image.Image:
    """O logo, quadrado e com transparência (recortando as margens vazias)."""
    img = Image.open(caminho).convert("RGBA")
    caixa = img.getbbox()
    if caixa:
        img = img.crop(caixa)
    lado = max(img.size)
    quadrado = Image.new("RGBA", (lado, lado), (0, 0, 0, 0))
    quadrado.paste(img, ((lado - img.width) // 2, (lado - img.height) // 2), img)
    return quadrado


def redondo(img: Image.Image) -> Image.Image:
    """Máscara circular (a variante redonda)."""
    mascara = Image.new("L", img.size, 0)
    ImageDraw.Draw(mascara).ellipse((0, 0, img.width - 1, img.height - 1), fill=255)
    saida = Image.new("RGBA", img.size, (0, 0, 0, 0))
    saida.paste(img, (0, 0), mascara)
    return saida


def monovariante(logo: Image.Image, cor=(255, 255, 255, 255)) -> Image.Image:
    """Uma só cor, mantendo a forma (ícones temáticos)."""
    saida = Image.new("RGBA", logo.size, cor)
    saida.putalpha(logo.getchannel("A"))
    return saida


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--logo", required=True, help="PNG do logo")
    p.add_argument("--background", default="0C141D", help="cor de fundo do ícone (hex, sem #)")
    p.add_argument("--so-previa", action="store_true", help="não escreve nada, só diz o que faria")
    args = p.parse_args()

    origem = pathlib.Path(args.logo)
    if not origem.is_file():
        print(f"erro: não encontrei {origem}", file=sys.stderr)
        return 1
    cor_fundo = tuple(int(args.background[i:i + 2], 16) for i in (0, 2, 4)) + (255,)
    logo = normalizar_logo(origem)
    print(f"logo: {origem.name} → normalizado a {logo.width}×{logo.height}")
    print(f"fundo: #{args.background.upper()}")
    if args.so_previa:
        print("(prévia) escreveria:", ", ".join(f"mipmap-{d}" for d in DENSIDADES), "+ anydpi-v26 + o XML da cor")
        return 0

    for densidade, (lado, lado_frente) in DENSIDADES.items():
        destino = RES / f"mipmap-{densidade}"
        destino.mkdir(parents=True, exist_ok=True)
        # Ícone clássico: fundo sólido + logo a 72% do lado.
        fundo = Image.new("RGBA", (lado, lado), cor_fundo)
        dentro = int(lado * 0.72)
        fundo.paste(logo.resize((dentro, dentro), Image.LANCZOS), ((lado - dentro) // 2,) * 2, logo.resize((dentro, dentro), Image.LANCZOS))
        fundo.convert("RGB").save(destino / "ic_launcher.png")
        redondo(fundo).save(destino / "ic_launcher_round.png")
        # Frente do adaptativo: só o logo, na tela de 108dp, com a margem de segurança (66/108).
        frente = Image.new("RGBA", (lado_frente, lado_frente), (0, 0, 0, 0))
        dentro = int(lado_frente * LOGO_NO_ADAPTATIVO)
        menor = logo.resize((dentro, dentro), Image.LANCZOS)
        frente.paste(menor, ((lado_frente - dentro) // 2,) * 2, menor)
        frente.save(destino / "ic_launcher_foreground.png")
        monovariante(frente).save(destino / "ic_launcher_monochrome.png")

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for nome in ("ic_launcher", "ic_launcher_round"):
        (anydpi / f"{nome}.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<!-- Adaptativo: fundo (cor) + frente (logo). O monochrome serve os ícones temáticos. -->\n"
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
            "</adaptive-icon>\n"
        )
    (RES / "values/ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f'    <color name="ic_launcher_background">#{args.background.upper()}</color>\n'
        "</resources>\n"
    )
    print("escrito: 5 densidades + anydpi-v26 + values/ic_launcher_background.xml")
    print("⚠️ o manifesto tem de apontar para @mipmap/ic_launcher (e @mipmap/ic_launcher_round)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
