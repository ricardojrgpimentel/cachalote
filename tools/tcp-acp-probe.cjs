#!/usr/bin/env node
/*
 * tcp-acp-probe.cjs — handshake ACP por TCP contra o daemon, sem app nenhuma.
 *
 *   CACHALOTE_PORT=39733 node tcp-acp-probe.cjs
 *
 * Serve para responder a uma pergunta só: o motor está a atender? O `client-smoke.cjs` fala
 * pelo socket AF_UNIX, que em /data/local/tmp o SELinux recusa ao domínio `shell` — mas a app
 * liga-se sempre por TCP, e é esse caminho que aqui se exercita (auth por token + initialize).
 */
const net = require("node:net");
const fs = require("node:fs");

const BASE = process.env.CACHALOTE_BASE || "/data/adb/cachalote";
const PORT = Number(process.env.CACHALOTE_PORT || 39731);
const token = process.env.CACHALOTE_TOKEN ||
  (() => { try { return fs.readFileSync(BASE + "/home/app.token", "utf8").trim(); } catch { return ""; } })();

if (!token) { console.error("sem token em " + BASE + "/home/app.token"); process.exit(2); }

const sock = net.connect(PORT, "127.0.0.1");
let buf = "";
const send = (o) => sock.write(JSON.stringify(o) + "\n");

sock.on("connect", () => send({ jsonrpc: "2.0", id: 1, method: "cachalote.auth", params: { token, client: "tool" } }));
sock.on("data", (chunk) => {
  buf += chunk.toString();
  let nl;
  while ((nl = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, nl); buf = buf.slice(nl + 1);
    if (!line.trim()) continue;
    let m;
    try { m = JSON.parse(line); } catch { continue; }
    if (m.id === 1) {
      if (m.error) { console.log("auth FALHOU: " + JSON.stringify(m.error)); process.exit(1); }
      console.log("auth OK (porto " + PORT + ")");
      send({ jsonrpc: "2.0", id: 2, method: "initialize", params: { protocolVersion: 1, clientCapabilities: {}, clientInfo: { name: "probe", version: "1" } } });
    } else if (m.id === 2) {
      if (m.error) { console.log("initialize FALHOU: " + JSON.stringify(m.error)); process.exit(1); }
      const ai = m.result?.agentInfo ?? {};
      console.log("initialize OK: " + ai.name + " " + ai.version +
        " | imagem=" + (m.result?.agentCapabilities?.promptCapabilities?.image === true));
      process.exit(0);
    }
  }
});
sock.on("error", (e) => { console.log("erro de ligação: " + e.message); process.exit(1); });
setTimeout(() => { console.log("timeout à espera do motor"); process.exit(1); }, 60000);
