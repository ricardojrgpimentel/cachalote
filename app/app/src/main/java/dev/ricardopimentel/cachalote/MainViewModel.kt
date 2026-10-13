package dev.ricardopimentel.cachalote

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Pedido de aprovação vindo do motor (ACP `session/request_permission`). */
data class PendingApproval(
    val requestId: Int,
    val title: String,
    val detail: String,
    val options: List<Pair<String, String>>, // optionId -> nome visível
)

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

    val transcript = mutableStateListOf<ChatLine>()

    init {
        // Ligações automáticas ao arrancar: se o token já estiver guardado e o daemon
        // estiver de pé, a app fica operacional sem tocar em nada.
        viewModelScope.launch {
            runCatching {
                controller.ensureConnected()
                controller.ensureAcp()
            }.onSuccess {
                tokenState = "obtido (guardado)"
                agentInfo = "deepseek-harness-acp"
            }.onFailure {
                tokenState = "(toca em Obter token)" 
            }
            runCatching { refreshStatus() }
            runCatching { run("chave") { refreshKeyStatus() } }
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
        if (!controller.daemonInstalled()) {
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
        transcript.clear()
        transcript.add(ChatLine("system", "sessão ${sessionId?.take(8) ?: "?"} criada"))
    }

    fun send(text: String) = run("prompt") {
        if (text.isBlank()) return@run
        var sid = sessionId
        if (sid.isNullOrBlank()) {
            val res = controller.newSession()
            sid = res.optString("sessionId")
            sessionId = sid
        }
        transcript.add(ChatLine("user", text))
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

    /** Pedido do motor: hoje só o de aprovação, que exige resposta do utilizador. */
    private fun onRequest(obj: JSONObject) {
        if (obj.optString("method") != "session/request_permission") {
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

    private fun append(role: String, chunk: String) {
        if (chunk.isEmpty()) return
        val last = transcript.lastOrNull()
        if (last != null && last.role == role) {
            transcript[transcript.size - 1] = last.copy(text = last.text + chunk)
        } else {
            transcript.add(ChatLine(role, chunk))
        }
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
