#!/usr/bin/env node
/*
 * bridge.cjs — corre NO TELEMÓVEL. É o coração do daemon:
 *
 *   motor DSH (stdio NDJSON)  ←→  este processo  ←→  { AF_UNIX 0600, TCP 127.0.0.1+token }  ←→  app
 *
 * Porque uma ponte em vez de apontar o motor directamente ao socket:
 *   · o app pode desligar/reconectar sem matar o motor (o motor é caro: 10 k+ ficheiros);
 *   · o daemon supervisiona o motor e reinicia-o se ele morrer;
 *   · serve os dois sentidos: o app fala ACP e também as operações do próprio daemon
 *     (`cachalote.*`: estado, credenciais, restart) — o motor nunca vê essas chamadas;
 *   · o stderr do motor fica num log.
 *
 * Dois transportes, porque a app Android NÃO consegue usar o socket em /data/adb:
 *   · AF_UNIX  $BASE/run/cachalote.sock  (0600, root) — para o CLI/adb e para testes;
 *   · TCP     127.0.0.1:$CACHALOTE_PORT (por omissão 39731) — para o app, autenticado
 *     por token de 128 bits lido de $BASE/home/app.token (0600, root). O SELinux rotula
 *     /data/adb como `adb_data_file` e não há regra que deixe `untrusted_app` lá chegar,
 *     por isso o loopback com token é o caminho viável (ver D11 do plano).
 *
 * Protocolo: JSON-RPC 2.0, um objecto por linha ('\n'), igual ao do motor.
 * Num socket TCP, o PRIMEIRO frame tem de ser
 *   {"jsonrpc":"2.0","id":0,"method":"cachalote.auth","params":{"token":"…"}}
 * antes de qualquer outro método.
 */
const net = require("node:net");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { spawn } = require("node:child_process");

const BASE = process.env.CACHALOTE_BASE || "/data/adb/cachalote";
const SOCK = process.env.CACHALOTE_SOCK || path.join(BASE, "run", "cachalote.sock");
// O porto pode vir do ambiente ou de $BASE/home/port (assim o daemon root e o daemon shell
// coexistem no mesmo telefone, cada um no seu porto). Ver base de conhecimento 75.
const PORT = (() => {
  const fromEnv = Number(process.env.CACHALOTE_PORT || 0);
  if (fromEnv) return fromEnv;
  try {
    const fromFile = Number(fs.readFileSync(path.join(BASE, "home", "port"), "utf8").trim());
    if (fromFile) return fromFile;
  } catch {}
  return 39731;
})();
const PROFILE = process.env.CACHALOTE_PROFILE || "acp";
const LOGDIR = path.join(BASE, "logs");
const ENGINE_LOG = path.join(LOGDIR, "engine.log");
const DAEMON_LOG = path.join(LOGDIR, "daemon.log");
const ENV_FILE = path.join(BASE, "home", ".env");
const TOKEN_FILE = path.join(BASE, "home", "app.token");
const BINJS = path.join(BASE, "kernel", "dsh", "node_modules", "@deepseek-ai", "dsh", "lib", "bin.js");
const NODE = path.join(BASE, "runtime", "bin", "node");
const MAX_LOG = 8 * 1024 * 1024;
const VERSION = "0.2.0-f2";

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

/* ---------------- token do app ---------------- */
function loadToken() {
  try {
    const t = fs.readFileSync(TOKEN_FILE, "utf8").trim();
    if (t.length >= 32) return t;
  } catch {}
  const t = crypto.randomBytes(16).toString("hex");
  fs.mkdirSync(path.dirname(TOKEN_FILE), { recursive: true });
  fs.writeFileSync(TOKEN_FILE, t + "\n", { mode: 0o600 });
  try { fs.chmodSync(TOKEN_FILE, 0o600); } catch {}
  log("token do app criado em " + TOKEN_FILE);
  return t;
}
let TOKEN = loadToken();

/* ---------------- credenciais ---------------- */
function readEnv() {
  try { return fs.readFileSync(ENV_FILE, "utf8"); } catch { return ""; }
}
function credentialState(name) {
  const line = readEnv().split("\n").find((l) => l.startsWith(name + "="));
  let updatedAt = null;
  try { updatedAt = fs.statSync(ENV_FILE).mtime.toISOString(); } catch {}
  return { name, present: !!line && line.length > name.length + 1, length: line ? line.length - name.length - 1 : 0, updatedAt, file: ENV_FILE };
}
function setCredential(name, value) {
  if (!/^[A-Z][A-Z0-9_]*$/.test(name)) throw new Error("nome de variável inválido");
  if (typeof value !== "string" || value.trim().length < 8) throw new Error("valor demasiado curto");
  if (/[\r\n]/.test(value)) throw new Error("o valor não pode ter quebras de linha");
  const kept = readEnv().split("\n").filter((l) => l.trim() && !l.startsWith(name + "="));
  kept.push(`${name}=${value.trim()}`);
  fs.mkdirSync(path.dirname(ENV_FILE), { recursive: true });
  const tmp = ENV_FILE + ".tmp";
  fs.writeFileSync(tmp, kept.join("\n") + "\n", { mode: 0o600 });
  fs.chmodSync(tmp, 0o600);
  fs.renameSync(tmp, ENV_FILE);   // rename: o SELinux do Android nega link()
  try { fs.chmodSync(ENV_FILE, 0o600); } catch {}
  log(`credencial ${name} gravada (${value.trim().length} caracteres) em ${ENV_FILE}`);
  return credentialState(name);
}

/* ---------------- motor ---------------- */
let engine = null;
let stopping = false;
let restarts = 0;
let clients = new Set();
let startedAt = Date.now();

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
    // O plugin do kernel fala com o daemon por TCP: tem de saber QUAL (o daemon root e o do
    // Shizuku coexistem, em portas diferentes). Sem isto, o plugin do daemon shell ligava-se
    // ao daemon root e as capacidades falhavam com "token inválido".
    CACHALOTE_PORT: String(PORT),
    CACHALOTE_TOKEN_FILE: path.join(BASE, "home", "app.token"),
  };
  fs.mkdirSync(env.TMPDIR, { recursive: true });
  // Áreas permitidas do bash (guarda de caminhos). Ficheiro editável pela app:
  // uma ou mais linhas; convertidas no separador que o patch espera (`:`).
  try {
    const rootsFile = path.join(BASE, "home", "allowed-roots");
    const roots = fs.readFileSync(rootsFile, "utf8").trim();
    if (roots) env.CACHALOTE_ALLOWED_ROOTS = roots.split(/\s+/).filter(Boolean).join(":");
  } catch {}

  log(`a arrancar motor: perfil=${PROFILE} node=${NODE}`);
  engine = spawn(NODE, ["--expose-internals", BINJS, "--profile", PROFILE], {
    env, cwd: path.join(BASE, "home"), stdio: ["pipe", "pipe", "pipe"],
  });
  // O motor também não deve ser a primeira vítima do LMK: é caro de reconstruir (10 k+ ficheiros).
  try {
    fs.writeFileSync(`/proc/${engine.pid}/oom_score_adj`, "-500");
  } catch {}
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

/**
 * Arranca o motor **uma única vez**, assim que o daemon esteja a atender em ALGUM transporte.
 *
 * Estava dentro do callback do socket unix, e isso fazia com que uma falha do socket unix
 * deixasse o daemon de pé mas **sem motor**: em /data/local/tmp o SELinux nega a criação do
 * socket ao domínio `shell` (EACCES), o log dizia "sigo só com TCP" — e o motor nunca
 * arrancava, porque o único sítio que o arrancava era o callback que não corria. O pior dos
 * dois mundos: o daemon parecia saudável e não tinha motor nenhum.
 */
let engineBooted = false;
function bootEngine() {
  if (engineBooted) return;
  engineBooted = true;
  startEngine();
}

/** Reinicia o motor (com atraso curto, para a resposta ao cliente sair primeiro). */
function scheduleEngineRestart(why) {
  log(`a reiniciar o motor: ${why}`);
  setTimeout(() => { try { engine?.kill("SIGTERM"); } catch {} }, 700);
}

/* ---------------- canal reverso: o motor pede, a APP executa ----------------
 * Fluxo: plugin do kernel → `cachalote.host.call` (TCP, cliente "tool") → daemon →
 * pedido `host.call` (id string "host-N") → app → resposta → daemon → plugin.
 * Os ids string distinguem-se dos ids numéricos do ACP: não há colisões possíveis.
 */
let appSocket = null;          // ligação autenticada com client:"app"
let hostSeq = 0;
const hostPending = new Map();

function hostCall(capability, params, timeoutMs = 30_000) {
  const sock = appSocket;
  if (!sock || sock.destroyed) return Promise.reject(new Error("a app não está ligada ao daemon"));
  const id = "host-" + (++hostSeq);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      hostPending.delete(id);
      reject(new Error(`a app não respondeu a ${capability} em ${timeoutMs} ms`));
    }, timeoutMs);
    hostPending.set(id, { resolve, reject, timer });
    try {
      sock.write(JSON.stringify({ jsonrpc: "2.0", id, method: "host.call", params: { capability, params } }) + "\n");
    } catch (e) {
      clearTimeout(timer);
      hostPending.delete(id);
      reject(e);
    }
  });
}

function settleHost(msg) {
  const slot = hostPending.get(msg.id);
  if (!slot) return false;
  hostPending.delete(msg.id);
  clearTimeout(slot.timer);
  if (msg.error) slot.reject(new Error(msg.error.message || JSON.stringify(msg.error)));
  else slot.resolve(msg.result ?? {});
  return true;
}

/* ---------------- operações do daemon (não vão ao motor) ---------------- */
function control(method, params) {
  switch (method) {
    case "cachalote.status":
      return {
        daemon: true, version: VERSION, pid: process.pid, profile: PROFILE,
        engine: !!engine, engineRestarts: restarts,
        // `appConnected` responde à pergunta que o `doctor` e a app fazem: a app está ligada
        // agora? (sem isto só se via pelo log da ponte).
        appConnected: !!appSocket && !appSocket.destroyed,
        uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
        socket: SOCK, port: PORT, base: BASE,
      };
    case "cachalote.credentials.status":
      return credentialState(params?.name || "DEEPSEEK_API_KEY");
    case "cachalote.credentials.set": {
      const state = setCredential(params?.name || "DEEPSEEK_API_KEY", params?.value);
      // O motor lê as credenciais no ARRANQUE: gravar o .env com ele a correr não
      // chega (verificado — o turno seguinte ainda dizia "no API key"). Reinicia-se.
      scheduleEngineRestart("credencial alterada");
      return { ...state, engineRestart: "agendado" };
    }
    case "cachalote.credentials.remove": {
      const name = params?.name || "DEEPSEEK_API_KEY";
      const kept = readEnv().split("\n").filter((l) => l.trim() && !l.startsWith(name + "="));
      fs.writeFileSync(ENV_FILE, kept.join("\n") + (kept.length ? "\n" : ""), { mode: 0o600 });
      scheduleEngineRestart("credencial removida");
      return { ...credentialState(name), engineRestart: "agendado" };
    }
    case "cachalote.credentials.effective":
      // Confirma que o motor tem mesmo a chave: pergunta ao serviço de credenciais
      // através de um turno trivial seria caro; aqui só dizemos o que está no ficheiro.
      return credentialState(params?.name || "DEEPSEEK_API_KEY");
    case "cachalote.host.call":
      return hostCall(params?.capability, params?.params ?? {}, params?.timeoutMs ?? 30_000);
    case "cachalote.host.status":
      return { appConnected: !!appSocket && !appSocket.destroyed, pending: hostPending.size };
    case "cachalote.engine.restart":
      scheduleEngineRestart("pedido explícito");
      return { ok: true, note: "motor a reiniciar" };
    default:
      return undefined;
  }
}

/* ---------------- ligações ---------------- */
function attach(socket, { requireAuth }) {
  let authed = !requireAuth;
  let registered = false;
  let buffer = "";

  const reply = (id, result, error) =>
    socket.write(JSON.stringify(error ? { jsonrpc: "2.0", id, error } : { jsonrpc: "2.0", id, result }) + "\n");

  socket.on("data", (chunk) => {
    buffer += chunk.toString();
    let nl;
    while ((nl = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, nl + 1);
      buffer = buffer.slice(nl + 1);
      if (!line.trim()) continue;

      let msg = null;
      try { msg = JSON.parse(line); } catch {
        reply(null, null, { code: -32700, message: "cachalote: frame não é JSON" });
        continue;
      }

      // resposta da app a um pedido host.* (id string)
      if (typeof msg.id === "string" && msg.id.startsWith("host-") && (msg.result !== undefined || msg.error !== undefined)) {
        if (!settleHost(msg)) log("resposta host sem pedido pendente: " + msg.id);
        continue;
      }

      // autenticação (só TCP)
      if (msg.method === "cachalote.auth") {
        const okToken = typeof msg.params?.token === "string" && msg.params.token.length === TOKEN.length &&
          crypto.timingSafeEqual(Buffer.from(msg.params.token), Buffer.from(TOKEN));
        if (okToken) {
          authed = true;
          const role = msg.params?.client === "tool" ? "tool" : "app";
          if (role === "app") appSocket = socket;
          log(`cliente "${role}" autenticado no TCP`);
          reply(msg.id, { ok: true, version: VERSION, role });
        }
        else { log("token inválido — a fechar ligação"); reply(msg.id, null, { code: -32001, message: "token inválido" }); try { socket.destroy(); } catch {} }
        continue;
      }
      if (!authed) {
        reply(msg.id, null, { code: -32001, message: "cachalote: falta autenticação (cachalote.auth)" });
        try { socket.destroy(); } catch {}
        continue;
      }

      // operações do daemon
      if (typeof msg.method === "string" && msg.method.startsWith("cachalote.")) {
        try {
          const result = control(msg.method, msg.params);
          if (result === undefined) reply(msg.id, null, { code: -32601, message: "método de daemon desconhecido: " + msg.method });
          else if (typeof result?.then === "function") {
            result.then((v) => reply(msg.id, v ?? {})).catch((e) => reply(msg.id, null, { code: -32603, message: String(e?.message || e) }));
          } else reply(msg.id, result);
        } catch (e) {
          reply(msg.id, null, { code: -32603, message: String(e?.message || e) });
        }
        continue;
      }

      // tudo o resto é ACP: passa ao motor (um cliente ACP de cada vez)
      if (!registered) {
        for (const c of clients) { if (c !== socket) { log("a substituir o cliente ACP anterior"); try { c.destroy(); } catch {} } }
        clients.clear();
        clients.add(socket);
        registered = true;
        log("cliente ACP ligado (" + (requireAuth ? "tcp" : "unix") + ")");
      }
      if (engine?.stdin?.writable) engine.stdin.write(line);
      else reply(msg.id ?? null, null, { code: -32000, message: "cachalote: o motor não está a correr" });
    }
  });

  const bye = () => {
    if (registered) { clients.delete(socket); log("cliente ACP desligado"); }
    if (appSocket === socket) { appSocket = null; log("app desligada do daemon"); }
  };
  socket.on("close", bye);
  socket.on("error", (e) => { log("erro de socket: " + e.message); bye(); });
}

const unixServer = net.createServer((s) => attach(s, { requireAuth: false }));
// O socket unix é um extra (ferramentas locais em root). Em /data/local/tmp o SELinux nega a
// criação do socket ao domínio `shell` (EACCES) — e a app liga-se sempre por TCP, portanto
// uma falha aqui não pode derrubar o daemon. Ver base de conhecimento 75.
unixServer.on("error", (e) => {
  log("aviso: socket unix indisponível (" + e.message + ") — sigo só com TCP");
  unixReady = false;
  bootEngine();
});
try { fs.unlinkSync(SOCK); } catch {}
let unixReady = true;
unixServer.listen(SOCK, () => {
  try { fs.chmodSync(SOCK, 0o600); } catch {}
  log(`AF_UNIX à escuta em ${SOCK} (0600), pid ${process.pid}`);
  bootEngine();
});

const tcpServer = net.createServer((s) => attach(s, { requireAuth: true }));
tcpServer.on("error", (e) => log("ERRO no servidor tcp: " + e.message));
tcpServer.on("error", (e) => log("ERRO no servidor tcp: " + e.message + " (porta " + PORT + " já ocupada?)"));
tcpServer.listen(PORT, "127.0.0.1", () => {
  log(`TCP à escuta em 127.0.0.1:${PORT} (token obrigatório)`);
  bootEngine();
});

function shutdown(why) {
  if (stopping) return;
  stopping = true;
  log("a terminar: " + why);
  try { unixServer.close(); } catch {}
  try { tcpServer.close(); } catch {}
  try { engine?.kill("SIGTERM"); } catch {}
  setTimeout(() => { try { engine?.kill("SIGKILL"); } catch {} process.exit(0); }, 3000);
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
process.on("uncaughtException", (e) => log("excepção não tratada: " + (e?.stack || e)));
