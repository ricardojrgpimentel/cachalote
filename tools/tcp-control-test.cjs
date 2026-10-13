#!/usr/bin/env node
/*
 * tcp-control-test.cjs — exercita o canal de controlo do daemon tal como a app o fará.
 * Corre NO TELEMÓVEL (ou via adb forward) e limpa o que escreveu.
 *
 *   node tcp-control-test.cjs [port] [token]
 */
const net = require("node:net");

const PORT = Number(process.argv[2] || 39731);
const TOKEN = process.argv[3] || process.env.CACHALOTE_TOKEN || "";
const DUMMY = "sk-dummy-teste-canal-app-0000";

const sock = net.connect(PORT, "127.0.0.1");
let buf = "";
let step = 0;
const falhas = [];

const send = (o) => { sock.write(JSON.stringify(o) + "\n"); console.log("→ " + JSON.stringify(o).slice(0, 140)); };
const check = (nome, cond, extra) => { console.log(`   ${cond ? "OK  " : "FALHA"} ${nome}${extra ? " — " + extra : ""}`); if (!cond) falhas.push(nome); };

sock.on("connect", () => {
  console.log("ligado a 127.0.0.1:" + PORT);
  send({ jsonrpc: "2.0", id: 1, method: "cachalote.auth", params: { token: TOKEN } });
});

sock.on("data", (b) => {
  buf += b.toString();
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i); buf = buf.slice(i + 1);
    if (!line.trim()) continue;
    let m; try { m = JSON.parse(line); } catch { console.log("← " + line.slice(0, 120)); continue; }
    console.log("← " + JSON.stringify(m.result ?? m.error ?? m).slice(0, 200));

    if (m.id === 1) {
      check("auth", !!m.result?.ok, m.error?.message);
      if (!m.result?.ok) return fim();
      send({ jsonrpc: "2.0", id: 2, method: "cachalote.status", params: {} });
    } else if (m.id === 2) {
      check("status.daemon", m.result?.daemon === true);
      check("status.engine", m.result?.engine === true, "profile=" + m.result?.profile);
      send({ jsonrpc: "2.0", id: 3, method: "cachalote.credentials.set", params: { name: "DEEPSEEK_API_KEY", value: DUMMY } });
    } else if (m.id === 3) {
      check("credentials.set", m.result?.present === true && m.result?.length === DUMMY.length, "len=" + m.result?.length);
      send({ jsonrpc: "2.0", id: 4, method: "cachalote.credentials.status", params: { name: "DEEPSEEK_API_KEY" } });
    } else if (m.id === 4) {
      check("credentials.status", m.result?.present === true);
      send({ jsonrpc: "2.0", id: 5, method: "cachalote.credentials.remove", params: { name: "DEEPSEEK_API_KEY" } });
    } else if (m.id === 5) {
      check("credentials.remove", m.result?.present === false, "limpeza feita");
      send({ jsonrpc: "2.0", id: 6, method: "cachalote.metodo.inexistente", params: {} });
    } else if (m.id === 6) {
      check("método desconhecido devolve erro", m.error?.code === -32601);
      fim();
    }
  }
});
sock.on("error", (e) => { console.log("erro: " + e.message); falhas.push("socket"); fim(); });

function fim() {
  console.log("\n== resultado: " + (falhas.length ? "FALHAS: " + falhas.join(", ") : "tudo OK") + " ==");
  try { sock.destroy(); } catch {}
  process.exit(falhas.length ? 1 : 0);
}
setTimeout(fim, 15000);
