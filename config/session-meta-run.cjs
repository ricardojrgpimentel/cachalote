// meta.cjs — os metadados de todas as sessões, em JSON.
//
// No modo embutido não há daemon que leia os ficheiros das sessões: a lista ficava só com os ids
// do ACP (sem título, data nem prévia). O Node que já está na APK lê-os com o mesmo leitor do
// daemon (`daemon/session-meta.cjs`), por isso a lista fica igual à dos outros modos.
//
// Uso:  node meta.cjs <DSH_HOME>
//       node meta.cjs <DSH_HOME> turns <sessionId>   (passos/tempo de cada turno)
const path = require("node:path");
const { sessionMeta, resumoTurnos } = require(path.join(__dirname, "session-meta.cjs"));

const [home, acao, id] = process.argv.slice(2);
if (!home) {
  process.stderr.write("uso: meta.cjs <DSH_HOME> [turns <sessionId>]\n");
  process.exit(2);
}
try {
  // O mesmo script serve as duas leituras (é o que está na APK): a linha de telemetria do turno
  // também tem de existir no modo embutido, onde não há daemon a quem pedi-la.
  if (acao === "turns") {
    if (!id) {
      process.stderr.write("falta o id da sessão\n");
      process.exit(2);
    }
    process.stdout.write(JSON.stringify(resumoTurnos(home, id)));
  } else {
    process.stdout.write(JSON.stringify(sessionMeta(home)));
  }
} catch (e) {
  process.stderr.write((e && e.message) || String(e));
  process.exit(1);
}
