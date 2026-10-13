/**
 * @deepseek-ai/dsh-tool-cachalote — ferramentas Android para o Cachalote.
 *
 * Arquitectura: este plugin corre DENTRO do motor (root), mas quem tem as capacidades
 * Android é a app (notificações, acessibilidade, ecrã). O plugin fala com o daemon por
 * TCP em 127.0.0.1 e o daemon faz a ponte para a app:
 *
 *   ferramenta (aqui) → daemon (`cachalote.host.call`) → app (`host.call`) → resposta
 *
 * O token do canal está em `$DSH_HOME/app.token` (0600, root) — o motor corre como root
 * e consegue lê-lo.
 *
 * @module @deepseek-ai/dsh-tool-cachalote
 */
import net from "node:net";
import fs from "node:fs";
import path from "node:path";
import { defineTool } from "@deepseek-ai/dsh-tools";

const name = "tool-cachalote";
const inject = ["tools"];

const PORT = Number(process.env.CACHALOTE_PORT || 39731);
const TOKEN_PATH = process.env.CACHALOTE_TOKEN_FILE ||
  path.join(process.env.DSH_HOME || path.join(process.env.HOME || ".", ".dsh"), "app.token");

/** Uma chamada ao daemon: liga, autentica como "tool", pede, devolve o resultado. */
function hostCall(capability, params = {}, timeoutMs = 30_000) {
  return new Promise((resolve, reject) => {
    let token;
    try {
      token = fs.readFileSync(TOKEN_PATH, "utf8").trim();
    } catch (error) {
      reject(new Error(`não consegui ler o token do daemon em ${TOKEN_PATH}: ${error.message}`));
      return;
    }
    if (!token) {
      reject(new Error(`token vazio em ${TOKEN_PATH}`));
      return;
    }

    const socket = net.connect(PORT, "127.0.0.1");
    let buffer = "";
    let settled = false;
    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { socket.destroy(); } catch {}
      if (error) reject(error); else resolve(value);
    };
    const timer = setTimeout(
      () => finish(new Error(`o daemon/app não respondeu em ${timeoutMs} ms (${capability})`)),
      timeoutMs + 5_000,
    );

    socket.setNoDelay(true);
    socket.on("connect", () => {
      socket.write(JSON.stringify({
        jsonrpc: "2.0", id: 1, method: "cachalote.auth",
        params: { token, client: "tool" },
      }) + "\n");
    });
    socket.on("data", (chunk) => {
      buffer += chunk.toString();
      let nl;
      while ((nl = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, nl);
        buffer = buffer.slice(nl + 1);
        if (!line.trim()) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.id === 1) {
          if (msg.error) { finish(new Error("autenticação no daemon falhou: " + msg.error.message)); return; }
          socket.write(JSON.stringify({
            jsonrpc: "2.0", id: 2, method: "cachalote.host.call",
            params: { capability, params, timeoutMs },
          }) + "\n");
        } else if (msg.id === 2) {
          if (msg.error) finish(new Error(msg.error.message));
          else finish(null, msg.result ?? {});
        }
      }
    });
    socket.on("error", (error) => finish(error));
    socket.on("close", () => finish(new Error("ligação ao daemon fechada antes da resposta")));
  });
}

/** Resultado textual padrão das ferramentas deste plugin. */
const textOutput = (description) => ({
  schema: {
    type: "object",
    additionalProperties: false,
    properties: { text: { type: "string", required: true } },
  },
  render: (_args, value) => [{ type: "text", text: value.text }],
  presentationMeta: () => ({ title: description }),
});

const asText = (value) => ({ text: typeof value === "string" ? value : JSON.stringify(value, null, 1) });

function apply(ctx) {
  ctx.tools.register(defineTool({
    name: "android_status",
    description:
      "Report what Android capabilities the Cachalote app currently has: whether the accessibility " +
      "service is enabled (needed for android_screen/android_tap/android_type), whether the app is " +
      "connected to the daemon, and which capabilities the user has allowed. Call this first when an " +
      "Android action fails.",
    parameters: {},
    output: textOutput("Android capabilities"),
    async execute() {
      return asText(await hostCall("status", {}));
    },
  }));

  ctx.tools.register(defineTool({
    name: "android_notify",
    description:
      "Post a notification on the phone (Android notification shade). Use it to tell the user that a " +
      "long task finished or that something needs their attention. Only needs the notification " +
      "permission — no accessibility and no root.",
    parameters: {
      title: { type: "string", required: true, description: "Notification title, short." },
      text: { type: "string", required: true, description: "Notification body, one or two sentences." },
    },
    output: textOutput("Notification posted"),
    async execute(args) {
      return asText(await hostCall("notify", { title: args.title, text: args.text }));
    },
  }));

  ctx.tools.register(defineTool({
    name: "android_screen",
    description:
      "Read the current screen as a structured list of UI elements (text, content description, " +
      "bounds, whether it is clickable or editable) plus the foreground app. This is how you 'look' " +
      "at the phone without a screenshot. Requires the accessibility service to be enabled.",
    parameters: {
      filter: {
        type: "string",
        description: "Optional case-insensitive substring: only elements whose text or description contains it.",
      },
      max: { type: "number", description: "Maximum elements to return (default 60)." },
    },
    output: textOutput("Screen contents"),
    async execute(args) {
      return asText(await hostCall("screen", { filter: args.filter, max: args.max ?? 60 }));
    },
  }));

  ctx.tools.register(defineTool({
    name: "android_tap",
    description:
      "Tap on the phone screen: either by visible text, by content description, or at absolute " +
      "coordinates. Prefer text/description (survives layout changes). Requires the accessibility service.",
    parameters: {
      text: { type: "string", description: "Tap the element whose visible text matches this exactly (fallback: contains)." },
      description: { type: "string", description: "Tap the element with this content description." },
      x: { type: "number", description: "Absolute X coordinate (use with y when there is no text)." },
      y: { type: "number", description: "Absolute Y coordinate." },
    },
    output: textOutput("Tap performed"),
    async execute(args) {
      return asText(await hostCall("tap", { text: args.text, description: args.description, x: args.x, y: args.y }));
    },
  }));

  ctx.tools.register(defineTool({
    name: "android_type",
    description:
      "Type text into the focused input field on the phone. Requires the accessibility service.",
    parameters: {
      text: { type: "string", required: true, description: "The text to type." },
      submit: { type: "boolean", description: "Also press the enter/submit key afterwards." },
    },
    output: textOutput("Text typed"),
    async execute(args) {
      return asText(await hostCall("type", { text: args.text, submit: args.submit === true }));
    },
  }));

  ctx.tools.register(defineTool({
    name: "android_key",
    description:
      "Press a system key on the phone: back, home, recents, enter, or a notification-shade pull. " +
      "Requires the accessibility service.",
    parameters: {
      key: {
        type: "string",
        required: true,
        enum: ["back", "home", "recents", "enter", "notifications"],
        description: "Which system key/action to perform.",
      },
    },
    output: textOutput("Key pressed"),
    async execute(args) {
      return asText(await hostCall("key", { key: args.key }));
    },
  }));

  ctx.tools.register(defineTool({
    name: "android_see",
    description:
      "Take a screenshot on the phone and save it as a PNG (returns the path and size). Use it when " +
      "you need the pixels — e.g. a canvas, a game, or a layout whose text tree is not enough. " +
      "Requires the accessibility service (Android 11+).",
    parameters: {},
    output: textOutput("Screenshot taken"),
    async execute() {
      return asText(await hostCall("screenshot", {}));
    },
  }));
}

export { apply, inject, name };
