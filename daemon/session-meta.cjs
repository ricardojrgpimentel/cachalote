"use strict";
// session-meta.cjs — o título e as datas de cada sessão, lidos do que o motor grava em disco.
//
// Porque existe: o `session/list` do ACP devolve **só** `sessionId` e `cwd` (confirmado no S10+ em
// 2026-10-15, com os 76 registos do telefone). Sem título nem data, a lista de conversas da app
// — que segue o desenho do Stitch — era um muro de hexadecimais por ordem arbitrária.
//
// O motor grava cada sessão em
//   $DSH_HOME/sessions/<slug-do-cwd>/<id>/session.v4.jsonl.zstd
// como **frames zstd concatenados** (um por lote durável). É por isso que o `zstdDecompressSync`
// do ficheiro inteiro devolvia só o primeiro frame: 174 bytes de um ficheiro de 17 KB. Aqui há um
// leitor de frames — o mesmo truque do kernel (`dsh-session-persistence-jsonl`).
//
// O que se tira de lá:
//   · `session/title`            → o título que **o motor** gerou (é o melhor dos dois);
//   · `user/message`             → o primeiro prompt, quando ainda não há título;
//   · o `time` do último registo → quando a conversa mexeu pela última vez (ordenação);
//   · o cabeçalho `session`      → `createdAt` e `cwd`.
//
// Quem chama: `cachalote.sessions.meta` no bridge.cjs, que é o que a app pede ao desenhar a lista.

const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");

const ZSTD_MAGIC = 4247762216;
const FICHEIRO_ZSTD = "session.v4.jsonl.zstd";
const FICHEIRO_PLAIN = "session.v4.jsonl";

/**
 * Onde começam e acabam os frames zstd de um ficheiro (sem descomprimir os blocos). Devolve só os
 * frames **completos**: um frame a meio (o motor pode ser morto a escrever) fica de fora, que é o
 * que se quer para ler metadados.
 */
function scanZstdFrames(buffer, maxFrames = Infinity) {
  const frames = [];
  let offset = 0;
  while (offset < buffer.length && frames.length < maxFrames) {
    const start = offset;
    if (buffer.length - offset < 4) break;
    if (buffer.readUInt32LE(offset) !== ZSTD_MAGIC) break;
    offset += 4;
    if (offset === buffer.length) break;
    const descriptor = buffer.readUInt8(offset);
    offset += 1;
    if ((descriptor & 24) !== 0) break; // bit reservado: não é um frame válido
    const contentSizeFlag = descriptor >>> 6;
    const singleSegment = (descriptor & 32) !== 0;
    const checksum = (descriptor & 4) !== 0;
    const dictionaryFlag = descriptor & 3;
    const dictionaryBytes = dictionaryFlag === 3 ? 4 : dictionaryFlag;
    const contentSizeBytes = contentSizeFlag === 0 ? (singleSegment ? 1 : 0) : 1 << contentSizeFlag;
    const restoDoCabecalho = (singleSegment ? 0 : 1) + dictionaryBytes + contentSizeBytes;
    if (buffer.length - offset < restoDoCabecalho) break;
    offset += restoDoCabecalho;
    let completo = true;
    for (;;) {
      if (buffer.length - offset < 3) {
        completo = false;
        break;
      }
      const cabecalhoBloco = buffer.readUIntLE(offset, 3);
      offset += 3;
      const ultimoBloco = (cabecalhoBloco & 1) !== 0;
      const tipoBloco = (cabecalhoBloco >>> 1) & 3;
      const tamanho = cabecalhoBloco >>> 3;
      const bytes = tipoBloco === 1 ? 1 : tamanho;
      if (buffer.length - offset < bytes) {
        completo = false;
        break;
      }
      offset += bytes;
      if (ultimoBloco) break;
    }
    if (!completo) break;
    if (checksum) {
      if (buffer.length - offset < 4) break;
      offset += 4;
    }
    frames.push({ start, end: offset });
  }
  return frames;
}

/** O texto (JSONL) de um ficheiro de sessão, juntando os frames por ordem. */
function textoDaSessao(arquivo, maxFrames = 512) {
  const cru = fs.readFileSync(arquivo);
  if (!arquivo.endsWith(".zstd")) return cru.toString("utf8");
  const partes = [];
  for (const frame of scanZstdFrames(cru, maxFrames)) {
    try {
      partes.push(zlib.zstdDecompressSync(cru.subarray(frame.start, frame.end)));
    } catch {
      break; // frame ilegível: fica-se com o que já se leu
    }
  }
  return Buffer.concat(partes).toString("utf8");
}

/** O primeiro texto de um bloco de conteúdo (`[{type:"text",text:"…"}]`). */
function primeiroTexto(content) {
  if (!Array.isArray(content)) return "";
  for (const bloco of content) {
    if (bloco && bloco.type === "text" && typeof bloco.text === "string" && bloco.text.trim()) {
      return bloco.text.trim();
    }
  }
  return "";
}

/**
 * Os metadados de uma sessão. Nunca lança por conteúdo estranho: um registo ilegível é saltado — o
 * que interessa é não deixar a lista de conversas sem nada por causa de uma sessão corrompida.
 */
function lerSessao(dir, id, opts = {}) {
  const zst = path.join(dir, FICHEIRO_ZSTD);
  const plain = path.join(dir, FICHEIRO_PLAIN);
  const arquivo = fs.existsSync(zst) ? zst : fs.existsSync(plain) ? plain : null;
  if (!arquivo) return null;
  const stat = fs.statSync(arquivo);
  const dados = {
    sessionId: id,
    title: "",
    cwd: "",
    createdAt: "",
    updatedAt: new Date(stat.mtimeMs).toISOString(),
    bytes: stat.size,
  };
  let texto;
  try {
    texto = textoDaSessao(arquivo, opts.maxFrames ?? 512);
  } catch {
    return dados; // fica só a data do ficheiro
  }
  // O título do motor (`session/title`) vem **depois** do primeiro prompt no ficheiro, por isso não
  // se pode ficar com o primeiro que aparece: guardam-se os dois e o do motor ganha no fim.
  let tituloDoMotor = "";
  let tituloDoPedido = "";
  for (const linha of texto.split("\n")) {
    if (!linha.trim()) continue;
    let registo;
    try {
      registo = JSON.parse(linha);
    } catch {
      continue;
    }
    if (typeof registo.time === "number" && registo.time > 0) {
      dados.updatedAt = new Date(registo.time).toISOString();
    }
    if (registo.type === "session") {
      if (typeof registo.createdAt === "number" && registo.createdAt > 0) {
        dados.createdAt = new Date(registo.createdAt).toISOString();
      }
      if (typeof registo.id === "string" && registo.id) dados.sessionId = registo.id;
      if (typeof registo.cwd === "string" && registo.cwd) dados.cwd = registo.cwd;
    }
    if (registo.type === "session/title" && !tituloDoMotor) {
      const t = registo.data && registo.data.title;
      if (typeof t === "string" && t.trim()) tituloDoMotor = t.trim();
    }
    if (!tituloDoPedido && (registo.type === "user/message" || registo.type === "agent/inbox/spliced")) {
      const data = registo.data || {};
      const inseridos = Array.isArray(data.inserted) ? data.inserted : [];
      const t = primeiroTexto(data.content) || inseridos.map((i) => primeiroTexto(i && i.content)).find(Boolean) || "";
      if (t) tituloDoPedido = t;
    }
  }
  dados.title = tituloDoMotor || tituloDoPedido;
  return dados;
}

/**
 * Todas as sessões de um `$DSH_HOME`, da mais recente para a mais antiga. É o que a lista de
 * conversas da app mostra: o motor não o dá, mas está todo em disco e o daemon é quem pode lê-lo.
 */
function sessionMeta(dshHome, opts = {}) {
  const raiz = path.join(dshHome, "sessions");
  const saida = [];
  let slugs;
  try {
    slugs = fs.readdirSync(raiz, { withFileTypes: true });
  } catch {
    return { sessions: [] };
  }
  for (const slug of slugs) {
    if (!slug.isDirectory()) continue;
    const pasta = path.join(raiz, slug.name);
    let ids;
    try {
      ids = fs.readdirSync(pasta, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entrada of ids) {
      if (!entrada.isDirectory()) continue;
      try {
        const meta = lerSessao(path.join(pasta, entrada.name), entrada.name, opts);
        if (meta) saida.push(meta);
      } catch {
        // sessão ilegível não pode derrubar a lista inteira
      }
    }
  }
  saida.sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
  return { sessions: saida };
}

module.exports = { sessionMeta, lerSessao, textoDaSessao, scanZstdFrames };
