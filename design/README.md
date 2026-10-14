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
| `logo/marca.png` | a marca recortada, com transparência (para o ícone e para dentro da app) |
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
| Logo e ícone | ✅ feitos e **no telefone** (`app/icone-no-lancador.png`) |
| Conversa (principal) | ✅ desenhado — `ecras/conversa.png` · **tema já aplicado** (`app/tema-aplicado-conversa.png`) |
| Aprovação (diálogo) | ✅ desenhado — `ecras/aprovacao.png` |
| Estado | por desenhar |
| Chave | por desenhar |
| Poderes | por desenhar |
| Sessões | por desenhar |
| Ecrã virtual | por desenhar |
| Implementação em Compose | por fazer |

**Nota de qualidade do primeiro ecrã:** o botão *Enviar* fica cortado na margem direita (a linha do
campo transborda). Fica registado para corrigir no desenho seguinte — e é o tipo de coisa que a
implementação em Compose resolve com um `Row` com `weight`.
