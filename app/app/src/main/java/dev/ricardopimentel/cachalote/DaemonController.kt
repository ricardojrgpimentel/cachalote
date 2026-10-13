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

    enum class Mode { DAEMON, EMBEDDED }

    val client = CachaloteClient()
    private val prefs = Prefs(context)
    private val engine = EmbeddedEngine(context)
    private val caps = HostCapabilities(context, prefs)
    private var hostServer: HostServer? = null
    private var acpReady = false

    var base: String
        get() = prefs.base
        set(v) { prefs.base = v }

    var port: Int
        get() = prefs.port
        set(v) { prefs.port = v }

    /**
     * Directório de trabalho das sessões. No modo embutido tem de ser um caminho que a app
     * consegue ler/escrever (o motor corre com o uid da app); o do daemon é root-only.
     */
    val workspace: String
        get() = if (mode == Mode.EMBEDDED) File(engine.home, "ws").absolutePath else prefs.workspace

    var mode: Mode
        get() = if (prefs.mode == "embedded") Mode.EMBEDDED else Mode.DAEMON
        set(v) {
            prefs.mode = if (v == Mode.EMBEDDED) "embedded" else "daemon"
            client.disconnect()
            acpReady = false
        }

    // ---------------------------------------------------------------- ligação

    suspend fun ensureToken(rotate: Boolean = false): String {
        val cached = prefs.token
        if (!rotate && !cached.isNullOrBlank() && cached.length >= 32) return cached
        val out = RootShell.run("${prefs.base}/bin/cachalote app-token" + if (rotate) " --rotate" else "").getOrThrow()
        val token = out.lines().map { it.trim() }.lastOrNull { it.length >= 32 }
            ?: error("o daemon não devolveu token (saída: ${out.take(200)})")
        prefs.token = token
        return token
    }

    suspend fun connect(): JSONObject {
        acpReady = false
        return when (mode) {
            Mode.DAEMON -> client.connectTcp(prefs.port, ensureToken())
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

    /** Handshake obrigatório do ACP antes de criar sessões. */
    suspend fun ensureAcp(): JSONObject {
        ensureConnected()
        if (acpReady) return JSONObject()
        val res = client.callForResult(
            "initialize",
            JSONObject()
                .put("protocolVersion", 1)
                .put("clientCapabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "cachalote-android").put("version", "0.3.0")),
        )
        acpReady = true
        return res
    }

    suspend fun newSession(): JSONObject {
        ensureAcp()
        val res = client.callForResult(
            "session/new",
            // `workspace` (getter) respeita o modo: no embutido é um caminho que a app pode
            // ler/escrever; o do daemon é root-only.
            JSONObject().put("cwd", workspace).put("mcpServers", org.json.JSONArray()),
        )
        res.optString("sessionId").takeIf { it.isNotBlank() }?.let { prefs.lastSessionId = it }
        return res
    }

    suspend fun listSessions(cwd: String): JSONObject {
        ensureAcp()
        return client.callForResult("session/list", JSONObject().put("cwd", cwd))
    }

    suspend fun resumeSession(sessionId: String, cwd: String): JSONObject {
        ensureAcp()
        return client.callForResult(
            "session/resume",
            JSONObject().put("sessionId", sessionId).put("cwd", cwd).put("mcpServers", org.json.JSONArray()),
        )
    }

    suspend fun setConfigOption(sessionId: String, configId: String, value: String): JSONObject {
        ensureAcp()
        return client.callForResult(
            "session/set_config_option",
            JSONObject().put("sessionId", sessionId).put("configId", configId).put("value", value),
        )
    }

    suspend fun prompt(sessionId: String, text: String): JSONObject {
        ensureAcp()
        val blocks = org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text))
        return client.callForResult(
            "session/prompt",
            JSONObject().put("sessionId", sessionId).put("prompt", blocks),
            timeoutMs = 15 * 60 * 1000,
        )
    }

    // ---------------------------------------------------------------- estado / credenciais

    suspend fun status(): JSONObject = when (mode) {
        Mode.DAEMON -> {
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

    suspend fun startDaemon(): String = RootShell.run("${prefs.base}/bin/cachalote start", 45).getOrThrow()

    suspend fun stopDaemon(): String = RootShell.run("${prefs.base}/bin/cachalote stop").getOrThrow()

    suspend fun daemonInstalled(): Boolean = RootShell.daemonInstalled()

    suspend fun hasRoot(): Boolean = RootShell.hasRoot()

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

    suspend fun prepareEmbedded(): Int = engine.prepare()

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
