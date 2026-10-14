## Brand & Style

This design system establishes an on-device, dark-first operational environment for mobile software engineering. The brand personality evokes extreme technical competence, silent execution power, and absolute privacy. Like a deep-diving organism navigating intense pressures, the interface remains calm, deliberate, and structurally robust under heavy computational loads.

The visual direction synthesizes Material 3 structural foundations with high-density developer tooling aesthetics. It discards decorative fluff in favor of razor-sharp data legibility, low thermal distraction, and rapid contextual switching. The emotional signature is calm mastery: zero latency anxiety, clear telemetry, and reliable autonomous tool orchestration.

European Portuguese (pt-PT) serves as the primary system voice. Terminology is natural, technically accurate, and standard among Portuguese software engineers (e.g., *depuração*, *registo de eventos*, *contexto ativo*, *executar*, *encadear ferramentas*), avoiding localized colloquialisms or awkward machine translations.

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