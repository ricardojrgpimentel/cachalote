package dev.ricardopimentel.cachalote

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Um turno de uma conversa, como o motor o grava no registo da sessão: quantos passos deu, quantas
 * ferramentas chamou e quanto tempo levou.
 *
 * O ACP **não manda nada disto** — é a razão pela qual a linha de telemetria do desenho («Em
 * directo · 2/6 · 1.102/8.000 · 3s») ficou por fazer em §64: o motor não envia contagem de passos
 * nem a duração do turno. Mas ele **grava-os** no ficheiro da sessão (`turn/start`, `step/start`,
 * `tool/call`, `turn/end`), e o daemon já sabe ler esse ficheiro — por isso a telemetria passou a
 * ser medida do disco em vez de esperada pelo protocolo (STATUS §82).
 *
 * Um turno ainda aberto (ou cortado a meio) tem `ms == null`: não se inventa duração — mostra-se o
 * que se sabe e mais nada.
 */
data class Turno(
    val numero: Int,
    val passos: Int,
    val ferramentas: Int,
    val ms: Long?,
    val razao: String?,
) {
    /** Terminou bem? `null` quando o turno ainda não acabou. */
    val concluido: Boolean? get() = razao?.let { it == "completed" || it == "done" }

    /**
     * A duração em texto curto: `3,4 s`, `45 s`, `1 m 05 s`. Vazio quando não há fim.
     *
     * O `s` e o `m` são unidades do SI (iguais nos dois idiomas), e a vírgula decimal vem do
     * locale — em inglês sai `3.4 s`.
     */
    fun duracao(): String {
        val m = ms ?: return ""
        return when {
            m < 10_000 -> String.format(Locale.getDefault(), "%.1f s", m / 1000.0)
            m < 60_000 -> "${m / 1000} s"
            else -> String.format(Locale.getDefault(), "%d m %02d s", m / 60_000, (m % 60_000) / 1000)
        }
    }

    fun passosTexto(ctx: Context): String =
        ctx.resources.getQuantityString(R.plurals.turnos_passos, passos, passos)

    fun ferramentasTexto(ctx: Context): String =
        ctx.resources.getQuantityString(R.plurals.turnos_ferramentas, ferramentas, ferramentas)

    /** «turno 3 · 1 passo · 1,8 s» — é o que a linha de telemetria mostra depois do turno. */
    fun rotulo(ctx: Context): String = listOf(
        ctx.getString(R.string.telemetria_turno_label, numero),
        passosTexto(ctx),
        duracao(),
    ).filter { it.isNotBlank() }.joinToString("  \u00B7  ")

    /** Uma linha do cartão: «2 passos · 1 ferramenta · 3,4 s». */
    fun linha(ctx: Context): String {
        val partes = mutableListOf(passosTexto(ctx), ferramentasTexto(ctx))
        if (ms != null) partes.add(duracao())
        return partes.joinToString("  \u00B7  ")
    }

    companion object {
        /** Lê a resposta do daemon (`{turns:[…]}`). Ignora o que não tiver o mínimo. */
        fun lista(json: JSONObject): List<Turno> {
            val arr = json.optJSONArray("turns") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Turno(
                    numero = o.optInt("turn", 0),
                    passos = o.optInt("steps", 0),
                    ferramentas = o.optInt("tools", 0),
                    ms = if (o.has("ms") && !o.isNull("ms")) o.optLong("ms") else null,
                    // `optString` devolve a **string** "null" quando o JSON traz `null` (é o
                    // comportamento do org.json do Android): tem de se perguntar antes.
                    razao = if (o.has("reason") && !o.isNull("reason")) {
                        o.optString("reason").takeIf { it.isNotBlank() }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
