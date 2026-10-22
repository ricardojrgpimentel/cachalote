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
//   · a **última** mensagem (do dono ou do agente) → a prévia da linha, como no WhatsApp;
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
    preview: "",
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
  let ultimaMensagem = "";
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
    if (registo.type === "session/title") {
      // O motor grava **dois**: primeiro um de recurso (o primeiro prompt cortado) e só depois o
      // que ele mesmo gerou (`source.kind === "provider"`). Ficar com o primeiro dava o título de
      // recurso na lista — era o que se via no S10+ (o cabeçalho mostrava «usa aa ferramenta…» em
      // vez de «Contagem total de ferramentas Android»). O do provedor ganha; sem ele, fica o
      // primeiro de recurso, que já é melhor do que nada.
      const t = registo.data && registo.data.title;
      const fonte = registo.data && registo.data.source && registo.data.source.kind;
      if (typeof t === "string" && t.trim() && (fonte === "provider" || !tituloDoMotor)) {
        tituloDoMotor = t.trim();
      }
    }
    if (!tituloDoPedido && (registo.type === "user/message" || registo.type === "agent/inbox/spliced")) {
      const data = registo.data || {};
      const inseridos = Array.isArray(data.inserted) ? data.inserted : [];
      const t = primeiroTexto(data.content) || inseridos.map((i) => primeiroTexto(i && i.content)).find(Boolean) || "";
      if (t) tituloDoPedido = t;
      if (t) ultimaMensagem = t;
    }
    if (registo.type === "assistant/attempt") {
      // O texto do agente vem em pedaços (`stream[].chunk.type === "text"`), tal como chegou ao
      // cliente pelo ACP. Uma tentativa sem texto (transporte falhado) não apaga a anterior.
      const pedacos = [];
      for (const s of (registo.data && registo.data.stream) || []) {
        const c = s && s.chunk;
        if (c && c.type === "text" && typeof c.text === "string") pedacos.push(c.text);
      }
      const t = pedacos.join("").replace(/\s+/g, " ").trim();
      if (t) ultimaMensagem = t;
    }
  }
  dados.title = tituloDoMotor || tituloDoPedido;
  dados.preview = ultimaMensagem.replace(/\s+/g, " ").trim().slice(0, 200);
  return dados;
}

/**
 * A pasta de uma sessão (`sessions/<slug>/<id>`), ou `null` se não existir. O `id` é validado para
 * nunca se seguir um caminho escolhido por quem chama.
 */
function pastaDaSessao(dshHome, id) {
  if (typeof id !== "string" || !/^[A-Za-z0-9._-]{6,80}$/.test(id)) {
    throw new Error("id de sessão inválido");
  }
  const raiz = path.join(dshHome, "sessions");
  let slugs;
  try {
    slugs = fs.readdirSync(raiz, { withFileTypes: true });
  } catch {
    return null;
  }
  for (const slug of slugs) {
    if (!slug.isDirectory()) continue;
    const pasta = path.join(raiz, slug.name, id);
    if (fs.existsSync(pasta)) return pasta;
  }
  return null;
}

// Limites do histórico: uma conversa antiga pode ter megabytes. O que interessa ao abrir uma
// sessão é o **fim** dela, por isso corta-se pelo princípio — e diz-se que se cortou.
const LIMITE_TEXTO_LIDO = 16 * 1024 * 1024; // texto descomprimido que se chega a analisar
const LIMITE_EVENTOS = 300; // igual ao que o `SessionStore` da app guarda
const LIMITE_BYTES_EVENTOS = 512 * 1024;
const LIMITE_POR_PAPEL = { user: 8000, assistant: 32000, thought: 6000, tool: 200 };

/** Corta o texto de um evento ao que faz sentido mostrar, marcando que se cortou. */
function cortarTexto(role, texto) {
  const limite = LIMITE_POR_PAPEL[role] ?? 8000;
  if (texto.length <= limite) return texto;
  return texto.slice(0, limite) + "…";
}

/**
 * O histórico de uma sessão, na mesma forma em que ele chegou ao cliente em direto:
 * `user`, `thought`, `assistant` e `tool` (com `toolId`, `estado` e `done`).
 *
 * É o que o ACP não dá: o `session/load` está na lista das superfícies que o `dsh-acp` recusa, e
 * sem isto abrir uma conversa antiga só dizia «sessão X retomada». O daemon é quem pode ler os
 * ficheiros, pela mesma via dos metadados (ver [textoDaSessao]).
 *
 * Nunca lança por conteúdo estranho: um registo ilegível é saltado. Um cartão de ferramenta sem
 * resultado fica `in_progress` — é a verdade do ficheiro, não um erro a esconder.
 */
function historicoSessao(dshHome, id, opts = {}) {
  const pasta = pastaDaSessao(dshHome, id);
  if (!pasta) throw new Error("não encontrei a sessão " + id);
  const zst = path.join(pasta, FICHEIRO_ZSTD);
  const plain = path.join(pasta, FICHEIRO_PLAIN);
  const arquivo = fs.existsSync(zst) ? zst : fs.existsSync(plain) ? plain : null;
  if (!arquivo) throw new Error("a sessão " + id + " não tem ficheiro de registo");

  let texto = textoDaSessao(arquivo, opts.maxFrames ?? 4096);
  if (texto.length > LIMITE_TEXTO_LIDO) texto = texto.slice(texto.length - LIMITE_TEXTO_LIDO);

  const eventos = [];
  const ferramentas = new Map();
  let titulo = "";
  let totalEventos = 0;

  // Junta pedaços do mesmo papel (o texto do agente chega em pedaços), separando blocos distintos.
  // O que o dono escreveu **nunca** se junta: cada prompt é o seu balão, mesmo que dois seguidos
  // não tenham tido resposta (turno interrompido).
  const juntar = (role, t) => {
    if (!t) return;
    const limpo = cortarTexto(role, t);
    if (!limpo) return;
    const anterior = eventos[eventos.length - 1];
    if (anterior && anterior.role === role && role !== "user" && role !== "system") {
      anterior.text = anterior.text ? anterior.text + "\n\n" + limpo : limpo;
      return;
    }
    eventos.push({ role, text: limpo });
    totalEventos += 1;
  };

  for (const linha of texto.split("\n")) {
    if (!linha.trim()) continue;
    let registo;
    try {
      registo = JSON.parse(linha);
    } catch {
      continue;
    }
    const data = registo.data || {};
    if (registo.type === "session/title") {
      // Mesma preferência dos metadados: o título do provedor ganha ao de recurso.
      const t = data.title;
      const fonte = data.source && data.source.kind;
      if (typeof t === "string" && t.trim() && (fonte === "provider" || !titulo)) titulo = t.trim();
      continue;
    }
    if (registo.type === "user/message") {
      // O motor guarda no mesmo sítio o que o dono escreveu e o que ele próprio injecta
      // (`runtime-context`, `system-prompt`). Só o primeiro é conversa.
      const kind = data.source && data.source.kind;
      if (kind && kind !== "user") continue;
      juntar("user", primeiroTexto(data.content));
      continue;
    }
    if (registo.type === "assistant/message" || registo.type === "assistant/attempt") {
      const blocos = data.message && Array.isArray(data.message.content) ? data.message.content : [];
      let textoDoAgente = "";
      for (const bloco of blocos) {
        if (!bloco) continue;
        if (bloco.type === "reasoning") juntar("thought", String(bloco.text || "").trim());
        else if (bloco.type === "text") textoDoAgente += bloco.text || "";
      }
      juntar("assistant", textoDoAgente.trim());
      // Sessões anteriores à versão 4 guardam a resposta em pedaços (`stream[].chunk`), não em
      // blocos; o `assistant/attempt` é o que existe lá (e o que o `lerSessao` já lê). Um
      // `assistant/message` **com** blocos também pode trazer o `stream` (turno interrompido) —
      // nesse caso os blocos já são o texto montado e ler o stream duplicava-o.
      if (blocos.length > 0) continue;
      let textoDoStream = "";
      for (const s of data.stream || []) {
        const c = s && s.chunk;
        if (!c) continue;
        if (c.type === "text") textoDoStream += c.text || "";
        else if (c.type === "reasoning") juntar("thought", String(c.text || "").trim());
      }
      juntar("assistant", textoDoStream.trim());
      continue;
    }
    if (registo.type === "tool/call") {
      const callId = String(data.callId || "");
      const nome = String(data.name || "").trim() || "ferramenta";
      const cartao = { role: "tool", text: cortarTexto("tool", nome), toolId: callId, estado: "in_progress", done: false };
      eventos.push(cartao);
      totalEventos += 1;
      if (callId) ferramentas.set(callId, cartao);
      continue;
    }
    if (registo.type === "tool/result") {
      const mensagem = data.message || {};
      const callId = String(mensagem.toolCallId || data.toolCallId || "");
      const cartao = ferramentas.get(callId);
      if (!cartao) continue; // a chamada ficou para trás no corte: um resultado sozinho não diz nada
      cartao.estado = mensagem.isError === true ? "failed" : "completed";
      cartao.done = true;
      const nome = data.meta && data.meta.title;
      if (typeof nome === "string" && nome.trim()) cartao.text = cortarTexto("tool", nome.trim());
      continue;
    }
  }

  let truncado = false;
  const limiteEventos = Math.max(1, opts.maxEvents ?? LIMITE_EVENTOS);
  if (eventos.length > limiteEventos) {
    eventos.splice(0, eventos.length - limiteEventos);
    truncado = true;
  }
  const limiteBytes = Math.max(4096, opts.maxBytes ?? LIMITE_BYTES_EVENTOS);
  let bytes = eventos.reduce((n, e) => n + e.text.length + 40, 0);
  while (bytes > limiteBytes && eventos.length > 20) {
    bytes -= eventos.shift().text.length + 40;
    truncado = true;
  }

  // O título também se pode tirar do primeiro pedido, como nos metadados — só quando o motor não
  // gravou nenhum (é o caso das sessões interrompidas antes de o título ser gerado).
  if (!titulo) {
    const primeiro = eventos.find((e) => e.role === "user");
    if (primeiro) titulo = primeiro.text.slice(0, 120);
  }
  return { sessionId: id, title: titulo, events: eventos, totalEvents: totalEventos, truncated: truncado };
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

/**
 * Apaga uma sessão do disco: a pasta inteira (`sessions/<slug>/<id>/`), com o log dela dentro.
 *
 * O ACP **não tem** apagar sessões -- o README do `dsh-acp` lista `deletion` entre as superfícies
 * que recusa. O que existe é `session/close`, que a desactiva e deixa o histórico. Como a lista de
 * conversas da app é lida do disco (é o daemon que a lê), apagar só pode ser isto: tirar os
 * ficheiros. O `id` é validado contra o que está lá dentro, para nunca apagar uma pasta escolhida
 * por quem chama.
 */
/**
 * Resumo dos turnos de uma sessão: passos, ferramentas e duração de cada um.
 *
 * Sai do **registo em disco** (`turn/start`, `step/start`, `tool/call`, `turn/end`), porque o ACP
 * não manda contagem de passos nem a duração do turno — era a linha de telemetria que faltava no
 * desenho («Em directo · 2/6 · 1.102/8.000 · 3s»). O que é medido fica medido: `ms` é `null` num
 * turno que ainda não acabou ou que foi cortado a meio, em vez de se inventar um número.
 */
function resumoTurnos(dshHome, id, opts = {}) {
  const pasta = pastaDaSessao(dshHome, id);
  if (!pasta) throw new Error("não encontrei a sessão " + id);
  const zst = path.join(pasta, FICHEIRO_ZSTD);
  const plain = path.join(pasta, FICHEIRO_PLAIN);
  const arquivo = fs.existsSync(zst) ? zst : fs.existsSync(plain) ? plain : null;
  if (!arquivo) return { sessionId: id, turns: [] };

  let texto = textoDaSessao(arquivo, opts.maxFrames ?? 4096);
  if (texto.length > LIMITE_TEXTO_LIDO) texto = texto.slice(texto.length - LIMITE_TEXTO_LIDO);

  const porTurno = new Map();
  const obter = (n) => {
    const chave = Number.isFinite(n) ? Number(n) : 0;
    if (!porTurno.has(chave)) {
      porTurno.set(chave, { turn: chave, steps: 0, tools: 0, inicio: null, fim: null, reason: null });
    }
    return porTurno.get(chave);
  };

  for (const linha of texto.split("\n")) {
    if (!linha.trim()) continue;
    let registo;
    try {
      registo = JSON.parse(linha);
    } catch {
      continue;
    }
    const data = registo.data || {};
    switch (registo.type) {
      case "turn/start":
        obter(data.turn).inicio = registo.time;
        break;
      case "step/start":
        obter(data.turn).steps += 1;
        break;
      case "tool/call":
        obter(data.turn).tools += 1;
        break;
      case "turn/end": {
        const t = obter(data.turn);
        t.fim = registo.time;
        t.reason = data.reason && data.reason.kind ? String(data.reason.kind) : null;
        break;
      }
      default:
        break;
    }
  }

  const turns = [...porTurno.values()]
    .filter((t) => t.inicio !== null || t.steps > 0 || t.tools > 0)
    .sort((a, b) => a.turn - b.turn)
    .map((t) => ({
      turn: t.turn,
      steps: t.steps,
      tools: t.tools,
      ms: t.inicio !== null && t.fim !== null ? Math.max(0, t.fim - t.inicio) : null,
      reason: t.reason,
    }));
  return { sessionId: id, turns };
}

function apagarSessao(dshHome, id) {
  if (typeof id !== "string" || !/^[A-Za-z0-9._-]{6,80}$/.test(id)) {
    throw new Error("id de sessão inválido");
  }
  // A cache do projecto guarda um `<id>.json` por sessão (título, datas, contagens). Se ficasse,
  // uma conversa «apagada» continuava a ter um ficheiro com os metadados dela em disco — e foi
  // por essa cache que a lista chegou a mostrar conversas já apagadas. Sai sempre.
  const cache = path.join(dshHome, "storages", "session_projcache", "sessions", id + ".json");
  const tinhaCache = fs.existsSync(cache);
  const limparCache = () => { if (tinhaCache) fs.rmSync(cache, { force: true }); };

  const raiz = path.join(dshHome, "sessions");
  let slugs;
  try {
    slugs = fs.readdirSync(raiz, { withFileTypes: true });
  } catch {
    if (tinhaCache) { limparCache(); return { apagada: id, soCache: true }; }
    throw new Error("não há sessões para apagar");
  }
  for (const slug of slugs) {
    if (!slug.isDirectory()) continue;
    const pasta = path.join(raiz, slug.name, id);
    if (!fs.existsSync(pasta)) continue;
    // Só se apaga o que parece mesmo uma sessão: nunca uma pasta com outra coisa lá dentro.
    const conteudo = fs.readdirSync(pasta);
    if (!conteudo.some((f) => f.startsWith("session.v4.jsonl"))) continue;
    fs.rmSync(pasta, { recursive: true, force: true });
    limparCache();
    return { apagada: id, pasta };
  }
  // Ficou só a cache (uma sessão apagada por outra via, ou antes desta correcção): limpa-se.
  if (tinhaCache) { limparCache(); return { apagada: id, soCache: true }; }
  throw new Error("não encontrei a sessão " + id);
}

module.exports = {
  sessionMeta,
  lerSessao,
  historicoSessao,
  pastaDaSessao,
  textoDaSessao,
  scanZstdFrames,
  apagarSessao,
  resumoTurnos,
};
