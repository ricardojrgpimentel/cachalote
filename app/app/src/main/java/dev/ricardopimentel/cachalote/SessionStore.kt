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
class SessionStore(private val context: Context) {

    /**
     * Um ficheiro por **modo de motor**. A sessão e o histórico pertencem ao motor onde
     * nasceram: o `cwd` da sessão é diferente em cada modo (root: /data/adb/cachalote/home/ws;
     * embutido: files/dsh-home/ws) e retomar uma sessão no motor errado é recusado pelo ACP
     * ("session/resume cwd mismatch"). Era isto que dava "a ligação ao motor caiu" ao trocar de
     * modo. Ver knowledge 93.
     */
    private fun arquivoDoModo(modo: String) = File(context.filesDir, "sessao-$modo.json")
    private val modoAtual: String get() = context.getSharedPreferences("cachalote", Context.MODE_PRIVATE)
        .getString("mode", "daemon")!!

    data class Snapshot(
        val sessionId: String?,
        val cwd: String?,
        val model: String?,
        val effort: String?,
        val lines: List<ChatLine>,
        /** Título por sessão (o do motor, se vier; senão o que derivamos do primeiro prompt). */
        val titles: Map<String, String> = emptyMap(),
    )

    fun save(
        sessionId: String?,
        cwd: String?,
        model: String?,
        effort: String?,
        lines: List<ChatLine>,
        titles: Map<String, String> = emptyMap(),
    ) {
        val file = arquivoDoModo(modoAtual)
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
            .put("titles", JSONObject(titles as Map<*, *>))
        runCatching { file.writeText(obj.toString()) }
    }

    fun load(): Snapshot? {
        val file = arquivoDoModo(modoAtual)
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
            val titles = LinkedHashMap<String, String>()
            obj.optJSONObject("titles")?.let { t ->
                t.keys().forEach { k -> titles[k] = t.optString(k) }
            }
            Snapshot(
                sessionId = obj.optString("sessionId").takeIf { it.isNotBlank() && it != "null" },
                cwd = obj.optString("cwd").takeIf { it.isNotBlank() && it != "null" },
                model = obj.optString("model").takeIf { it.isNotBlank() && it != "null" },
                effort = obj.optString("effort").takeIf { it.isNotBlank() && it != "null" },
                lines = lines,
                titles = titles,
            )
        }.getOrNull()
    }

    fun clear() = runCatching { arquivoDoModo(modoAtual).delete() }.let { }
}
