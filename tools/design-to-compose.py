#!/usr/bin/env python3
"""design-to-compose.py — do sistema de design (Stitch) para o tema Compose.

Lê `design/sistema/tema.json` e escreve `app/app/src/main/java/dev/ricardopimentel/cachalote/Tema.kt`
com o `darkColorScheme` e a tipografia. Assim, iterar no design e actualizar a app é um comando —
em vez de copiar cores à mão (e deixá-las divergir).

    ./tools/design-to-compose.py
"""
import json
import pathlib
import re
import sys

RAIZ = pathlib.Path(__file__).resolve().parent.parent
TEMA = RAIZ / "design/sistema/tema.json"
DESTINO = RAIZ / "app/app/src/main/java/dev/ricardopimentel/cachalote/Tema.kt"

# Parâmetros que o `darkColorScheme` do Material 3 aceita (nomes em camelCase).
SUPORTADOS = {
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
    "surfaceTint", "inverseSurface", "inverseOnSurface", "error", "onError", "errorContainer",
    "onErrorContainer", "outline", "outlineVariant", "scrim",
    "surfaceBright", "surfaceDim", "surfaceContainer", "surfaceContainerHigh",
    "surfaceContainerHighest", "surfaceContainerLow", "surfaceContainerLowest",
}
# Nota: os tons `*Fixed*` do Material 3 "expressive" ficam de fora — a versão do Compose que a app
# usa (compose-bom 2024.10.01) ainda não os expõe no `darkColorScheme`. Se um dia actualizarmos,
# basta devolvê-los a esta lista.

# A escala do design → escala do Material 3.
TIPOGRAFIA = {
    "headline-lg": "headlineLarge", "headline-md": "headlineMedium", "headline-sm": "headlineSmall",
    "body-lg": "bodyLarge", "body-md": "bodyMedium", "body-sm": "bodySmall",
    "label-lg": "labelLarge", "label-md": "labelMedium", "label-sm": "labelSmall",
}


def camel(nome: str) -> str:
    partes = nome.split("_")
    return partes[0] + "".join(p.capitalize() for p in partes[1:])


def hex_arg(cor: str) -> str:
    return "Color(0xFF%s)" % cor.lstrip("#").upper()


def px(valor) -> float:
    m = re.match(r"([\d.]+)", str(valor))
    return float(m.group(1)) if m else 14.0


def main() -> int:
    if not TEMA.is_file():
        print(f"erro: não encontrei {TEMA}", file=sys.stderr)
        return 1
    t = json.loads(TEMA.read_text())
    cores = {camel(k): v for k, v in t["namedColors"].items()}
    usadas = {k: v for k, v in cores.items() if k in SUPORTADOS}
    ignoradas = sorted(set(cores) - SUPORTADOS)

    linhas = [
        "package dev.ricardopimentel.cachalote",
        "",
        "// GERADO por tools/design-to-compose.py a partir de design/sistema/tema.json.",
        "// Não editar à mão: mexe no design e volta a correr o gerador.",
        "",
        "import androidx.compose.material3.Typography",
        "import androidx.compose.material3.darkColorScheme",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.text.TextStyle",
        "import androidx.compose.ui.text.font.FontFamily",
        "import androidx.compose.ui.text.font.FontWeight",
        "import androidx.compose.ui.unit.dp",
        "import androidx.compose.ui.unit.em",
        "import androidx.compose.ui.unit.sp",
        "",
        f"// Sistema de design \"{t.get('designSystemName', 'Cachalote')}\" — modo {t.get('colorMode', 'DARK')}.",
        "val cachaloteColors = darkColorScheme(",
    ]
    for k in sorted(usadas):
        linhas.append(f"    {k} = {hex_arg(usadas[k])},")
    linhas += [")", ""]

    # Tipografia: as fontes do design (Space Grotesk/Geist/JetBrains Mono) ainda não estão
    # empacotadas na app; por agora mapeia-se a escala e usam-se as famílias do sistema.
    linhas += [
        "// Escala tipográfica do design. `mono` para as strings técnicas (ids, comandos, JSON).",
        "val Mono = FontFamily.Monospace",
        "",
        "val cachaloteTypography = Typography(",
    ]
    for origem, destino in TIPOGRAFIA.items():
        v = t["typography"].get(origem)
        if not v:
            continue
        tamanho = px(v.get("fontSize"))
        peso = int(re.sub(r"\D", "", str(v.get("fontWeight", "400"))) or 400)
        altura = px(v.get("lineHeight", tamanho * 1.4))
        espaco = str(v.get("letterSpacing", "0")).replace("em", "").strip() or "0"
        try:
            espaco_em = float(espaco)
        except ValueError:
            espaco_em = 0.0
        linhas.append(
            f"    {destino} = TextStyle(fontSize = {tamanho:g}.sp, fontWeight = FontWeight({peso}), "
            f"lineHeight = {altura:g}.sp, letterSpacing = {espaco_em:g}.em),"
        )
    linhas += [")", ""]

    # Espaçamento, em dp, com os nomes do design.
    esp = t.get("spacing", {})
    ordem = ["space-xs", "space-sm", "gutter", "space-md", "space-lg", "space-xl", "margin"]
    linhas.append("// Escala de espaçamento do design (rem → dp, a 1rem = 16dp).")
    linhas.append("object Espaco {")
    nomes_dp = {"space-xs": "xs", "space-sm": "sm", "gutter": "gutter", "space-md": "md",
                "space-lg": "lg", "space-xl": "xl", "margin": "margin"}
    for nome in ordem:
        if nome in esp:
            valor = px(esp[nome]) * 16
            linhas.append(f"    val {nomes_dp[nome]} = {valor:g}.dp")
    linhas += ["}", ""]

    DESTINO.write_text("\n".join(linhas) + "\n")
    print(f"escrito {DESTINO.relative_to(RAIZ)}: {len(usadas)} cores, {len(TIPOGRAFIA)} estilos, {len(esp)} espaçamentos")
    if ignoradas:
        print(f"  (cores do design sem equivalente no Material 3, ignoradas: {', '.join(ignoradas[:6])}"
              f"{' …' if len(ignoradas) > 6 else ''})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
