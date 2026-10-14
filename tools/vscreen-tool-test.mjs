// vscreen-tool-test.mjs — chama a ferramenta `android_vscreen` do plugin **directamente**,
// contra o daemon a correr no telefone. Corre no telefone (é lá que o 127.0.0.1:39731 existe).
//
//   node vscreen-tool-test.mjs <caminho-do-plugin> <displayId>
//
// Porque existe: verificar as acções novas (launch/tap/key/text) sem depender de automação de UI
// (que falha por o campo não ter foco, o teclado tapar o botão, etc.). Aqui chama-se o código do
// plugin — o mesmo que o agente chama — e vê-se a resposta do daemon.
const plugin = process.argv[2] || "/data/adb/cachalote/kernel/dsh/node_modules/@deepseek-ai/dsh-tool-cachalote/lib/index.js";
const displayId = Number(process.argv[3] || 0);
const { apply: aplicar } = await import(plugin);

const registadas = new Map();
const ctx = {
  tools: { register: (t) => registadas.set(t.name, t) },
  get: () => undefined,
};
aplicar(ctx);

const ferramenta = registadas.get("android_vscreen");
if (!ferramenta) {
  console.log("FALHA: a ferramenta android_vscreen não ficou registada");
  process.exit(1);
}

const acoes = [
  ["tap errado (display 999) — tem de ser recusado", { action: "tap", displayId: 999, x: 10, y: 10 }, true],
  ["subcomando proibido via componente inválido", { action: "launch", displayId, componente: "x; rm -rf /" }, true],
  ["launch da calculadora", { action: "launch", displayId, componente: "com.android.calculator2/.Calculator" }, false],
  ["frame (devolve imagem)", { action: "frame", maxWidth: 540 }, false],
  ["tap na tecla 8", { action: "tap", displayId, x: 267, y: 838 }, false],
  ["tecla BACK", { action: "key", displayId, tecla: "KEYCODE_BACK" }, false],
];

let falhas = 0;
for (const [nome, args, esperaErro] of acoes) {
  try {
    const r = await ferramenta.execute(args);
    const resumo = JSON.stringify(r).slice(0, 120);
    if (esperaErro) {
      console.log(`  [!!] ${nome}: devia ter falhado e devolveu ${resumo}`);
      falhas++;
    } else {
      console.log(`  [ok] ${nome}: ${resumo}`);
    }
  } catch (error) {
    const mensagem = error?.message ?? String(error);
    if (esperaErro) console.log(`  [ok] ${nome}: recusado — ${mensagem.slice(0, 110)}`);
    else {
      console.log(`  [XX] ${nome}: falhou — ${mensagem.slice(0, 160)}`);
      falhas++;
    }
  }
}

console.log(falhas === 0 ? "\nferramenta android_vscreen: tudo bem" : `\nferramenta android_vscreen: ${falhas} falha(s)`);
process.exit(falhas === 0 ? 0 : 1);
