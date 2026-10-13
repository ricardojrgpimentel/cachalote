#!/usr/bin/env node
// session-info.mjs — pergunta ao motor (pelo socket unix do daemon) que sessão cria e que
// opções de configuração oferece: modelos disponíveis e qual está em uso. Serve para confirmar
// se o modelo em uso aceita imagens (visão).
//
//   node tools/session-info.mjs [caminho-do-socket]
import net from "node:net";

const SOCK = process.argv[2] || "/data/adb/cachalote/run/cachalote.sock";
const CWD = process.argv[3] || "/data/adb/cachalote/home/ws";
const socket = net.connect(SOCK);
let buffer = "";
let id = 1;

function send(method, params) {
  socket.write(JSON.stringify({ jsonrpc: "2.0", id: id++, method, params }) + "\n");
}

socket.on("connect", () => {
  send("initialize", { protocolVersion: 1, clientCapabilities: {}, clientInfo: { name: "session-info", version: "1" } });
});

socket.on("data", (chunk) => {
  buffer += chunk.toString();
  let index;
  while ((index = buffer.indexOf("\n")) >= 0) {
    const line = buffer.slice(0, index);
    buffer = buffer.slice(index + 1);
    if (!line.trim()) continue;
    const msg = JSON.parse(line);
    if (msg.id === 1) {
      if (msg.error) { console.log("initialize falhou:", JSON.stringify(msg.error)); process.exit(1); }
      send("session/new", { cwd: CWD, mcpServers: [] });
    } else if (msg.id === 2) {
      if (msg.error) { console.log("session/new falhou:", JSON.stringify(msg.error)); process.exit(1); }
      const options = msg.result?.configOptions ?? [];
      console.log("sessão:", msg.result?.sessionId);
      for (const option of options) {
        const values = (option.options ?? []).map((o) => o.value).join(", ");
        console.log(`  ${option.id}: ${option.currentValue}${values ? "   [opções: " + values + "]" : ""}`);
      }
      if (options.length === 0) console.log("  (sem configOptions; result:", JSON.stringify(msg.result).slice(0, 400), ")");
      process.exit(0);
    }
  }
});
socket.on("error", (error) => { console.log("erro de ligação:", error.message); process.exit(1); });
setTimeout(() => { console.log("tempo esgotado"); process.exit(1); }, 30000);
