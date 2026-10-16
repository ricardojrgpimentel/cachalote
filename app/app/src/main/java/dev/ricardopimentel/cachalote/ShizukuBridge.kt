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
                // stdout e stderr lidos **em paralelo**: ler um até ao fim antes do outro
                // encravava se o segundo enchesse o tubo (64 KB), e o processo remoto ficava
                // preso à espera que alguém o lesse.
                val out = StringBuilder()
                val err = StringBuilder()
                val tOut = leitor(proc.inputStream, out)
                val tErr = leitor(proc.errorStream, err)
                // O `waitFor` do processo remoto também pode reclamar («process hasn't exited»)
                // mesmo quando o comando acabou: não se decide nada por ele. Quem decide é o
                // **output** — os comandos deste ficheiro terminam com `__OK__` (ver `runOuFalha`).
                if (!esperarSaida(proc, timeoutSeconds * 1000)) {
                    runCatching { proc.destroyForcibly() }
                    error("tempo esgotado a correr via Shizuku")
                }
                tOut.join(3000); tErr.join(3000)
                // O stderr vai junto quando existe: um comando que falha em silêncio no stdout
                // (um `chmod` sobre um ficheiro que não existe, por exemplo) deixava o erro sem
                // uma única pista.
                val e = err.toString().trim()
                if (e.isEmpty()) out.toString().trim() else (out.toString() + "\n" + e).trim()
            } finally {
                runCatching { proc.destroy() }
            }
        }

    /**
     * Corre um comando e **exige** que ele diga que correu bem.
     *
     * O código de saída de um processo do Shizuku não é de confiança (a implementação remota
     * atira `process hasn't exited` mesmo depois de o comando terminar — foi o que rebentou a
     * instalação do motor no S10+ em 2026-10-16). Em vez de adivinhar, o comando é embrulhado
     * para imprimir `__OK__` no fim: se a marca não vier, falhou.
     */
    suspend fun runOuFalha(command: String, timeoutSeconds: Long = 300): String {
        val saida = run("$command && echo __OK__", timeoutSeconds)
        if (!saida.contains("__OK__")) {
            error("o comando não chegou ao fim: ${command.take(120)} — saída: ${saida.takeLast(200)}")
        }
        return saida.replace("__OK__", "").trim()
    }

    /**
     * Espera que um processo do Shizuku saia — **sem** usar o `Process.waitFor(tempo)`.
     *
     * O `waitFor(long, TimeUnit)` do Android faz um ciclo a chamar `exitValue()` e só apanha
     * `IllegalThreadStateException`. O `ShizukuRemoteProcess` atira **`IllegalArgumentException`**
     * («process hasn't exited»), que passa pelo ciclo fora e rebenta a chamada — sempre, a partir
     * do momento em que o processo ainda não acabou. Foi isto que partiu a instalação do motor três
     * vezes seguidas (S10+, 2026-10-16), incluindo dentro do `sendFile`, no primeiro ficheiro.
     *
     * Aqui o `exitValue()` é sondado por nós, e o `runCatching` apanha as duas exceções.
     */
    private fun esperarSaida(proc: Process, timeoutMs: Long): Boolean {
        val limite = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < limite) {
            if (runCatching { proc.exitValue() }.isSuccess) return true
            Thread.sleep(50)
        }
        return runCatching { proc.exitValue() }.isSuccess
    }

    /** Um leitor de stream numa thread, para stdout e stderr não se encravarem um ao outro. */
    private fun leitor(entrada: java.io.InputStream, destino: StringBuilder): Thread =
        Thread {
            runCatching {
                entrada.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n <= 0) break
                        synchronized(destino) { destino.append(buf, 0, n) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

    /**
     * Envia um ficheiro para o lado do Shizuku, **pelos bytes do stdin** do comando.
     *
     * É o que permite à app instalar o motor sozinha: ela não pode escrever em `/data/local/tmp`,
     * mas o uid *shell* que o Shizuku lhe dá pode — e os bytes vão por um tubo local, sem passar
     * por memória (o kernel tem 212 MB).
     *
     * [onBytes] é chamado a cada bloco, para a UI poder mostrar progresso a sério em vez de um
     * botão pendurado.
     */
    suspend fun sendFile(
        command: String,
        origem: java.io.InputStream,
        timeoutSeconds: Long = 900,
        onBytes: (Long) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        require(available()) { "o Shizuku não está a correr (abre a app Shizuku e inicia-o)" }
        require(hasPermission()) { "o Cachalote ainda não tem permissão do Shizuku" }
        val proc = spawn(command)
        var total = 0L
        try {
            // O stderr é lido enquanto se escreve: se o `cat` reclamar (disco cheio, por exemplo),
            // o tubo não pode encher e encravar-nos a meio do envio.
            val err = StringBuilder()
            val tErr = leitor(proc.errorStream, err)
            proc.outputStream.use { out ->
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
            if (!esperarSaida(proc, timeoutSeconds * 1000)) {
                runCatching { proc.destroyForcibly() }
                error("tempo esgotado a enviar por Shizuku")
            }
            tErr.join(3000)
            val erro = err.toString().trim()
            if (erro.contains("No space left", ignoreCase = true)) {
                error("sem espaço no telefone: $erro")
            }
            total
        } finally {
            runCatching { proc.destroy() }
        }
    }

    /**
     * Arranca um comando **destacado** (`nohup … &`) e espera que ele deixe uma marca.
     *
     * A extracção do kernel mexe em 24 000 ficheiros e leva minutos. Mantê-la presa a um processo
     * do Shizuku (com o binder e os tubos vivos todo esse tempo) era frágil — foi assim que a
     * primeira instalação morreu a meio. Destacada, o trabalho continua no telefone e a app só
     * olha para o resultado: mais simples e não se perde se a app for para segundo plano.
     */
    suspend fun runDestacado(
        comando: String,
        marca: String,
        timeoutSeconds: Long = 900,
        onEspera: suspend (Long) -> Unit = {},
    ): String {
        require(available()) { "o Shizuku não está a correr" }
        require(hasPermission()) { "o Cachalote ainda não tem permissão do Shizuku" }
        run("rm -f $marca; nohup $comando >/dev/null 2>&1; touch $marca && echo iniciado", 60)
        val limite = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < limite) {
            kotlinx.coroutines.delay(1500)
            onEspera(System.currentTimeMillis())
            val pronto = runCatching { run("test -f $marca.pronto && echo sim", 30) }.getOrDefault("")
            if (pronto.contains("sim")) return run("cat $marca.pronto", 30)
        }
        error("tempo esgotado à espera de: $comando")
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
