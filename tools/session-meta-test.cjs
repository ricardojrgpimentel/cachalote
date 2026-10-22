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
const { lerSessao, historicoSessao, sessionMeta, scanZstdFrames } = require("../daemon/session-meta.cjs");

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
/** O cabeçalho de uma sessão com outro id (o motor escreve um por ficheiro). */
const cabecalhoRepetido = (id) => frame({
  type: "session",
  version: 4,
  id,
  createdAt: 1791977431778,
  cwd: "/data/adb/cachalote/home/ws",
});
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

// 6) o histórico: a conversa de uma sessão antiga, na ordem em que aconteceu — e sem o que o motor
//    injecta (runtime-context/system-prompt) nem duplicados do `agent/inbox/spliced`.
const dir3 = path.join(slug, "cccc3333-4444-5555-6666-777777777777");
fs.mkdirSync(dir3, { recursive: true });
const conversa = Buffer.concat([
  cabecalho,
  frame({
    type: "user/message",
    seq: 3,
    time: 1791977605010,
    data: { content: [{ type: "text", text: "vê o estado do telefone" }], source: { kind: "user" } },
  }),
  frame({
    type: "user/message",
    seq: 4,
    time: 1791977605011,
    data: { content: [{ type: "text", text: "contexto de execução que ninguém escreveu" }], source: { kind: "runtime-context" } },
  }),
  frame({
    type: "assistant/message",
    seq: 5,
    time: 1791977606000,
    data: {
      message: {
        role: "assistant",
        content: [
          { type: "reasoning", text: "vou chamar a ferramenta" },
          { type: "text", text: "Deixa ver." },
          { type: "tool-call", id: "call_1", name: "android_status", arguments: "{}" },
        ],
      },
    },
  }),
  frame({ type: "tool/call", seq: 6, time: 1791977606010, data: { callId: "call_1", name: "android_status", arguments: "{}" } }),
  frame({
    type: "tool/result",
    seq: 7,
    time: 1791977606020,
    data: {
      message: { role: "tool", toolCallId: "call_1", content: [{ type: "text", text: "{}" }], isError: false },
      meta: { title: "Android capabilities" },
    },
  }),
  frame({
    type: "assistant/attempt",
    seq: 8,
    time: 1791977609000,
    data: { turn: 1, step: 2, stream: [{ type: "chunk", chunk: { type: "text", text: "está tudo bem" } }] },
  }),
  frame({
    type: "tool/call",
    seq: 9,
    time: 1791977609010,
    data: { callId: "call_2", name: "android_tap", arguments: "{}" },
  }),
  // O motor grava o título de recurso e só depois o do provedor: o segundo é o que se quer ver.
  frame({ type: "session/title", seq: 10, time: 1791977609020, data: { title: "versão de recurso", source: { kind: "fallback" } } }),
  frame({ type: "session/title", seq: 11, time: 1791977609030, data: { title: "o título do motor", source: { kind: "provider" } } }),
]);
fs.writeFileSync(path.join(dir3, "session.v4.jsonl.zstd"), conversa);
const hist = historicoSessao(raiz, "cccc3333-4444-5555-6666-777777777777");
console.log("== histórico ==");
ok("papéis na ordem certa", hist.events.map((e) => e.role).join(",") === "user,thought,assistant,tool,assistant,tool",
  hist.events.map((e) => e.role).join(","));
ok("o pedido do dono", hist.events[0].text === "vê o estado do telefone", hist.events[0].text);
ok("o runtime-context não entra", !hist.events.some((e) => e.text.includes("ninguém escreveu")));
ok("o pensamento está lá", hist.events[1].text === "vou chamar a ferramenta", hist.events[1].text);
ok("a resposta do agente (blocos)", hist.events[2].text === "Deixa ver.", hist.events[2].text);
ok("o cartão da ferramenta usa o título do resultado", hist.events[3].text === "Android capabilities", hist.events[3].text);
ok("a ferramenta ficou concluída", hist.events[3].done === true && hist.events[3].estado === "completed",
  hist.events[3].estado + "/" + hist.events[3].done);
ok("a ferramenta sem resultado fica a correr", hist.events[5].done === false && hist.events[5].estado === "in_progress",
  hist.events[5].estado + "/" + hist.events[5].done);
ok("o stream antigo (assistant/attempt) entra", hist.events[4].text === "está tudo bem", hist.events[4].text);
ok("o título do provedor ganha ao de recurso", hist.title === "o título do motor", hist.title);
ok("e os metadados também preferem o do provedor", lerSessao(dir3, "cccc3333-4444-5555-6666-777777777777").title === "o título do motor");
const curto = historicoSessao(raiz, "cccc3333-4444-5555-6666-777777777777", { maxEvents: 2 });
ok("corta pelo princípio", curto.events.length === 2 && curto.events[0].role === "assistant", curto.events.map((e) => e.role).join(","));
ok("diz que truncou", curto.truncated === true && curto.totalEvents === 6, JSON.stringify({ t: curto.truncated, n: curto.totalEvents }));
const semSessao = (() => { try { historicoSessao(raiz, "nao-existe-esta"); return null; } catch (e) { return e.message; } })();
ok("sessão inexistente dá erro claro", typeof semSessao === "string" && semSessao.includes("não encontrei"), String(semSessao));

// 7) dois prompts seguidos (turno interrompido sem resposta) ficam em balões separados
const dir4 = path.join(slug, "dddd4444-5555-6666-7777-888888888888");
fs.mkdirSync(dir4, { recursive: true });
fs.writeFileSync(path.join(dir4, "session.v4.jsonl.zstd"), Buffer.concat([
  cabecalho,
  frame({ type: "user/message", seq: 1, time: 1791977605001, data: { content: [{ type: "text", text: "primeiro" }], source: { kind: "user" } } }),
  frame({ type: "user/message", seq: 2, time: 1791977605002, data: { content: [{ type: "text", text: "segundo" }], source: { kind: "user" } } }),
]));
const dois = historicoSessao(raiz, "dddd4444-5555-6666-7777-888888888888");
ok("dois prompts seguidos não se juntam", dois.events.length === 2 && dois.events[1].text === "segundo",
  dois.events.map((e) => e.text).join(" | "));

// 8) turno interrompido: o `assistant/message` traz os blocos montados **e** o `stream` cru; ler os
//    dois duplicava o texto.
const dir5 = path.join(slug, "eeee5555-6666-7777-8888-999999999999");
fs.mkdirSync(dir5, { recursive: true });
fs.writeFileSync(path.join(dir5, "session.v4.jsonl.zstd"), Buffer.concat([
  cabecalho,
  frame({
    type: "assistant/message",
    seq: 5,
    time: 1791977607000,
    data: {
      message: { role: "assistant", content: [{ type: "text", text: "resposta a meio" }] },
      interrupted: true,
      stream: [{ type: "chunk", chunk: { type: "text", text: "resposta a meio" } }],
    },
  }),
]));
const interrompido = historicoSessao(raiz, "eeee5555-6666-7777-8888-999999999999");
ok("o stream de um message com blocos não duplica", interrompido.events.length === 1 && interrompido.events[0].text === "resposta a meio",
  JSON.stringify(interrompido.events));

// --- turnos: passos, ferramentas e duração, lidos do registo (a telemetria que o ACP não manda).
// O registo de mentira tem de ter a mesma forma do motor: `turn/start`, `step/start`, `turn/end`.
const { resumoTurnos } = require("../daemon/session-meta.cjs");
const idTurnos = "abcd1234-0000-1111-2222-333344445555";
const dirTurnos = path.join(slug, idTurnos);
fs.mkdirSync(dirTurnos, { recursive: true });
const t0 = 1792000000000;
fs.writeFileSync(path.join(dirTurnos, "session.v4.jsonl.zstd"), Buffer.concat([
  cabecalhoRepetido(idTurnos),
  frame({ type: "turn/start", seq: 2, time: t0, data: { turn: 1 } }),
  frame({ type: "step/start", seq: 3, time: t0 + 100, data: { turn: 1, step: 1 } }),
  frame({ type: "tool/call", seq: 4, time: t0 + 200, data: { turn: 1, toolCallId: "c1", toolName: "bash" } }),
  frame({ type: "step/start", seq: 5, time: t0 + 900, data: { turn: 1, step: 2 } }),
  frame({ type: "turn/end", seq: 6, time: t0 + 3454, data: { turn: 1, reason: { kind: "completed" } } }),
  frame({ type: "turn/start", seq: 7, time: t0 + 5000, data: { turn: 2 } }),
  frame({ type: "step/start", seq: 8, time: t0 + 5100, data: { turn: 2, step: 1 } }),
]));

const rTurnos = resumoTurnos(raiz, idTurnos);
ok("lê os turnos do registo", rTurnos.turns.length === 2, JSON.stringify(rTurnos.turns));
ok("conta os passos e as ferramentas do turno",
  rTurnos.turns[0].steps === 2 && rTurnos.turns[0].tools === 1, JSON.stringify(rTurnos.turns[0]));
ok("a duração é o intervalo entre turn/start e turn/end",
  rTurnos.turns[0].ms === 3454 && rTurnos.turns[0].reason === "completed", JSON.stringify(rTurnos.turns[0]));
ok("um turno aberto não tem duração", rTurnos.turns[1].ms === null, JSON.stringify(rTurnos.turns[1]));

// --- apagar: a pasta da sessão **e** a entrada na cache do projecto (o que fazia uma conversa
// apagada continuar a ter um .json com o título dela em disco).
const { apagarSessao } = require("../daemon/session-meta.cjs");
const idApagar = "ffff9999-8888-7777-6666-555555555555";
const dirApagar = path.join(slug, idApagar);
const cacheApagar = path.join(raiz, "storages", "session_projcache", "sessions", idApagar + ".json");
fs.mkdirSync(dirApagar, { recursive: true });
fs.writeFileSync(path.join(dirApagar, "session.v4.jsonl.zstd"), cabecalho);
fs.mkdirSync(path.dirname(cacheApagar), { recursive: true });
fs.writeFileSync(cacheApagar, JSON.stringify({ id: idApagar, title: "conversa de teste" }));

const r = apagarSessao(raiz, idApagar);
ok("apagar tira a pasta da sessão", !fs.existsSync(dirApagar) && r.apagada === idApagar, JSON.stringify(r));
ok("apagar tira a entrada da cache do projecto", !fs.existsSync(cacheApagar));

// Uma cache órfã (sessão já apagada antes desta correcção) também tem de sair — sem erro.
fs.writeFileSync(cacheApagar, JSON.stringify({ id: idApagar, title: "órfã" }));
const r2 = apagarSessao(raiz, idApagar);
ok("apagar limpa a cache órfã sem pasta", r2.soCache === true && !fs.existsSync(cacheApagar), JSON.stringify(r2));

let rebentou = false;
try { apagarSessao(raiz, "11112222-3333-4444-5555-666677778888"); } catch { rebentou = true; }
ok("apagar uma sessão que não existe dá erro", rebentou);

fs.rmSync(raiz, { recursive: true, force: true });
console.log("");
if (falhas === 0) {
  console.log("metadados das sessões: tudo bem");
  process.exit(0);
}
console.log("metadados das sessões: " + falhas + " problema(s)");
process.exit(1);
