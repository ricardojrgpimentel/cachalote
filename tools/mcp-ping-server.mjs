#!/usr/bin/env node
// mcp-ping-server.mjs — servidor MCP mínimo (stdio) para TESTAR a ligação de servidores MCP
// externos ao agente. Implementa só o necessário: initialize, tools/list, tools/call.
//
//   node mcp-ping-server.mjs
//
// Ferramentas: `ping` (devolve "pong") e `echo` (devolve o texto recebido). Ver
// docs/knowledge-android.md (item 99).
import readline from "node:readline";

const PROTOCOL = "2024-11-05";
const TOOLS = [
  {
    name: "ping",
    description: "Responde 'pong'. Serve para confirmar que um servidor MCP externo está ligado.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    name: "echo",
    description: "Devolve o texto recebido em 'text'.",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string", description: "Texto a devolver." } },
      required: ["text"],
      additionalProperties: false,
    },
  },
];

const rl = readline.createInterface({ input: process.stdin, terminal: false });
const send = (msg) => process.stdout.write(JSON.stringify(msg) + "\n");

rl.on("line", (line) => {
  if (!line.trim()) return;
  let msg;
  try { msg = JSON.parse(line); } catch { return; }
  const id = msg.id;
  const reply = (result) => id !== undefined && send({ jsonrpc: "2.0", id, result });
  const fail = (message) => id !== undefined && send({ jsonrpc: "2.0", id, error: { code: -32000, message } });
  switch (msg.method) {
    case "initialize":
      reply({
        protocolVersion: PROTOCOL,
        capabilities: { tools: {} },
        serverInfo: { name: "cachalote-ping", version: "1.0.0" },
      });
      break;
    case "notifications/initialized":
    case "initialized":
      break;
    case "tools/list":
      reply({ tools: TOOLS });
      break;
    case "tools/call": {
      const nome = msg.params?.name;
      const args = msg.params?.arguments ?? {};
      if (nome === "ping") reply({ content: [{ type: "text", text: "pong" }] });
      else if (nome === "echo") reply({ content: [{ type: "text", text: String(args.text ?? "") }] });
      else fail(`ferramenta desconhecida: ${nome}`);
      break;
    }
    case "ping":
      reply({});
      break;
    default:
      if (id !== undefined) fail(`método não suportado: ${msg.method}`);
  }
});
