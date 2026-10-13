package dev.ricardopimentel.cachalote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executa as capacidades Android que o agente pede (canal reverso `host.call`).
 *
 * Duas regras:
 *  1. **Allow-list**: cada capacidade (excepto `status`) tem de estar autorizada nas
 *     preferências da app — é o separador "Capacidades". O agente pode pedir, mas quem
 *     decide é o utilizador.
 *  2. **Registo**: cada pedido fica no histórico (capacidade + resumo), visível na app.
 *
 * Isto existe porque no Android não há sandbox a travar o agente (ver D10 no plano):
 * a barreira tem de ser nossa.
 */
class HostCapabilities(private val context: Context, private val prefs: Prefs) {

    companion object {
        const val CHANNEL_ID = "cachalote_agent"
        val CAPABILITIES = listOf("notify", "screen", "tap", "type", "key", "screenshot")
    }

    suspend fun execute(capability: String, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        // `audit` é só registo (a guarda do bash escreve aqui): sempre permitida.
        if (capability == "audit") {
            record("audit", params)
            return@withContext JSONObject().put("ok", true)
        }
        if (capability != "status" && capability !in CAPABILITIES) {
            throw IllegalArgumentException("capacidade desconhecida: $capability")
        }
        if (capability != "status" && !prefs.isCapabilityAllowed(capability)) {
            throw IllegalStateException(
                "capacidade '$capability' não está autorizada na app (separador Capacidades)",
            )
        }
        record(capability, params)
        when (capability) {
            "status" -> status()
            "notify" -> notify(params)
            "screen" -> screen(params)
            "tap" -> tap(params)
            "type" -> type(params)
            "key" -> key(params)
            "screenshot" -> screenshot(params)
            else -> throw IllegalArgumentException("capacidade desconhecida: $capability")
        }
    }

    // ---------------------------------------------------------------- capacidades

    private fun status(): JSONObject {
        val svc = CachaloteAccessibilityService.instance
        val allowed = JSONArray()
        CAPABILITIES.forEach { if (prefs.isCapabilityAllowed(it)) allowed.put(it) }
        return JSONObject()
            .put("app", "cachalote")
            .put("accessibility", svc != null)
            .put("notifications", notificationsGranted())
            .put("allowed", allowed)
            .put("foregroundPackage", svc?.foregroundPackage())
            .put(
                "hint",
                if (svc == null) "liga o serviço de acessibilidade do Cachalote (Definições → Acessibilidade)"
                else "pronto",
            )
    }

    private fun notify(params: JSONObject): JSONObject {
        if (!notificationsGranted()) throw IllegalStateException("a app não tem permissão de notificações")
        val title = params.optString("title", "Cachalote")
        val text = params.optString("text", "")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agente Cachalote", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Avisos do agente (tarefas concluídas, atenção necessária)" },
            )
        }
        val notif = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), notif)
        return JSONObject().put("ok", true).put("posted", title)
    }

    private fun screen(params: JSONObject): JSONObject {
        val svc = requireService()
        val nodes = svc.describe(params.optString("filter").takeIf { it.isNotBlank() }, params.optInt("max", 60))
        return JSONObject()
            .put("app", svc.foregroundPackage())
            .put("count", nodes.size)
            .put("elements", JSONArray(nodes.map { JSONObject(it) }))
    }

    private fun tap(params: JSONObject): JSONObject {
        val svc = requireService()
        val text = params.optString("text").takeIf { it.isNotBlank() }
        val desc = params.optString("description").takeIf { it.isNotBlank() }
        if (text != null || desc != null) {
            val node = svc.findNode(text, desc)
                ?: throw IllegalStateException("não encontrei nenhum elemento com esse texto/descrição")
            val clicked = svc.clickNode(node)
            if (!clicked) throw IllegalStateException("encontrei o elemento mas ele não aceita cliques")
            return JSONObject().put("ok", true).put("clicked", text ?: desc)
        }
        val x = params.optDouble("x", Double.NaN)
        val y = params.optDouble("y", Double.NaN)
        if (x.isNaN() || y.isNaN()) throw IllegalArgumentException("indica text, description ou x/y")
        val latch = java.util.concurrent.CountDownLatch(1)
        var okFlag = false
        svc.tap(x.toFloat(), y.toFloat()) { okFlag = it; latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        if (!okFlag) throw IllegalStateException("o gesto de toque foi cancelado")
        return JSONObject().put("ok", true).put("tappedAt", "$x,$y")
    }

    private fun type(params: JSONObject): JSONObject {
        val svc = requireService()
        val text = params.optString("text")
        if (text.isEmpty()) throw IllegalArgumentException("texto vazio")
        val ok = svc.typeText(text, params.optBoolean("submit", false))
        if (!ok) throw IllegalStateException("não encontrei nenhum campo de texto para escrever")
        return JSONObject().put("ok", true).put("typed", text.length)
    }

    private fun key(params: JSONObject): JSONObject {
        val svc = requireService()
        val key = params.optString("key")
        val ok = svc.pressKey(key)
        if (!ok) throw IllegalStateException("não consegui executar a tecla '$key'")
        return JSONObject().put("ok", true).put("key", key)
    }

    private fun screenshot(params: JSONObject): JSONObject {
        val svc = requireService()
        return JSONObject(svc.screenshot())
    }

    // ---------------------------------------------------------------- auxiliares

    private fun requireService(): CachaloteAccessibilityService =
        CachaloteAccessibilityService.instance
            ?: throw IllegalStateException(
                "o serviço de acessibilidade do Cachalote não está ligado (Definições → Acessibilidade)",
            )

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun record(capability: String, params: JSONObject) {
        val summary = params.toString().take(160)
        prefs.auditAdd("${System.currentTimeMillis()}  $capability  $summary")
    }
}
