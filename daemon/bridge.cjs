#!/usr/bin/env node
/*
 * bridge.cjs — corre NO TELEMÓVEL. É o coração do daemon:
 *
 *   motor DSH (stdio NDJSON)  ←→  este processo  ←→  socket AF_UNIX (0600)  ←→  app
 *
 * Porque uma ponte em vez de apontar o motor directamente ao socket:
 *   · o app pode desligar/reconectar sem matar o motor (o motor é caro: 10 k+ ficheiros);
 *   · o daemon supervisiona o motor e reinicia-o se ele morrer;
 *   · um único socket serve os dois sentidos, incluindo a futura ponte `host.*`
 *     para as capacidades Android (acessibilidade, notificações, overlay);
 *   · o stderr do motor fica num log em vez de se perder.
 *
 * Protocolo no socket: exactamente o mesmo do motor — JSON-RPC 2.0, um objecto
 * por linha ('\n'). O cliente não precisa de saber que existe uma ponte.
 */
const net = require("node:net");
const fs = require("node:fs");
const path = require("node:path");
const { spawn } = require("node:child_process");

const BASE = process.env.CACHALOTE_BASE || "/data/adb/cachalote";
const SOCK = process.env.CACHALOTE_SOCK || path.join(BASE, "run", "cachalote.sock");
const PROFILE = process.env.CACHALOTE_PROFILE || "acp";
const LOGDIR = path.join(BASE, "logs");
const ENGINE_LOG = path.join(LOGDIR, "engine.log");
const DAEMON_LOG = path.join(LOGDIR, "daemon.log");
const BINJS = path.join(BASE, "kernel", "dsh", "node_modules", "@deepseek-ai", "dsh", "lib", "bin.js");
const NODE = path.join(BASE, "runtime", "bin", "node");
const MAX_LOG = 8 * 1024 * 1024;

fs.mkdirSync(LOGDIR, { recursive: true });
fs.mkdirSync(path.dirname(SOCK), { recursive: true });

const stamp = () => new Date().toISOString();
const log = (...a) => {
  const line = `[${stamp()}] ${a.join(" ")}\n`;
  process.stdout.write(line);
  try { fs.appendFileSync(DAEMON_LOG, line); } catch {}
};
const rotate = (file) => {
  try { if (fs.statSync(file).size > MAX_LOG) fs.renameSync(file, file + ".1"); } catch {}
};

/* ---------------- motor ---------------- */
let engine = null;
let stopping = false;
let restarts = 0;
let clients = new Set();

function startEngine() {
  rotate(ENGINE_LOG);
  const env = {
    ...process.env,
    DSH_HOME: path.join(BASE, "home"),
    HOME: BASE,
    TMPDIR: path.join(BASE, "tmp"),
    PATH: `${path.join(BASE, "bin")}:${path.join(BASE, "runtime", "bin")}:/system/bin:/system/xbin`,
    LD_LIBRARY_PATH: path.join(BASE, "runtime", "lib"),
    TERM: "dumb",
    NODE_OPTIONS: "",
  };
  fs.mkdirSync(env.TMPDIR, { recursive: true });

  log(`a arrancar motor: perfil=${PROFILE} node=${NODE}`);
  engine = spawn(NODE, ["--expose-internals", BINJS, "--profile", PROFILE], {
    env,
    cwd: path.join(BASE, "home"),
    stdio: ["pipe", "pipe", "pipe"],
  });

  const engineOut = fs.createWriteStream(ENGINE_LOG, { flags: "a" });
  engine.stdout.on("data", (b) => {
    engineOut.write(b);
    for (const c of clients) { try { c.write(b); } catch {} }
  });
  engine.stderr.on("data", (b) => {
    engineOut.write(b);
    const t = b.toString().trim();
    if (t) log("[motor] " + t.split("\n").slice(0, 4).join(" | ").slice(0, 600));
  });
  engine.on("exit", (code, signal) => {
    log(`motor saiu (code=${code} signal=${signal})`);
    engine = null;
    if (stopping) return;
    restarts++;
    const delay = Math.min(30000, 1000 * Math.pow(2, Math.min(restarts, 5)));
    log(`a reiniciar o motor dentro de ${delay / 1000}s (reinício #${restarts})`);
    setTimeout(() => { if (!stopping) startEngine(); }, delay);
  });
}

/* ---------------- clientes ---------------- */
const server = net.createServer((socket) => {
  // "o último cliente ganha": o app é o único cliente previsto. Um cliente novo
  // (ex.: app reaberta) substitui o antigo em vez de ficar em fila.
  for (const c of clients) { if (c !== socket) { log("a substituir cliente antigo"); try { c.destroy(); } catch {} } }
  clients.clear();
  clients.add(socket);
  log("cliente ligado");

  let buffer = "";
  socket.on("data", (chunk) => {
    buffer += chunk.toString();
    let nl;
    while ((nl = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, nl + 1);
      buffer = buffer.slice(nl + 1);
      if (!line.trim()) continue;
      if (engine?.stdin?.writable) {
        engine.stdin.write(line);
      } else {
        // Motor em baixo: responder com um erro JSON-RPC em vez de deixar pendurado.
        let id = null;
        try { id = JSON.parse(line).id ?? null; } catch {}
        socket.write(JSON.stringify({ jsonrpc: "2.0", id, error: { code: -32000, message: "cachalote: o motor não está a correr" } }) + "\n");
      }
    }
  });
  const bye = () => { clients.delete(socket); log("cliente desligado"); };
  socket.on("close", bye);
  socket.on("error", (e) => { log("erro de socket: " + e.message); bye(); });
});

server.on("error", (e) => { log("ERRO no servidor: " + e.message); process.exit(1); });

try { fs.unlinkSync(SOCK); } catch {}
server.listen(SOCK, () => {
  try { fs.chmodSync(SOCK, 0o600); } catch {}
  log(`à escuta em ${SOCK} (modo 0600), pid ${process.pid}`);
  startEngine();
});

function shutdown(why) {
  if (stopping) return;
  stopping = true;
  log("a terminar: " + why);
  try { server.close(); } catch {}
  try { engine?.kill("SIGTERM"); } catch {}
  setTimeout(() => { try { engine?.kill("SIGKILL"); } catch {} process.exit(0); }, 3000);
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
process.on("uncaughtException", (e) => log("excepção não tratada: " + (e?.stack || e)));
