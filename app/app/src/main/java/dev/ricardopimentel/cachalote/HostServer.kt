package dev.ricardopimentel.cachalote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Servidor de capacidades **dentro da app**, para o modo sem root.
 *
 * No modo daemon é o daemon que faz a ponte `host.*` (plugin do kernel → daemon → app). No
 * modo embutido não há daemon, portanto a app levanta este servidor em 127.0.0.1 e o plugin do
 * kernel fala directamente com ela — o mesmo protocolo, o mesmo token, as mesmas capacidades
 * e o mesmo registo de auditoria.
 *
 * Autenticação: o primeiro frame tem de ser `cachalote.auth` com o token local (o mesmo que
 * vive em `dsh-home/app.token`, 0600). Só depois se aceitam `cachalote.host.call`.
 */
class HostServer(
    private val ctx: Context,
    private val caps: HostCapabilities,
    private val port: Int,
) {
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val running: Boolean get() = server?.isClosed == false

    fun start() {
        if (running) return
        // IPv4 explícito: `getLoopbackAddress()` pode dar ::1, e o plugin liga a 127.0.0.1 —
        // foi exactamente o que deu `ECONNREFUSED 127.0.0.1:39732` no primeiro teste.
        val s = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        scope.launch {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                scope.launch { serve(socket) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private suspend fun serve(socket: Socket) {
        val token = HostToken.ensure(ctx)
        var authed = false
        try {
            BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)).use { out ->
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isBlank()) continue
                        val msg = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        val id = msg.opt("id")

                        fun reply(result: JSONObject) {
                            out.write(
                                JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString(),
                            )
                            out.write("\n")
                            out.flush()
                        }

                        fun fail(message: String) {
                            out.write(
                                JSONObject().put("jsonrpc", "2.0").put("id", id)
                                    .put("error", JSONObject().put("code", -32000).put("message", message)).toString(),
                            )
                            out.write("\n")
                            out.flush()
                        }

                        when {
                            msg.optString("method") == "cachalote.auth" -> {
                                val given = msg.optJSONObject("params")?.optString("token") ?: ""
                                if (given == token) {
                                    authed = true
                                    reply(JSONObject().put("ok", true).put("role", "host-embedded"))
                                } else {
                                    fail("token inválido")
                                    break
                                }
                            }
                            !authed -> {
                                fail("falta autenticação (cachalote.auth)")
                                break
                            }
                            msg.optString("method") == "cachalote.host.call" -> {
                                val params = msg.optJSONObject("params") ?: JSONObject()
                                val capability = params.optString("capability")
                                val args = params.optJSONObject("params") ?: JSONObject()
                                val result = runCatching { caps.execute(capability, args) }
                                result.onSuccess { reply(it) }
                                    .onFailure { fail(it.message ?: "falhou") }
                            }
                            else -> fail("método desconhecido: " + msg.optString("method"))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // ligação caiu: nada a fazer
        } finally {
            runCatching { socket.close() }
        }
    }
}
