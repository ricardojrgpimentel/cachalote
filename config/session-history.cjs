// history.cjs — lê o registo de uma sessão e imprime-o em JSON.
//
// Existe para o **modo embutido**: aí não há daemon que leia o ficheiro da sessão, e o registo é
// zstd de frames concatenados (o Kotlin não descomprime zstd). O Node que já está na APK sabe
// fazê-lo — é o mesmo leitor que o daemon usa (`daemon/session-meta.cjs`).
//
// Uso:  node history.cjs <DSH_HOME> <sessionId>
//
// Imprime `{sessionId, title, events, totalEvents, truncated}` no stdout, ou sai com 1 e a razão
// no stderr.
const path = require("node:path");
const { historicoSessao } = require(path.join(__dirname, "session-meta.cjs"));

const [home, id] = process.argv.slice(2);
if (!home || !id) {
  process.stderr.write("uso: history.cjs <DSH_HOME> <sessionId>\n");
  process.exit(2);
}
try {
  process.stdout.write(JSON.stringify(historicoSessao(home, id)));
} catch (e) {
  process.stderr.write((e && e.message) || String(e));
  process.exit(1);
}
