/**
 * Exemplo mínimo de um **plugin do DSH** a correr dentro do Cachalote.
 *
 * Serve para responder a "dá para correr plugins do DSH?": dá, e é assim. Um plugin não é mais do
 * que isto — exporta `name`, `inject` e `apply(ctx)` — e monta-se pela nossa camada de patch
 * (`config/cachalote.patch.yml`, secção `insert`), que é exactamente o que já fazemos com o plugin
 * das capacidades Android.
 *
 * Para instalar outro plugin qualquer (de terceiros): põe o pacote em
 * `kernel/dsh/node_modules/<pacote>` e acrescenta um `insert` com o caminho do `lib/index.js`.
 *
 * @module @exemplo/dsh-plugin-cachalote
 */
import { defineTool } from "@deepseek-ai/dsh-tools";

const name = "exemplo";
const inject = ["tools"];

function apply(ctx) {
  ctx.tools.register(defineTool({
    name: "exemplo_ola",
    description: "Ferramenta de exemplo: devolve uma saudação com o nome da app que a montou.",
    parameters: { quem: { type: "string", description: "A quem cumprimentar (opcional)." } },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: { text: { type: "string", required: true } },
      },
      render: (_args, value) => [{ type: "text", text: value.text }],
      presentationMeta: () => ({ title: "Exemplo" }),
    },
    async execute(args) {
      return { text: `olá ${args.quem ?? "mundo"}, daqui é o plugin de exemplo do Cachalote` };
    },
  }));
  // A prova de que o plugin activou: uma linha no registo do motor, com o nome da ferramenta.
  console.log("[plugin-exemplo] activo: registei a ferramenta exemplo_ola");
}

export { apply, inject, name };
