package dev.ricardopimentel.cachalote

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Ponte para o Shizuku — o modo "shell" (sem root).
 *
 * Como funciona: depois de o utilizador autorizar o Cachalote no Shizuku, a app liga-se a um
 * **user service** ([ShizukuShellService]) que o servidor do Shizuku instancia no *próprio
 * processo dele*. Os comandos enviados por binder executam com o uid do Shizuku (shell/2000;
 * root se o Shizuku foi iniciado por root).
 *
 * É a via pública e suportada: a `Shizuku.newProcess` que os exemplos usam é API oculta e não
 * existe nos artefactos 13.1.5 (`rikka.shizuku.Shizuku` não a expõe).
 *
 * O daemon vive em `/data/local/tmp/cachalote` (gravável pelo shell) e não em `/data/adb` como
 * no modo root — ver base de conhecimento 75.
 */
object ShizukuBridge {

    const val REQUEST_CODE = 4242
    const val DAEMON_BASE = "/data/local/tmp/cachalote"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // O binder do Shizuku é entregue pelo provider quando o servidor o conhece; ouvir o
        // evento deixa o estado (pingBinder) actualizar-se sem o utilizador fazer nada.
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { binderReady = true }
        }
    }

    /** Última vez que o binder do Shizuku foi recebido (para a UI mostrar estado fresco). */
    @Volatile
    var binderReady: Boolean = false
        private set

    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = available() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(activity: Activity) {
        if (available()) runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /**
     * Arranca um processo como shell através do Shizuku.
     *
     * `Shizuku.newProcess` é API **oculta**: existe no artefacto mas é `private`. É o que os
     * exemplos do Shizuku usam (`Shizuku.newProcess(new String[]{"sh"}, null, null)`) — chama-se
     * por reflexão. Se um dia a assinatura mudar, a alternativa é um *user service*
     * (`Shizuku.bindUserService`) que corre no processo do servidor.
     */
    private fun spawn(command: String): Process {
        val method = newProcessMethod
            ?: error("esta versão do Shizuku não expõe newProcess (usa o modo root ou embutido)")
        val proc = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
            ?: error("o Shizuku não devolveu um processo")
        return proc
    }

    private val newProcessMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    /** Corre um comando como shell e devolve o stdout. */
    suspend fun run(command: String, timeoutSeconds: Long = 60): String =
        runWithInput(command, null, timeoutSeconds)

    /** Como [run], mas escreve `input` no stdin (para segredos: nunca passam em argv). */
    suspend fun runWithInput(command: String, input: String?, timeoutSeconds: Long = 60): String =
        withContext(Dispatchers.IO) {
            require(available()) { "o Shizuku não está a correr (abre a app Shizuku e inicia-o)" }
            require(hasPermission()) { "o Cachalote ainda não tem permissão do Shizuku" }
            val proc = spawn(command)
            try {
                if (input != null) {
                    proc.outputStream.use { it.write((input + "\n").toByteArray()); it.flush() }
                } else {
                    runCatching { proc.outputStream.close() }
                }
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                if (!proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    error("tempo esgotado a correr via Shizuku")
                }
                if (proc.exitValue() != 0) {
                    error("shizuku saiu com ${proc.exitValue()}: ${(err + out).trim().takeLast(300)}")
                }
                out.trim()
            } finally {
                runCatching { proc.destroy() }
            }
        }

    /** O daemon do modo Shizuku está instalado em /data/local/tmp? */
    suspend fun daemonInstalled(): Boolean = runCatching {
        run("test -x $DAEMON_BASE/bin/cachalote && echo ok", 20)
    }.getOrDefault("").contains("ok")

    suspend fun startDaemon(): String = run("$DAEMON_BASE/bin/cachalote start", 90)

    suspend fun stopDaemon(): String = run("$DAEMON_BASE/bin/cachalote stop", 60)

    suspend fun daemonStatus(): String = run("$DAEMON_BASE/bin/cachalote status", 60)

    /** Token do canal TCP do daemon (cria-o se ainda não existir). */
    suspend fun token(rotate: Boolean = false): String {
        val args = StringBuilder("app-token")
        if (rotate) args.append(" --rotate")
        val out = run("$DAEMON_BASE/bin/cachalote $args", 60)
        return out.lines().map { it.trim() }.lastOrNull { it.length >= 32 }
            ?: error("não obtive token do daemon (saída: ${out.take(200)})")
    }

    /** Grava a chave do provedor no `.env` do daemon (o valor vai por stdin). */
    suspend fun setKey(name: String, value: String): String =
        runWithInput("$DAEMON_BASE/bin/cachalote key-set $name", value, 120)

    suspend fun removeKey(name: String): String = run("$DAEMON_BASE/bin/cachalote key-remove $name", 60)

    suspend fun keyStatus(name: String): String = run("$DAEMON_BASE/bin/cachalote key-status $name", 30)

    /**
     * Porto do canal TCP do daemon shell. Lido do próprio daemon (`home/port`), para o modo
     * Shizuku poder coexistir com o daemon root (39731 vs 39733).
     */
    suspend fun daemonPort(default: Int = 39733): Int = runCatching {
        run("cat $DAEMON_BASE/home/port", 20).trim().toInt()
    }.getOrDefault(default)

}
