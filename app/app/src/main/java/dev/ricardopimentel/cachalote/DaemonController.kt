package dev.ricardopimentel.cachalote

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Acesso ao motor, em dois modos:
 *
 *  · **DAEMON** (root ou Shizuku): o motor corre fora do sandbox da app, num daemon que
 *    arranca no boot. A app liga-se por TCP autenticado e as operações do daemon
 *    (`cachalote.*`: credenciais, restart) são-lhe delegadas.
 *  · **EMBEDDED** (sem root): a app extrai o kernel, arranca o motor como seu filho
 *    (`libnode.so`, ver [EmbeddedEngine]) e fala-lhe por stdio. Levanta também o
 *    [HostServer], que dá as capacidades Android ao plugin do kernel.
 *
 * A chave do provedor nunca é guardada pela app: no modo daemon é o daemon que a escreve
 * (0600, root); no modo embutido a app escreve `dsh-home/.env` (só legível pelo uid da app).
 */
class DaemonController(private val context: Context) {

    enum class Mode { DAEMON, SHIZUKU, EMBEDDED }

    val client = CachaloteClient()
    private val prefs = Prefs(context)
    private val engine = EmbeddedEngine(context)
    private val caps = HostCapabilities(context, prefs)
    private var hostServer: HostServer? = null
    private var acpReady = false

    /** O daemon do modo root vive em /data/adb; o do Shizuku, em /data/local/tmp. */
    private val daemonBase: String
        get() = if (prefs.mode == "shizuku") ShizukuBridge.DAEMON_BASE else prefs.base

    var base: String
        get() = prefs.base
        set(v) { prefs.base = v }

    var port: Int
        get() = prefs.port
        set(v) { prefs.port = v }

    /** Porto efectivo do canal: o daemon shell usa o dele (ficheiro `home/port`). */
    private suspend fun effectivePort(): Int =
        if (mode == Mode.SHIZUKU) ShizukuBridge.daemonPort(prefs.port) else prefs.port

    /**
     * Directório de trabalho das sessões. No modo embutido tem de ser um caminho que a app
     * consegue ler/escrever (o motor corre com o uid da app); o do daemon é root-only.
     */
    val workspace: String
        get() = if (mode == Mode.EMBEDDED) File(engine.home, "ws").absolutePath else prefs.workspace

    var mode: Mode
        get() = when (prefs.mode) {
            "embedded" -> Mode.EMBEDDED
            "shizuku" -> Mode.SHIZUKU
            else -> Mode.DAEMON
        }
        set(v) {
            prefs.mode = when (v) {
                Mode.EMBEDDED -> "embedded"
                Mode.SHIZUKU -> "shizuku"
                Mode.DAEMON -> "daemon"
            }
            client.disconnect()
            acpReady = false
        }

    // ---------------------------------------------------------------- ligação

    suspend fun ensureToken(rotate: Boolean = false): String {
        // O token é POR MODO: o daemon root e o daemon Shizuku são processos distintos (e
        // portos distintos), logo um token em cache do outro dá "token inválido".
        val cached = if (mode == Mode.SHIZUKU) prefs.tokenShizuku else prefs.token
        if (!rotate && !cached.isNullOrBlank() && cached.length >= 32) return cached
        val token = when (mode) {
            Mode.SHIZUKU -> ShizukuBridge.token(rotate)
            Mode.EMBEDDED -> error("o modo embutido não usa token")
            Mode.DAEMON -> {
                val out = RootShell.run("$daemonBase/bin/cachalote app-token" + if (rotate) " --rotate" else "")
                    .getOrThrow()
                out.lines().map { it.trim() }.lastOrNull { it.length >= 32 }
                    ?: error("o daemon não devolveu token (saída: ${out.take(200)})")
            }
        }
        if (mode == Mode.SHIZUKU) prefs.tokenShizuku = token else prefs.token = token
        return token
    }

    init {
        // Sem isto, um pedido feito durante o reinício do motor (gravar a chave, watchdog a
        // levantar o daemon) falhava logo com "a ligação ao motor caiu" (ver STATUS §21).
        client.reconnector = {
            if (!client.connected.value) {
                runCatching { connect() }
            }
        }
    }

    /**
     * Repete um pedido quando o falhanço é **transitório e não entregue**: a ponte responde
     * "o motor não está a correr" enquanto o reinicia, e a ligação pode cair no modo embutido.
     * Nesses casos NADA foi executado, logo repetir é seguro — ao contrário de um turno já
     * entregue (esse nunca é repetido). Ver STATUS §21 ("ligação aos solavancos").
     */
    private suspend fun <T> comMotorPronto(tentativas: Int = 6, bloco: suspend () -> T): T {
        var ultimo: Throwable? = null
        for (t in 1..tentativas) {
            try {
                return bloco()
            } catch (e: Throwable) {
                ultimo = e
                val m = e.message ?: ""
                val transitorio = m.contains("motor não está a correr") ||
                    m.contains("sem ligação ao motor") ||
                    m.contains("a ligação ao motor caiu")
                if (!transitorio || t == tentativas) throw e
                android.util.Log.i("cachalote-reconexao", "transitório ($m) — tentativa $t")
                kotlinx.coroutines.delay(1_500)
                runCatching { if (!client.connected.value) connect() }
            }
        }
        throw (ultimo ?: IllegalStateException("o motor não respondeu"))
    }

    suspend fun connect(): JSONObject {
        acpReady = false
        return when (mode) {
            Mode.DAEMON -> client.connectTcp(prefs.port, ensureToken())
            Mode.SHIZUKU -> client.connectTcp(effectivePort(), ensureToken())
            Mode.EMBEDDED -> {
                if (!engine.isPrepared()) engine.prepare()
                val streams = if (engine.running) engine.restart(prefs.allowedRoots) else engine.start(prefs.allowedRoots)
                if (hostServer == null) {
                    hostServer = HostServer(context, caps, EmbeddedEngine.HOST_PORT).also { it.start() }
                }
                client.connect(streams)
            }
        }
    }

    suspend fun ensureConnected(): Boolean {
        if (client.connected.value && acpReady) return true
        if (!client.connected.value) connect()
        return true
    }

    /**
     * Handshake obrigatório do ACP antes de criar sessões.
     *
     * O daemon pode estar a meio de um reinício do motor (acontece ao gravar a chave ou ao
     * arrancar o motor): nesse instante ele responde "o motor não está a correr". Isso é
     * transitório, por isso tentamos algumas vezes em vez de falhar o modo logo à primeira.
     */
    suspend fun ensureAcp(): JSONObject {
        ensureConnected()
        if (acpReady) return JSONObject()
        var last: Throwable? = null
        for (attempt in 1..4) {
            try {
                val res = client.callForResult(
                    "initialize",
                    JSONObject()
                        .put("protocolVersion", 1)
                        .put("clientCapabilities", JSONObject())
                        .put("clientInfo", JSONObject().put("name", "cachalote-android").put("version", "0.3.0")),
                    timeoutMs = 20_000,
                )
                acpReady = true
                return res
            } catch (e: Throwable) {
                last = e
                if (attempt < 4) {
                    kotlinx.coroutines.delay(2_000)
                    if (!client.connected.value) connect()
                }
            }
        }
        throw (last ?: IllegalStateException("o motor não respondeu"))
    }

    /**
     * Servidores MCP configurados (JSON do ACP). JSON inválido → lista vazia (e o erro fica
     * visível no ecrã da app, onde o utilizador o pode corrigir).
     */
    private fun mcpServers(): org.json.JSONArray = runCatching {
        val txt = prefs.mcpServersJson.trim().ifBlank { "[]" }
        org.json.JSONArray(txt)
    }.getOrDefault(org.json.JSONArray())

    var mcpServersJson: String
        get() = prefs.mcpServersJson
        set(v) { prefs.mcpServersJson = v }

    suspend fun newSession(): JSONObject = comMotorPronto {
        ensureAcp()
        val res = client.callForResult(
            "session/new",
            // `workspace` (getter) respeita o modo: no embutido é um caminho que a app pode
            // ler/escrever; o do daemon é root-only.
            JSONObject().put("cwd", workspace).put("mcpServers", mcpServers()),
        )
        res.optString("sessionId").takeIf { it.isNotBlank() }?.let { prefs.lastSessionId = it }
        res
    }

    suspend fun listSessions(cwd: String): JSONObject {
        ensureAcp()
        return client.callForResult("session/list", JSONObject().put("cwd", cwd))
    }

    /**
     * Fecha uma sessão no motor (`session/close`). Não apaga o histórico em disco: desactiva-a,
     * que é o que o ACP oferece (`sessionCapabilities.close`). Ver knowledge 93.
     */
    suspend fun closeSession(sessionId: String): JSONObject = comMotorPronto {
        ensureAcp()
        client.callForResult("session/close", JSONObject().put("sessionId", sessionId))
    }

    suspend fun resumeSession(sessionId: String, cwd: String): JSONObject {
        ensureAcp()
        return client.callForResult(
            "session/resume",
            JSONObject().put("sessionId", sessionId).put("cwd", cwd).put("mcpServers", mcpServers()),
        )
    }

    suspend fun setConfigOption(sessionId: String, configId: String, value: String): JSONObject {
        ensureAcp()
        return client.callForResult(
            "session/set_config_option",
            JSONObject().put("sessionId", sessionId).put("configId", configId).put("value", value),
        )
    }

    /**
     * Envia um prompt. A imagem é opcional.
     *
     * O bloco `image` é o mesmo que o `android_see` usa para mostrar o ecrã ao modelo (o motor
     * anuncia `promptCapabilities.image: true` — patch 9, leitura de imagens em JS puro, e o
     * modelo declarado com `inputModalities` de imagem). A diferença é quem escolhe a imagem:
     * ali é a captura do ecrã, aqui é o utilizador.
     */
    suspend fun prompt(
        sessionId: String,
        text: String,
        image: ByteArray? = null,
        imageMime: String = "image/png",
    ): JSONObject {
        ensureAcp()
        val blocks = org.json.JSONArray()
        if (text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", text))
        if (image != null && image.isNotEmpty()) {
            blocks.put(
                JSONObject()
                    .put("type", "image")
                    .put("data", android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP))
                    .put("mimeType", imageMime),
            )
        }
        return client.callForResult(
            "session/prompt",
            JSONObject().put("sessionId", sessionId).put("prompt", blocks),
            timeoutMs = 15 * 60 * 1000,
        )
    }

    // ---------------------------------------------------------------- estado / credenciais

    suspend fun status(): JSONObject = when (mode) {
        Mode.DAEMON, Mode.SHIZUKU -> {
            ensureConnected()
            client.callForResult("cachalote.status")
        }
        Mode.EMBEDDED -> JSONObject()
            .put("daemon", false)
            .put("embedded", true)
            .put("engine", engine.running)
            .put("kernelExtracted", engine.isPrepared())
            .put("kernelZip", engine.hasZip())
            .put("hostServer", hostServer?.running == true)
            .put("base", engine.home.absolutePath)
    }

    private val embeddedEnv: File get() = File(engine.home, ".env")

    suspend fun keyStatus(name: String = "DEEPSEEK_API_KEY"): JSONObject {
        if (mode == Mode.SHIZUKU) {
            val present = runCatching { ShizukuBridge.keyStatus(name) }.getOrDefault("ausente").contains("presente")
            return JSONObject().put("name", name).put("present", present)
                .put("file", ShizukuBridge.DAEMON_BASE + "/home/.env")
        }
        if (mode == Mode.EMBEDDED) {
            val line = runCatching { embeddedEnv.readLines().first { it.startsWith("$name=") } }.getOrNull()
            return JSONObject()
                .put("name", name)
                .put("present", line != null && line.length > name.length + 1)
                .put("length", if (line != null) line.length - name.length - 1 else 0)
                .put("file", embeddedEnv.absolutePath)
        }
        ensureConnected()
        return client.callForResult("cachalote.credentials.status", JSONObject().put("name", name))
    }

    suspend fun setKey(value: String, name: String = "DEEPSEEK_API_KEY"): JSONObject {
        if (mode == Mode.SHIZUKU) {
            require(value.trim().length >= 8) { "valor demasiado curto" }
            ShizukuBridge.setKey(name, value.trim())
            acpReady = false
            return keyStatus(name)
        }
        if (mode == Mode.EMBEDDED) {
            require(value.trim().length >= 8) { "valor demasiado curto" }
            engine.home.mkdirs()
            val kept = runCatching { embeddedEnv.readLines() }.getOrDefault(emptyList())
                .filter { it.isNotBlank() && !it.startsWith("$name=") }
            embeddedEnv.writeText((kept + "$name=${value.trim()}").joinToString("\n") + "\n")
            restartEmbedded()
            return keyStatus(name)
        }
        ensureConnected()
        return client.callForResult("cachalote.credentials.set", JSONObject().put("name", name).put("value", value))
    }

    suspend fun removeKey(name: String = "DEEPSEEK_API_KEY"): JSONObject {
        if (mode == Mode.SHIZUKU) {
            ShizukuBridge.removeKey(name)
            acpReady = false
            return keyStatus(name)
        }
        if (mode == Mode.EMBEDDED) {
            val kept = runCatching { embeddedEnv.readLines() }.getOrDefault(emptyList())
                .filter { it.isNotBlank() && !it.startsWith("$name=") }
            embeddedEnv.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
            restartEmbedded()
            return keyStatus(name)
        }
        ensureConnected()
        return client.callForResult("cachalote.credentials.remove", JSONObject().put("name", name))
    }

    suspend fun restartEngine(): JSONObject = when (mode) {
        Mode.SHIZUKU -> {
            ShizukuBridge.run("$daemonBase/bin/cachalote engine.restart 2>/dev/null || $daemonBase/bin/cachalote restart", 90)
            client.disconnect()
            acpReady = false
            ensureAcp()
            JSONObject().put("ok", true).put("shizuku", true)
        }
        Mode.EMBEDDED -> {
            restartEmbedded()
            JSONObject().put("ok", true).put("embedded", true)
        }
        Mode.DAEMON -> {
            ensureConnected()
            client.callForResult("cachalote.engine.restart")
        }
    }

    /** O motor lê as credenciais no arranque: depois de mexer na chave, reiniciar. */
    private suspend fun restartEmbedded() {
        val streams = engine.restart(prefs.allowedRoots)
        client.connect(streams)
        acpReady = false
        ensureAcp()
    }

    // ---------------------------------------------------------------- daemon (root/Shizuku)

    suspend fun startDaemon(): String = when (mode) {
        Mode.SHIZUKU -> ShizukuBridge.startDaemon()
        else -> RootShell.run("$daemonBase/bin/cachalote start", 45).getOrThrow()
    }

    suspend fun stopDaemon(): String = when (mode) {
        Mode.SHIZUKU -> ShizukuBridge.stopDaemon()
        else -> RootShell.run("$daemonBase/bin/cachalote stop").getOrThrow()
    }

    suspend fun daemonInstalled(): Boolean = when (mode) {
        Mode.SHIZUKU -> ShizukuBridge.daemonInstalled()
        else -> RootShell.daemonInstalled()
    }

    suspend fun hasRoot(): Boolean = RootShell.hasRoot()

    // ---------------------------------------------------------------- shizuku

    fun shizukuAvailable(): Boolean = ShizukuBridge.available()

    fun shizukuHasPermission(): Boolean = ShizukuBridge.hasPermission()

    /** Áreas permitidas (uma por linha) — lidas pelo daemon e pelo motor embutido. */
    var allowedRoots: String
        get() = prefs.allowedRoots
        set(v) { prefs.allowedRoots = v }

    /** Grava as áreas permitidas e reinicia o motor para as aplicar. */
    suspend fun saveAllowedRoots(roots: String): JSONObject {
        prefs.allowedRoots = roots
        return when (mode) {
            Mode.EMBEDDED -> {
                restartEmbedded()
                JSONObject().put("ok", true).put("mode", "embedded").put("roots", roots)
            }
            Mode.SHIZUKU -> {
                val b64 = android.util.Base64.encodeToString(roots.toByteArray(), android.util.Base64.NO_WRAP)
                ShizukuBridge.run(
                    "cd ${ShizukuBridge.DAEMON_BASE} && printf '%s' '$b64' | base64 -d > home/allowed-roots && chmod 600 home/allowed-roots",
                    60,
                )
                restartEngine()
                JSONObject().put("ok", true).put("mode", "shizuku").put("roots", roots)
            }
            Mode.DAEMON -> {
                val b64 = android.util.Base64.encodeToString(roots.toByteArray(), android.util.Base64.NO_WRAP)
                RootShell.run(
                    "cd ${prefs.base} && printf '%s' '$b64' | base64 -d > home/allowed-roots && chmod 600 home/allowed-roots",
                ).getOrThrow()
                client.callForResult("cachalote.engine.restart")
                JSONObject().put("ok", true).put("mode", "daemon").put("roots", roots)
            }
        }
    }

    // ---------------------------------------------------------------- modo embutido

    suspend fun prepareEmbedded(progress: (Long, Long) -> Unit = { _, _ -> }): Int = engine.prepare(progress)

    /** De onde vem o kernel (ficheiro, asset ou download) — para a UI dizer o que vai acontecer. */
    fun embeddedKernelSource(): String? = engine.kernelSource()

    /** Última sessão deste modo (o id guarda-se por modo — ver knowledge 93). */
    fun lastSessionId(): String? = prefs.lastSessionId

    /** Esquece a última sessão (ao fechá-la, para não voltar no arranque ou na troca de modo). */
    fun forgetLastSession() { prefs.lastSessionId = null }

    fun modeLabel(): String = when (mode) {
        Mode.DAEMON -> "daemon (root)"
        Mode.SHIZUKU -> "Shizuku"
        Mode.EMBEDDED -> "embutido"
    }

    var kernelUrl: String
        get() = prefs.kernelUrl
        set(v) { prefs.kernelUrl = v }

    fun embeddedPrepared(): Boolean = engine.isPrepared()

    fun embeddedHasZip(): Boolean = engine.hasZip()

    fun embeddedEngineRunning(): Boolean = engine.running

    fun stopEmbedded() {
        hostServer?.stop()
        hostServer = null
        engine.stop()
        client.disconnect()
    }
}
