package dev.ricardopimentel.cachalote

/**
 * O subconjunto de markdown que o agente escreve, analisado sem dependências.
 *
 * Existe porque o agente responde em markdown e ele saía **em bruto**: `## Resultado`,
 * `**negrito**`, `| a | b |` com a linha `|---|---|` à vista. Numa resposta longa lê-se mal (visto
 * no S10+ em 2026-10-16, e o autor notou-o).
 *
 * Não é um parser de markdown: é o que o modelo escreve na prática — títulos, ênfase, código,
 * listas, citações e tabelas. Tudo o que não se reconhece fica como texto, que é o mais seguro.
 *
 * A análise está separada do desenho (devolve dados, não Compose) para se poder testar na JVM.
 */

/** Um pedaço de linha: texto com (ou sem) ênfase. */
data class Span(
    val texto: String,
    val negrito: Boolean = false,
    val italico: Boolean = false,
    val codigo: Boolean = false,
)

/** Uma linha classificada. */
sealed interface Bloco {
    data class Paragrafo(val spans: List<Span>) : Bloco
    data class Titulo(val nivel: Int, val spans: List<Span>) : Bloco
    data class ItemDeLista(val marcador: String, val spans: List<Span>) : Bloco
    data class Citacao(val spans: List<Span>) : Bloco
    data class Codigo(val linhas: List<String>) : Bloco
    /** A primeira linha é o cabeçalho. */
    data class Tabela(val linhas: List<List<String>>) : Bloco
    data object Separador : Bloco
}

/**
 * Parte uma linha em pedaços com ênfase.
 *
 * Trata `**negrito**`, `*itálico*`/`_itálico_` e `` `código` ``. O que não fecha fica literal —
 * é melhor mostrar os asteriscos do que engolir texto.
 */
fun analisarInline(linha: String): List<Span> {
    val saida = ArrayList<Span>()
    val texto = StringBuilder()
    var i = 0
    fun fechar() {
        if (texto.isNotEmpty()) {
            saida.add(Span(texto.toString()))
            texto.setLength(0)
        }
    }
    while (i < linha.length) {
        val c = linha[i]
        when {
            // `**negrito**`
            c == '*' && i + 1 < linha.length && linha[i + 1] == '*' -> {
                val fim = linha.indexOf("**", i + 2)
                if (fim > i + 2) {
                    fechar()
                    saida.add(Span(linha.substring(i + 2, fim), negrito = true))
                    i = fim + 2
                } else {
                    texto.append(c); i++
                }
            }
            // `*itálico*` e `_itálico_`
            (c == '*' || c == '_') -> {
                val fim = linha.indexOf(c, i + 1)
                if (fim > i + 1) {
                    fechar()
                    saida.add(Span(linha.substring(i + 1, fim), italico = true))
                    i = fim + 1
                } else {
                    texto.append(c); i++
                }
            }
            // `` `código` ``
            c == '`' -> {
                val fim = linha.indexOf('`', i + 1)
                if (fim > i + 1) {
                    fechar()
                    saida.add(Span(linha.substring(i + 1, fim), codigo = true))
                    i = fim + 1
                } else {
                    texto.append(c); i++
                }
            }
            else -> {
                texto.append(c); i++
            }
        }
    }
    fechar()
    return if (saida.isEmpty()) listOf(Span(linha)) else saida
}

/** Separa as células de uma linha de tabela (`| a | b |`). */
private fun celulas(linha: String): List<String> =
    linha.trim().trim('|').split('|').map { it.trim() }

/** Uma linha `|---|---|` — o separador do cabeçalho da tabela. */
private fun eSeparadorDeTabela(linha: String): Boolean {
    val t = linha.trim()
    if (!t.startsWith("|") && !t.contains("|")) return false
    return t.all { it == '|' || it == '-' || it == ':' || it == ' ' } && t.contains('-')
}

private val RE_ITEM = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)$")
private val RE_TITULO = Regex("^(#{1,6})\\s+(.*)$")

/**
 * Parte o texto em blocos. Uma cerca de código (```) fica um bloco próprio, com as linhas tal e
 * qual; uma tabela é reconhecida quando a segunda linha é o separador.
 */
fun analisarMarkdown(texto: String): List<Bloco> {
    val linhas = texto.replace("\r\n", "\n").split("\n")
    val saida = ArrayList<Bloco>()
    val paragrafo = ArrayList<String>()
    var i = 0

    fun fecharParagrafo() {
        if (paragrafo.isNotEmpty()) {
            val junto = paragrafo.joinToString(" ").trim()
            if (junto.isNotEmpty()) saida.add(Bloco.Paragrafo(analisarInline(junto)))
            paragrafo.clear()
        }
    }

    while (i < linhas.size) {
        val linha = linhas[i]
        val t = linha.trim()

        // Cerca de código
        if (t.startsWith("```")) {
            fecharParagrafo()
            val corpo = ArrayList<String>()
            i++
            while (i < linhas.size && !linhas[i].trim().startsWith("```")) {
                corpo.add(linhas[i]); i++
            }
            if (i < linhas.size) i++   // a cerca de fecho
            saida.add(Bloco.Codigo(corpo))
            continue
        }
        // Separador horizontal
        if (t == "---" || t == "***" || t == "___") {
            fecharParagrafo(); saida.add(Bloco.Separador); i++; continue
        }
        // Título
        val titulo = RE_TITULO.find(linha)
        if (titulo != null) {
            fecharParagrafo()
            saida.add(Bloco.Titulo(titulo.groupValues[1].length, analisarInline(titulo.groupValues[2])))
            i++
            continue
        }
        // Tabela: esta linha tem `|` e a seguinte é o separador
        if (t.contains("|") && i + 1 < linhas.size && eSeparadorDeTabela(linhas[i + 1])) {
            fecharParagrafo()
            val corpo = ArrayList<List<String>>()
            corpo.add(celulas(linha))
            i += 2
            while (i < linhas.size && linhas[i].contains("|") && linhas[i].trim().isNotEmpty()) {
                corpo.add(celulas(linhas[i])); i++
            }
            saida.add(Bloco.Tabela(corpo))
            continue
        }
        // Citação
        if (t.startsWith(">")) {
            fecharParagrafo()
            val corpo = ArrayList<String>()
            while (i < linhas.size && linhas[i].trim().startsWith(">")) {
                corpo.add(linhas[i].trim().removePrefix(">").trim()); i++
            }
            saida.add(Bloco.Citacao(analisarInline(corpo.joinToString(" "))))
            continue
        }
        // Item de lista
        val item = RE_ITEM.find(linha)
        if (item != null) {
            fecharParagrafo()
            val bruto = item.groupValues[1]
            val marcador = if (bruto.first().isDigit()) bruto.trimEnd('.', ')') + "." else "\u2022"
            saida.add(Bloco.ItemDeLista(marcador, analisarInline(item.groupValues[2])))
            i++
            continue
        }
        // Linha em branco: fecha o parágrafo
        if (t.isEmpty()) { fecharParagrafo(); i++; continue }

        paragrafo.add(linha)
        i++
    }
    fecharParagrafo()
    return saida
}
