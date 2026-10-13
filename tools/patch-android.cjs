#!/usr/bin/env node
/*
 * patch-android.cjs — aplica os patches Android à árvore do kernel DSH já extraída.
 *
 * Usa-se depois de tools/extract-kernel.cjs. Cada patch é idempotente e diz o que fez.
 *
 *   node tools/patch-android.cjs --tree .work/f0/kernel
 *
 * Patch 1 — app-boot: o arranque exige o addon NATIVO `node-addon-require-builtin`
 *   (só há prebuilds darwin/win32/linux-x64; não existe Android) para carregar cinco
 *   internals do Node. Verificámos no telemóvel que o Node 24.18.0 com
 *   `--expose-internals` carrega os cinco por `require()` normal — logo o addon é
 *   dispensável, desde que se degrade para `require()`.
 *   Isto é o que destrava o arranque do motor no Android.
 */
const fs = require("fs");

const argv = process.argv.slice(2);
const opt = (n, d) => {
  const i = argv.indexOf(n);
  return i >= 0 ? argv[i + 1] : d;
};
const tree = opt("--tree");
if (!tree) {
  console.error("uso: patch-android.cjs --tree <dir-com-dsh/node_modules>");
  process.exit(2);
}

const path = require("node:path");
const REPO = path.resolve(__dirname, "..");
const report = [];
const HOME = tree + "/dsh/node_modules";

/* ---------- patch 1: app-boot, addon nativo -> fallback JS ---------- */
const appBoot = HOME + "/@deepseek-ai/dsh-app-boot/lib/index.js";
if (fs.existsSync(appBoot)) {
  let src = fs.readFileSync(appBoot, "utf8");
  const marker = 'createRequire(import.meta.url)("node-addon-require-builtin")';
  const patched = "__cachaloteAddonFallback";
  if (src.includes(patched)) {
    report.push("app-boot: já patcheado (nada a fazer)");
  } else if (!src.includes(marker)) {
    report.push("app-boot: AVISO — não encontrei o bloco do addon; a versão do kernel mudou?");
  } else {
    // O `const addon = ...` passa a devolver sempre um objecto com requireBuiltin(),
    // que usa o addon nativo quando existe e cai para require() quando não existe.
    // (Os pontos de chamada `addon.requireBuiltin(...)` ficam intactos.)
    src = src.replace(
      marker,
      '(() => { /* ' + patched + ' */ const __req = createRequire(import.meta.url); let __a = null; ' +
        'try { __a = __req("node-addon-require-builtin"); } catch (__e) { __a = null; } ' +
        'return { requireBuiltin: (id) => (__a ? __a.requireBuiltin(id) : __req(id)) }; })()',
    );
    fs.writeFileSync(appBoot, src);
    report.push("app-boot: addon nativo passou a opcional (fallback para require() com --expose-internals)");
  }
} else {
  report.push("app-boot: AVISO — ficheiro não encontrado em " + appBoot);
}

/* ---------- patch 2: flock no Android (motor mono-processo) ---------- */
const flock = HOME + "/@deepseek-ai/node-addon-system/lib/flock.js";
if (fs.existsSync(flock)) {
  let src = fs.readFileSync(flock, "utf8");
  const marker = "export async function tryLockExclusive(fd) {";
  const patched = "cachalote: android single-process flock";
  if (src.includes(patched)) {
    report.push("flock: já patcheado (nada a fazer)");
  } else if (!src.includes(marker)) {
    report.push("flock: AVISO — não encontrei tryLockExclusive; versão mudou?");
  } else {
    // No Android o binding nativo não existe e o loadBinding() lança
    // ERR_FLOCK_UNSUPPORTED_PLATFORM, o que faz falhar a persistência de sessão
    // ("ACP session persistence flush failed"). O motor é mono-processo, e o
    // próprio upstream faz stub do flock no worker do browser pela mesma razão:
    // a reivindicação de escrita in-process já exclui qualquer outro escritor.
    src = src.replace(
      marker,
      marker + "\n    /* " + patched + " */\n    if (process.platform === 'android')\n        return;",
    );
    fs.writeFileSync(flock, src);
    report.push("flock: Android passa a lock imediato (mono-processo, como o stub do browser worker)");
  }
} else {
  report.push("flock: AVISO — não encontrei " + flock);
}

/* ---------- patch 3: bash-local declara um sandboxMode ---------- */
const bashLocal = HOME + "/@deepseek-ai/dsh-bash-local/lib/index.js";
if (fs.existsSync(bashLocal)) {
  let src = fs.readFileSync(bashLocal, "utf8");
  const marker = 'static inject = ["subprocess"];';
  const patched = "cachalote: sandboxMode declarado";
  if (src.includes(patched)) {
    report.push("bash-local: já patcheado (nada a fazer)");
  } else if (!src.includes(marker)) {
    report.push("bash-local: AVISO — não encontrei `static inject`; versão mudou?");
  } else {
    // Sem isto: `dsh-permission-presets` lança no arranque ("the mounted bash
    // executor does not confine (no sandboxMode)") e, por arrasto, `tool-bash`
    // também não monta.
    //
    // ⚠ HONESTIDADE: no Android NÃO há Landlock, portanto o confinamento é
    // *declarativo* — quem trava um comando fora do workspace é a camada de
    // aprovação (preset `workspace-write` com approval: ask), não o kernel.
    // Declarar `danger-full-access` seria pior: a política deixaria de pedir
    // aprovação. O confinamento real é trabalho de F3 (ponte host.*).
    src = src.replace(
      marker,
      marker + "\n\t/* " + patched + " (ver PLANO.md D10) */\n\tget sandboxMode() {\n\t\treturn \"workspace-write\";\n\t}",
    );
    fs.writeFileSync(bashLocal, src);
    report.push("bash-local: sandboxMode = workspace-write (confinamento declarativo; ver D10)");
  }
} else {
  report.push("bash-local: AVISO — não encontrei " + bashLocal);
}

/* ---------- patch 4: SELinux proíbe link() -> publicar com rename() ---------- */
// No Android o `link(2)` é negado pelo SELinux (EACCES) mesmo com origem e
// destino no mesmo directório — foi o erro que apareceu em ACP `session/new`:
//   EACCES: permission denied, link '.../session.v4.jsonl.zstd.5af...'
// O `rename(2)` no mesmo sistema de ficheiros é atómico e permitido.
// (O projecto de referência chegou à mesma conclusão; item 6 da base de conhecimento.)
const LINK_SITES = [
  {
    file: HOME + "/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
    subs: [
      ["await internals.fs.link(staged, currentPath);", "await internals.fs.rename(staged, currentPath);"],
      ["await link(tmp, finalPath);", "await rename(tmp, finalPath);"],
      ["\tlink,\n", "\tlink,\n\trename,\n"],
    ],
  },
  {
    file: HOME + "/@deepseek-ai/dsh-attachment-local/lib/index.js",
    subs: [
      ["await link(source, target);", "await rename(source, target);"],
      ["await link(staged.path, target);", "await rename(staged.path, target);"],
      // Com hard link o nome de staging continuava a existir; com rename já não. O unlink
      // seguinte (que fazia sentido antes) tem de tolerar ENOENT — foi ISTO que impedia as
      // imagens de serem publicadas ("unable to persist the prompt image batch") e, com elas,
      // a visão chegar ao modelo. Ver base de conhecimento 80.
      ["await unlink(staged.path);", "await unlink(staged.path).catch(() => {});"],
    ],
  },
];

for (const site of LINK_SITES) {
  const short = site.file.replace(HOME + "/", "");
  if (!fs.existsSync(site.file)) {
    report.push(short + ": AVISO — ficheiro não encontrado");
    continue;
  }
  let src = fs.readFileSync(site.file, "utf8");
  if (src.includes("cachalote: link->rename v2")) {
    report.push(short + ": já patcheado (nada a fazer)");
    continue;
  }
  // Garantir que `rename` vem de node:fs/promises (as chamadas são awaited).
  const promisesImport = src.split("\n").find((l) => /from "node:fs\/promises"/.test(l)) || "";
  if (promisesImport && !/\brename\b/.test(promisesImport)) {
    src = src.replace(/(import \{)([^}]*)(\} from "node:fs\/promises";)/, "$1 rename,$2$3");
  } else if (!promisesImport) {
    report.push(short + ": AVISO — sem import de node:fs/promises; rename não injectado");
  }
  let n = 0;
  for (const [from, to] of site.subs) {
    if (src.includes(from)) {
      src = src.replace(from, to);
      n++;
    }
  }
  if (n === 0) {
    report.push(short + ": AVISO — nenhum ponto de link() reconhecido");
    continue;
  }
  src = "/* cachalote: link->rename v2 (o SELinux do Android nega link; unlink tolerante após rename) */\n" + src;
  fs.writeFileSync(site.file, src);
  report.push(short + ": " + n + " publicação(ões) link()->rename()");
}

/* ---------- patch 5: tool-bash declara que precisa do sandboxPolicy ---------- */
// `tool-bash` lê `ctx.get("sandboxPolicy")` no apply() mas NÃO o declara em
// `inject`. Com o executor do desktop (bash-sandbox) a ordem de composição
// calhava bem; com o `bash-local` (que só injecta subprocess) o tool-bash monta
// antes de o serviço existir e rebenta:
//   "tool-bash: the mounted bash executor confines but ctx.sandboxPolicy is missing"
// Declarar a dependência é a correcção correcta: o sandbox-policy vem no dsh-base
// em qualquer perfil.
const toolBash = HOME + "/@deepseek-ai/dsh-tool-bash/lib/index.js";
if (fs.existsSync(toolBash)) {
  let src = fs.readFileSync(toolBash, "utf8");
  const injectEnd = '"shellEnv"\n];';
  if (src.includes('"shellEnv",\n\t"sandboxPolicy"')) {
    report.push("tool-bash: já patcheado (nada a fazer)");
  } else if (!src.includes(injectEnd)) {
    report.push("tool-bash: AVISO — não reconheci a lista inject; versão mudou?");
  } else {
    src = src.replace(injectEnd, '"shellEnv",\n\t"sandboxPolicy"\n];');
    fs.writeFileSync(toolBash, src);
    report.push("tool-bash: inject += sandboxPolicy (corrige a corrida de composição)");
  }
} else {
  report.push("tool-bash: AVISO — não encontrei " + toolBash);
}

/* ---------- patch 6: instalar o plugin de ferramentas Android ---------- */
// O plugin vive no repositório (kernel-plugins/cachalote-tools) e é copiado para dentro
// da árvore, no mesmo sítio onde o projecto de referência punha os dele — e declarado nas
// dependências do pacote `dsh`, que é o que o loader consulta.
const pluginSrc = path.join(REPO, "kernel-plugins", "cachalote-tools");
// Duas localizações de propósito: o kernel 0.2.0-rc.2 tem as dependências *hoisted* no
// topo (`dsh/node_modules/@deepseek-ai/`), mas o projecto de referência instalava os
// plugins dele dentro de `dsh/node_modules/...` e declarava-os em `dsh/package.json`.
// Instalar nos dois sítios torna o plugin resolúvel em qualquer das convenções.
const pluginDsts = [
  path.join(HOME, "@deepseek-ai", "dsh-tool-cachalote"),
  path.join(HOME, "@deepseek-ai", "dsh", "node_modules", "@deepseek-ai", "dsh-tool-cachalote"),
];
if (fs.existsSync(pluginSrc)) {
  const copyTree = (from, to) => {
    fs.mkdirSync(to, { recursive: true });
    for (const e of fs.readdirSync(from, { withFileTypes: true })) {
      const a = path.join(from, e.name), b = path.join(to, e.name);
      if (e.isDirectory()) copyTree(a, b);
      else fs.writeFileSync(b, fs.readFileSync(a));
    }
  };
  for (const dst of pluginDsts) {
    fs.rmSync(dst, { recursive: true, force: true });
    copyTree(pluginSrc, dst);
  }
  const dshPkg = path.join(HOME, "@deepseek-ai", "dsh", "package.json");
  const pkg = JSON.parse(fs.readFileSync(dshPkg, "utf8"));
  pkg.dependencies = pkg.dependencies || {};
  if (!pkg.dependencies["@deepseek-ai/dsh-tool-cachalote"]) {
    pkg.dependencies["@deepseek-ai/dsh-tool-cachalote"] = "0.1.0";
    fs.writeFileSync(dshPkg, JSON.stringify(pkg, null, 2) + "\n");
    report.push("plugin cachalote-tools: instalado (topo + ninho) e declarado em dsh/package.json");
  } else {
    report.push("plugin cachalote-tools: reinstalado (dependência já declarada)");
  }
} else {
  report.push("plugin cachalote-tools: AVISO — " + pluginSrc + " não existe");
}

/* ---------- patch 7: guarda de caminhos no tool-bash ---------- */
// No Android não há Landlock: o `bash` corre sem confinamento e as aprovações do DSH nunca
// eram pedidas (nascem do *escalonamento*, que só acontece quando o sandbox falha). Esta
// guarda fecha esse buraco: antes de executar, se o comando toca em caminhos fora da área
// permitida, pede autorização pelo serviço `approval` — que o perfil ACP transforma num
// `session/request_permission` e a app mostra no telefone. Ver D10 no PLANO.md.
//
// Nota: tentámos primeiro um listener em `tools/pre-execute` (o seam documentado), mas o
// waterfall é despachado num escopo por-agente e os listeners registados na raiz não o
// recebem (ver base de conhecimento #59).
const toolBashFile = HOME + "/@deepseek-ai/dsh-tool-bash/lib/index.js";
if (fs.existsSync(toolBashFile)) {
  let src = fs.readFileSync(toolBashFile, "utf8");
  const marker = "cachalote: guarda de caminhos";
  if (src.includes(marker)) {
    report.push("tool-bash (guarda): já patcheado (nada a fazer)");
  } else {
    const anchor = "\tconst approveBashEscalation = (mode, justification, exec, standingPolicy) => {";
    const callAnchor = "\t\t\t\tvalidateBashArgs(args, standingPolicy?.mode);";
    if (!src.includes(anchor) || !src.includes(callAnchor)) {
      report.push("tool-bash (guarda): AVISO — âncoras não encontradas; versão mudou?");
    } else {
      const guard = [
        "\t/** " + marker + " (ver D10 do PLANO.md). */",
        "\tconst cachaloteGuard = async (command, exec) => {",
        '\t\tconst readOnly = ["/system","/vendor","/product","/apex","/proc","/sys","/dev","/etc","/bin","/sbin","/lib","/lib64","/usr","/odm","/metadata"];',
        "\t\tconst cwd = exec?.agent?.session?.header?.cwd;",
        '\t\tconst roots = [...new Set([...(process.env.CACHALOTE_ALLOWED_ROOTS || "").split(":").filter(Boolean), ...(cwd ? [cwd] : []), process.env.DSH_HOME || "/data/adb/cachalote/home"])];',
        "\t\tconst bad = /* @__PURE__ */ new Set();",
        "\t\tfor (const raw of String(command).match(/(?<![\\w.-])\\/[^\\s'\"`;|&()<>]*/g) || []) {",
        '\t\t\tconst clean = raw.replace(/\\/+$/, "") || "/";',
        '\t\t\tif (readOnly.some((p) => clean === p || clean.startsWith(p + "/"))) continue;',
        '\t\t\tif (roots.some((r) => clean === r || clean.startsWith(String(r).replace(/\\/+$/, "") + "/"))) continue;',
        "\t\t\tbad.add(clean);",
        "\t\t}",
        "\t\tif (bad.size === 0) return;",
        "\t\tconst list = [...bad].slice(0, 5);",
        '\t\tconst approver = ctx.get("approval");',
        "\t\tif (approver === void 0) throw new Error(`cachalote guard: comando fora da área permitida (${list.join(\", \")}) e não há canal de aprovação nesta composição`);",
        "\t\tconst outcome = await approver.request({",
        "\t\t\tagent: exec.agent,",
        '\t\t\ttoolName: "bash",',
        "\t\t\tcallId: exec.callId,",
        "\t\t\treason: `cachalote guard: o comando toca em caminhos fora da área permitida: ${list.join(\", \")}`,",
        "\t\t\tdisplayReason: { en: `This command touches paths outside the allowed area: ${list.join(\", \")}`, zh: `该命令访问了允许范围之外的路径：${list.join(\", \")}` },",
        "\t\t\tsignal: exec.signal",
        "\t\t});",
        "\t\tif (outcome !== \"allowed-once\") throw new Error(`cachalote guard: o utilizador não autorizou (${outcome})`);",
        "\t};",
        "",
      ].join("\n");
      src = src.replace(anchor, guard + anchor);
      src = src.replace(callAnchor, callAnchor + "\n\t\t\t\tawait cachaloteGuard(args.command, exec); // " + marker);
      fs.writeFileSync(toolBashFile, src);
      report.push("tool-bash (guarda): guarda de caminhos instalada (pede aprovação no telefone)");
    }
  }
} else {
  report.push("tool-bash (guarda): AVISO — ficheiro não encontrado");
}

/* ---------- patch 8: fallback do shell no bash-local ---------- */
// O `dsh-bash-local` invoca o binário `bash` fixo no argv. O Android não tem bash (só `sh`,
// mksh), e no modo embutido não há sequer um directório com executáveis nossos para pôr um
// shim (o directório privado da app é noexec com targetSdk moderno). Solução: respeitar
// `CACHALOTE_SHELL` (o motor embutido injecta `/system/bin/sh`).
const bashLocalFile = HOME + "/@deepseek-ai/dsh-bash-local/lib/index.js";
if (fs.existsSync(bashLocalFile)) {
  let src = fs.readFileSync(bashLocalFile, "utf8");
  const marker = "cachalote: shell configurável";
  if (src.includes(marker)) {
    report.push("bash-local (shell): já patcheado (nada a fazer)");
  } else {
    const anchor = '\t\treturn this.executeArgv(spec, [\n\t\t\t"bash",';
    if (!src.includes(anchor)) {
      report.push("bash-local (shell): AVISO — âncora não encontrada; versão mudou?");
    } else {
      src = src.replace(anchor, '\t\treturn this.executeArgv(spec, [\n\t\t\tprocess.env.CACHALOTE_SHELL || "bash", // ' + marker);
      fs.writeFileSync(bashLocalFile, src);
      report.push("bash-local (shell): usa CACHALOTE_SHELL quando definido (Android não tem bash)");
    }
  }
} else {
  report.push("bash-local (shell): AVISO — ficheiro não encontrado");
}

/* ---------- patch 9: imagens sem sharp ---------- */
// O `dsh-attachment-local` exige **sharp** (libvips) para ler metadados e normalizar imagens.
// Não existe build do sharp para **bionic** (o prebuild linux-arm64 é glibc; o musl também não
// serve), portanto no Android qualquer imagem falhava com
// "unable to persist the prompt image batch" — e com isso a visão (android_see) nunca chegava
// ao modelo. O harness tem um caminho de **passagem directa** para imagens já limpas
// (PNG 8-bit sRGB sem metadados e dentro dos limites): basta saber os factos sem sharp.
// Implementamos a leitura em JS puro; tudo o que precise mesmo de re-encodar falha com uma
// mensagem clara. Ver base de conhecimento 80.
const attachFile = HOME + "/@deepseek-ai/dsh-attachment-local/lib/index.js";
if (fs.existsSync(attachFile)) {
  let src = fs.readFileSync(attachFile, "utf8");
  const imarker = "cachalote: imagens sem sharp";
  const probeAnchor = "async function probeImage(data) {\n\tconst sharp = requireSharp();";
  const detectAnchor = "async function detectImage(data, limits) {\n\tconst sharp = requireSharp();";
  if (src.includes(imarker)) {
    report.push("attachment-local (imagens): já patcheado (nada a fazer)");
  } else if (!src.includes(probeAnchor) || !src.includes(detectAnchor)) {
    report.push("attachment-local (imagens): AVISO — âncoras não encontradas; versão mudou?");
  } else {
    const helper = [
      "/** " + imarker + " — factos de imagem sem libvips (PNG limpo; o resto exige sharp). */",
      "function cachaloteImageFacts(data) {",
      "\tconst b = Buffer.isBuffer(data) ? data : Buffer.from(data);",
      "\t// PNG: assinatura + IHDR + varrimento dos chunks (metadados, APNG, alfa).",
      "\tif (b.length >= 26 && b.readUInt32BE(0) === 0x89504e47 && b.readUInt32BE(4) === 0x0d0a1a0a) {",
      "\t\tconst width = b.readUInt32BE(16);",
      "\t\tconst height = b.readUInt32BE(20);",
      "\t\tconst bitDepth = b[24];",
      "\t\tconst colourType = b[25];",
      "\t\tlet off = 8, animated = false, carriesMetadata = false, hasAlpha = colourType === 4 || colourType === 6;",
      "\t\twhile (off + 8 <= b.length) {",
      "\t\t\tconst len = b.readUInt32BE(off);",
      "\t\t\tconst type = b.toString(\"latin1\", off + 4, off + 8);",
      "\t\t\tif (type === \"acTL\") animated = true;",
      "\t\t\tif (type === \"tRNS\") hasAlpha = true;",
      "\t\t\tif (type === \"tEXt\" || type === \"iTXt\" || type === \"zTXt\" || type === \"eXIf\" || type === \"tIME\" || type === \"iCCP\") carriesMetadata = true;",
      "\t\t\toff += 12 + len;",
      "\t\t\tif (type === \"IEND\") break;",
      "\t\t}",
      "\t\treturn {",
      "\t\t\tmediaType: \"image/png\",",
      "\t\t\twidth,",
      "\t\t\theight,",
      "\t\t\tanimated,",
      "\t\t\tcarriesMetadata,",
      "\t\t\tdepth: bitDepth === 8 ? \"uchar\" : String(bitDepth),",
      "\t\t\tspace: \"srgb\",",
      "\t\t\thasAlpha",
      "\t\t};",
      "\t}",
      "\t// JPEG: varrer marcadores até ao SOF (dimensões). Tem metadados quase sempre, logo",
      "\t// segue o caminho do sharp — que no Android falha com mensagem clara.",
      "\tif (b.length > 4 && b[0] === 0xff && b[1] === 0xd8) {",
      "\t\tlet off = 2;",
      "\t\twhile (off + 9 < b.length) {",
      "\t\t\tif (b[off] !== 0xff) { off++; continue; }",
      "\t\t\tconst marker = b[off + 1];",
      "\t\t\tif (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {",
      "\t\t\t\treturn {",
      "\t\t\t\t\tmediaType: \"image/jpeg\",",
      "\t\t\t\t\theight: b.readUInt16BE(off + 5),",
      "\t\t\t\t\twidth: b.readUInt16BE(off + 7),",
      "\t\t\t\t\tanimated: false,",
      "\t\t\t\t\tcarriesMetadata: true,",
      "\t\t\t\t\tdepth: \"uchar\",",
      "\t\t\t\t\tspace: \"srgb\",",
      "\t\t\t\t\thasAlpha: false",
      "\t\t\t\t};",
      "\t\t\t}",
      "\t\t\toff += 2 + b.readUInt16BE(off + 2);",
      "\t\t}",
      "\t}",
      "\tthrow new AttachmentError(\"Unsupported or malformed image data.\", \"INVALID_IMAGE\");",
      "}",
      "",
      "async function cachaloteProbe(data, limits) {",
      "\tconst detected = cachaloteImageFacts(data);",
      "\tif (limits?.maxPixels !== void 0 && detected.width * detected.height > limits.maxPixels) throw new AttachmentError(\"Image exceeds the configured decoded-pixel limit.\", \"IMAGE_TOO_MANY_PIXELS\");",
      "\tif (limits?.maxDimension !== void 0 && Math.max(detected.width, detected.height) > limits.maxDimension) throw new AttachmentError(\"Image exceeds the configured per-side pixel limit.\", \"IMAGE_DIMENSION_TOO_LARGE\");",
      "\treturn detected;",
      "}",
      "",
    ].join("\n");

    src = src.replace(probeAnchor, helper + "async function probeImage(data) {\n\treturn cachaloteImageFacts(data);\n}\n\nasync function probeImageSharp(data) {\n\tconst sharp = requireSharp();");
    src = src.replace(detectAnchor, "async function detectImage(data, limits) {\n\treturn cachaloteProbe(data, limits);\n}\n\nasync function detectImageSharp(data, limits) {\n\tconst sharp = requireSharp();");
    fs.writeFileSync(attachFile, src);
    report.push("attachment-local (imagens): leitura em JS puro (o Android não tem sharp)");
  }
} else {
  report.push("attachment-local (imagens): AVISO — ficheiro não encontrado");
}

/* ---------- patch 10: fsync de directórios que a app não alcança ---------- */
// O `dsh-attachment-local` prova a durabilidade de um caminho fazendo fsync de **todos** os
// directórios antepassados, de $DSH_HOME até à raiz. O daemon corre como root e consegue; o
// motor **embutido** corre como o uid da app e não consegue sequer ABRIR `/data` (EACCES) —
// e qualquer anexo morria com "unable to persist the prompt image batch", uma mensagem que
// não diz nada sobre a causa (é a mesma de quando faltava o sharp, patch 9).
// No Android os antepassados acima da área da app não são nossos: não os conseguir abrir não
// pode derrubar a operação. Ver base de conhecimento 88.
const durableFile = HOME + "/@deepseek-ai/dsh-attachment-local/lib/index.js";
if (fs.existsSync(durableFile)) {
  let src = fs.readFileSync(durableFile, "utf8");
  const dmarker = "cachalote: fsync tolerante";
  const openAnchor = "\tconst handle = await open(path, constants.O_RDONLY);";
  const tolerant = [
    "\t/* " + dmarker + " — acima da área da app o uid não abre o directório (EACCES) e isso */",
    "\t/* não é erro nosso: não pode impedir a gravação do anexo. Ver knowledge-android 88. */",
    "\tlet handle;",
    "\ttry {",
    "\t\thandle = await open(path, constants.O_RDONLY);",
    "\t} catch (error) {",
    "\t\tif (error?.code === \"EACCES\" || error?.code === \"EPERM\" || error?.code === \"ENOENT\") return;",
    "\t\tthrow error;",
    "\t}",
  ].join("\n");
  if (src.includes(dmarker)) {
    report.push("attachment-local (fsync): já patcheado (nada a fazer)");
  } else if (!src.includes(openAnchor)) {
    report.push("attachment-local (fsync): AVISO — âncora não encontrada; versão mudou?");
  } else {
    src = src.replace(openAnchor, tolerant);
    fs.writeFileSync(durableFile, src);
    report.push("attachment-local (fsync): tolera directórios que o uid da app não pode abrir");
  }
} else {
  report.push("attachment-local (fsync): AVISO — ficheiro não encontrado");
}

/* ---------- verificação final: nenhum .node na árvore ---------- */
let natives = [];
(function walk(dir) {
  let list;
  try {
    list = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const e of list) {
    const p = dir + "/" + e.name;
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith(".node")) natives.push(p.replace(HOME + "/", ""));
  }
})(HOME);
report.push("binários .node restantes na árvore: " + natives.length + (natives.length ? " -> " + natives.slice(0, 5).join(", ") : ""));

console.log(report.map((r) => "  • " + r).join("\n"));
