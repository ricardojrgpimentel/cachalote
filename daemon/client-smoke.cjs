#!/usr/bin/env node
/*
 * client-smoke.cjs — cliente de referência do socket do daemon. É o que o app
 * (F2, Kotlin) vai fazer; aqui em JS para poder ser testado por adb.
 *
 *   node client-smoke.cjs [socket] [--prompt "texto"] [--timeout 30]
 *
 * Fala JSON-RPC 2.0 NDJSON (um objecto por linha) no socket AF_UNIX e mostra o
 * que o motor responde, incluindo as notificações de sessão.
 */
const net = require("node:net");

const args = process.argv.slice(2);
const SOCK = args[0] && !args[0].startsWith("--") ? args[0] : process.env.CACHALOTE_SOCK || "/data/adb/cachalote/run/cachalote.sock";
const pIdx = args.indexOf("--prompt");
const PROMPT = pIdx >= 0 ? args[pIdx + 1] : null;
const tIdx = args.indexOf("--timeout");
const TIMEOUT = (tIdx >= 0 ? Number(args[tIdx + 1]) : 30) * 1000;

const t0 = Date.now();
const sock = net.connect(SOCK);
let buf = "";
let sessionId = null;
const ok = { initialize: false, session: false, prompt: false };

const send = (o) => { sock.write(JSON.stringify(o) + "\n"); console.log("→ " + JSON.stringify(o).slice(0, 150)); };
const done = (why) => {
  console.log(`\n== resultado (${((Date.now() - t0) / 1000).toFixed(1)}s, ${why}) ==`);
  console.log(`   initialize=${ok.initialize ? "OK" : "FALHOU"} session=${ok.session ? "OK" : "FALHOU"}` + (PROMPT ? ` prompt=${ok.prompt ? "aceite" : "falhou"}` : ""));
  try { sock.destroy(); } catch {}
  process.exit(ok.initialize && ok.session ? 0 : 1);
};

sock.on("connect", () => {
  console.log("ligado a " + SOCK);
  send({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: 1, clientCapabilities: {}, clientInfo: { name: "cachalote-client-smoke", version: "0.1" } } });
});
sock.on("data", (b) => {
  buf += b.toString();
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i); buf = buf.slice(i + 1);
    if (!line.trim()) continue;
    let m; try { m = JSON.parse(line); } catch { console.log("← (não-JSON) " + line.slice(0, 120)); continue; }
    if (m.id === 1) {
      if (m.result) { ok.initialize = true; console.log("← initialize OK: " + JSON.stringify(m.result.agentInfo)); setTimeout(() => send({ jsonrpc: "2.0", id: 2, method: "session/new", params: { cwd: process.env.CACHALOTE_WS || "/data/adb/cachalote/home/ws", mcpServers: [] } }), 300); }
      else { console.log("← initialize ERRO: " + JSON.stringify(m.error)); done("erro"); }
    } else if (m.id === 2) {
      if (m.result) { ok.session = true; sessionId = m.result.sessionId; console.log("← session/new OK: " + sessionId); if (PROMPT) { setTimeout(() => send({ jsonrpc: "2.0", id: 3, method: "session/prompt", params: { sessionId, prompt: [{ type: "text", text: PROMPT }] } }), 300); } else done("sem prompt"); }
      else { console.log("← session/new ERRO: " + JSON.stringify(m.error)); done("erro"); }
    } else if (m.id === 3) {
      ok.prompt = !m.error;
      console.log("← prompt: " + JSON.stringify(m.result || m.error).slice(0, 300));
      setTimeout(() => done("fim"), 1500);
    } else if (m.method) {
      const s = JSON.stringify(m.params);
      if (/update|status|event/.test(m.method)) console.log("← " + m.method + " " + (s.length > 160 ? s.slice(0, 160) + "…" : s));
    } else console.log("← " + line.slice(0, 200));
  }
});
sock.on("error", (e) => { console.log("erro de socket: " + e.message); done("erro de socket"); });
setTimeout(() => done("timeout"), TIMEOUT);
