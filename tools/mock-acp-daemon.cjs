#!/usr/bin/env node
/*
 * mock-acp-daemon.cjs — daemon ACP falso para os ensaios E2E, sem modelo, chave ou rede.
 *
 * O objectivo é percorrer a app com respostas **guionadas**: o mesmo turno, as mesmas ferramentas,
 * o mesmo pedido de autorização e o mesmo erro, sempre. Não tenta imitar o motor DSH por dentro;
 * imita o contrato que a app usa (JSON-RPC por linha, autenticação do daemon e os métodos
 * `cachalote.*` que a lista, o resumo e o histórico pedem).
 *
 *   node tools/mock-acp-daemon.cjs --port 39732 --token mock-token
 *
 * No telefone/emulador a app liga-se a 127.0.0.1:<port>; usa-se `adb reverse tcp:<port> tcp:<port>`
 * para chegar ao processo do computador. O token é o que estiver no `shared_prefs` da app.
 *
 * Cenários (pelo texto do prompt):
 *   · «aprova»  — o servidor pede autorização e espera pela resposta da app;
 *   · «erro»    — o turno devolve um erro JSON-RPC;
 *   · resto     — duas bolhas de texto, um cartão de ferramenta e telemetria.
 */
"use strict";

const net = require("node:net");

const args = process.argv.slice(2);
const opt = (nome, omissao) => {
  const i = args.indexOf("--" + nome);
  return i >= 0 && args[i + 1] ? args[i + 1] : omissao;
};
const PORT = Number(opt("port", "39732"));
const TOKEN = opt("token", "mock-token");
const VERBOSE = args.includes("--verbose");

let seq = 0;
const sessions = new Map();

function log(...a) {
  if (VERBOSE) console.log(new Date().toISOString(), ...a);
}

function novaSessao() {
  const id = "mock-" + ++seq;
  const s = {
    id,
    title: "Conversa de teste",
    preview: "",
    updatedAt: new Date().toISOString(),
    events: [],
    turns: [],
  };
  sessions.set(id, s);
  return s;
}

function configOptions() {
  return [
    {
      id: "model",
      name: "Model",
      currentValue: "mock-model",
      options: [{ value: "mock-model", name: "Mock Model" }],
    },
    {
      id: "reasoning_effort",
      name: "Reasoning effort",
      currentValue: "low",
      options: [
        { value: "low", name: "Low" },
        { value: "high", name: "High" },
      ],
    },
  ];
}

function sessaoMeta(s) {
  return { sessionId: s.id, title: s.title, updatedAt: s.updatedAt, preview: s.preview };
}

function enviar(socket, obj) {
  if (!socket.destroyed) socket.write(JSON.stringify(obj) + "\n");
}

function responder(socket, id, result) {
  enviar(socket, { jsonrpc: "2.0", id, result });
}

function erro(socket, id, message) {
  enviar(socket, { jsonrpc: "2.0", id, error: { code: -32603, message } });
}

function nota(sessionId, update) {
  return { jsonrpc: "2.0", method: "session/update", params: { sessionId, update } };
}

/** Pede autorização à app e espera pela resposta (`id` do pedido é nosso). */
function pedirAutorizacao(socket, sessionId, pedidoId) {
  return new Promise((resolve) => {
    const pedido = {
      jsonrpc: "2.0",
      id: pedidoId,
      method: "session/request_permission",
      params: {
        sessionId,
        toolCall: {
          toolCallId: "tool-" + pedidoId,
          title: 'touch /sdcard/Download/mock.txt',
          rawInput: {
            command: 'touch /sdcard/Download/mock.txt',
            paths: ["/sdcard/Download/mock.txt"],
          },
        },
        options: [
          { optionId: "allow_once", kind: "allow_once", name: "Allow once" },
          { optionId: "reject_once", kind: "reject_once", name: "Reject" },
        ],
      },
    };
    const chave = String(pedidoId);
    pendentes.set(chave, resolve);
    log("pedido de autorização", chave);
    enviar(socket, pedido);
  });
}

const pendentes = new Map();

async function responderPrompt(socket, id, params) {
  const sessionId = String(params?.sessionId || "");
  const texto = (params?.prompt || [])
    .filter((b) => b && b.type === "text")
    .map((b) => String(b.text || ""))
    .join(" ")
    .trim();
  const s = sessions.get(sessionId) || novaSessao();
  s.updatedAt = new Date().toISOString();
  if (s.events.length === 0 && texto) s.title = texto.replace(/\s+/g, " ").slice(0, 48);

  if (texto.toLowerCase().includes("erro")) {
    erro(socket, id, "erro simulado pelo mock");
    return;
  }

  enviar(socket, nota(sessionId, { sessionUpdate: "usage_update", used: 1234, size: 200000 }));
  enviar(socket, nota(sessionId, { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "a pensar no mock" } }));
  enviar(socket, nota(sessionId, { sessionUpdate: "tool_call", toolCallId: "tool-1", title: "bash", status: "in_progress" }));

  let resposta;
  if (texto.toLowerCase().includes("aprova")) {
    const resultado = await pedirAutorizacao(socket, sessionId, 900);
    log("autorização resolvida", JSON.stringify(resultado));
    const escolha = resultado?.outcome?.optionId || "";
    resposta = escolha.startsWith("reject")
      ? "Pedido recusado pelo mock."
      : "Pedido autorizado pelo mock.";
  } else {
    resposta = "Olá do mock. Isto não veio de um modelo.";
  }

  log("a enviar a resposta do turno:", resposta);
  enviar(socket, nota(sessionId, { sessionUpdate: "tool_call_update", toolCallId: "tool-1", status: "completed" }));
  for (const parte of [resposta.slice(0, 12), resposta.slice(12)]) {
    if (parte) {
      enviar(socket, nota(sessionId, { sessionUpdate: "agent_message_chunk", content: { type: "text", text: parte } }));
    }
  }

  s.events.push({ role: "user", text: texto });
  s.events.push({ role: "tool", text: "bash", toolId: "tool-1", estado: "completed", done: true });
  s.events.push({ role: "assistant", text: resposta });
  s.preview = resposta;
  s.turns = [{ turn: 1, steps: 1, tools: 1, ms: 1200, reason: "completed" }];
  responder(socket, id, { stopReason: "end_turn" });
}

function tratar(socket, msg) {
  // Resposta da app a um pedido nosso (autorização).
  if (msg.id !== undefined && msg.method === undefined) {
    const chave = String(msg.id);
    const resolver = pendentes.get(chave);
    log("resposta da app ao pedido", chave, JSON.stringify(msg.result || msg.error || {}));
    if (resolver) {
      pendentes.delete(chave);
      resolver(msg.result || msg.error || {});
    }
    return;
  }
  const id = msg.id;
  const method = String(msg.method || "");
  const params = msg.params || {};
  log("método", method, "id", id);

  if (method === "cachalote.auth") {
    if (String(params.token || "") !== TOKEN) {
      enviar(socket, { jsonrpc: "2.0", id, error: { code: -32001, message: "token inválido" } });
      socket.destroy();
      return;
    }
    responder(socket, id, { ok: true, version: "mock", role: "app" });
    return;
  }

  if (method === "initialize") {
    responder(socket, id, {
      protocolVersion: 1,
      agentInfo: { name: "mock-acp", version: "1.0" },
      authMethods: [],
      promptCapabilities: { image: true },
    });
    return;
  }

  if (method === "cachalote.status") {
    responder(socket, id, {
      daemon: true,
      engine: true,
      profile: "acp",
      base: "/mock/cachalote",
      port: PORT,
      sessions: sessions.size,
      version: "mock",
    });
    return;
  }

  if (method === "cachalote.credentials.status") {
    responder(socket, id, { present: true, name: "DEEPSEEK_API_KEY", length: 35, updatedAt: new Date().toISOString() });
    return;
  }

  if (method === "cachalote.sessions.meta") {
    responder(socket, id, { sessions: [...sessions.values()].map(sessaoMeta) });
    return;
  }

  if (method === "cachalote.sessions.turns") {
    const s = sessions.get(String(params.sessionId || ""));
    responder(socket, id, { turns: s?.turns || [] });
    return;
  }

  if (method === "cachalote.sessions.history") {
    const s = sessions.get(String(params.sessionId || ""));
    responder(socket, id, { events: s?.events || [], totalEvents: s?.events.length || 0 });
    return;
  }

  if (method === "cachalote.sessions.delete") {
    sessions.delete(String(params.sessionId || ""));
    responder(socket, id, { apagada: params.sessionId });
    return;
  }

  if (method === "cachalote.doctor") {
    responder(socket, id, {
      problemas: 0,
      avisos: 0,
      base: "/mock",
      secoes: [{ titulo: "MOCK", itens: [{ texto: "mock a responder", estado: "ok" }] }],
    });
    return;
  }

  if (method === "cachalote.engine.restart") {
    responder(socket, id, { ok: true });
    return;
  }

  if (method === "cachalote.credentials.set" || method === "cachalote.credentials.remove") {
    responder(socket, id, { present: method.endsWith(".set"), name: params.name || "DEEPSEEK_API_KEY", length: 35 });
    return;
  }

  if (method === "session/list") {
    responder(socket, id, {
      sessions: [...sessions.values()].map((s) => ({ sessionId: s.id, cwd: params.cwd || "/mock", title: s.title, updatedAt: s.updatedAt })),
    });
    return;
  }

  if (method === "session/new") {
    const s = novaSessao();
    responder(socket, id, { sessionId: s.id, configOptions: configOptions() });
    return;
  }

  if (method === "session/resume") {
    const s = sessions.get(String(params.sessionId || "")) || novaSessao();
    responder(socket, id, { sessionId: s.id, configOptions: configOptions() });
    return;
  }

  if (method === "session/set_config_option") {
    responder(socket, id, { configOptions: configOptions() });
    return;
  }

  if (method === "session/close") {
    responder(socket, id, { ok: true });
    return;
  }

  if (method === "session/prompt") {
    responderPrompt(socket, id, params).catch((e) => erro(socket, id, String(e?.message || e)));
    return;
  }

  enviar(socket, { jsonrpc: "2.0", id, error: { code: -32601, message: "método desconhecido no mock: " + method } });
}

const server = net.createServer((socket) => {
  let buffer = "";
  socket.setNoDelay(true);
  log("ligação de", socket.remoteAddress, socket.remotePort);
  socket.on("data", (chunk) => {
    buffer += chunk.toString("utf8");
    let nl;
    while ((nl = buffer.indexOf("\n")) >= 0) {
      const linha = buffer.slice(0, nl).trim();
      buffer = buffer.slice(nl + 1);
      if (!linha) continue;
      let msg;
      try {
        msg = JSON.parse(linha);
      } catch {
        continue;
      }
      try {
        tratar(socket, msg);
      } catch (e) {
        erro(socket, msg.id, String(e?.message || e));
      }
    }
  });
  socket.on("error", () => {});
});

server.listen(PORT, "127.0.0.1", () => {
  console.log(`mock ACP à escuta em 127.0.0.1:${PORT} (token ${TOKEN})`);
});

process.on("SIGTERM", () => server.close(() => process.exit(0)));
process.on("SIGINT", () => server.close(() => process.exit(0)));
