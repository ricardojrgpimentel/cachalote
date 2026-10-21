// meta.cjs — os metadados de todas as sessões, em JSON.
//
// No modo embutido não há daemon que leia os ficheiros das sessões: a lista ficava só com os ids
// do ACP (sem título, data nem prévia). O Node que já está na APK lê-os com o mesmo leitor do
// daemon (`daemon/session-meta.cjs`), por isso a lista fica igual à dos outros modos.
//
// Uso:  node meta.cjs <DSH_HOME>
const path = require("node:path");
const { sessionMeta } = require(path.join(__dirname, "session-meta.cjs"));

const [home] = process.argv.slice(2);
if (!home) {
  process.stderr.write("uso: meta.cjs <DSH_HOME>\n");
  process.exit(2);
}
try {
  process.stdout.write(JSON.stringify(sessionMeta(home)));
} catch (e) {
  process.stderr.write((e && e.message) || String(e));
  process.exit(1);
}
