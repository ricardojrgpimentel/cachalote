package dev.ricardopimentel.cachalote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uma tarefa da fila: o que o agente deve fazer a seguir, e o que já aconteceu. */
data class Tarefa(
    val id: String,
    val texto: String,
    val estado: String = PENDENTE,   // pendente | feita | falhada
) {
    companion object {
        const val PENDENTE = "pendente"
        const val FEITA = "feita"
        const val FALHADA = "falhada"
    }
}

/**
 * **Fila de tarefas**: "faz isto e avisa-me".
 *
 * A lógica vive aqui, separada da UI e do ViewModel, para se poder testar sem aparelho. O que ela
 * decide é simples: qual é a próxima tarefa pendente, o que fazer quando uma acaba, e como se
 * guarda tudo para sobreviver a fechar a app.
 *
 * Não é um agendador: as tarefas correm **uma a seguir à outra** enquanto a app estiver viva, e
 * param naturalmente se o agente pedir autorização (o turno não acaba até o dono responder — e a
 * notificação de autorização já existe).
 */
class Fila(private val ficheiro: File?) {

    private val itens = ArrayList<Tarefa>()

    val todas: List<Tarefa> get() = itens.toList()
    val pendentes: List<Tarefa> get() = itens.filter { it.estado == Tarefa.PENDENTE }
    val vazia: Boolean get() = pendentes.isEmpty()

    fun adicionar(texto: String): Tarefa? {
        val limpo = texto.trim()
        if (limpo.isEmpty()) return null
        val t = Tarefa(id = "t${System.currentTimeMillis()}-${itens.size}", texto = limpo)
        itens.add(t)
        guardar()
        return t
    }

    fun remover(id: String): Boolean {
        val apagado = itens.removeAll { it.id == id }
        if (apagado) guardar()
        return apagado
    }

    /** A próxima a correr (a mais antiga ainda pendente), sem a marcar. */
    fun proxima(): Tarefa? = itens.firstOrNull { it.estado == Tarefa.PENDENTE }

    fun concluir(id: String, falhou: Boolean = false) {
        val i = itens.indexOfFirst { it.id == id }
        if (i < 0) return
        itens[i] = itens[i].copy(estado = if (falhou) Tarefa.FALHADA else Tarefa.FEITA)
        guardar()
    }

    /** Limpa o que já acabou (as feitas e as falhadas). */
    fun limparConcluidas(): Int {
        val antes = itens.size
        itens.removeAll { it.estado != Tarefa.PENDENTE }
        val n = antes - itens.size
        if (n > 0) guardar()
        return n
    }

    // ---------------------------------------------------------------- persistência

    fun guardar() {
        val f = ficheiro ?: return
        runCatching {
            val arr = JSONArray()
            itens.forEach { t ->
                arr.put(JSONObject().put("id", t.id).put("texto", t.texto).put("estado", t.estado))
            }
            f.writeText(JSONObject().put("itens", arr).toString())
        }
    }

    fun carregar(): Fila {
        val f = ficheiro ?: return this
        if (!f.isFile) return this
        runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("itens") ?: return@runCatching
            itens.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val texto = o.optString("texto")
                if (texto.isBlank()) continue
                // Uma tarefa que estava a correr quando a app morreu volta a "pendente": não se
                // sabe se acabou, e é melhor repetir do que perder o trabalho.
                val estado = o.optString("estado", Tarefa.PENDENTE).let {
                    if (it == Tarefa.PENDENTE) Tarefa.PENDENTE else it
                }
                itens.add(Tarefa(o.optString("id", "t$i"), texto, estado))
            }
        }
        return this
    }

    companion object {
        fun em(ctx: Context) = Fila(File(ctx.filesDir, "fila.json")).carregar()
    }
}
