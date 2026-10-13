package dev.ricardopimentel.cachalote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Guarda o transcript e o estado da sessão no armazenamento privado da app, para a
 * conversa sobreviver a fechar/abrir a app. Não guarda nada de secreto (a chave vive
 * no daemon).
 */
class SessionStore(context: Context) {

    private val file = File(context.filesDir, "sessao.json")

    data class Snapshot(
        val sessionId: String?,
        val cwd: String?,
        val model: String?,
        val effort: String?,
        val lines: List<ChatLine>,
    )

    fun save(sessionId: String?, cwd: String?, model: String?, effort: String?, lines: List<ChatLine>) {
        val arr = JSONArray()
        lines.takeLast(300).forEach { l ->
            arr.put(
                JSONObject()
                    .put("role", l.role)
                    .put("text", l.text)
                    .put("toolId", l.toolId ?: JSONObject.NULL)
                    .put("done", l.done),
            )
        }
        val obj = JSONObject()
            .put("sessionId", sessionId ?: JSONObject.NULL)
            .put("cwd", cwd ?: JSONObject.NULL)
            .put("model", model ?: JSONObject.NULL)
            .put("effort", effort ?: JSONObject.NULL)
            .put("lines", arr)
        runCatching { file.writeText(obj.toString()) }
    }

    fun load(): Snapshot? {
        if (!file.isFile) return null
        return runCatching {
            val obj = JSONObject(file.readText())
            val lines = ArrayList<ChatLine>()
            val arr = obj.optJSONArray("lines") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                lines.add(
                    ChatLine(
                        role = o.optString("role", "system"),
                        text = o.optString("text", ""),
                        toolId = o.optString("toolId").takeIf { it.isNotBlank() && it != "null" },
                        done = o.optBoolean("done", false),
                    ),
                )
            }
            Snapshot(
                sessionId = obj.optString("sessionId").takeIf { it.isNotBlank() && it != "null" },
                cwd = obj.optString("cwd").takeIf { it.isNotBlank() && it != "null" },
                model = obj.optString("model").takeIf { it.isNotBlank() && it != "null" },
                effort = obj.optString("effort").takeIf { it.isNotBlank() && it != "null" },
                lines = lines,
            )
        }.getOrNull()
    }

    fun clear() = runCatching { file.delete() }.let { }
}
