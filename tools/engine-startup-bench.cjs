#!/usr/bin/env node
/*
 * engine-startup-bench.cjs — mede o **arranque a frio** do motor (decisão D6b).
 *
 *   CACHALOTE_BASE=/data/adb/cachalote RUNS=7 node tools/engine-startup-bench.cjs
 *
 * O que mede: do `spawn` do processo do motor até à resposta ao `initialize` do ACP. É o
 * número que o D6b quer baixar (medido no passado: 187 ms cru → 31 ms com SEA + snapshot V8,
 * 5,9×). Sem uma medida repetível não se sabe se uma optimização ganhou ou apenas mexeu.
 *
 * Porque é que isto é um instrumento e não um teste: o número depende do dispositivo e da
 * carga, e o que interessa é a **comparação** antes/depois no mesmo dispositivo. Imprime o
 * mínimo (o que menos sofre com ruído), a mediana e o máximo.
 *
 * Escreve o `initialize` mal o processo arranca — não espera por nada do motor —, portanto o
 * que se mede inclui mesmo o arranque do Node e a composição do kernel.
 */
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const BASE = process.env.CACHALOTE_BASE || "/data/adb/cachalote";
const NODE = process.env.CACHALOTE_NODE || path.join(BASE, "runtime", "bin", "node");
const BINJS = process.env.CACHALOTE_BINJS ||
  path.join(BASE, "kernel", "dsh", "node_modules", "@deepseek-ai", "dsh", "lib", "bin.js");
const RUNS = Number(process.env.RUNS || 5);
const TIMEOUT_MS = Number(process.env.TIMEOUT_MS || 60000);

for (const [what, p] of [["node", NODE], ["kernel", BINJS]]) {
  if (!fs.existsSync(p)) { console.error(`ERRO: não encontrei o ${what} em ${p}`); process.exit(2); }
}

function envForEngine() {
  return {
    ...process.env,
    DSH_HOME: path.join(BASE, "home"),
    HOME: BASE,
    TMPDIR: path.join(BASE, "tmp"),
    PATH: `${path.join(BASE, "bin")}:${path.join(BASE, "runtime", "bin")}:/system/bin:/system/xbin`,
    LD_LIBRARY_PATH: path.join(BASE, "runtime", "lib"),
    TERM: "dumb",
    NODE_OPTIONS: "",
    CACHALOTE_PORT: process.env.CACHALOTE_PORT || "39733",
  };
}

/** Um arranque a frio: devolve os milissegundos até o `initialize` responder. */
function once() {
  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    const proc = spawn(NODE, [
      ...(process.env.CACHALOTE_NODE_ARGS ? process.env.CACHALOTE_NODE_ARGS.split(/\s+/).filter(Boolean) : []),
      "--expose-internals", BINJS, "--profile", "acp",
    ], {
      env: envForEngine(),
      cwd: path.join(BASE, "home"),
      stdio: ["pipe", "pipe", "ignore"],
    });
    let buffer = "";
    let settled = false;
    const finish = (err, ms) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { proc.kill("SIGKILL"); } catch {}
      if (err) reject(err); else resolve(ms);
    };
    const timer = setTimeout(() => finish(new Error(`sem resposta em ${TIMEOUT_MS} ms`)), TIMEOUT_MS);

    proc.on("error", (e) => finish(e));
    proc.on("exit", (code) => { if (!settled) finish(new Error(`o motor saiu (code=${code}) antes de responder`)); });
    proc.stdout.on("data", (chunk) => {
      buffer += chunk.toString();
      let nl;
      while ((nl = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, nl).trim();
        buffer = buffer.slice(nl + 1);
        if (!line) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.id === 1) {
          if (msg.error) finish(new Error("initialize: " + JSON.stringify(msg.error)));
          else finish(null, Date.now() - t0);
        }
      }
    });

    const frame = JSON.stringify({
      jsonrpc: "2.0", id: 1, method: "initialize",
      params: { protocolVersion: 1, clientCapabilities: {}, clientInfo: { name: "startup-bench", version: "1" } },
    }) + "\n";
    // O pipe guarda isto até o motor o ler: escrever já inclui o arranque do Node na medida.
    proc.stdin.write(frame);
  });
}

(async () => {
  console.log(`arranque a frio — ${RUNS} corridas`);
  console.log(`  node   : ${NODE}`);
  console.log(`  kernel : ${BINJS}`);
  const times = [];
  for (let i = 1; i <= RUNS; i++) {
    const ms = await once();
    times.push(ms);
    console.log(`  ${String(i).padStart(2)}: ${ms} ms`);
  }
  const sorted = [...times].sort((a, b) => a - b);
  const median = sorted.length % 2 ? sorted[(sorted.length - 1) / 2]
    : Math.round((sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2);
  console.log(`== min ${sorted[0]} ms · mediana ${median} ms · max ${sorted[sorted.length - 1]} ms ==`);
})().catch((e) => { console.error("FALHA: " + e.message); process.exit(1); });
