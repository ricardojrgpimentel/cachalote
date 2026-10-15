#!/usr/bin/env node
// session-meta-test.cjs — o leitor de metadados das sessões (daemon/session-meta.cjs), sem telefone.
//
//   node tools/session-meta-test.cjs
//
// Constrói uma sessão de mentira **com a mesma forma** da do motor (JSONL em frames zstd
// concatenados, com um frame a meio no fim para imitar o motor morto a escrever) e exige que o
// leitor tire de lá o título, o `createdAt`, o `updatedAt` e o `cwd` — e que não invente nada
// quando o ficheiro está truncado.
//
// Existe porque isto é o que a lista de conversas mostra: o `session/list` do ACP não traz título
// nem data (medido no S10+ em 2026-10-15), e sem este leitor a lista eram 76 linhas de hexadecimal.
"use strict";
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const zlib = require("node:zlib");
const { lerSessao, sessionMeta, scanZstdFrames } = require("../daemon/session-meta.cjs");

if (typeof zlib.zstdCompressSync !== "function") {
  console.log("zstd não existe neste Node (" + process.version + ") — teste saltado");
  process.exit(0);
}

let falhas = 0;
function ok(nome, condicao, detalhe = "") {
  if (condicao) {
    console.log("  [ok] " + nome);
  } else {
    console.log("  [!!] " + nome + (detalhe ? " — " + detalhe : ""));
    falhas += 1;
  }
}

const raiz = fs.mkdtempSync(path.join(os.tmpdir(), "cachalote-sess-"));
const slug = path.join(raiz, "sessions", "--data-adb-cachalote-home-ws--");
const dir = path.join(slug, "aaaa1111-2222-3333-4444-555555555555");
fs.mkdirSync(dir, { recursive: true });

// Um frame zstd por "lote durável" — como o motor escreve.
const frame = (obj) => zlib.zstdCompressSync(Buffer.from(JSON.stringify(obj) + "\n", "utf8"));
const cabecalho = frame({
  type: "session",
  version: 4,
  id: "aaaa1111-2222-3333-4444-555555555555",
  createdAt: 1791977431778,
  cwd: "/data/adb/cachalote/home/ws",
});
const pedido = frame({
  type: "user/message",
  seq: 8,
  time: 1791977605015,
  data: { content: [{ type: "text", text: "usa o bash para escrever o ficheiro" }] },
});
const titulo = frame({
  type: "session/title",
  seq: 12,
  time: 1791977605055,
  data: { title: "escrever o ficheiro", source: { kind: "fallback" } },
});
const resposta = frame({
  type: "assistant/attempt",
  seq: 16,
  time: 1791977609000,
  data: { turn: 1, step: 1, stream: [{ type: "chunk", chunk: { type: "text", text: "o ficheiro ficou escrito" } }] },
});
const fim = frame({ type: "turn/end", seq: 20, time: 1791983393816, data: {} });
const arquivo = path.join(dir, "session.v4.jsonl.zstd");

// 1) ficheiro completo
fs.writeFileSync(arquivo, Buffer.concat([cabecalho, pedido, titulo, resposta, fim]));
const meta = lerSessao(dir, path.basename(dir));
console.log("== sessão completa ==");
ok("título do motor", meta.title === "escrever o ficheiro", JSON.stringify(meta.title));
ok("criada em", meta.createdAt === new Date(1791977431778).toISOString(), meta.createdAt);
ok("mexida em (último registo)", meta.updatedAt === new Date(1791983393816).toISOString(), meta.updatedAt);
ok("cwd", meta.cwd === "/data/adb/cachalote/home/ws", meta.cwd);
ok("id", meta.sessionId === "aaaa1111-2222-3333-4444-555555555555");
ok("prévia: a última mensagem é do agente", meta.preview === "o ficheiro ficou escrito", meta.preview);

// 2) frame a meio no fim (motor morto a escrever): lê o que está completo e ignora o resto
const meio = Buffer.from(cabecalho.subarray(0, 9));
fs.writeFileSync(arquivo, Buffer.concat([cabecalho, pedido, titulo, resposta, fim, meio]));
const metaTruncado = lerSessao(dir, path.basename(dir));
console.log("== frame truncado no fim ==");
const frames = scanZstdFrames(fs.readFileSync(arquivo));
ok("o frame a meio não conta", frames.length === 5, "frames=" + frames.length);
ok("continua a dar o título", metaTruncado.title === "escrever o ficheiro", metaTruncado.title);
ok("continua a dar o último registo bom", metaTruncado.updatedAt === new Date(1791983393816).toISOString());

// 3) sem `session/title`: o título é o primeiro prompt; e sem resposta do agente a prévia é o pedido
fs.writeFileSync(arquivo, Buffer.concat([cabecalho, pedido, fim]));
const semTitulo = lerSessao(dir, path.basename(dir));
console.log("== sem session/title ==");
ok("título do primeiro prompt", semTitulo.title === "usa o bash para escrever o ficheiro", semTitulo.title);
ok("prévia: o pedido do dono", semTitulo.preview === "usa o bash para escrever o ficheiro", semTitulo.preview);

// 4) lista ordenada do mais recente para o mais antigo + pasta de outra sessão
const dir2 = path.join(slug, "bbbb2222-3333-3333-4444-555555555555");
fs.mkdirSync(dir2, { recursive: true });
fs.writeFileSync(path.join(dir2, "session.v4.jsonl.zstd"), Buffer.concat([cabecalho, pedido]));
const lista = sessionMeta(raiz);
console.log("== lista ==");
ok("duas sessões", lista.sessions.length === 2, "n=" + lista.sessions.length);
ok(
  "a mais recente primeiro",
  lista.sessions[0].sessionId === "aaaa1111-2222-3333-4444-555555555555",
  lista.sessions.map((s) => s.sessionId).join(","),
);

// 5) $DSH_HOME sem sessões nenhumas: lista vazia, sem rebentar
ok("sem sessões não rebenta", sessionMeta(path.join(raiz, "nao-existe")).sessions.length === 0);

fs.rmSync(raiz, { recursive: true, force: true });
console.log("");
if (falhas === 0) {
  console.log("metadados das sessões: tudo bem");
  process.exit(0);
}
console.log("metadados das sessões: " + falhas + " problema(s)");
process.exit(1);
