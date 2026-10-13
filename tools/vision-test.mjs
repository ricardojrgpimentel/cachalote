#!/usr/bin/env node
// vision-test.mjs — verifica o caminho "imagem → modelo" sem passar pelo agente:
// liga-se ao daemon, abre uma sessão e envia um prompt com TEXTO + BLOCO DE IMAGEM (o PNG
// capturado pela app), imprimindo a resposta do modelo.
//
//   node vision-test.mjs <ficheiro.json-com-base64> [pergunta] [socket]
//
// O JSON vem de: ctl.cjs host.call '{"capability":"screenshot","params":{"maxWidth":720}}'
import fs from "node:fs";
import net from "node:net";

const shotFile = process.argv[2];
const question = process.argv[3] || "Que numero vês no visor da calculadora? Responde só com o numero.";
const sock = process.argv[4] || "/data/adb/cachalote/run/cachalote.sock";
const shot = JSON.parse(fs.readFileSync(shotFile, "utf8"));
if (!shot.data) { console.log("o ficheiro não tem 'data' (base64). Chaves:", Object.keys(shot).join(", ")); process.exit(1); }

const socket = net.connect(sock);
let buffer = "";
let id = 1;
let sessionId = null;
const chunks = [];
const send = (method, params) => socket.write(JSON.stringify({ jsonrpc: "2.0", id: id++, method, params }) + "\n");

socket.on("connect", () => send("initialize", { protocolVersion: 1, clientCapabilities: {}, clientInfo: { name: "vision-test", version: "1" } }));
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
      console.log("imagens anunciadas:", msg.result?.agentCapabilities?.promptCapabilities?.image === true);
      send("session/new", { cwd: process.env.CACHALOTE_WS || "/data/adb/cachalote/home/ws", mcpServers: [] });
    } else if (msg.id === 2) {
      if (msg.error) { console.log("session/new falhou:", JSON.stringify(msg.error)); process.exit(1); }
      sessionId = msg.result?.sessionId;
      console.log("sessão:", sessionId, "| imagem:", shot.width + "x" + shot.height, Math.round((shot.data.length * 3) / 4 / 1024) + " KB");
      send("session/prompt", {
        sessionId,
        prompt: [
          { type: "text", text: question },
          { type: "image", data: shot.data, mimeType: shot.mimeType || "image/png" },
        ],
      });
    } else if (msg.id === 3) {
      console.log("--- resposta ---");
      console.log(chunks.join("").trim().slice(0, 600) || "(sem texto)");
      console.log(msg.error ? "erro: " + JSON.stringify(msg.error).slice(0, 300) : "turno OK");
      process.exit(0);
    } else if (msg.method === "session/update") {
      const update = msg.params?.update;
      if (update?.sessionUpdate === "agent_message_chunk") chunks.push(update.content?.text ?? "");
      if (update?.sessionUpdate === "tool_call") console.log("[ferramenta]", update.title ?? update.toolCallId);
    }
  }
});
socket.on("error", (e) => { console.log("erro de ligação:", e.message); process.exit(1); });
setTimeout(() => { console.log("tempo esgotado. Pedaços:", chunks.join("").slice(0, 200)); process.exit(1); }, 300000);
