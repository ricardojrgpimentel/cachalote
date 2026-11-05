#!/usr/bin/env node
/*
 * mock-acp-test.cjs — testa o daemon falso **sem telefone**.
 *
 * A app é exercitada no emulador por `mock-e2e-test.sh`; este teste garante que o contrato do mock
 * (auth, initialize, sessão, prompt, ferramentas, autorização e erro) não parte numa mudança de
 * Node ou de schema. Corre na CI, sem Android.
 */
"use strict";

const { spawn } = require("node:child_process");
const net = require("node:net");
const path = require("node:path");

const MOCK = path.join(__dirname, "mock-acp-daemon.cjs");
const PORT = Number(process.env.MOCK_ACP_PORT || 39733);
const TOKEN = "mock-test-" + "b".repeat(64);
const updates = [];
let pedidoDeAutorizacao = null;

function esperar(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function arrancarMock() {
  const p = spawn(process.execPath, [MOCK, "--port", String(PORT), "--token", TOKEN], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  p.stdout.on("data", (b) => process.stdout.write("[mock] " + b.toString()));
  p.stderr.on("data", (b) => process.stderr.write("[mock] " + b.toString()));
  return p;
}

function ligar() {
  const s = net.connect(PORT, "127.0.0.1");
  let buf = "";
  let id = 0;
  const pendentes = new Map();
  s.on("data", (chunk) => {
    buf += chunk.toString("utf8");
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const linha = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!linha) continue;
      const msg = JSON.parse(linha);
      if (msg.method === "session/update") {
        updates.push(msg.params.update);
      } else if (msg.method === "session/request_permission") {
        pedidoDeAutorizacao = msg;
        // Recusa sempre: é o caminho que a app tem de reportar como «pedido recusado».
        s.write(JSON.stringify({
          jsonrpc: "2.0",
          id: msg.id,
          result: { outcome: { outcome: "selected", optionId: "reject_once" } },
        }) + "\n");
      } else if (msg.id !== undefined) {
        const p = pendentes.get(String(msg.id));
        if (p) { pendentes.delete(String(msg.id)); p(msg); }
      }
    }
  });
  return {
    socket: s,
    call(method, params = {}) {
      const meu = ++id;
      return new Promise((resolve, reject) => {
        const t = setTimeout(() => { pendentes.delete(String(meu)); reject(new Error("timeout em " + method)); }, 5000);
        pendentes.set(String(meu), (msg) => {
          clearTimeout(t);
          if (msg.error) reject(new Error(msg.error.message));
          else resolve(msg.result);
        });
        s.write(JSON.stringify({ jsonrpc: "2.0", id: meu, method, params }) + "\n");
      });
    },
  };
}

async function main() {
  const mock = arrancarMock();
  rs: for (let i = 0; i < 50; i++) {
    try {
      const s = net.connect(PORT, "127.0.0.1");
      await new Promise((res, rej) => { s.once("connect", res); s.once("error", rej); });
      s.destroy();
      break rs;
    } catch {
      await esperar(100);
    }
  }
  const c = ligar();
  let falhas = 0;
  const ok = (cond, msg) => {
    if (cond) console.log("   OK     " + msg);
    else { falhas++; console.log("   FALHA  " + msg); }
  };

  ok((await c.call("cachalote.auth", { token: TOKEN })).ok === true, "auth");
  const init = await c.call("initialize", {});
  ok(init.agentInfo?.name === "mock-acp", "initialize devolve agentInfo");
  const nova = await c.call("session/new", { cwd: "/mock" });
  ok(String(nova.sessionId || "").startsWith("mock-"), "session/new cria sessão");
  ok(Array.isArray(nova.configOptions) && nova.configOptions.length === 2, "session/new traz as opções");

  updates.length = 0;
  const r1 = await c.call("session/prompt", { sessionId: nova.sessionId, prompt: [{ type: "text", text: "ola mock" }] });
  ok(r1.stopReason === "end_turn", "prompt termina em end_turn");
  ok(updates.some((u) => u.sessionUpdate === "agent_message_chunk"), "prompt manda texto");
  ok(updates.some((u) => u.sessionUpdate === "tool_call"), "prompt manda ferramenta");
  ok(updates.some((u) => u.sessionUpdate === "usage_update"), "prompt manda consumo");

  updates.length = 0;
  const r2 = await c.call("session/prompt", { sessionId: nova.sessionId, prompt: [{ type: "text", text: "aprova mock" }] });
  ok(pedidoDeAutorizacao !== null, "o servidor pediu autorização");
  ok(r2.stopReason === "end_turn", "o turno de aprovação termina");
  const texto = updates
    .filter((u) => u.sessionUpdate === "agent_message_chunk")
    .map((u) => u.content?.text || "")
    .join("");
  ok(texto.includes("Pedido recusado"), "a recusa vira resposta guionada");

  let erro = null;
  try {
    await c.call("session/prompt", { sessionId: nova.sessionId, prompt: [{ type: "text", text: "erro mock" }] });
  } catch (e) { erro = e; }
  ok(erro && erro.message.includes("erro simulado"), "prompt de erro devolve JSON-RPC error");

  const meta = await c.call("cachalote.sessions.meta", {});
  ok(meta.sessions.length === 1, "metadados listam a sessão");
  const turns = await c.call("cachalote.sessions.turns", { sessionId: nova.sessionId });
  ok(turns.turns.length === 1 && turns.turns[0].steps === 1, "turnos do mock");
  const hist = await c.call("cachalote.sessions.history", { sessionId: nova.sessionId });
  ok(hist.events.length >= 3, "histórico do mock");

  c.socket.end();
  mock.kill("SIGTERM");
  console.log(falhas === 0 ? "\nmock: tudo bem" : `\nmock: ${falhas} falhas`);
  process.exit(falhas === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("mock test falhou:", e.message);
  process.exit(1);
});
