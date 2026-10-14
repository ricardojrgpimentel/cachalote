// Cliente mínimo do MCP do Stitch (JSON-RPC sobre HTTP) — para eu poder desenhar directamente.
//   node mcp.mjs tools                       lista ferramentas e esquemas
//   node mcp.mjs call <nome> '<json>'        chama uma ferramenta
import { readFileSync, writeFileSync } from "node:fs";
const URL = "https://stitch.googleapis.com/mcp";
const KEY = process.env.STITCH_API_KEY;
if (!KEY) { console.error("falta STITCH_API_KEY"); process.exit(2); }

async function rpc(method, params) {
  const r = await fetch(URL, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Accept": "application/json, text/event-stream",
      "X-Goog-Api-Key": KEY,
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
  });
  const texto = await r.text();
  // O servidor pode responder em SSE (`data: {...}`); aceitamos os dois.
  const linha = texto.split("\n").find((l) => l.trim().startsWith("data:")) ?? texto;
  const json = JSON.parse(linha.replace(/^data:\s*/, ""));
  if (json.error) throw new Error(JSON.stringify(json.error).slice(0, 400));
  return json.result;
}

const [cmd, nome, args] = process.argv.slice(2);
await rpc("initialize", { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "cachalote-design", version: "0.1" } });
if (cmd === "tools") {
  const r = await rpc("tools/list", {});
  for (const t of r.tools ?? []) {
    if (nome && t.name !== nome) continue;
    console.log("###", t.name);
    console.log("   ", (t.description ?? "").split("\n")[0].slice(0, 120));
    if (t.inputSchema) console.log("    schema:", JSON.stringify(t.inputSchema).slice(0, 900));
  }
} else if (cmd === "schemas") {
  const r = await rpc("tools/list", {});
  writeFileSync(".work/stitch/schemas.json", JSON.stringify(r, null, 1));
  console.log("esquemas em .work/stitch/schemas.json (" + (r.tools ?? []).length + " ferramentas)");
} else if (cmd === "call") {
  // Os argumentos podem vir de um ficheiro (`@caminho.json`): evita o inferno das aspas na shell.
  let payload = {};
  if (args) payload = args.startsWith("@") ? JSON.parse(readFileSync(args.slice(1), "utf8")) : JSON.parse(args);
  const r = await rpc("tools/call", { name: nome, arguments: payload });
  const texto = (r.content ?? []).map((c) => c.text ?? JSON.stringify(c)).join("\n");
  console.log(texto);   // sem truncar: quem chama decide (redireccionar para ficheiro)
} else {
  console.error("uso: mcp.mjs tools [nome] | mcp.mjs call <nome> '<json>'");
  process.exit(2);
}
