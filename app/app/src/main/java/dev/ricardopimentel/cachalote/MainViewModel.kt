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

/**
 * Imagem anexada pelo utilizador, à espera de ir no próximo prompt.
 *
 * Não é guardada no estado da sessão (só o nome vai para o transcript): o que fica em disco é
 * o registo da conversa, não os bytes escolhidos.
 */
class PendingImage(val bytes: ByteArray, val mime: String, val name: String) {
    val kb: Int get() = (bytes.size + 1023) / 1024
}

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

    /**
     * Título por sessão. O motor pode gerar um (`session/title`), mas hoje não o expõe no
     * `session/list` — por isso derivamos um do **primeiro prompt** do utilizador. É
     * determinístico, não gasta chamadas e é o que faz a lista deixar de ser só hexadecimais.
     */
    var sessionTitles by mutableStateOf<Map<String, String>>(emptyMap())
    var showSessions by mutableStateOf(false)
    var configPicker by mutableStateOf<ConfigOption?>(null)
    var reconnecting by mutableStateOf(false)
    var pendingImage by mutableStateOf<PendingImage?>(null)
    var audit by mutableStateOf<List<String>>(emptyList())
    var embedTest by mutableStateOf("(não testado)")

    /**
     * Acessibilidade ligada? Lido pela MESMA via que o agente usa (`caps.status`), e não pelo
     * singleton do serviço: verificado no dispositivo que a flag do singleton pode dizer
     * `false` enquanto `android_screen` funciona (o valor tem de ser o que o agente vê).
     */
    var accessibilityOn by mutableStateOf(CachaloteAccessibilityService.isEnabled())
    var a11yTicks by mutableStateOf(0)

    /** Que motor está em uso: daemon com root, daemon via Shizuku, ou motor embutido. */
    var modeKind by mutableStateOf(controller.mode)
    val embeddedMode: Boolean get() = modeKind == DaemonController.Mode.EMBEDDED
    var embeddedInfo by mutableStateOf("")
    var shizukuState by mutableStateOf("")
    var allowedRootsText by mutableStateOf("")
    var allowedRootsSaved by mutableStateOf("")
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
        // 2) Laço de reconexão: o daemon pode reiniciar (ex.: ao mudar a chave) e o socket
        // cai; a app volta sozinha e retoma a sessão.
        //
        // Duas coisas que este laço fazia mal:
        //   · esperava sempre 5 s, sem recuo — com o daemon em baixo, batia à porta para
        //     sempre ao mesmo ritmo;
        //   · tratava `session/resume` recusado como falha e engolia-a. Quando o motor
        //     responde "session is already active" **não é falha nenhuma**: a sessão está
        //     activa, que é exactamente o que queríamos. Era a origem das centenas de
        //     `session is already active` no log do motor.
        viewModelScope.launch {
            var waitMs = 1_000L
            while (true) {
                if (controller.client.connected.value) {
                    waitMs = 1_000L
                    kotlinx.coroutines.delay(2_000)
                    continue
                }
                val ok = runCatching {
                    reconnecting = true
                    controller.ensureConnected()
                    controller.ensureAcp()
                    // Retomar a sessão é um extra: se ainda não há nenhuma (primeira execução,
                    // sem chave) o transporte reconecta-se à mesma. Exigir `loaded` aqui deixava
                    // a app "desligada" para sempre quando o motor morria antes de haver sessão
                    // — foi o que se viu a matar o motor embutido no emulador.
                    val sid = sessionId
                    val cwd = controller.workspace
                    if (!sid.isNullOrBlank() && cwd.isNotBlank()) {
                        try {
                            applyConfigOptions(controller.resumeSession(sid, cwd))
                        } catch (t: Throwable) {
                            if (isSessionAlreadyActive(t)) restoreChips(snapModel, snapEffort)
                            else throw t
                        }
                    }
                }.isSuccess
                reconnecting = false
                waitMs = if (ok) 1_000L else (waitMs * 2).coerceAtMost(30_000L)
                kotlinx.coroutines.delay(waitMs)
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
        ShizukuBridge.init(app)
        refreshCapabilities()
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

    /**
     * Troca entre o daemon (root/Shizuku) e o motor embutido (sem root). No modo embutido a
     * app extrai o kernel de `files/kernel.zip` (uma vez) e arranca o motor como seu filho.
     */
    /**
     * Troca de motor:
     *  · DAEMON   — daemon em /data/adb, root, arranca no boot (o mais poderoso)
     *  · SHIZUKU  — daemon em /data/local/tmp arrancado pelo Shizuku (uid shell)
     *  · EMBEDDED — motor dentro da app, sem root nem Shizuku (sandbox do Android)
     */
    fun switchMode(kind: DaemonController.Mode) = run("modo") {
        if (kind == modeKind) return@run
        // Guardar o estado deste modo antes de sair: cada modo tem a sua sessão e o seu
        // histórico (o cwd é diferente e o motor recusa retomar sessões de outro).
        runCatching { saveState() }
        sessionId = null
        transcript.clear()
        controller.stopEmbedded()
        controller.mode = kind
        modeKind = kind
        shizukuState = ""
        when (kind) {
            DaemonController.Mode.SHIZUKU -> {
                if (!controller.shizukuAvailable()) error("o Shizuku não está a correr (abre a app Shizuku e inicia-o)")
                if (!controller.shizukuHasPermission()) {
                    shizukuState = "falta autorizar o Cachalote no Shizuku (botão abaixo)"
                    return@run
                }
                shizukuState = "a arrancar o daemon via Shizuku…"
                controller.startDaemon()
                shizukuState = "daemon activo via Shizuku (" + controller.ensureToken().take(6) + "…)"
            }
            DaemonController.Mode.DAEMON -> tokenState = "(por obter)"
            DaemonController.Mode.EMBEDDED -> {
                tokenState = "n/d (modo embutido)"
                if (!controller.embeddedPrepared()) provisionKernel()
            }
        }
        controller.ensureConnected()
        controller.ensureAcp()
        refreshStatus()
        runCatching { refreshKeyStatus() }
    }

    /**
     * Garante o kernel no armazenamento da app. Se não houver asset embutido, **descarrega**
     * (com verificação de sha256) — é o que permite distribuir a app sem 389 MB de kernel.
     */
    private suspend fun provisionKernel() {
        val origem = controller.embeddedKernelSource()
        if (origem == null) {
            embeddedInfo = "sem kernel: nem embutido nem URL de download (define em Poderes)"
            return
        }
        embeddedInfo = if (origem.startsWith("download")) "a descarregar o kernel…" else "a preparar o kernel ($origem)…"
        val n = controller.prepareEmbedded { read, total ->
            val mb = { v: Long -> "%.0f".format(v / 1048576.0) }
            embeddedInfo = if (total > 0) "a descarregar o kernel: ${mb(read)}/${mb(total)} MB"
                           else "a descarregar o kernel: ${mb(read)} MB"
        }
        embeddedInfo = if (n > 0) "kernel pronto ($n ficheiros)" else "kernel já estava pronto"
    }

    /** Botão "Descarregar kernel" (modo embutido sem kernel). */
    fun downloadKernel() = run("kernel") {
        if (controller.embeddedPrepared()) { embeddedInfo = "kernel já está pronto"; return@run }
        provisionKernel()
        controller.ensureConnected()
        refreshStatus()
    }

    var kernelUrlText by mutableStateOf("")
    var mcpJsonText by mutableStateOf("")
    var licencas by mutableStateOf<List<Licenca>>(emptyList())
    var licencaAberta by mutableStateOf<Licenca?>(null)
    var mostrarLicencas by mutableStateOf(false)
    var vscreenInfo by mutableStateOf("")
    var vscreenRunning by mutableStateOf(false)
    var vscreenBitmap by mutableStateOf<android.graphics.Bitmap?>(null)

    /** Pré-visualização ao vivo: pede fotogramas seguidos enquanto o interruptor estiver ligado. */
    var vscreenAoVivo by mutableStateOf(false)

    /** Ecrã virtual: iniciar, parar, estado e ver o fotograma actual. */
    fun vscreenStart() = run("ecrã virtual") {
        val r = capsExecute("vscreen_start", org.json.JSONObject().put("width", 720).put("height", 1280).put("density", 320))
        vscreenRunning = r.optBoolean("running")
        vscreenInfo = "display ${r.optInt("displayId")} · ${r.optInt("width")}×${r.optInt("height")} @${r.optInt("density")} dpi"
        transcript.add(ChatLine("system", "ecrã virtual iniciado: $vscreenInfo — põe apps lá com bash: am start --display ${r.optInt("displayId")} -n <pkg>/<activity>"))
    }

    fun vscreenStop() = run("ecrã virtual") {
        capsExecute("vscreen_stop", org.json.JSONObject())
        vscreenRunning = false
        vscreenBitmap = null
        vscreenInfo = "parado"
    }

    /**
     * Pede um fotograma e guarda-o para o mostrar no ecrã (não vai para o contexto do modelo).
     * `silencioso` é o modo da pré-visualização ao vivo: não mexe em `busy` (senão os botões
     * piscavam a cada 1,5 s) nem na faixa de erro.
     */
    fun vscreenRefresh(silencioso: Boolean = false) {
        if (!silencioso) return run("ecrã virtual") { vscreenFrame() }
        viewModelScope.launch { runCatching { vscreenFrame() } }
    }

    private suspend fun vscreenFrame() {
        val r = capsExecute("vscreen_frame", org.json.JSONObject().put("maxWidth", 540))
        val bytes = android.util.Base64.decode(r.optString("data"), android.util.Base64.NO_WRAP)
        vscreenBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        vscreenRunning = true
        vscreenInfo = "display ${r.optInt("displayId")} · ${r.optInt("width")}×${r.optInt("height")} · ${r.optInt("bytes") / 1024} KB"
    }

    /** Lê as licenças de terceiros (textos integrais) do asset. */
    fun carregarLicencas() {
        if (licencas.isEmpty()) licencas = Licencas.carregar(getApplication())
        mostrarLicencas = true
    }

    /** Chama uma capacidade da app localmente (o mesmo caminho que o agente usa). */
    private suspend fun capsExecute(capability: String, params: org.json.JSONObject): org.json.JSONObject =
        controller.capability(capability, params)

    var mcpSaved by mutableStateOf("")

    /** Grava a lista de servidores MCP (JSON do ACP) que vai em cada sessão nova. */
    fun saveMcpServers() = run("servidores MCP") {
        val txt = mcpJsonText.trim().ifBlank { "[]" }
        val arr = runCatching { org.json.JSONArray(txt) }.getOrElse {
            error("JSON inválido: ${it.message?.take(80)}")
        }
        controller.mcpServersJson = arr.toString()
        mcpSaved = "gravado: ${arr.length()} servidor(es)"
        transcript.add(ChatLine("system", "servidores MCP: ${arr.length()} configurado(s) — vale para sessões novas"))
    }

    fun saveKernelUrl() = run("url do kernel") {
        controller.kernelUrl = kernelUrlText.trim()
        transcript.add(ChatLine("system", "URL do kernel: " + kernelUrlText.trim().ifBlank { "(vazio: usa o do build)" }))
    }

    /** Arranca (ou liga-se ao) daemon do modo Shizuku. */
    private suspend fun startShizukuLocked() {
        shizukuState = "a arrancar o daemon via Shizuku…"
        controller.startDaemon()
        val token = controller.ensureToken()
        shizukuState = "daemon activo via Shizuku (" + token.take(6) + "…)"
    }

    /** Pede a permissão do Shizuku (abre o diálogo do sistema). */
    fun requestShizukuPermission(activity: android.app.Activity) {
        if (!controller.shizukuAvailable()) {
            shizukuState = "o Shizuku não está a correr"
            return
        }
        ShizukuBridge.requestPermission(activity)
        shizukuState = "pedido enviado ao Shizuku — autoriza no diálogo"
    }

    /** Actualizado pelo listener do MainActivity quando o utilizador responde ao diálogo. */
    fun onShizukuPermissionResult(granted: Boolean) {
        shizukuState = if (granted) "autorizado pelo Shizuku" else "permissão recusada"
        if (!granted) return
        run("shizuku") {
            startShizukuLocked()
            controller.ensureConnected()
            controller.ensureAcp()
            refreshStatus()
            runCatching { refreshKeyStatus() }
        }
    }

    fun refreshStatus() = run("estado") {
        rootOk = controller.hasRoot()
        // A acessibilidade lê-se pela mesma via que o agente usa (a flag do singleton pode
        // discordar do que o serviço realmente faz — ver knowledge 66).
        accessibilityOn = runCatching {
            caps.execute("status", JSONObject()).optBoolean("accessibility")
        }.getOrElse { CachaloteAccessibilityService.isEnabled() }
        a11yTicks += 1
        daemonInstalled = controller.daemonInstalled()
        if (daemonInstalled != true) {
            error = if (embeddedMode) "kernel não extraído"
            else if (modeKind == DaemonController.Mode.SHIZUKU)
                "o daemon não está instalado em /data/local/tmp/cachalote — corre daemon/install.sh --base /data/local/tmp/cachalote --no-root"
            else "o daemon não está instalado em ${controller.base} — corre daemon/install.sh no computador"
            return@run
        }
        if (embeddedMode) embeddedInfo = if (controller.embeddedEngineRunning()) "motor embutido a correr" else "motor embutido parado"
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
        val img = pendingImage
        if (text.isBlank() && img == null) return@run
        var sid = sessionId
        if (sid.isNullOrBlank()) {
            val res = controller.newSession()
            sid = res.optString("sessionId")
            sessionId = sid
            applyConfigOptions(res)
        }
        val shown = when {
            img == null -> text
            text.isBlank() -> "[imagem: ${img.name}]"
            else -> "$text  [imagem: ${img.name}]"
        }
        // Primeiro prompt desta sessão → é o título que a lista mostra (o motor ainda não
        // expõe títulos no session/list; ver knowledge 97).
        if (sessionTitles[sid].isNullOrBlank() && shown.isNotBlank()) {
            val limpo = shown.replace(Regex("\\s+"), " ").trim()
            sessionTitles = sessionTitles + (sid to limpo.take(48) + if (limpo.length > 48) "…" else "")
        }
        transcript.add(ChatLine("user", shown))
        pendingImage = null
        loaded = true
        saveState()
        // Só regressa no fim do turno; as falhas ficam no transcript (não apenas na faixa
        // de erro), para se perceber o que aconteceu ao turno.
        try {
            controller.prompt(sid!!, text, img?.bytes, img?.mime ?: "image/png")
            // Turno acabado. Com a app em segundo plano, avisa-se por notificação (é o que
            // permite lançar trabalho e ir fazer outra coisa).
            val resposta = transcript.lastOrNull { it.role == "assistant" }?.text.orEmpty()
            Notificacoes.fimDeTurno(
                getApplication(),
                getApplication<Application>().getString(R.string.notif_turno_titulo),
                resposta.ifBlank { getApplication<Application>().getString(R.string.notif_sem_texto) },
            )
        } catch (t: Throwable) {
            transcript.add(ChatLine("error", "turno falhou: ${t.message ?: t::class.java.simpleName}"))
            Notificacoes.fimDeTurno(
                getApplication(),
                getApplication<Application>().getString(R.string.notif_turno_titulo),
                getApplication<Application>().getString(R.string.notif_turno_falhou, t.message ?: "?"),
            )
        }
    }

    // ---------------------------------------------------------------- anexos

    /** Lê a imagem escolhida pelo utilizador e guarda-a para o próximo prompt. */
    fun attachImage(uri: android.net.Uri) = run("anexar imagem") {
        val cr = getApplication<Application>().contentResolver
        val mime = cr.getType(uri) ?: "image/png"
        // O `lastPathSegment` do photo picker é um id numérico; o nome legível está na coluna
        // DISPLAY_NAME da MediaStore.
        val name = runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "imagem"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
            ?: error("não consegui ler a imagem escolhida")
        pendingImage = PendingImage(bytes, mime, name)
    }

    fun clearImage() { pendingImage = null }

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
                    title = o.optString("title").takeIf { it.isNotBlank() }
                        ?: sessionTitles[o.optString("sessionId")]
                        ?: o.optString("sessionId").take(8),
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

    /**
     * Fecha uma sessão. Se for a sessão actual, a app fica sem sessão (a próxima mensagem cria
     * uma nova) — é mais honesto do que continuar a escrever numa sessão fechada.
     */
    fun closeSession(id: String) = run("fechar sessão") {
        // O ACP só fecha sessões activas (o motor responde "unknown session" às do histórico),
        // por isso a app diz isso mesmo em vez de mostrar o erro cru do motor.
        if (id != sessionId) {
            error("o motor só fecha a sessão activa — toca na linha para a retomares primeiro")
        }
        controller.closeSession(id)
        if (id == sessionId) {
            sessionId = null
            // Sem isto, o arranque seguinte (ou a troca de modo) voltava a retomar a sessão
            // que acabámos de fechar — verificado em teste.
            controller.forgetLastSession()
            transcript.clear()
            transcript.add(ChatLine("system", "sessão ${id.take(8)} fechada — a próxima mensagem abre uma nova"))
        } else {
            transcript.add(ChatLine("system", "sessão ${id.take(8)} fechada"))
        }
        saveState()
        refreshSessions()
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

    /** Áreas onde o agente pode escrever sem pedir autorização (uma por linha). */
    fun saveAllowedRoots() = run("áreas permitidas") {
        val res = controller.saveAllowedRoots(allowedRootsText.trim())
        allowedRootsSaved = "gravado (" + res.optString("mode") + "): " +
            allowedRootsText.trim().lines().filter { it.isNotBlank() }.joinToString(", ")
        transcript.add(ChatLine("system", "áreas permitidas actualizadas — motor reiniciado"))
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

    /**
     * O motor recusa `session/resume` quando a sessão já está activa ("Invalid params: session
     * is already active: …"). Não é uma falha: é o estado que queríamos atingir. Tratar isto
     * como erro enchia o log do motor de resumes recusados sem nada de errado acontecer.
     */
    private fun isSessionAlreadyActive(t: Throwable): Boolean =
        t.message?.contains("already active", ignoreCase = true) == true

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
        store.save(sessionId, controller.workspace, model, effort, transcript, sessionTitles)
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
