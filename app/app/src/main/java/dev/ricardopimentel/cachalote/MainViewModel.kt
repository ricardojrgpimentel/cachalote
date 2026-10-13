package dev.ricardopimentel.cachalote

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Pedido de aprovação vindo do motor (ACP `session/request_permission`). */
data class PendingApproval(
    val requestId: Int,
    val title: String,
    val detail: String,
    val options: List<Pair<String, String>>, // optionId -> nome visível
)

/** Uma escolha dentro de uma opção de configuração (modelo, esforço…). */
data class ConfigChoice(val value: String, val label: String)

/** Opção de configuração da sessão, tal como o motor a descreve (ACP `configOptions`). */
data class ConfigOption(
    val id: String,
    val name: String,
    val currentValue: String,
    val choices: List<ConfigChoice>,
) {
    val currentLabel: String get() = choices.firstOrNull { it.value == currentValue }?.label ?: currentValue
}

/** Linha da lista de sessões persistidas no motor. */
data class SessionRow(val id: String, val title: String, val updatedAt: String)

data class ChatLine(
    val role: String,          // user | assistant | thought | tool | error | system
    val text: String,
    val toolId: String? = null,
    val done: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = DaemonController(app)

    var daemonInfo by mutableStateOf("(sem dados — toca em Atualizar)")
    var rootOk by mutableStateOf<Boolean?>(null)
    var daemonInstalled by mutableStateOf<Boolean?>(null)
    var tokenState by mutableStateOf("(por obter)")
    var keyPresent by mutableStateOf(false)
    var keyLength by mutableStateOf(0)
    var keyUpdatedAt by mutableStateOf("")
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")
    var agentInfo by mutableStateOf("")
    var sessionId by mutableStateOf<String?>(null)
    var connected by mutableStateOf(false)
    var approval by mutableStateOf<PendingApproval?>(null)
    var configOptions by mutableStateOf<List<ConfigOption>>(emptyList())
    var sessions by mutableStateOf<List<SessionRow>>(emptyList())
    var showSessions by mutableStateOf(false)
    var configPicker by mutableStateOf<ConfigOption?>(null)
    var reconnecting by mutableStateOf(false)
    var audit by mutableStateOf<List<String>>(emptyList())
    var embedTest by mutableStateOf("(não testado)")

    /** Acessibilidade ligada? Vem do próprio serviço; refrescado por um laço do ViewModel. */
    var accessibilityOn by mutableStateOf(CachaloteAccessibilityService.isEnabled())
    var a11yTicks by mutableStateOf(0)
    var capabilityToggles by mutableStateOf<Map<String, Boolean>>(emptyMap())

    private val store = SessionStore(app)
    private val caps = HostCapabilities(app, Prefs(app))
    val capabilityNames: List<String> = HostCapabilities.CAPABILITIES
    private var loaded = false

    val transcript = mutableStateListOf<ChatLine>()

    init {
        // 1) Repor a conversa anterior (a app pode ter sido morta pelo sistema).
        var snapModel: String? = null
        var snapEffort: String? = null
        store.load()?.let { snap ->
            transcript.clear()
            transcript.addAll(snap.lines)
            sessionId = snap.sessionId
            snapModel = snap.model
            snapEffort = snap.effort
            loaded = true
        }
        // 2) Laço de reconexão: o daemon pode reiniciar (ex.: ao mudar a chave) e o
        // socket cai; a app volta sozinha e retoma a sessão.
        viewModelScope.launch {
            while (true) {
                if (!controller.client.connected.value && loaded) {
                    runCatching {
                        reconnecting = true
                        controller.ensureConnected()
                        controller.ensureAcp()
                        val sid = sessionId
                        val cwd = controller.workspace
                        if (!sid.isNullOrBlank() && cwd.isNotBlank()) {
                            controller.resumeSession(sid, cwd)
                        }
                    }.onFailure { /* fica para a próxima volta */ }
                    reconnecting = false
                }
                kotlinx.coroutines.delay(5_000)
            }
        }
        // Ligações automáticas ao arrancar: se o token já estiver guardado e o daemon
        // estiver de pé, a app fica operacional sem tocar em nada.
        viewModelScope.launch {
            runCatching {
                controller.ensureConnected()
                controller.ensureAcp()
            }.onSuccess {
                tokenState = "obtido (guardado)"
                agentInfo = "deepseek-harness-acp"
                // Se temos uma sessão guardada, tentar retomá-la para recuperar as opções
                // (modelo/esforço). Se o motor ainda a tiver activa, o resume é recusado —
                // nesse caso mostramos os rótulos que guardámos.
                val sid = sessionId
                if (!sid.isNullOrBlank()) {
                    runCatching { controller.resumeSession(sid, controller.workspace) }
                        .onSuccess { applyConfigOptions(it) }
                        .onFailure { restoreChips(snapModel, snapEffort) }
                }
            }.onFailure {
                tokenState = "(toca em Obter token)" 
            }
            runCatching { refreshStatus() }
            runCatching { run("chave") { refreshKeyStatus() } }
        refreshCapabilities()
        // O serviço de acessibilidade liga/desliga fora do nosso controlo (Definições do
        // sistema, force-stop): manter o estado fresco sem depender de efeitos da UI.
        viewModelScope.launch {
            while (true) {
                accessibilityOn = CachaloteAccessibilityService.isEnabled()
                a11yTicks += 1
                kotlinx.coroutines.delay(2_000)
            }
        }
        }
        viewModelScope.launch {
            controller.client.events.collect { onEvent(it) }
        }
        viewModelScope.launch {
            controller.client.requests.collect { onRequest(it) }
        }
        viewModelScope.launch {
            controller.client.connected.collect { connected = it }
        }
    }

    // ---------------------------------------------------------------- estado

    fun refreshStatus() = run("estado") {
        rootOk = controller.hasRoot()
        daemonInstalled = controller.daemonInstalled()
        if (daemonInstalled != true) {
            error = "o daemon não está instalado em ${controller.base} — corre daemon/install.sh no computador"
            return@run
        }
        runCatching { controller.status().toString(2) }
            .onSuccess { daemonInfo = it }
            .onFailure { daemonInfo = "não consegui falar com o daemon:\n${it.message}" }
    }

    fun obtainToken(rotate: Boolean = false) = run("token") {
        val t = controller.ensureToken(rotate)
        tokenState = "obtido (${t.length} caracteres)" + if (rotate) " — rodado" else ""
        controller.connect()
        agentInfo = controller.ensureAcp().optJSONObject("agentInfo")?.toString() ?: ""
        refreshKeyStatus()
    }

    fun startDaemon() = run("arrancar daemon") {
        daemonInfo = controller.startDaemon()
        refreshStatus()
    }

    fun stopDaemon() = run("parar daemon") {
        daemonInfo = controller.stopDaemon()
    }

    fun restartEngine() = run("reiniciar motor") {
        controller.ensureConnected()
        controller.restartEngine()
        refreshStatus()
    }

    // ---------------------------------------------------------------- chave

    fun refreshKeyStatus() = run("chave") {
        val st = controller.keyStatus()
        keyPresent = st.optBoolean("present")
        keyLength = st.optInt("length")
        keyUpdatedAt = st.optString("updatedAt")
    }

    /** A app envia a chave ao daemon (que corre como root) e esquece-a. */
    fun saveKey(value: String) = run("guardar chave") {
        val st = controller.setKey(value)
        keyPresent = st.optBoolean("present")
        keyLength = st.optInt("length")
        keyUpdatedAt = st.optString("updatedAt")
    }

    fun removeKey() = run("remover chave") {
        val st = controller.removeKey()
        keyPresent = st.optBoolean("present")
        keyLength = st.optInt("length")
    }

    // ---------------------------------------------------------------- conversa

    fun newSession() = run("nova sessão") {
        val res = controller.newSession()
        sessionId = res.optString("sessionId").takeIf { it.isNotBlank() }
        applyConfigOptions(res)
        transcript.clear()
        transcript.add(ChatLine("system", "sessão ${sessionId?.take(8) ?: "?"} criada"))
        loaded = true
        saveState()
    }

    fun send(text: String) = run("prompt") {
        if (text.isBlank()) return@run
        var sid = sessionId
        if (sid.isNullOrBlank()) {
            val res = controller.newSession()
            sid = res.optString("sessionId")
            sessionId = sid
            applyConfigOptions(res)
        }
        transcript.add(ChatLine("user", text))
        loaded = true
        saveState()
        // Só regressa no fim do turno; as falhas ficam no transcript (não apenas na faixa
        // de erro), para se perceber o que aconteceu ao turno.
        try {
            controller.prompt(sid!!, text)
        } catch (t: Throwable) {
            transcript.add(ChatLine("error", "turno falhou: ${t.message ?: t::class.java.simpleName}"))
        }
    }

    // ---------------------------------------------------------------- eventos ACP

    private fun onEvent(obj: JSONObject) {
        if (obj.optString("method") != "session/update") return
        val params = obj.optJSONObject("params") ?: return
        val sid = params.optString("sessionId")
        if (sessionId != null && sid.isNotEmpty() && sid != sessionId) return
        val update = params.optJSONObject("update") ?: return
        when (update.optString("sessionUpdate")) {
            "agent_message_chunk" -> append("assistant", textOf(update))
            "agent_thought_chunk" -> append("thought", textOf(update))
            "tool_call" -> transcript.add(
                ChatLine("tool", update.optString("title", "ferramenta"), update.optString("toolCallId")),
            )
            "tool_call_update" -> {
                val id = update.optString("toolCallId")
                val idx = transcript.indexOfFirst { it.toolId == id }
                if (idx >= 0) {
                    val old = transcript[idx]
                    val status = update.optString("status", "")
                    transcript[idx] = old.copy(
                        text = old.text + if (status.isNotEmpty()) "  [$status]" else "",
                        done = status == "completed" || status == "failed",
                    )
                }
            }
        }
    }

    /** Guarda as `configOptions` que o motor devolve em session/new, resume e set_config_option. */
    private fun applyConfigOptions(res: JSONObject?) {
        val arr = res?.optJSONArray("configOptions") ?: return
        val out = ArrayList<ConfigOption>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val choices = ArrayList<ConfigChoice>()
            val opts = o.optJSONArray("options") ?: JSONArray()
            for (j in 0 until opts.length()) {
                val e = opts.optJSONObject(j) ?: continue
                val nested = e.optJSONArray("options")
                if (nested != null) {
                    // forma agrupada: {group, name, options:[{value,name}]}
                    val group = e.optString("group", e.optString("name"))
                    for (k in 0 until nested.length()) {
                        val sub = nested.optJSONObject(k) ?: continue
                        choices.add(ConfigChoice(sub.optString("value"), group + ": " + sub.optString("name")))
                    }
                } else if (e.has("value")) {
                    choices.add(ConfigChoice(e.optString("value"), e.optString("name", e.optString("value"))))
                }
            }
            out.add(
                ConfigOption(
                    id = o.optString("id"),
                    name = o.optString("name", o.optString("id")),
                    currentValue = o.optString("currentValue"),
                    choices = choices,
                ),
            )
        }
        configOptions = out
        saveState()
    }

    /** Lista as sessões persistidas no motor (para retomar). */
    fun refreshSessions() = run("sessões") {
        val res = controller.listSessions(controller.workspace)
        val arr = res.optJSONArray("sessions") ?: JSONArray()
        val rows = ArrayList<SessionRow>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            rows.add(
                SessionRow(
                    id = o.optString("sessionId"),
                    title = o.optString("title").ifBlank { o.optString("sessionId").take(8) },
                    updatedAt = o.optString("updatedAt"),
                ),
            )
        }
        sessions = rows
        showSessions = true
    }

    /** Retoma uma sessão; o transcript local é substituído pelo da sessão escolhida. */
    fun resumeSession(id: String) = run("retomar") {
        val res = controller.resumeSession(id, controller.workspace)
        sessionId = id
        applyConfigOptions(res)
        transcript.clear()
        transcript.add(ChatLine("system", "sessão ${id.take(8)} retomada"))
        showSessions = false
        saveState()
    }

    /** Muda modelo/esforço e o motor devolve as opções actualizadas. */
    fun chooseOption(configId: String, value: String) = run("opção") {
        val sid = sessionId ?: error("sem sessão activa")
        val res = controller.setConfigOption(sid, configId, value)
        applyConfigOptions(res)
        configPicker = null
        transcript.add(ChatLine("system", "opção $configId = ${value.take(60)}"))
        saveState()
    }

    /** Recarrega os interruptores de capacidades e o registo de auditoria. */
    fun refreshCapabilities() {
        val p = Prefs(getApplication())
        capabilityToggles = HostCapabilities.CAPABILITIES.associateWith { p.isCapabilityAllowed(it) }
        audit = p.auditList()
    }

    fun setCapability(capability: String, allowed: Boolean) {
        Prefs(getApplication()).setCapabilityAllowed(capability, allowed)
        refreshCapabilities()
    }

    /**
     * Experiência decisiva do flavor "in-app": com targetSdk 35, o directório privado é
     * noexec, mas o `nativeLibraryDir` (onde a APK extrai os jniLibs) é executável. Se
     * isto devolver a versão do Node, o motor pode correr dentro da app sem root.
     */
    fun testEmbeddedNode() = run("teste embutido") {
        val dir = File(getApplication<Application>().applicationInfo.nativeLibraryDir)
        val node = File(dir, "libnode.so")
        if (!node.isFile) {
            embedTest = "libnode.so não está no APK (corre tools/prepare-embed.sh e reconstrói)"
            return@run
        }
        embedTest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // A AGP só empacota `lib*.so`, portanto os sonames versionados do runtime
                // (libz.so.1, libcrypto.so.3, …) vão embarcados com alias. Aqui
                // reconstruímos os nomes verdadeiros com symlinks no nosso filesDir: o
                // dlopen segue o link e o alvo (nativeLibraryDir) é executável.
                val farm = File(getApplication<Application>().filesDir, "embed-lib").apply { mkdirs() }
                getApplication<Application>().assets.open("embed-libs.txt").bufferedReader().forEachLine { line ->
                    val parts = line.trim().split("=")
                    if (parts.size != 2) return@forEachLine
                    val link = File(farm, parts[0])
                    val target = File(dir, parts[1])
                    if (!target.isFile) return@forEachLine
                    if (link.exists()) link.delete()
                    runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath()) }
                        .onFailure { runCatching { target.copyTo(link, overwrite = true) } }
                }
                val proc = ProcessBuilder(node.absolutePath, "-v")
                    .apply { environment()["LD_LIBRARY_PATH"] = farm.absolutePath }
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                val ended = proc.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                if (!ended) proc.destroyForcibly()
                "libnode.so executou: ${out.ifBlank { "(sem saída)" }}  [exit ${proc.exitValue()}, farm ${farm.list()?.size ?: 0} libs]"
            }.getOrElse { "falhou: ${it.message}" }
        }
    }

    fun clearAudit() {
        Prefs(getApplication()).auditClear()
        refreshCapabilities()
    }

    /**
     * Pedidos que vêm do motor: `session/request_permission` (aprovação do utilizador) e
     * `host.call` (o agente quer uma capacidade Android — notificação, ecrã, toque…).
     */
    private fun onRequest(obj: JSONObject) {
        val method = obj.optString("method")

        if (method == "host.call") {
            val rawId: Any = obj.opt("id") ?: return
            val params = obj.optJSONObject("params") ?: JSONObject()
            val capability = params.optString("capability")
            val args = params.optJSONObject("params") ?: JSONObject()
            viewModelScope.launch {
                val result = runCatching { caps.execute(capability, args) }
                runCatching {
                    controller.client.respond(
                        rawId,
                        result.getOrElse { e ->
                            JSONObject().put("error", JSONObject().put("code", -32000).put("message", e.message ?: "falhou"))
                        },
                    )
                }
                if (result.isFailure) {
                    transcript.add(ChatLine("error", "capacidade $capability: ${result.exceptionOrNull()?.message}"))
                }
                refreshCapabilities()
                saveState()
            }
            return
        }

        if (method != "session/request_permission") {
            // Desconhecido mas com id: responder "cancelled" para não pendurar o turno.
            viewModelScope.launch {
                runCatching { controller.client.respond(obj.optInt("id"), JSONObject().put("outcome", "cancelled")) }
            }
            return
        }
        val params = obj.optJSONObject("params") ?: return
        val tool = params.optJSONObject("toolCall")
        val opts = params.optJSONArray("options")
        val list = ArrayList<Pair<String, String>>()
        if (opts != null) {
            for (i in 0 until opts.length()) {
                val o = opts.optJSONObject(i) ?: continue
                list.add(o.optString("optionId") to o.optString("name", o.optString("kind")))
            }
        }
        Prefs(getApplication()).auditAdd(
            "${System.currentTimeMillis()}  aprovação  " + (tool?.optString("title") ?: "?") +
                "  " + (tool?.opt("rawInput")?.toString()?.take(120) ?: ""),
        )
        approval = PendingApproval(
            requestId = obj.optInt("id"),
            title = tool?.optString("title") ?: "o agente pede autorização",
            detail = tool?.opt("rawInput")?.toString()?.take(600) ?: "",
            options = list,
        )
        transcript.add(ChatLine("system", "o agente pede autorização: " + (tool?.optString("title") ?: "?")))
    }

    /** Resposta ao pedido de aprovação. `optionId` nulo = cancelar o turno. */
    fun answerApproval(optionId: String?) = run("aprovação") {
        val req = approval ?: return@run
        approval = null
        val outcome = if (optionId == null) {
            JSONObject().put("outcome", "cancelled")
        } else {
            JSONObject().put("outcome", JSONObject().put("outcome", "selected").put("optionId", optionId))
        }
        controller.client.respond(req.requestId, outcome)
        transcript.add(ChatLine("system", if (optionId == null) "pedido recusado" else "autorizado"))
    }

    /** Interrompe o turno em curso (ACP `session/cancel`). */
    fun cancelTurn() = run("cancelar") {
        val sid = sessionId ?: return@run
        controller.client.notify("session/cancel", JSONObject().put("sessionId", sid))
        transcript.add(ChatLine("system", "turno cancelado"))
    }

    private fun textOf(update: JSONObject): String =
        update.optJSONObject("content")?.optString("text", "") ?: ""

    /** Chips só com o que estava guardado (sem escolhas) — usado quando o motor recusa o resume. */
    private fun restoreChips(model: String?, effort: String?) {
        val out = ArrayList<ConfigOption>()
        if (!model.isNullOrBlank()) {
            // O valor do modelo é uma tupla JSON (["provider","modelo"]) — mostrar só o modelo.
            val label = runCatching {
                val a = JSONArray(model)
                a.optString(a.length() - 1)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: model
            out.add(ConfigOption("model", "Model", model, listOf(ConfigChoice(model, label))))
        }
        if (!effort.isNullOrBlank()) {
            val label = effort.replaceFirstChar { it.uppercase() }
            out.add(ConfigOption("reasoning_effort", "Reasoning effort", effort, listOf(ConfigChoice(effort, label))))
        }
        if (out.isNotEmpty()) configOptions = out
    }

    /** Persiste o essencial (sessão, opções e transcript) no armazenamento privado. */
    fun saveState() {
        val model = configOptions.firstOrNull { it.id == "model" }?.currentValue
        val effort = configOptions.firstOrNull { it.id == "reasoning_effort" }?.currentValue
        store.save(sessionId, controller.workspace, model, effort, transcript)
    }

    private fun append(role: String, chunk: String) {
        if (chunk.isEmpty()) return
        val last = transcript.lastOrNull()
        if (last != null && last.role == role) {
            transcript[transcript.size - 1] = last.copy(text = last.text + chunk)
        } else {
            transcript.add(ChatLine(role, chunk))
        }
        saveState()
    }

    // ---------------------------------------------------------------- utilitário

    private fun run(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = ""
            try {
                block()
            } catch (t: Throwable) {
                error = "$label: ${t.message ?: t::class.java.simpleName}"
            } finally {
                busy = false
            }
        }
    }
}
