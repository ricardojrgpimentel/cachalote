package dev.ricardopimentel.cachalote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cliente JSON-RPC do motor: um objecto JSON por linha ('\n').
 *
 * Dois transportes, o mesmo protocolo:
 *  · **TCP 127.0.0.1 + token** — contra o daemon (modo root/Shizuku). O primeiro frame é
 *    `cachalote.auth`; as chamadas `cachalote.*` são operações do daemon.
 *  · **stdio** — contra um motor que é filho da própria app (modo sem root). Não há
 *    autenticação (é o nosso próprio processo filho) e os pedidos `host.call` chegam pelo
 *    servidor de capacidades que a app levanta.
 *
 * A app não pode usar o socket AF_UNIX em /data/adb (o SELinux rotula-o `adb_data_file` e não
 * há regra para `untrusted_app`) — daí o TCP no modo daemon. Ver D11 do PLANO.md.
 */
class CachaloteClient {

    /** Par de streams que faz o transporte (socket TCP ou stdio de um processo filho). */
    data class Streams(
        val input: InputStream,
        val output: OutputStream,
        val close: () -> Unit = {},
    )

    /**
     * Como voltar a ligar (o controlador sabe se é daemon, Shizuku ou embutido). O cliente chama
     * isto quando um pedido chega sem ligação — é o que evita o "a ligação ao motor caiu"
     * quando o motor está a reiniciar (gravar a chave, por exemplo).
     */
    var reconnector: (suspend () -> Unit)? = null

    private var streams: Streams? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val ids = AtomicInteger(1)
    // Os pedidos pendentes são guardados por `pendingLock`. O mapa concorrente que aqui estava
    // dava duas avarias medidas no S10+ (2026-10-14): (a) `pending.values.toList()` explodia com
    // NoSuchElementException — com uma entrada só, o atalho de tamanho da stdlib chama
    // `iterator().next()` e o mapa já podia estar vazio (o `readLoop` morria no finally);
    // (b) um pedido registado entre o "tirar cópia" e o "limpar" ficava sem ninguém a
    // completá-lo, e o turno acabava em «Timed out waiting for 15000 ms».
    private val pendingLock = Any()
    private val pending = HashMap<Int, CompletableDeferred<JSONObject>>()

    private val _events = MutableSharedFlow<JSONObject>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JSONObject> = _events

    /** Pedidos que vêm DO motor (aprovações do ACP, capacidades `host.call`). */
    private val _requests = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val requests: SharedFlow<JSONObject> = _requests

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Liga a um handshake já aberto (stdio do motor embutido). Não envia autenticação. */
    suspend fun connect(streams: Streams): JSONObject = withContext(Dispatchers.IO) {
        disconnect()
        this@CachaloteClient.streams = streams
        _connected.value = true
        readerJob = scope.launch { readLoop(streams.input) }
        JSONObject()
    }

    /** Liga ao daemon por TCP e autentica com o token do canal. */
    suspend fun connectTcp(port: Int, token: String, timeoutMs: Int = 15_000): JSONObject =
        withContext(Dispatchers.IO) {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            connect(Streams(s.getInputStream(), s.getOutputStream(), close = { runCatching { s.close() } }))
            callForResult("cachalote.auth", JSONObject().put("token", token), timeoutMs.toLong())
        }

    suspend fun call(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 30_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        // ⚠ Todo o I/O de socket/pipe tem de sair do thread principal: a escrever no Main, o
        // Android lança NetworkOnMainThreadException (foi apanhado em teste).
        val id = ids.getAndIncrement()
        val slot = CompletableDeferred<JSONObject>()
        synchronized(pendingLock) { pending[id] = slot }
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        try {
            // A ligação (ou o motor) pode estar a reiniciar: dar-lhe uma hipótese antes de
            // desistir. Só aqui, ANTES de enviar — um pedido já enviado nunca é repetido (isso
            // duplicaria turnos).
            if (streams == null) {
                runCatching { reconnector?.invoke() }
            }
            writeLock.withLock {
                val out = streams?.output ?: error("sem ligação ao motor")
                val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
                w.write(frame.toString())
                w.write("\n")
                w.flush()
            }
            android.util.Log.d("cachalote-rpc", "→ $method (id=$id)")
            withTimeout(timeoutMs) { slot.await() }
        } finally {
            synchronized(pendingLock) { pending.remove(id) }
        }
    }

    /** Responde a um pedido do motor (o `id` vem no pedido; pode ser string). */
    suspend fun respond(id: Any, result: JSONObject) = withContext(Dispatchers.IO) {
        sendFrame(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
    }

    /** Envia uma notificação ao motor (ex.: `session/cancel`). */
    suspend fun notify(method: String, params: JSONObject = JSONObject()) = withContext(Dispatchers.IO) {
        sendFrame(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))
    }

    private suspend fun sendFrame(frame: JSONObject) = writeLock.withLock {
        val out = streams?.output ?: error("sem ligação ao motor")
        val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
        w.write(frame.toString())
        w.write("\n")
        w.flush()
    }

    /** Como [call], mas devolve o `result` e transforma `error` em excepção. */
    suspend fun callForResult(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 30_000,
    ): JSONObject = unwrap(call(method, params, timeoutMs))

    fun unwrap(response: JSONObject): JSONObject {
        if (response.has("error") && !response.isNull("error")) {
            val e = response.optJSONObject("error")
            // O `details` é onde o motor diz a causa a sério («mcp-client(stitch): initial
            // connection …»). Só com o `message` ficava-se em «Internal error», que não diz nada
            // a ninguém — e foi o que escondeu o MCP em baixo durante uma tarde inteira
            // (knowledge 134).
            val detalhe = e?.optJSONObject("data")?.optString("details").orEmpty()
            val mensagem = e?.optString("message") ?: "erro do motor"
            throw IllegalStateException(if (detalhe.isNotBlank()) "$mensagem — $detalhe" else mensagem)
        }
        return response.optJSONObject("result") ?: JSONObject()
    }

    private fun readLoop(input: InputStream) {
        try {
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val hasId = obj.has("id") && !obj.isNull("id")
                    when {
                        // Um frame com id E method é um PEDIDO do motor, não uma resposta.
                        // Tratá-lo como resposta descartava os pedidos de aprovação em
                        // silêncio e o turno ficava pendurado (ver knowledge 50).
                        hasId && obj.has("method") -> {
                            android.util.Log.d("cachalote-rpc", "← pedido " + obj.optString("method"))
                            _requests.tryEmit(obj)
                        }
                        hasId -> {
                            android.util.Log.d("cachalote-rpc", "← resposta id=" + obj.opt("id") + " erro=" + obj.has("error"))
                            val espera = synchronized(pendingLock) { pending.remove(obj.optInt("id", -1)) }
                            espera?.complete(obj)
                        }
                        else -> {
                            android.util.Log.d("cachalote-rpc", "← nota " + obj.optString("method"))
                            _events.tryEmit(obj)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // queda da ligação: tratada no finally
        } finally {
            _connected.value = false
            // Cópia e limpeza debaixo do mesmo lock: nenhum pedido fica a meio caminho.
            val stragglers = synchronized(pendingLock) {
                val copia = pending.values.toList()
                pending.clear()
                copia
            }
            stragglers.forEach { it.completeExceptionally(IllegalStateException("a ligação ao motor caiu")) }
        }
    }

    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        runCatching { streams?.close?.invoke() }
        streams = null
        _connected.value = false
    }
}
