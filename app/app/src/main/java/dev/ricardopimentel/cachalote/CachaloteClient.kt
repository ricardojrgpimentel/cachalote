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
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cliente do daemon: socket TCP em 127.0.0.1, JSON-RPC 2.0, um objecto JSON por
 * linha ('\n') — exactamente o que a ponte do daemon fala.
 *
 * A app não pode usar o socket AF_UNIX em /data/adb (o SELinux rotula-o
 * `adb_data_file` e não há regra que deixe `untrusted_app` lá chegar), daí o
 * loopback com token (ver D11 do PLANO.md).
 *
 * O primeiro frame é sempre `cachalote.auth`; a partir daí:
 *  · `cachalote.*` são operações do daemon (estado, credenciais, restart);
 *  · tudo o resto é Agent Client Protocol e vai ao motor.
 */
class CachaloteClient {

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    private val _events = MutableSharedFlow<JSONObject>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JSONObject> = _events

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Pedidos que vêm DO motor (ex.: `session/request_permission`): o cliente tem de responder. */
    private val _requests = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val requests: SharedFlow<JSONObject> = _requests

    suspend fun connect(port: Int, token: String, timeoutMs: Int = 15_000): JSONObject =
        withContext(Dispatchers.IO) {
            disconnect()
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            socket = s
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            _connected.value = true
            readerJob = scope.launch { readLoop(s) }
            callForResult("cachalote.auth", JSONObject().put("token", token), timeoutMs.toLong())
        }

    suspend fun call(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = 30_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        // ⚠ Todo o I/O de socket tem de sair do thread principal: a escrever no
        // Main, o Android lança NetworkOnMainThreadException (foi apanhado em teste).
        val id = ids.getAndIncrement()
        val slot = CompletableDeferred<JSONObject>()
        pending[id] = slot
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        try {
            writeLock.withLock {
                val w = writer ?: error("sem ligação ao daemon")
                w.write(frame.toString())
                w.write("\n")
                w.flush()
            }
            withTimeout(timeoutMs) { slot.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Responde a um pedido do motor (o `id` vem no pedido). */
    suspend fun respond(id: Any, result: JSONObject) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val w = writer ?: error("sem ligação ao daemon")
            w.write(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
            w.write("\n")
            w.flush()
        }
    }

    /** Envia uma notificação ao motor (ex.: `session/cancel`). */
    suspend fun notify(method: String, params: JSONObject = JSONObject()) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val w = writer ?: error("sem ligação ao daemon")
            w.write(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params).toString())
            w.write("\n")
            w.flush()
        }
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
            throw IllegalStateException(e?.optString("message") ?: "erro do daemon")
        }
        return response.optJSONObject("result") ?: JSONObject()
    }

    private fun readLoop(s: Socket) {
        try {
            BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val hasId = obj.has("id") && !obj.isNull("id")
                    when {
                        // ⚠ Um frame com id E method é um PEDIDO do motor, não uma resposta.
                        // Tratá-lo como resposta (como fazíamos) descartava os pedidos de
                        // aprovação em silêncio e o turno ficava pendurado.
                        hasId && obj.has("method") -> _requests.tryEmit(obj)
                        hasId -> pending.remove(obj.optInt("id", -1))?.complete(obj)
                        else -> _events.tryEmit(obj)
                    }
                }
            }
        } catch (_: Throwable) {
            // queda da ligação: tratada no finally
        } finally {
            _connected.value = false
            val stragglers = pending.values.toList()
            pending.clear()
            stragglers.forEach { it.completeExceptionally(IllegalStateException("a ligação ao daemon caiu")) }
        }
    }

    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        runCatching { socket?.close() }
        socket = null
        writer = null
        _connected.value = false
    }
}
