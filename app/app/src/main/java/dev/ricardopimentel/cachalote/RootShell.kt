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
                if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
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

    suspend fun hasRoot(): Boolean = run("id -u").map { it.trim() == "0" }.getOrDefault(false)

    suspend fun daemonInstalled(): Boolean = run("test -x $BASE/bin/cachalote && echo ok")
        .map { it.contains("ok") }.getOrDefault(false)
}
