package dev.ricardopimentel.cachalote

import org.json.JSONObject

/**
 * Consumo de contexto da sessão, como o motor o reporta (`usage_update`).
 *
 * O motor já enviava isto em cada turno e a app **deitava fora** — o que é uma pena: é a única
 * forma de o utilizador ter noção de custo (são tokens a sério) e de ver a sessão a encher antes
 * de bater no limite. Aqui fica só o parsing e a formatação, para se poderem testar sem UI.
 */
data class Uso(val usado: Int, val limite: Int) {

    /** Percentagem arredondada (0 se o limite não for conhecido). */
    val percentagem: Int get() = if (limite > 0) (usado * 100.0 / limite).roundToIntSafe() else 0

    /**
     * Os números, sem prefixo: `6 290 / 1 000 000 · 1%`. Separador de milhares com espaço fino (é o
     * que se usa em português) e sem casas decimais — isto é um indicador, não um relatório.
     *
     * O prefixo («contexto: …») fica no recurso, em [`texto`], para a formatação continuar a poder
     * ser testada sem Android.
     */
    fun numeros(): String =
        if (limite <= 0) milhares(usado)
        else "${milhares(usado)} / ${milhares(limite)} · $percentagem%"

    /** Texto para o cabeçalho, com o prefixo traduzido. */
    fun texto(ctx: android.content.Context): String =
        if (limite <= 0) ctx.getString(R.string.contexto, numeros())
        else ctx.getString(R.string.contexto_com_limite_simples, numeros())

    companion object {
        /**
         * Lê um `session/update` do tipo `usage_update`. Devolve `null` se não for disso (para o
         * chamador poder ignorar sem ramificações).
         */
        fun de(update: JSONObject): Uso? {
            if (update.optString("sessionUpdate") != "usage_update") return null
            if (!update.has("used") && !update.has("size")) return null
            return Uso(update.optInt("used", 0), update.optInt("size", 0))
        }

        /** Separa os milhares com espaço fino (U+202F), como manda o português. */
        fun milhares(n: Int): String {
            val s = kotlin.math.abs(n).toString()
            val partes = StringBuilder()
            for ((i, c) in s.withIndex()) {
                if (i > 0 && (s.length - i) % 3 == 0) partes.append('\u202F')
                partes.append(c)
            }
            return (if (n < 0) "-" else "") + partes
        }

        private fun Double.roundToIntSafe(): Int = kotlin.math.round(this).toInt()
    }
}
