package dev.ricardopimentel.cachalote

import android.content.Context
import org.json.JSONObject

/**
 * Junta as duas peças de acesso ao motor:
 *  · [CachaloteClient] — canal TCP autenticado, para tudo o que é ACP e operações do daemon;
 *  · [RootShell] — só para o que exige root: obter/rodar o token, arrancar/parar o daemon.
 *
 * Nota de desenho: a API key nunca é guardada pela app. O ecrã da chave envia-a para
 * o daemon (`cachalote.credentials.set`), que a escreve em `home/.env` com modo 600.
 */
class DaemonController(private val context: Context) {

    val client = CachaloteClient()
    private val prefs = Prefs(context)
    private var acpReady = false

    var base: String
        get() = prefs.base
        set(v) { prefs.base = v }

    var port: Int
        get() = prefs.port
        set(v) { prefs.port = v }

    /** Directório de trabalho das sessões (o motor usa-o como workspace do agente). */
    val workspace: String
        get() = prefs.workspace

    /** Token do canal TCP: vem das prefs ou é pedido ao daemon por `su`. */
    suspend fun ensureToken(rotate: Boolean = false): String {
        val cached = prefs.token
        if (!rotate && !cached.isNullOrBlank() && cached.length >= 32) return cached
        val args = StringBuilder("app-token")
        if (rotate) args.append(" --rotate")
        val out = RootShell.run("${prefs.base}/bin/cachalote $args").getOrThrow()
        val token = out.lines().map { it.trim() }.lastOrNull { it.length >= 32 }
            ?: error("o daemon não devolveu token (saída: ${out.take(200)})")
        prefs.token = token
        return token
    }

    suspend fun connect(): JSONObject {
        val token = ensureToken()
        acpReady = false
        return client.connect(prefs.port, token)
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
            JSONObject().put("cwd", prefs.workspace).put("mcpServers", org.json.JSONArray()),
        )
        res.optString("sessionId").takeIf { it.isNotBlank() }?.let { prefs.lastSessionId = it }
        return res
    }

    /** Sessões persistidas no motor (para a lista/retoma). */
    suspend fun listSessions(cwd: String): JSONObject {
        ensureAcp()
        return client.callForResult("session/list", JSONObject().put("cwd", cwd))
    }

    /** Retoma uma sessão existente. O `cwd` tem de ser o mesmo com que foi criada. */
    suspend fun resumeSession(sessionId: String, cwd: String): JSONObject {
        ensureAcp()
        return client.callForResult(
            "session/resume",
            JSONObject().put("sessionId", sessionId).put("cwd", cwd).put("mcpServers", org.json.JSONArray()),
        )
    }

    /** Muda uma opção da sessão (modelo, esforço de raciocínio…). Devolve as opções actualizadas. */
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

    // Todas as operações garantem a ligação: a app pode arrancar sem sessão TCP
    // (processo morto, telefone reiniciado) e o token já estar guardado.
    suspend fun status(): JSONObject {
        ensureConnected()
        return client.callForResult("cachalote.status")
    }

    suspend fun keyStatus(name: String = "DEEPSEEK_API_KEY"): JSONObject {
        ensureConnected()
        return client.callForResult("cachalote.credentials.status", JSONObject().put("name", name))
    }

    suspend fun setKey(value: String, name: String = "DEEPSEEK_API_KEY"): JSONObject {
        ensureConnected()
        return client.callForResult("cachalote.credentials.set", JSONObject().put("name", name).put("value", value))
    }

    suspend fun removeKey(name: String = "DEEPSEEK_API_KEY"): JSONObject {
        ensureConnected()
        return client.callForResult("cachalote.credentials.remove", JSONObject().put("name", name))
    }

    suspend fun restartEngine(): JSONObject {
        ensureConnected()
        return client.callForResult("cachalote.engine.restart")
    }

    suspend fun startDaemon(): String = RootShell.run("${prefs.base}/bin/cachalote start", 45).getOrThrow()

    suspend fun stopDaemon(): String = RootShell.run("${prefs.base}/bin/cachalote stop").getOrThrow()

    suspend fun daemonCliStatus(): String = RootShell.run("${prefs.base}/bin/cachalote status").getOrThrow()

    suspend fun daemonInstalled(): Boolean = RootShell.daemonInstalled()

    suspend fun hasRoot(): Boolean = RootShell.hasRoot()
}
