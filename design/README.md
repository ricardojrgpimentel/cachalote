# Design do Cachalote

Este directório guarda o **design** da app: os ecrãs desenhados (fora do código) e o sistema de
design que os mantém coerentes. A implementação é em Compose; aqui fica a referência.

## O que está aqui

| Ficheiro | O que é |
|---|---|
| `sistema/DESIGN.md` | o sistema de design em markdown (cores, tipografia, espaçamento, forma) |
| `sistema/tema.json` | o tema completo: as 47 cores nomeadas (Material 3), tipografia por função, escala de espaçamento |
| `sistema/diretrizes.md` | a orientação de estilo (personalidade, hierarquia, movimento) |
| `ecras/<nome>.png` | o ecrã desenhado, em tamanho real |
| `app/<nome>.png` | como a app está de facto (para comparar com o desenho) |
| `logo/folha-de-marca.{png,html}` | a folha de marca: ícone, teste de legibilidade, monocromático, wordmark e lockup |
| `logo/icone-fonte.png` | o ícone isolado, 1024×1024 (a fonte de todos os tamanhos) |
| `logo/marca.png` | a marca recortada, com transparência (para o ícone) |
| `logo/marca.svg` | a marca **em vector** — extraída dos `<svg>` que o Stitch devolveu no HTML da folha de marca (`currentColor`, escala sem perda) |
| `ecras/<nome>.html` | o HTML do ecrã (referência de medidas e cores exactas para a implementação) |

## Como se reproduz (Google Stitch, MCP)

1. Chave da API do Stitch em `STITCH_API_KEY` (a mesma que se põe em *Poderes → Servidores MCP*).
2. Com o cliente mínimo `stitch/mcp.mjs` (que fala o MCP por HTTP):

   ```sh
   export STITCH_API_KEY=...
   node stitch/mcp.mjs tools                        # lista as ferramentas
   node stitch/mcp.mjs schemas                      # despeja os esquemas
   node stitch/mcp.mjs call create_project '{"title":"Cachalote"}'
   node stitch/mcp.mjs call generate_screen_from_text @payload.json
   ```

   ⚠️ O `projectId` vai **sem** o prefixo `projects/`. E as capturas vêm em miniatura: acrescentar
   `=w1200` ao URL do `lh3.googleusercontent.com` dá a imagem em tamanho real.
3. Os ecrãs seguintes devem reutilizar o **mesmo** `designSystem` (senão cada um inventa um estilo).

## Do desenho para o código

O tema da app é **gerado** do sistema de design, para não divergirem a cada iteração:

```sh
./tools/design-to-compose.py     # lê design/sistema/tema.json → app/.../Tema.kt
```

Isso produz o `darkColorScheme` (as cores nomeadas do design são nomes Material 3, o mapeamento é
1:1), a escala tipográfica e a escala de espaçamento (`Espaco`). As fontes do design (Space Grotesk,
Geist, JetBrains Mono) ainda não estão empacotadas: por agora usa-se a escala com as famílias do
sistema — empacotar as fontes é um passo à parte (são OFL, dá para as trazer).

## Logo e ícone

A marca é uma **baleia (cachalote) a mergulhar sob a linha de superfície** — o mergulho autónomo em
profundidade técnica e o regresso com o que encontrou. Sem gradientes, geométrica, legível a 48 px.

A marca existe em dois formatos: **raster** (`marca.png`, para o ícone, onde o Android quer PNG) e
**vector** (`marca.svg`, para tudo o resto). O vector saiu do próprio HTML da folha de marca — o
Stitch desenha em SVG, e isso está à vista em `folha-de-marca.html`. O mesmo path está em
`app/app/src/main/res/drawable/ic_marca.xml` (o cabeçalho da app usa-o).

O ícone da app é gerado dela:

```sh
python3 tools/make-icon.py --logo design/logo/marca.png --background 0C141D
```

Isso escreve as cinco densidades (`mipmap-*`), a variante redonda, a frente do ícone adaptativo, a
versão monocromática (ícones temáticos) e o XML da cor de fundo — e o manifesto aponta para
`@mipmap/ic_launcher`. **Precisa do Pillow**: no harness, o Python que o traz está em
`.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3`.

## Estado

| Ecrã | Estado |
|---|---|
| Logo, ícone e folha de marca | ✅ feitos · **no telefone** (`app/icone-no-lancador.png`, `app/marca-no-cabecalho.png`) |
| Conversa (principal) | ✅ desenhado · **tema aplicado** |
| Ajustes (menu de secções + secção aberta) | ✅ desenhado — `ecras/ajustes.png` (já com a barra de **dois** itens) |
| Aprovação (diálogo) | ✅ desenhado |
| Estado do sistema | ✅ desenhado |
| Chave de API | ✅ desenhado |
| Ecrã virtual | ✅ desenhado |
| Sessões (diálogo) | ✅ desenhado |
| Primeiros passos (onboarding) | ✅ desenhado · **assistente implementado** (6 passos, ainda com o layout simples — o desenho aplica-se depois) |
| ~~Poderes~~ | substituído por *Ajustes* (a IA mudou: a conversa é a primeira aba e os ajustes são um menu) |
| Resumo (doctor + consumo) | ✅ **implementado** (ainda com o layout simples) |
| Implementação das disposições em Compose | por fazer (a seguir, ecrã a ecrã) |

⚠️ **Nota:** os ecrãs desenhados antes da reorganização mostram a barra antiga de quatro itens
(Estado · Chave · Conversa · Poderes). O conteúdo continua a servir de referência; a **navegação** é
a que está no código (Conversa + Ajustes) e o ecrã *Ajustes* já foi redesenhado com ela.
