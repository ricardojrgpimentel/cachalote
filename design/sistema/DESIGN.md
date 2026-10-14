---
name: Cachalote Native Runtime
colors:
  surface: '#0c141d'
  surface-dim: '#0c141d'
  surface-bright: '#323a44'
  surface-container-lowest: '#070f17'
  surface-container-low: '#141c25'
  surface-container: '#182029'
  surface-container-high: '#232b34'
  surface-container-highest: '#2d353f'
  on-surface: '#dbe3f0'
  on-surface-variant: '#bacac2'
  inverse-surface: '#dbe3f0'
  inverse-on-surface: '#29313b'
  outline: '#84948d'
  outline-variant: '#3b4a44'
  surface-tint: '#15e0b4'
  primary: '#beffe7'
  on-primary: '#00382b'
  primary-container: '#38efc2'
  on-primary-container: '#006952'
  inverse-primary: '#006b55'
  secondary: '#58d6f1'
  on-secondary: '#00363f'
  secondary-container: '#00a7c0'
  on-secondary-container: '#003640'
  tertiary: '#c0ffdc'
  on-tertiary: '#003824'
  tertiary-container: '#5fedb0'
  on-tertiary-container: '#006947'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#4dfdcf'
  primary-fixed-dim: '#15e0b4'
  on-primary-fixed: '#002118'
  on-primary-fixed-variant: '#00513f'
  secondary-fixed: '#a7edff'
  secondary-fixed-dim: '#58d6f1'
  on-secondary-fixed: '#001f25'
  on-secondary-fixed-variant: '#004e5b'
  tertiary-fixed: '#6ffbbe'
  tertiary-fixed-dim: '#4edea3'
  on-tertiary-fixed: '#002113'
  on-tertiary-fixed-variant: '#005236'
  background: '#0c141d'
  on-background: '#dbe3f0'
  surface-variant: '#2d353f'
typography:
  headline-lg:
    fontFamily: Space Grotesk
    fontSize: 30px
    fontWeight: '600'
    lineHeight: 38px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Space Grotesk
    fontSize: 24px
    fontWeight: '500'
    lineHeight: 32px
    letterSpacing: -0.015em
  headline-sm:
    fontFamily: Space Grotesk
    fontSize: 20px
    fontWeight: '500'
    lineHeight: 26px
    letterSpacing: -0.01em
  body-lg:
    fontFamily: Geist
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: 0em
  body-md:
    fontFamily: Geist
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0.01em
  body-sm:
    fontFamily: Geist
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.02em
  label-lg:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
    letterSpacing: -0.01em
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '400'
    lineHeight: 14px
    letterSpacing: 0.02em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 0.75rem
  margin: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
---

## Brand & Style

This design system establishes an on-device, dark-first operational environment for mobile software engineering. The brand personality evokes extreme technical competence, silent execution power, and absolute privacy. Like a deep-diving organism navigating intense pressures, the interface remains calm, deliberate, and structurally robust under heavy computational loads.

The visual direction synthesizes Material 3 structural foundations with high-density developer tooling aesthetics. It discards decorative fluff in favor of razor-sharp data legibility, low thermal distraction, and rapid contextual switching. The emotional signature is calm mastery: zero latency anxiety, clear telemetry, and reliable autonomous tool orchestration.

European Portuguese (pt-PT) serves as the primary system voice. Terminology is natural, technically accurate, and standard among Portuguese software engineers (e.g., *depuração*, *registo de eventos*, *contexto ativo*, *executar*, *encadear ferramentas*), avoiding localized colloquialisms or awkward machine translations.

## Colors

The palette is engineered specifically for OLED displays and extended low-light terminal interaction. The surface stack relies on deep blue-grey luminance shifts to signify depth without triggering backlight fatigue.

- **Background & Canvas (`#0d131a`):** The absolute baseline. Anchors the viewport, status bar, and root scaffolding.
- **Surface Container Lowest (`#131b24`):** Base container for recessed areas, log traces, and background panels.
- **Surface Container (`#1b2431`):** Default card tier, inspector surfaces, and collapsed process trees.
- **Surface Container High (`#222e3e`):** Elevated overlays, modal bottom sheets, contextual menus, and active tool nodes.
- **Primary Accent (`#38efc2`):** Crisp neon teal. Represents user prompts, primary dispatch actions, and active local inference loops. High-contrast against all surface tiers.
- **Secondary Accent (`#48cae4`):** Soft luminous cyan. Reserved for syntax highlights, diff modifications, and agent reasoning streams.
- **Status Emerald (`#10b981`):** On-device hardware status, active weights verification, zero-error validation, and low memory pressure states.
- **Warning & Error Red (`#ef4444`):** Model stalls, context overflow, file permission locks, and runtime exceptions.
- **On-Surface High Emphasis (`#f1f5f9`):** Primary readable content, diff additions, and core metrics.
- **On-Surface Low Emphasis (`#64748b`):** Metadata timestamps, token limits, line numbering, and dormant flags.

## Typography

The typographic hierarchy distinguishes operational text from functional telemetry through deliberate structural divergence.

- **Display & Headings:** Set in *Space Grotesk*. Provides geometric precision and modern architectural authority without overwhelming small mobile viewports.
- **Narrative & System Copy:** Set in *Geist*. Built for supreme screen clarity, neutral rendering, and comfortable multi-line reasoning traces.
- **Code, Telemetry, and Metrology:** Set strictly in *JetBrains Mono*. All token meters (`tk/s`), hardware utilization metrics (`RAM 4.2/8.0 GB`), AST tool payloads, and raw shell captures must use this family to ensure absolute tabular alignment and instant terminal parsing.

All code viewports must disable artificial font smoothing anomalies and enforce proportional line heights to guarantee strict baseline vertical rhythm across dense diff inspections.

## Layout & Spacing

The layout is built for fluid touch ergonomics on handheld portrait viewports, adapting effortlessly to foldables and tablet splits. 

- **Grid Architecture:** 4-column layout on compact mobile devices (<600dp), expanding to an 8-column layout on medium/foldable screens. The lateral margins are strictly `16dp` (`1rem`) to maximize visible horizontal line length for code buffers.
- **Spatial Rhythm:** Built strictly around an 8dp baseline grid (with 4dp sub-steps for atomic inline badges and status dots).
- **Physical Touch Target Guarantee:** Every interactive node—from small terminal toggles to tool abort chips—must measure at minimum `48x48dp` in accessible bounds, using transparent touch expansions if the visual target is compact.
- **Thumb Zone Anchoring:** Input bars, action execution buttons, and stream interruptions are docked directly above the system navigation bar or virtual keyboard, eliminating upper-screen reach stress.

## Elevation & Depth

Visual hierarchy uses pure tonal stepping coupled with ultra-subtle ambient illumination rather than standard diffuse drop shadows.

- **Tonal Stepping:** Surfaces ascend physically by lightening their blue-grey values (`#0d131a` base → `#131b24` surface low → `#1b2431` card → `#222e3e` overlay).
- **Hairline Outlines:** Floating panels, code blocks, and context cards feature a 1dp crisp boundary with `rgba(255, 255, 255, 0.08)` to maintain absolute separation without lighting blur. Active panels transition their border to `rgba(56, 239, 194, 0.35)`.
- **Primary Ambient Glow:** Modals, floating action docks, and executing states cast an ultra-diffused, directional luminescence: `0px 8px 24px rgba(38, 208, 206, 0.12)`. This conveys active on-device neural processing without visual clutter.

## Shapes

The design system enforces a disciplined `16dp` (`1rem`) base curvature across all primary structural containers and dialog elements, ensuring a tactile, ergonomic Material 3 feel that softens intense engineering data.

- **Primary Cards and Sheets:** Standardized at `16dp` (`rounded-lg`).
- **Interactive Buttons & Input Bars:** Configured with `16dp` corners or full continuous pill curvature (`rounded-xl` / fully rounded) depending on terminal grouping context.
- **Code Blocks & Tool Payloads:** Scaled down to `8dp` (`rounded`) interior nests to retain rigid terminal-like structure while sitting seamlessly within `16dp` parent containers.

## Components

### Botões (Buttons)
- **Ação Principal (Primary Action):** Filled container (`#38efc2`), text and iconography in dark charcoal (`#0d131a`, `JetBrains Mono` medium). Height: `48dp`. High-priority commands: *Executar Agente*, *Aplicar Alterações*.
- **Ação Secundária (Secondary Action):** Outlined with 1dp `#222e3e`, background `#131b24`, text in `#38efc2`. Standard commands: *Depurar*, *Ver Registo*.
- **Interrupção Crítica (Destructive/Halt):** Surface container with `#ef4444` border and text. Minimum height `48dp`: *Abortar Processo*.

### Fichas Informativas & Filtros (Chips)
- Compact telemetry badges (`height: 32dp`, touch bounds `48dp`).
- Inactive state: `#131b24` background, `#64748b` text.
- Active hardware state: `#1b2431` background with `#10b981` pulsing indicator dot (`6dp`) and monospace readout (ex: `NPU: Ativa • 24 tok/s`).

### Blocos de Código e Diferenças (Code Blocks & Diffs)
- Recessed `#131b24` surface with an upper control strip displaying filename, active syntax, and line metrics in `JetBrains Mono`.
- Added lines: Background tinted `rgba(16, 185, 129, 0.12)`, text `#10b981`.
- Removed lines: Background tinted `rgba(239, 68, 68, 0.12)`, text `#ef4444`.

### Campos de Entrada (Input Fields)
- Base background `#1b2431` with `16dp` rounded corners.
- Placeholder text in `#64748b` (*Instrua o modelo local...*).
- Active focus state: 1.5dp border in `#38efc2`, zero outer shadow, cursor tinted `#38efc2`. Integrated suffix action for audio or direct file context binding.

### Cartões de Execução de Ferramentas (Tool Call Cards)
- Modular cards placed inline within the agent stream.
- Header showcases the tool status (*A Ler Ficheiro*, *A Escrever Diff*, *Terminal Shell*), an animated emerald spinner or tick icon, and execution duration.
- Body displays collapsible monospaced arguments and output limits to preserve screen space.

### Medidor de Contexto (Context Window Gauge)
- Linear progress track spanning the bottom sheet or app bar (`height: 4dp`, background `#1b2431`).
- Fill tracks dynamically from `#38efc2` (low context) to `#ef4444` (context exhaustion at >90% token window). Accompanied by inline monospace readouts (`ex: 14.2k / 16k tokens`).