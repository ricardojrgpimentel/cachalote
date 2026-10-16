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
data class SessionRow(
    val id: String,
    val title: String,
    val updatedAt: String,
    /** A última mensagem da conversa (prévia da lista). Vazia quando o daemon não a sabe dar. */
    val previa: String = "",
)

data class ChatLine(
    val role: String,          // user | assistant | thought | tool | error | system
    val text: String,
    val toolId: String? = null,
    val done: Boolean = false,
    // Estado da ferramenta tal como o motor o manda (`in_progress`, `completed`, `failed`…). É o
    // que o cartão da ferramenta mostra no chip — antes isto ia colado ao texto («  [completed]»)
    // e não dava para desenhar nada de jeito.
    val estado: String? = null,
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

    var daemonInfo by mutableStateOf("")   // preenchido no arranque; ver init()
    var rootOk by mutableStateOf<Boolean?>(null)
    var daemonInstalled by mutableStateOf<Boolean?>(null)
    var tokenState by mutableStateOf("")

    /**
     * O token do canal existe?
     *
     * É um booleano e não uma comparação com o texto do estado: o assistente comparava
     * `tokenState != "(por obter)"`, ou seja, a lógica dependia de uma frase que o utilizador lê —
     * bastava traduzi-la para o passo passar a mentir.
     */
    var tokenOk by mutableStateOf(false)
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

    /**
     * A conversa está aberta a ocupar o ecrã (como um chat do WhatsApp: sem barra de abas). Vive
     * no modelo, e não na UI, para o `newSession`/`resumeSession` a poderem abrir ao mesmo tempo
     * que abrem a sessão — e para o `closeSession` a fechar quando a sessão deixa de existir.
     */
    var conversaAberta by mutableStateOf(false)
    var configPicker by mutableStateOf<ConfigOption?>(null)

    /**
     * Pedido de navegação de outra secção (ex.: o ecrã virtual a mandar abrir «Capacidades» quando
     * falta uma autorização). Consumido uma vez por quem o lê — ver `AjustesScreen`.
     */
    var pedidoDeSecao by mutableStateOf<String?>(null)

    /** Última causa de falha de ligação e quantas vezes seguidas — ver o laço de reconexão. */
    private var erroDeLigacao by mutableStateOf("")
    private var falhasDeLigacao = 0
    fun pedirSecao(secao: String) { pedidoDeSecao = secao }
    var reconnecting by mutableStateOf(false)
    var pendingImage by mutableStateOf<PendingImage?>(null)
    var audit by mutableStateOf<List<String>>(emptyList())
    var embedTest by mutableStateOf("")    // idem

    /**
     * Acessibilidade ligada? Lido pela MESMA via que o agente usa (`caps.status`), e não pelo
     * singleton do serviço: verificado no dispositivo que a flag do singleton pode dizer
     * `false` enquanto `android_screen` funciona (o valor tem de ser o que o agente vê).
     */
    var accessibilityOn by mutableStateOf(CachaloteAccessibilityService.isEnabled())

    // ---------------------------------------------------------------- fila de tarefas

    // `getApplication()` e não `LocalContext`: isto é um ViewModel, não um composable.
    private var fila = Fila.em(getApplication())

    var tarefas by mutableStateOf<List<Tarefa>>(emptyList())
    var filaACorrer by mutableStateOf(false)

    /** Junta o que está escrito no campo à fila (sem enviar). */
    fun juntarATexto(texto: String) {
        fila.adicionar(texto) ?: return
        tarefas = fila.todas
    }

    fun removerDaFila(id: String) {
        fila.remover(id)
        tarefas = fila.todas
    }

    fun limparConcluidas() {
        fila.limparConcluidas()
        tarefas = fila.todas
    }

    fun pararFila() {
        filaACorrer = false
        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.fila_parada)))
    }

    /**
     * Corre a fila: uma tarefa de cada vez, esperando pelo fim de cada turno. Cada turno gera a
     * sua notificação (é o `enviarPrompt` que a manda), e um pedido de autorização pára-a
     * naturalmente — o turno não acaba até o dono responder.
     */
    fun correrFila() {
        if (filaACorrer) return
        filaACorrer = true
        viewModelScope.launch {
            transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.fila_corrida)))
            while (filaACorrer) {
                val tarefa = fila.proxima() ?: break
                try {
                    enviarPrompt(tarefa.texto)
                    fila.concluir(tarefa.id)
                } catch (t: Throwable) {
                    fila.concluir(tarefa.id, falhou = true)
                    transcript.add(ChatLine("error", getApplication<Application>().getString(R.string.fila_tarefa_falhou, t.message ?: "?")))
                }
                tarefas = fila.todas
            }
            filaACorrer = false
            tarefas = fila.todas
            if (fila.vazia) transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.fila_concluida)))
        }
    }

    /** Consumo de contexto, como o motor o reporta (`usage_update`). */
    var uso by mutableStateOf<Uso?>(null)

    /** Resumo do `cachalote doctor`, trazido pela ponte (estruturado). */
    var doctor by mutableStateOf<JSONObject?>(null)

    fun refreshDoctor() = run("diagnóstico") {
        doctor = controller.client.callForResult("cachalote.doctor", JSONObject(), 60_000)
    }

    /** O assistente de configuração inicial aparece enquanto isto for falso. */
    var wizardConcluido by mutableStateOf(Prefs(getApplication()).wizardConcluido)

    fun concluirWizard() {
        Prefs(getApplication()).wizardConcluido = true
        wizardConcluido = true
        refreshStatus()
    }

    /** Repetir a configuração inicial (Ajustes → Motor, por exemplo). */
    fun repetirWizard() {
        Prefs(getApplication()).wizardConcluido = false
        wizardConcluido = false
    }

    private val vigia = VigiaAcessibilidade(CachaloteAccessibilityService.isEnabled())
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
        // Os rótulos de estado que antes eram literais no `mutableStateOf` (agora traduzíveis).
        daemonInfo = getApplication<Application>().getString(R.string.sem_dados_atualizar)
        embedTest = getApplication<Application>().getString(R.string.nao_testado)
        // 1) Repor a conversa anterior (a app pode ter sido morta pelo sistema).
        var snapModel: String? = null
        var snapEffort: String? = null
        store.load()?.let { snap ->
            transcript.clear()
            transcript.addAll(snap.lines)
            sessionId = snap.sessionId
            // Sem esta linha os títulos derivados perdiam-se a cada arranque: o primeiro
            // `saveState()` gravava um mapa vazio por cima do que estava no ficheiro, e a lista de
            // conversas ficava só com hexadecimais (apanhado em 2026-10-15, ao pôr a lista).
            sessionTitles = snap.titles
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
                // `runCatching` guarda o erro, e não só um booleano: quando o motor não sobe (ou
                // sobe e não responde), o laço tentava para sempre **em silêncio** e o utilizador
                // via «desligado» sem uma única pista (visto no S10+ em 2026-10-16, no modo
                // embutido: um `libnode` novo a cada poucos segundos e nada no ecrã).
                val tentativa = runCatching {
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
                            when {
                                isSessionAlreadyActive(t) -> restoreChips(snapModel, snapEffort)
                                // O motor já não tem esta sessão (reiniciou, ou foi fechada noutro
                                // sítio). Insistir no mesmo id prendia a app num ciclo para sempre:
                                // «desligado» no cabeçalho, «retomar: a ligação ao motor caiu» ao
                                // abrir uma conversa, e um `auth`/`resume` a cada 1–3 s — medido no
                                // S10+ em 2026-10-15, e só se saía trocando de motor. Larga-se a
                                // sessão; a próxima mensagem abre uma nova.
                                sessaoJaNaoExiste(t) -> {
                                    transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.sessao_perdida)))
                                    sessionId = null
                                    saveState()
                                }
                                // Queda de ligação a meio: é transitório, vale a pena tentar outra
                                // vez com o mesmo id (o recuo do laço trata do ritmo).
                                else -> throw t
                            }
                        }
                    }
                }
                val ok = tentativa.isSuccess
                reconnecting = false
                // Depois de três tentativas seguidas sem ligar, diz-se o que se passa — uma vez,
                // e não a cada volta (senão vira ruído). Some quando a ligação volta.
                if (ok) {
                    falhasDeLigacao = 0
                    if (erroDeLigacao.isNotBlank()) { erroDeLigacao = ""; error = "" }
                } else {
                    falhasDeLigacao++
                    val causa = tentativa.exceptionOrNull()?.message.orEmpty()
                    if (falhasDeLigacao == 3 || (causa.isNotBlank() && causa != erroDeLigacao)) {
                        erroDeLigacao = causa
                        error = getApplication<Application>().getString(
                            R.string.erro_motor_sem_ligacao,
                            getApplication<Application>().getString(
                                when (modeKind) {
                                    DaemonController.Mode.EMBEDDED -> R.string.motor_embutido_nome
                                    DaemonController.Mode.SHIZUKU -> R.string.motor_shizuku_nome
                                    else -> R.string.motor_root_nome
                                },
                            ),
                            explicaErroDoMotor(causa.ifBlank { "?" }),
                        )
                    }
                }
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
                tokenState = getApplication<Application>().getString(R.string.token_obtido_guardado)
                tokenOk = true
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
                tokenState = getApplication<Application>().getString(R.string.token_toca_obter)
                tokenOk = false
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
                if (!controller.shizukuAvailable()) error(getApplication<Application>().getString(R.string.shizuku_nao_corre))
                if (!controller.shizukuHasPermission()) {
                    shizukuState = "falta autorizar o Cachalote no Shizuku (botão abaixo)"
                    return@run
                }
                shizukuState = getApplication<Application>().getString(R.string.shizuku_a_arrancar)
                controller.startDaemon()
                shizukuState = getApplication<Application>().getString(R.string.shizuku_activo, controller.ensureToken().take(6))
            }
            DaemonController.Mode.DAEMON -> { tokenState = getApplication<Application>().getString(R.string.token_por_obter); tokenOk = false }
            DaemonController.Mode.EMBEDDED -> {
                tokenState = getApplication<Application>().getString(R.string.token_nd_embutido)
                tokenOk = true
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
            embeddedInfo = getApplication<Application>().getString(R.string.sem_kernel)
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
    var vscreenRunning by mutableStateOf(false)
    var vscreenBitmap by mutableStateOf<android.graphics.Bitmap?>(null)

    /** Pré-visualização ao vivo: pede fotogramas seguidos enquanto o interruptor estiver ligado. */
    var vscreenAoVivo by mutableStateOf(false)

    // Os números do ecrã virtual em campos próprios: o ecrã do design mostra cada um no seu
    // sítio (chip do display, rodapé do monitor, linha da latência) e uma frase só não dava.
    // -1 = ainda não houve ecrã virtual nenhum. Nunca 0: o display 0 é o **ecrã físico**, e os
    // comandos de exemplo do registo não podem apontar para lá.
    var vscreenDisplayId by mutableStateOf(-1)
    var vscreenLargura by mutableStateOf(0)
    var vscreenAltura by mutableStateOf(0)
    var vscreenDensidade by mutableStateOf(0)
    var vscreenHz by mutableStateOf(0)
    var vscreenKb by mutableStateOf(0)
    var vscreenLatenciaMs by mutableStateOf(0L)

    /** Ecrã virtual: iniciar, parar, estado e ver o fotograma actual. */
    fun vscreenStart() = run("ecrã virtual") {
        val r = capsExecute("vscreen_start", org.json.JSONObject().put("width", 720).put("height", 1280).put("density", 320))
        lerVscreen(r)
        vscreenRunning = r.optBoolean("running")
        transcript.add(
            ChatLine(
                "system",
                getApplication<Application>().getString(
                    R.string.vscreen_iniciado,
                    vscreenDisplayId, vscreenLargura, vscreenAltura, vscreenDensidade,
                ),
            ),
        )
    }

    fun vscreenStop() = run("ecrã virtual") {
        capsExecute("vscreen_stop", org.json.JSONObject())
        vscreenRunning = false
        vscreenBitmap = null
        // O `displayId` fica: é o último que existiu, e os comandos de exemplo do registo
        // continuam a apontar para ele (o chip do cabeçalho diz que já não está a correr).
        vscreenAoVivo = false
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
        // A latência que a app mostra é a do caminho todo do fotograma: pedido → captura →
        // PNG → base64 → volta → descodificação. É o tempo que o dono espera, não o do
        // compositor — e é medido, não inventado.
        val t0 = System.currentTimeMillis()
        val r = capsExecute("vscreen_frame", org.json.JSONObject().put("maxWidth", 540))
        val bytes = android.util.Base64.decode(r.optString("data"), android.util.Base64.NO_WRAP)
        vscreenBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        vscreenLatenciaMs = System.currentTimeMillis() - t0
        lerVscreen(r)
        vscreenKb = (r.optLong("bytes", bytes.size.toLong()) / 1024).toInt()
        vscreenRunning = true
    }

    /** Copia para campos o que vem do daemon (`vscreen_start`, `vscreen_status`, `vscreen_frame`). */
    private fun lerVscreen(r: org.json.JSONObject) {
        if (r.has("displayId")) vscreenDisplayId = r.optInt("displayId")
        // O `frame` traz as medidas do display em `displayWidth/Height` (o `width/height` dele é
        // a imagem reduzida); o `start`/`status` traz as do display em `width/height`.
        val w = if (r.has("displayWidth")) r.optInt("displayWidth") else r.optInt("width")
        val h = if (r.has("displayHeight")) r.optInt("displayHeight") else r.optInt("height")
        if (w > 0) vscreenLargura = w
        if (h > 0) vscreenAltura = h
        if (r.optInt("density") > 0) vscreenDensidade = r.optInt("density")
        vscreenHz = Math.round(r.optDouble("refreshRate", 0.0)).toInt()
    }

    var backupAviso by mutableStateOf("")

    /** Exporta as definições (modo, capacidades, áreas do bash, URL do kernel, MCP) para um URI. */
    fun exportarDefinicoes(uri: android.net.Uri) = run("exportar definições") {
        val texto = Backup.exportar(getApplication(), Prefs(getApplication())).toString(2)
        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { saida ->
            saida.write(texto.toByteArray())
        } ?: error(getApplication<Application>().getString(R.string.nao_consegui_abrir_destino))
        backupAviso = "definições exportadas"
        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.backup_exportado_sem_segredos)))
    }

    /** Restaura um backup. A chave e o token **não** vêm no ficheiro, por decisão do projecto. */
    fun importarDefinicoes(uri: android.net.Uri) = run("importar definições") {
        val texto = getApplication<Application>().contentResolver.openInputStream(uri)
            ?.bufferedReader()?.use { it.readText() } ?: error(getApplication<Application>().getString(R.string.nao_consegui_ler_ficheiro))
        val n = Backup.importar(getApplication(), Prefs(getApplication()), texto)
        refreshCapabilities()
        mcpJsonText = controller.mcpServersJson
        kernelUrlText = controller.kernelUrl
        backupAviso = "$n definições restauradas"
        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.backup_restaurado, n)))
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
        mcpSaved = getApplication<Application>().resources.getQuantityString(R.plurals.mcp_gravados_n, arr.length(), arr.length())
        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.mcp_gravados, arr.length())))
    }

    fun saveKernelUrl() = run("url do kernel") {
        controller.kernelUrl = kernelUrlText.trim()
        transcript.add(ChatLine(
            "system",
            getApplication<Application>().getString(R.string.kernel_url_gravado, kernelUrlText.trim().ifBlank { getApplication<Application>().getString(R.string.kernel_url_vazio) }),
        ))
    }

    /** Arranca (ou liga-se ao) daemon do modo Shizuku. */
    private suspend fun startShizukuLocked() {
        shizukuState = getApplication<Application>().getString(R.string.shizuku_a_arrancar)
        controller.startDaemon()
        val token = controller.ensureToken()
        shizukuState = "daemon activo via Shizuku (" + token.take(6) + "…)"
    }

    /** Pede a permissão do Shizuku (abre o diálogo do sistema). */
    /**
     * Fica a verdadeiro quando falta a permissão do Shizuku: a UI vê isto e pede-a (o pedido tem
     * de vir de uma Activity, por isso não se faz aqui). Sem isto, quem carregasse em "Ligar
     * daemon" só via um erro e não sabia que havia um botão para autorizar.
     */
    var precisoPermissaoShizuku by mutableStateOf(false)

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
            .onFailure { daemonInfo = getApplication<Application>().getString(R.string.daemon_sem_resposta, it.message ?: "?") }
    }

    fun obtainToken(rotate: Boolean = false) = run("token") {
        val t = controller.ensureToken(rotate)
        tokenState = getApplication<Application>().getString(R.string.token_obtido_n, t.length).let { if (rotate) getApplication<Application>().getString(R.string.token_rodado, it) else it }
        tokenOk = true
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
        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.sessao_criada, sessionId?.take(8) ?: "?")))
        anotarAvisoMcp()
        loaded = true
        conversaAberta = true
        saveState()
    }

    fun send(text: String) = run("prompt") { enviarPrompt(text) }

    /**
     * Se o motor recusou a sessão por causa de um MCP que não responde e ela teve de correr sem
     * eles, di-lo no transcript — o dono tem de saber que os servidores que configurou não entraram
     * nesta sessão (ver `DaemonController.sessaoComMcp` e knowledge 134).
     */
    private fun anotarAvisoMcp() {
        controller.avisoMcp?.let {
            transcript.add(ChatLine("system", it))
            controller.limparAvisoMcp()
        }
    }

    /**
     * Fecha a **conversa aberta** e volta à lista. Não fecha a sessão no motor: é só navegação —
     * a sessão continua viva e a lista volta a mostrá-la com o realce de activa.
     */
    fun fecharConversa() {
        conversaAberta = false
    }

    /**
     * O corpo de um turno. Separado do `send` porque a **fila** tem de o esperar para só mandar a
     * tarefa seguinte quando a anterior acabar (o `run` não dá para esperar).
     */
    private suspend fun enviarPrompt(text: String) {
        val img = pendingImage
        if (text.isBlank() && img == null) return
        var sid = sessionId
        if (sid.isNullOrBlank()) {
            val res = controller.newSession()
            sid = res.optString("sessionId")
            sessionId = sid
            uso = null
            applyConfigOptions(res)
            anotarAvisoMcp()
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
            val msg = t.message ?: t::class.java.simpleName
            // O motor pode ter perdido a sessão (reinício, daemon novo, sessão fechada noutro
            // sítio). Sem isto, a app ficava a bater numa sessão que já não existe e **todos** os
            // turnos falhavam com «unknown session» — o utilizador não tem de saber o que é um
            // sessionId para voltar a trabalhar: abre-se sessão nova e repete-se o turno uma vez.
            if (msg.contains("unknown session", ignoreCase = true)) {
                transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.sessao_anterior_perdida)))
                val res = controller.newSession()
                val novo = res.optString("sessionId").takeIf { it.isNotBlank() }
                if (novo != null) {
                    sessionId = novo
                    applyConfigOptions(res)
                    uso = null
                    saveState()
                    try {
                        controller.prompt(novo, text, img?.bytes, img?.mime ?: "image/png")
                        val resposta = transcript.lastOrNull { it.role == "assistant" }?.text.orEmpty()
                        Notificacoes.fimDeTurno(
                            getApplication(),
                            getApplication<Application>().getString(R.string.notif_turno_titulo),
                            resposta.ifBlank { getApplication<Application>().getString(R.string.notif_sem_texto) },
                        )
                    } catch (t2: Throwable) {
                        transcript.add(ChatLine("error", getApplication<Application>().getString(R.string.turno_falhou_nova_sessao, explicaErroDoMotor(t2.message ?: t2::class.java.simpleName))))
                    }
                    return
                }
            }
            transcript.add(ChatLine("error", getApplication<Application>().getString(R.string.turno_falhou, explicaErroDoMotor(msg))))
            Notificacoes.fimDeTurno(
                getApplication(),
                getApplication<Application>().getString(R.string.notif_turno_titulo),
                getApplication<Application>().getString(R.string.notif_turno_falhou, msg),
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
        Uso.de(update)?.let { uso = it; return }
        when (update.optString("sessionUpdate")) {
            "agent_message_chunk" -> append("assistant", textOf(update))
            "agent_thought_chunk" -> append("thought", textOf(update))
            "tool_call" -> transcript.add(
                ChatLine(
                    "tool",
                    update.optString("title", "ferramenta"),
                    update.optString("toolCallId"),
                    estado = update.optString("status").ifBlank { "in_progress" },
                ),
            )
            "tool_call_update" -> {
                val id = update.optString("toolCallId")
                val idx = transcript.indexOfFirst { it.toolId == id }
                if (idx >= 0) {
                    val old = transcript[idx]
                    val status = update.optString("status", "")
                    // O estado passa a ser campo próprio (o cartão mostra-o no chip), em vez de
                    // texto colado ao nome da ferramenta.
                    transcript[idx] = old.copy(
                        estado = status.ifBlank { old.estado },
                        done = status == "completed" || status == "failed",
                    )
                }
            }
        }
    }

    /**
     * Nome de uma opção de configuração do motor, traduzido quando é uma das conhecidas.
     *
     * O protocolo traz «Model» e «Reasoning effort» em inglês, e essas duas apareciam assim na UI
     * portuguesa (visto no S10+ em 2026-10-15). Uma opção desconhecida fica com o nome que vier —
     * é melhor mostrar o que o motor mandou do que inventar.
     */
    private fun nomeDaOpcao(id: String, nomeDoMotor: String): String = when (id) {
        "model" -> getApplication<Application>().getString(R.string.config_modelo)
        "reasoning_effort" -> getApplication<Application>().getString(R.string.config_esforco)
        else -> nomeDoMotor
    }

    /** Guarda as `configOptions` que o motor devolve em session/new, resume e set_config_option. */
    /**
     * Nome de uma opção de autorização, traduzido pelo `kind` do protocolo.
     *
     * O motor manda «Allow once» e «Reject» em inglês, que apareciam assim numa app portuguesa
     * (visto no S10+ em 2026-10-15). Um `kind` desconhecido fica com o nome que vier.
     */
    private fun nomeDaOpcaoDePermissao(kind: String, nomeDoMotor: String): String {
        val s = getApplication<Application>()
        return when (kind) {
            "allow_once" -> s.getString(R.string.autorizacao_permitir_uma)
            "allow_always" -> s.getString(R.string.autorizacao_permitir_sempre)
            "reject_once", "reject_always" -> s.getString(R.string.autorizacao_recusar)
            else -> nomeDoMotor
        }
    }

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
                    // O motor manda os nomes das opções em inglês («Model», «Reasoning effort»).
                    // Conhecem-se os dois que existem e traduzem-se; o resto fica como vier.
                    name = nomeDaOpcao(o.optString("id"), o.optString("name", o.optString("id"))),
                    currentValue = o.optString("currentValue"),
                    choices = choices,
                ),
            )
        }
        configOptions = out
        saveState()
    }

    /**
     * A lista de conversas. A base é o **disco** (o daemon lê os ficheiros das sessões), e não o
     * `session/list` do motor: esse é instável — depois de um reinício chegou a listar 24 de 78
     * sessões, e nunca traz título nem data (knowledge 135). Sem daemon (modo embutido) cai-se no
     * que o motor souber, que é o melhor que há nesse modo.
     *
     * A ordem é a do WhatsApp: a conversa mexida mais recentemente em cima.
     */
    fun refreshSessions() = run("sessões") {
        val meta = runCatching { controller.sessionMeta() }.getOrNull()
        val doDisco = meta?.optJSONArray("sessions")
        val rows = ArrayList<SessionRow>()
        val titulos = HashMap<String, String>()

        if (doDisco != null && doDisco.length() > 0) {
            for (i in 0 until doDisco.length()) {
                val o = doDisco.optJSONObject(i) ?: continue
                val id = o.optString("sessionId").takeIf { it.isNotBlank() } ?: continue
                val doMotor = o.optString("title").takeIf { it.isNotBlank() }
                if (doMotor != null) titulos[id] = doMotor
                rows.add(
                    SessionRow(
                        id = id,
                        title = doMotor ?: sessionTitles[id] ?: id.take(8),
                        updatedAt = o.optString("updatedAt"),
                        previa = o.optString("preview").orEmpty(),
                    ),
                )
            }
        } else {
            val res = runCatching { controller.listSessions(controller.workspace) }.getOrNull()
            val arr = res?.optJSONArray("sessions")
            for (i in 0 until (arr?.length() ?: 0)) {
                val o = arr!!.optJSONObject(i) ?: continue
                val id = o.optString("sessionId")
                rows.add(
                    SessionRow(
                        id = id,
                        title = o.optString("title").takeIf { it.isNotBlank() }
                            ?: sessionTitles[id]
                            ?: id.take(8),
                        updatedAt = o.optString("updatedAt"),
                    ),
                )
            }
        }

        // O título do motor entra no mapa da app: é ele que o cabeçalho da conversa mostra, e
        // sobrevive ao arranque (o do motor é curto e legível; o derivado do prompt é maior).
        if (titulos.isNotEmpty()) sessionTitles = sessionTitles + titulos
        sessions = rows.sortedByDescending { it.updatedAt }
    }

    /** Retoma uma sessão; o transcript local é substituído pelo da sessão escolhida. */
    fun resumeSession(id: String) = run("retomar") {
        val jaEraEsta = id == sessionId
        // «session is already active» **não é falha**: o motor tem um cliente ACP de cada vez e a
        // app retoma a sessão dela ao ligar-se, por isso tocar na conversa que já está em uso
        // caía aqui. Sem isto, abrir a conversa activa dava erro e não abria nada (visto no S10+
        // em 2026-10-15). E, sendo a mesma, o transcript em memória já é o dela: não se apaga.
        val res = try {
            controller.resumeSession(id, controller.workspace)
        } catch (t: Throwable) {
            if (!isSessionAlreadyActive(t)) throw t
            null
        }
        sessionId = id
        res?.let { applyConfigOptions(it) }
        if (!jaEraEsta) {
            transcript.clear()
            transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.sessao_retomada, id.take(8))))
        }
        anotarAvisoMcp()
        conversaAberta = true
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
            conversaAberta = false
            // Sem isto, o arranque seguinte (ou a troca de modo) voltava a retomar a sessão
            // que acabámos de fechar — verificado em teste.
            controller.forgetLastSession()
            transcript.clear()
            transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.sessao_fechada_nova, id.take(8))))
        } else {
            transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.sessao_fechada, id.take(8))))
        }
        saveState()
        refreshSessions()
    }

    /**
     * Apaga uma conversa (toque longo na lista). Se for a que está aberta, fecha-a primeiro: o motor
     * tem de largar o ficheiro antes de ele desaparecer.
     */
    fun apagarSessao(id: String) = run("apagar conversa") {
        if (id == sessionId) {
            runCatching { controller.closeSession(id) }
            sessionId = null
            conversaAberta = false
            controller.forgetLastSession()
            transcript.clear()
        }
        controller.deleteSession(id)
        // A lista deixa de a ter: some já, sem esperar por uma ida ao disco.
        sessions = sessions.filterNot { it.id == id }
        sessionTitles = sessionTitles - id
        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.sessao_apagada, id.take(8))))
        saveState()
        refreshSessions()
    }

    /** Muda modelo/esforço e o motor devolve as opções actualizadas. */
    fun chooseOption(configId: String, value: String) = run("opção") {
        val sid = sessionId ?: error("sem sessão activa")
        val res = controller.setConfigOption(sid, configId, value)
        applyConfigOptions(res)
        configPicker = null
        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.opcao_definida, configId, value.take(60))))
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
            embedTest = getApplication<Application>().getString(R.string.embed_libnode_ausente)
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
        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.areas_actualizadas)))
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
                    transcript.add(ChatLine("error", getApplication<Application>().getString(R.string.capacidade_erro, capability, result.exceptionOrNull()?.message ?: "?")))
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
                // Os nomes vêm do motor em inglês («Allow once», «Reject»). Traduzem-se pelo
                // `kind`, que é estável; um `kind` desconhecido fica com o nome que vier.
                list.add(
                    o.optString("optionId") to nomeDaOpcaoDePermissao(
                        o.optString("kind"),
                        o.optString("name", o.optString("kind")),
                    ),
                )
            }
        }
        // O que o agente quer correr. O motor manda `toolCall.toolCallId` e (desde o patch à
        // `dsh-acp`) o `title` e o `rawInput`; no caso da guarda de caminhos o `rawInput` traz o
        // comando e os caminhos a sério. Sem isto o painel dizia «O agente quer correr:» e nada
        // por baixo -- decidia-se às cegas (visto no S10+ em 2026-10-15).
        val raw = tool?.optJSONObject("rawInput")
        val comando = raw?.optString("command").orEmpty().trim()
        val naAreaPermitida = raw?.optJSONArray("paths")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } ?: emptyList()
        // `optString` devolve "" (não null) quando a chave não existe: o `?:` antigo nunca
        // disparava e o título ficava vazio. Daí o `ifBlank`.
        val motorTitulo = tool?.optString("title").orEmpty().trim()
        val titulo = comando.ifBlank { motorTitulo }.ifBlank {
            getApplication<Application>().getString(R.string.autorizacao_sem_detalhe)
        }
        val detalhe = when {
            naAreaPermitida.isNotEmpty() -> getApplication<Application>()
                .getString(R.string.autorizacao_fora_da_area, naAreaPermitida.joinToString(", "))
            raw != null && raw.length() > 0 -> raw.toString().take(600)
            else -> ""
        }
        Prefs(getApplication()).auditAdd(
            java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) +
                "  aprovação  " + titulo.ifBlank { "?" } + "  " + naAreaPermitida.joinToString(", "),
        )
        approval = PendingApproval(
            requestId = obj.optInt("id"),
            title = titulo,
            detail = detalhe,
            options = list,
        )
        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.autorizacao_pedido, titulo.ifBlank { "?" })))
        // Com a app em segundo plano ninguém vê o diálogo: o turno fica à espera em silêncio.
        val app = getApplication<Application>()
        Notificacoes.pedidoDeAutorizacao(
            app,
            getApplication<Application>().getString(R.string.notif_autorizacao_titulo),
            getApplication<Application>().getString(R.string.notif_autorizacao_texto, (tool?.optString("title") ?: "?")),
        )
    }

    /** Resposta ao pedido de aprovação. `optionId` nulo = cancelar o turno. */
    fun answerApproval(optionId: String?) = run("aprovação") {
        val req = approval ?: return@run
        approval = null
        Notificacoes.cancelarPedidoDeAutorizacao(getApplication())
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
        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.turno_cancelado)))
    }

    private fun textOf(update: JSONObject): String =
        update.optJSONObject("content")?.optString("text", "") ?: ""

    /**
     * O motor recusa `session/resume` quando a sessão já está activa ("Invalid params: session
     * is already active: …"). Não é uma falha: é o estado que queríamos atingir. Tratar isto
     * como erro enchia o log do motor de resumes recusados sem nada de errado acontecer.
     */
    /**
     * Traduz o erro do motor para algo que se possa ler num telefone.
     *
     * O motor responde em inglês técnico e fala de coisas que não existem nesta app — por exemplo
     * «the web Models page writes it» e «the launching environment». Quem está a segurar o telefone
     * não tem como saber o que isso é nem onde fica (visto no S10+ em 2026-10-15). Os casos
     * conhecidos ganham uma frase da app; o resto fica contido, sem dominar o ecrã.
     */
    private fun explicaErroDoMotor(msg: String): String {
        val m = msg.lowercase()
        return when {
            m.contains("no api key") || m.contains("api key") && m.contains("store") ->
                getApplication<Application>().getString(R.string.erro_sem_chave)
            m.contains("network is unreachable") || m.contains("unknownhost") ||
                m.contains("transport failed") || m.contains("failed to connect") ->
                getApplication<Application>().getString(R.string.erro_sem_rede)
            m.contains("401") || m.contains("unauthorized") || m.contains("invalid api key") ->
                getApplication<Application>().getString(R.string.erro_chave_recusada)
            // Falhas de transporte: são a app a falar consigo própria, mas aparecem na faixa.
            m.contains("ligação ao motor caiu") || m.contains("sem ligação ao motor") ->
                getApplication<Application>().getString(R.string.erro_ligacao_caiu)
            m.contains("motor não está a correr") || m.contains("motor não respondeu") ->
                getApplication<Application>().getString(R.string.erro_motor_em_baixo)
            else -> getApplication<Application>().getString(R.string.erro_do_motor, msg.take(300))
        }
    }

    private fun isSessionAlreadyActive(t: Throwable): Boolean =
        t.message?.contains("already active", ignoreCase = true) == true

    /**
     * A sessão que a app guardou já não existe no motor.
     *
     * Distingue-se de uma queda de ligação a meio (essa é transitória e vale a pena repetir) e de
     * «já está activa» (essa é sucesso). Sem esta distinção, o laço de reconexão repetia o mesmo id
     * morto para sempre.
     */
    private fun sessaoJaNaoExiste(t: Throwable): Boolean {
        val m = t.message.orEmpty().lowercase()
        if (m.contains("already active")) return false
        if (m.contains("ligação ao motor caiu") || m.contains("sem ligação")) return false
        return m.contains("unknown session") ||
            m.contains("session not found") ||
            m.contains("sessão não existe") ||
            (m.contains("session") && m.contains("invalid"))
    }

    /** Chips só com o que estava guardado (sem escolhas) — usado quando o motor recusa o resume. */
    private fun restoreChips(model: String?, effort: String?) {
        val out = ArrayList<ConfigOption>()
        if (!model.isNullOrBlank()) {
            // O valor do modelo é uma tupla JSON (["provider","modelo"]) — mostrar só o modelo.
            val label = runCatching {
                val a = JSONArray(model)
                a.optString(a.length() - 1)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: model
            out.add(ConfigOption("model", getApplication<Application>().getString(R.string.config_modelo), model, listOf(ConfigChoice(model, label))))
        }
        if (!effort.isNullOrBlank()) {
            val label = effort.replaceFirstChar { it.uppercase() }
            out.add(ConfigOption("reasoning_effort", getApplication<Application>().getString(R.string.config_esforco), effort, listOf(ConfigChoice(effort, label))))
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

    init {
        // A fila é lida do ficheiro ao arrancar: sem isto, `tarefas` ficava vazio e a fila parecia
        // perdida depois de fechar a app — que é exactamente o que ela existe para evitar.
        tarefas = fila.todas
        // Vigia a acessibilidade a cada 5 s: se cair a meio da sessão, avisa na conversa (e por
        // notificação, se a app não estiver à frente). Sem isto o agente só o descobre ao falhar.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)
                val agora = CachaloteAccessibilityService.isEnabled()
                accessibilityOn = agora
                if (vigia.observar(agora) != null) {
                    val app = getApplication<Application>()
                    transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.acessibilidade_caiu)))
                    // Persistir: o aviso tem de sobreviver a fechar e reabrir a app (foi por não
                    // gravar que ele não aparecia na leitura do instantâneo).
                    saveState()
                    Notificacoes.acessibilidadeDesligada(
                        app,
                        getApplication<Application>().getString(R.string.notif_acessibilidade_titulo),
                        getApplication<Application>().getString(R.string.notif_acessibilidade_texto),
                    )
                } else if (agora && vigia.ligada) {
                    // Voltou a ligar-se depois de ter caído: diz-se uma vez, sem alarme.
                    if (vigia.quedas > 0 && transcript.lastOrNull()?.text == getApplication<Application>().getString(R.string.acessibilidade_caiu)) {
                        transcript.add(ChatLine("system", getApplication<Application>().getString(R.string.acessibilidade_voltou)))
                        saveState()
                    }
                }
            }
        }
    }

    private fun run(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = ""
            try {
                block()
            } catch (t: Throwable) {
                // A faixa de erro é lida por uma pessoa: passa pelo mesmo tradutor dos turnos, para
                // não mostrar o inglês do motor nem o jargão de transporte em bruto.
                error = "$label: " + explicaErroDoMotor(t.message ?: t::class.java.simpleName)
                // Falta a permissão do Shizuku? A UI pede-a sozinha (ver `precisoPermissaoShizuku`).
                if (error.contains("permissão do Shizuku")) precisoPermissaoShizuku = true
            } finally {
                busy = false
            }
        }
    }
}
