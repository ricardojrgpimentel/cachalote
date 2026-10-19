package dev.ricardopimentel.cachalote

import org.json.JSONObject

/** O que o daemon devolveu para uma conversa antiga, já pronto a entrar no transcript. */
data class HistoricoLido(
    val linhas: List<ChatLine>,
    val truncado: Boolean,
    val total: Int,
)

/**
 * O histórico de uma conversa antiga, traduzido para linhas do transcript.
 *
 * O daemon lê o ficheiro da sessão e devolve os eventos na mesma forma em que eles chegaram em
 * direto (`user`, `thought`, `assistant`, `tool` com `toolId`/`estado`/`done`) — ver
 * `daemon/session-meta.cjs`. Isto é só a travessia do JSON, para a lógica ficar testável sem
 * aparelho (o mesmo padrão do [Uso]).
 */
object Historico {
    fun ler(res: JSONObject): HistoricoLido {
        val arr = res.optJSONArray("events") ?: return HistoricoLido(emptyList(), false, 0)
        val linhas = ArrayList<ChatLine>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role").takeIf { it.isNotBlank() } ?: continue
            val texto = o.optString("text")
            if (texto.isBlank()) continue
            linhas.add(
                ChatLine(
                    role = role,
                    text = texto,
                    // O `org.json` devolve a string "null" para um JSONObject.NULL; o
                    // `SessionStore` já tem o mesmo cuidado ao ler o que gravou.
                    toolId = o.optString("toolId").takeIf { it.isNotBlank() && it != "null" },
                    done = o.optBoolean("done", false),
                    estado = o.optString("estado").takeIf { it.isNotBlank() && it != "null" },
                ),
            )
        }
        return HistoricoLido(
            linhas = linhas,
            truncado = res.optBoolean("truncated", false),
            total = res.optInt("totalEvents", linhas.size),
        )
    }
}
