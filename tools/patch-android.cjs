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
  if (src.includes("cachalote: link->rename")) {
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
  src = "/* cachalote: link->rename (o SELinux do Android nega link) */\n" + src;
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
