package dev.ricardopimentel.cachalote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Acesso ao daemon privilegiado. O daemon é instalado em /data/adb/cachalote e corre
 * como root; a app precisa do `su` (Magisk) para o controlar (arrancar/parar) e para
 * obter o token do canal TCP. Tudo o resto passa pelo socket — a app nunca precisa de
 * root para falar com o motor.
 */
object RootShell {
    const val BASE = "/data/adb/cachalote"

    suspend fun run(command: String, timeoutSeconds: Long = 25): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val p = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val out = p.inputStream.bufferedReader().readText()
                if (!esperarSaida(p, timeoutSeconds * 1000)) {
                    p.destroyForcibly()
                    error("tempo esgotado a executar: $command")
                }
                if (p.exitValue() != 0) {
                    error("su saiu com ${p.exitValue()}: ${out.trim().takeLast(400)}")
                }
                out.trim()
            }
        }

    suspend fun cachalote(arg: String, timeoutSeconds: Long = 25): Result<String> =
        run("$BASE/bin/cachalote $arg", timeoutSeconds)

    /**
     * Como [run], mas envia um ficheiro pelos bytes do stdin — é o que permite à app instalar o
     * motor em `/data/adb` sozinha, com o `su` que o utilizador já autorizou.
     */
    suspend fun sendFile(
        command: String,
        origem: java.io.InputStream,
        timeoutSeconds: Long = 900,
        onBytes: (Long) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            var total = 0L
            try {
                p.outputStream.use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = origem.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                    out.flush()
                }
                if (!esperarSaida(p, timeoutSeconds * 1000)) {
                    p.destroyForcibly()
                    error("tempo esgotado a enviar com su")
                }
                val codigo = runCatching { p.exitValue() }.getOrDefault(0)
                if (codigo != 0) error("su saiu com $codigo")
                total
            } finally {
                runCatching { p.destroy() }
            }
        }.getOrThrow()
    }

    /**
     * Como o homólogo do Shizuku: arranca **destacado** e espera por uma marca.
     *
     * A extracção do kernel mexe em 24 000 ficheiros; prendê-la a um `su` vivo todo esse tempo é
     * frágil e desnecessário. Destacada, continua no telefone e a app só olha para o resultado.
     */
    suspend fun runDestacado(
        comando: String,
        marca: String,
        timeoutSeconds: Long = 900,
        onEspera: suspend (Long) -> Unit = {},
    ): String {
        withContext(Dispatchers.IO) {
            run("rm -f $marca; nohup $comando >/dev/null 2>&1; touch $marca && echo iniciado", 60).getOrThrow()
        }
        val limite = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < limite) {
            kotlinx.coroutines.delay(1500)
            onEspera(System.currentTimeMillis())
            val pronto = runCatching {
                withContext(Dispatchers.IO) { run("test -f $marca.pronto && echo sim", 30) }.getOrThrow()
            }.getOrDefault("")
            if (pronto.contains("sim")) {
                return withContext(Dispatchers.IO) { run("cat $marca.pronto", 30).getOrThrow() }
            }
        }
        error("tempo esgotado à espera de: $comando")
    }

    /** Ver [ShizukuBridge.esperarSaida]: não se usa o `waitFor(tempo)` do Android. */
    private fun esperarSaida(proc: Process, timeoutMs: Long): Boolean {
        val limite = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < limite) {
            if (runCatching { proc.exitValue() }.isSuccess) return true
            Thread.sleep(50)
        }
        return runCatching { proc.exitValue() }.isSuccess
    }

    suspend fun hasRoot(): Boolean = run("id -u").map { it.trim() == "0" }.getOrDefault(false)

    suspend fun daemonInstalled(): Boolean = run("test -x $BASE/bin/cachalote && echo ok")
        .map { it.contains("ok") }.getOrDefault(false)
}
