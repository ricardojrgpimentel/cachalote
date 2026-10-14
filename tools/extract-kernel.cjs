#!/usr/bin/env node
/*
 * extract-kernel.cjs — copia a árvore do kernel DSH de dentro de um app.asar Electron
 * para um directório normal, opcionalmente cortando plataformas estranhas e binários nativos.
 *
 * Tem de correr em Electron-as-node, porque o suporte a asar vive no fs do Electron:
 *
 *   ELECTRON_RUN_AS_NODE=1 "/Applications/DeepSeek Harness.app/Contents/MacOS/DeepSeek Harness" \
 *     tools/extract-kernel.cjs --asar "<...>/app.asar" --out .work/f0/kernel --trim
 *
 * --trim remove: *.node, os pacotes de plataforma darwin/win32, telemetria OTel,
 * o host Electron (dsh-desktop-host) e o kit do LibreOffice (145 MB, só desktop).
 * O que sai é registado em <out>/../extract-report.txt.
 */
const fs = require("fs");

const argv = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = argv.indexOf(name);
  return i >= 0 ? argv[i + 1] : dflt;
};
const asar = opt("--asar");
const nodeModules = opt("--node-modules");
const out = opt("--out");
const trim = argv.includes("--trim");

if ((!asar && !nodeModules) || !out) {
  console.error("uso: extract-kernel.cjs (--asar <app.asar> | --node-modules <dir>) --out <dir> [--trim]");
  console.error("  --asar         tira a árvore de um app.asar do DSH no desktop (Electron)");
  console.error("  --node-modules tira-a de um node_modules já instalado (npm install @deepseek-ai/dsh)");
  process.exit(2);
}

// Duas origens possíveis:
//  · o app.asar do DSH no desktop (precisa do Electron-as-node, porque o fs do Electron lê asar);
//  · um node_modules instalado pelo npm — é o caminho reproduzível numa máquina limpa.
const src = asar ? asar + "/dsh/node_modules" : nodeModules;
try {
  fs.statSync(src);
} catch (e) {
  console.error("não encontrei " + src + (asar ? " (o asar tem a árvore em dsh/node_modules?)" : ""));
  process.exit(1);
}

// Pacotes irrelevantes/inutilizáveis no Android: plataformas estranhas, telemetria,
// o host Electron e o kit do LibreOffice (só existe para dar ficheiros Office no desktop).
const TRIM_PATTERNS = [
  /\.node$/,
  /(^|\/)(libreoffice-kit-darwin-arm64|sherpa-onnx-darwin-arm64|@img|@opentelemetry|dsh-desktop-host)(\/|$)/,
  /(^|\/)(@koromix\/koffi-darwin-arm64|node-addon-system-darwin-arm64|node-addon-require-builtin-darwin-arm64)(\/|$)/,
];

const shouldSkip = (rel) => trim && TRIM_PATTERNS.some((re) => re.test(rel));

let files = 0;
let bytes = 0;
const skipped = new Map();

function walk(dir, rel) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const r = rel ? rel + "/" + ent.name : ent.name;
    if (shouldSkip(r)) {
      skipped.set(r, (skipped.get(r) || 0) + 1);
      continue;
    }
    const from = dir + "/" + ent.name;
    const to = out + "/dsh/node_modules/" + r;
    if (ent.isDirectory()) {
      fs.mkdirSync(to, { recursive: true });
      walk(from, r);
    } else {
      const buf = fs.readFileSync(from);
      fs.writeFileSync(to, buf);
      files++;
      bytes += buf.length;
    }
  }
}

console.log("a extrair " + src + " -> " + out + (trim ? " (trim)" : ""));
walk(src, "");

// Marca de origem: sem isto não se sabe dizer se a árvore veio do npm ou do app.asar do
// desktop — foi uma pergunta que ficou sem resposta quando se olhou para o telefone e não havia
// marca nenhuma. O `cachalote doctor` mostra-a.
try {
  const pacote = JSON.parse(fs.readFileSync(out + "/dsh/node_modules/@deepseek-ai/dsh/package.json", "utf8"));
  const crypto = require("crypto");
  const hash = crypto.createHash("sha256");
  const contar = (dir) => {
    for (const nome of fs.readdirSync(dir).sort()) {
      const caminho = dir + "/" + nome;
      const st = fs.statSync(caminho);
      if (st.isDirectory()) contar(caminho);
      else {
        hash.update(caminho.slice(out.length));
        hash.update(fs.readFileSync(caminho));
      }
    }
  };
  contar(out + "/dsh");
  fs.writeFileSync(
    out + "/.cachalote-origem",
    JSON.stringify({
      origem: nodeModules ? "npm" : "app.asar (desktop)",
      fonte: nodeModules || asar,
      versao: pacote.version ?? "?",
      pacote: pacote.name ?? "?",
      cortado: trim,
      sha256: hash.digest("hex"),
      quando: new Date().toISOString(),
      extractor: "tools/extract-kernel.cjs",
    }, null, 1) + "\n",
  );
  console.log("origem gravada: " + (nodeModules ? "npm" : "app.asar") + " · versão " + (pacote.version ?? "?"));
} catch (e) {
  console.log("AVISO: não consegui gravar a marca de origem: " + e.message);
}

const report = [
  "ficheiros: " + files,
  "tamanho: " + (bytes / 1048576).toFixed(1) + " MB",
  "cortados: " + skipped.size + " entradas de topo",
  ...[...skipped.keys()].slice(0, 40).map((k) => "  - " + k),
].join("\n");
fs.writeFileSync(out + "/../extract-report.txt", report + "\n");
console.log(report);
