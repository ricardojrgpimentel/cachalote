#!/usr/bin/env node
/*
 * ctl.cjs — cliente de linha de comandos do canal de controlo do daemon.
 * Corre NO TELEMÓVEL (o token está lá). Serve para diagnosticar sem a app.
 *
 *   node ctl.cjs status
 *   node ctl.cjs host.status
 *   node ctl.cjs credentials.status '{"name":"DEEPSEEK_API_KEY"}'
 *   node ctl.cjs host.call '{"capability":"status","params":{}}'
 *
 * Lê o token de $CACHALOTE_TOKEN ou de <base>/home/app.token.
 */
const net = require("node:net");
const fs = require("node:fs");
const path = require("node:path");

const BASE = process.env.CACHALOTE_BASE || "/data/adb/cachalote";
const PORT = Number(process.env.CACHALOTE_PORT || 39731);
const token = process.env.CACHALOTE_TOKEN || (() => {
  try { return fs.readFileSync(path.join(BASE, "home", "app.token"), "utf8").trim(); } catch { return ""; }
})();

const method = process.argv[2] || "status";
const params = process.argv[3] ? JSON.parse(process.argv[3]) : {};

if (!token) { console.error("sem token (define CACHALOTE_TOKEN ou " + BASE + "/home/app.token)"); process.exit(2); }

const sock = net.connect(PORT, "127.0.0.1");
let buf = "";
const fail = (m) => { console.error(m); process.exit(1); };
const timer = setTimeout(() => fail("timeout"), 45_000);

sock.on("connect", () => sock.write(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "cachalote.auth", params: { token, client: "tool" } }) + "\n"));
sock.on("data", (chunk) => {
  buf += chunk.toString();
  let nl;
  while ((nl = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, nl); buf = buf.slice(nl + 1);
    if (!line.trim()) continue;
    let msg; try { msg = JSON.parse(line); } catch { continue; }
    if (msg.id === 1) {
      if (msg.error) fail("auth: " + msg.error.message);
      sock.write(JSON.stringify({ jsonrpc: "2.0", id: 2, method: "cachalote." + method, params }) + "\n");
    } else if (msg.id === 2) {
      clearTimeout(timer);
      console.log(JSON.stringify(msg.error ? { error: msg.error } : msg.result, null, 1));
      sock.destroy();
      process.exit(msg.error ? 1 : 0);
    }
  }
});
sock.on("error", (e) => fail("socket: " + e.message));
