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
const inject = ["tools", "attachments"];

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
      const st = await hostCall("status", {});
      // Estado do ecrã virtual: evita ter de o perguntar à parte.
      const vs = await hostCall("vscreen_status", {}).catch(() => null);
      if (vs) st.virtualScreen = { running: vs.running, displayId: vs.displayId, width: vs.width, height: vs.height };
      return asText(st);
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
      "Take a screenshot on the phone and SEE it: the PNG is attached to the conversation, so you " +
      "get the actual pixels (canvas, game, image, layout whose text tree is not enough). Also " +
      "returns the file path and size. Requires the accessibility service (Android 11+).",
    parameters: {
      maxWidth: {
        type: "integer",
        description: "Downscale the capture to this width before attaching (default 720; use 1080+ to read small text).",
      },
      frames: {
        type: "integer",
        description: "How many captures to take (default 1, max 12). Use 3-8 with intervalMs to see motion/animation.",
      },
      intervalMs: {
        type: "integer",
        description: "Milliseconds between captures when frames > 1 (default 400, max 5000).",
      },
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: true,
        properties: {
          path: { type: "string", required: true },
          bytes: { type: "integer" },
          width: { type: "integer" },
          height: { type: "integer" },
          mediaType: { type: "string" },
          attached: { type: "boolean" },
          // Referência opaca do harness (o objecto devolvido por `attachments.saveImage`).
          // `type: "json"` é o nó "só-anotação" do subconjunto de schema do kernel: um `{}`
          // sem `type` é recusado pelo compilador e derrubava o plugin INTEIRO (ficávamos
          // sem nenhuma ferramenta android_*). Não usar `object` sem `additionalProperties`.
          attachmentRef: { type: "json" },
          // Vários fotogramas (visão em vídeo): um anexo por captura.
          attachmentRefs: { type: "array", items: { type: "json" } },
          frames: { type: "integer" },
          capturedAt: { type: "array", items: { type: "string" } },
          hint: { type: "string" },
        },
      },
      render: (_args, value) => {
        const n = Array.isArray(value.attachmentRefs) ? value.attachmentRefs.length : 0;
        const parts = [{
          type: "text",
          text: (n > 1
            ? `sequência de ${n} capturas ${value.width ?? "?"}×${value.height ?? "?"} ` +
              `(${Math.round((value.bytes ?? 0) / 1024)} KB cada, intervalo ${value.intervalMs ?? "?"} ms)` +
              `, primeira em ${value.path}`
            : `captura ${value.width ?? "?"}×${value.height ?? "?"}, ` +
              `${Math.round((value.bytes ?? 0) / 1024)} KB, guardada em ${value.path}`) +
            (value.attached ? " (imagens em anexo, por ordem)" : "") +
            (value.hint ? ` — ${value.hint}` : ""),
        }];
        for (const ref of value.attachmentRefs ?? []) parts.push({ type: "image", attachment: ref });
        if (!value.attachmentRefs && value.attachmentRef) parts.push({ type: "image", attachment: value.attachmentRef });
        return parts;
      },
      presentationMeta: () => ({ title: "Screenshot (ver o ecrã)" }),
    },
    async execute(args) {
      const maxWidth = Number.isFinite(args.maxWidth) ? args.maxWidth : 720;
      const frames = Math.max(1, Math.min(Number.isFinite(args.frames) ? args.frames : 1, 12));
      const intervalMs = Math.max(0, Math.min(Number.isFinite(args.intervalMs) ? args.intervalMs : 400, 5000));
      const attachments = ctx.get("attachments");
      const refs = [];
      const capturedAt = [];
      const value = {
        path: null,
        bytes: 0,
        width: 0,
        height: 0,
        mediaType: "image/png",
        frames: 0,
        intervalMs: frames > 1 ? intervalMs : 0,
        attached: false,
        attachmentRef: null,
        attachmentRefs: [],
        capturedAt,
      };
      for (let i = 0; i < frames; i++) {
        if (i > 0 && intervalMs > 0) await new Promise((r) => setTimeout(r, intervalMs));
        const raw = await hostCall("screenshot", { maxWidth });
        if (i === 0) {
          value.path = raw.path;
          value.width = raw.width;
          value.height = raw.height;
          value.mediaType = raw.mediaType || "image/png";
        }
        value.bytes = raw.bytes ?? value.bytes;
        capturedAt.push(new Date().toISOString());
        // A app devolve o PNG em base64; transformamo-lo num anexo do harness para o modelo ver.
        // O `render` é síncrono, logo os anexos têm de ser criados aqui.
        if (attachments === void 0) {
          value.hint = "não há serviço de anexos nesta composição: as imagens não chegam ao modelo";
        } else if (typeof raw.data !== "string" || raw.data.length === 0) {
          value.hint = "a app não devolveu os pixels";
        } else {
          try {
            refs.push(await attachments.saveImage({
              data: Buffer.from(raw.data, "base64"),
              mediaType: raw.mediaType || "image/png",
            }));
          } catch (error) {
            value.hint = "falhou o anexo da imagem: " + (error?.message ?? String(error));
          }
        }
      }
      value.attachmentRefs = refs;
      value.attachmentRef = refs[0] ?? null;
      value.frames = refs.length;
      value.attached = refs.length > 0;
      return value;
    },
  }));

  /**
   * Ecrã virtual: uma segunda tela onde o agente trabalha sem mexer no ecrã do dono.
   * As acções de *ver* e *gerir* vão pelas capacidades da app; as de *pôr apps lá* e de *tocar*
   * são comandos de root (`am start --display`, `input -d`) que o agente corre com o `bash`.
   */
  ctx.tools.register(defineTool({
    name: "android_vscreen",
    description:
      "Virtual screen (a second, invisible display) where you can work WITHOUT taking over the " +
      "phone the user is holding. Actions: " +
      "'start' (params width/height/density) creates it and returns displayId; " +
      "'frame' returns a PNG of it (attached to the conversation, like android_see) — use " +
      "maxWidth for a smaller image; 'status' reports it; 'stop' releases it. " +
      "To put an app on it, run with bash: `am start --display <displayId> -n <pkg>/<activity>`. " +
      "To interact, run with bash: `input -d <displayId> tap X Y` (also swipe/text/keyevent). " +
      "The virtual screen needs NO accessibility service: the pixels come from the display and " +
      "the input from `input -d`. Its coordinates are its own (width×height), not the phone's.",
    parameters: {
      action: {
        type: "string",
        required: true,
        enum: ["start", "status", "frame", "stop"],
        description: "What to do with the virtual screen.",
      },
      width: { type: "integer", description: "start: width in pixels (default 720)." },
      height: { type: "integer", description: "start: height in pixels (default 1280)." },
      density: { type: "integer", description: "start: density in dpi (default 320)." },
      maxWidth: { type: "integer", description: "frame: downscale the capture to this width (default 720)." },
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: true,
        properties: {
          action: { type: "string" },
          running: { type: "boolean" },
          displayId: { type: "integer" },
          width: { type: "integer" },
          height: { type: "integer" },
          frames: { type: "integer" },
          path: { type: "string" },
          bytes: { type: "integer" },
          mediaType: { type: "string" },
          attached: { type: "boolean" },
          attachmentRef: { type: "json" },
          hint: { type: "string" },
        },
      },
      render: (_args, value) => {
        const parts = [{
          type: "text",
          text: value.action === "frame"
            ? `ecrã virtual ${value.displayId} — fotograma ${value.width}×${value.height}` +
              (value.attached ? " (imagem em anexo)" : "") + (value.hint ? ` — ${value.hint}` : "")
            : `ecrã virtual ${value.displayId >= 0 ? value.displayId : "(nenhum)"} · ` +
              `a correr: ${value.running === true} · ${value.width}×${value.height} @${value.density} dpi · ` +
              `${value.frames ?? 0} fotogramas` + (value.hint ? ` — ${value.hint}` : ""),
        }];
        if (value.attachmentRef) parts.push({ type: "image", attachment: value.attachmentRef });
        return parts;
      },
      presentationMeta: () => ({ title: "Ecrã virtual" }),
    },
    async execute(args) {
      const acao = String(args.action || "").toLowerCase();
      if (acao === "start") {
        const r = await hostCall("vscreen_start", {
          width: args.width ?? 720,
          height: args.height ?? 1280,
          density: args.density ?? 320,
        });
        return { action: acao, ...r, hint: "lança apps com: bash → am start --display <displayId> -n <pkg>/<activity>" };
      }
      if (acao === "stop") {
        const r = await hostCall("vscreen_stop", {});
        return { action: acao, running: false, displayId: -1, ...r };
      }
      if (acao === "status") {
        return { action: acao, ...(await hostCall("vscreen_status", {})) };
      }
      if (acao === "frame") {
        const raw = await hostCall("vscreen_frame", { maxWidth: args.maxWidth ?? 720 });
        const value = {
          action: acao,
          displayId: raw.displayId,
          width: raw.width,
          height: raw.height,
          bytes: raw.bytes,
          mediaType: raw.mediaType || "image/png",
          attached: false,
          attachmentRef: null,
        };
        const attachments = ctx.get("attachments");
        if (attachments === void 0) {
          value.hint = "não há serviço de anexos nesta composição: a imagem não chega ao modelo";
        } else if (typeof raw.data !== "string" || raw.data.length === 0) {
          value.hint = "a app não devolveu os pixels";
        } else {
          try {
            value.attachmentRef = await attachments.saveImage({
              data: Buffer.from(raw.data, "base64"),
              mediaType: value.mediaType,
            });
            value.attached = true;
          } catch (error) {
            value.hint = "falhou o anexo da imagem: " + (error?.message ?? String(error));
          }
        }
        return value;
      }
      throw new Error(`acção desconhecida: ${acao} (usa start, status, frame ou stop)`);
    },
  }));

}

export { apply, inject, name };
