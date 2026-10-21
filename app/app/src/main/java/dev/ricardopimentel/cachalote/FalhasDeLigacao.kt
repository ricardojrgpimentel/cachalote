package dev.ricardopimentel.cachalote

/**
 * Classificação das falhas de ligação ao daemon — lógica pura, para se poder testar sem aparelho.
 *
 * A distinção que importa é entre «**não há daemon deste modo à escuta**» (recuperável: arranca-se
 * o daemon) e «o daemon respondeu mal» (não se resolve a arrancar nada). Sem esta distinção, a app
 * desistia com «token inválido» quando o problema era o daemon do Shizuku estar parado e o porto ser
 * do daemon root (S10+, 2026-10-21).
 */
object FalhasDeLigacao {

    /** Frases que significam «a porta fechada / outro daemon no porto», e não «erro do motor». */
    private val SEM_DAEMON = listOf(
        "token inválido",
        "failed to connect",
        "connection refused",
        "econnrefused",
        "ligação recusada",
        "connection reset",
    )

    fun portaSemDaemon(mensagem: String?): Boolean {
        val m = mensagem.orEmpty().lowercase()
        return SEM_DAEMON.any { m.contains(it) }
    }

    /**
     * A falha pode ser um **MCP configurado que não responde**? (knowledge 134)
     *
     * Com um MCP inalcançável, o motor devolve `-32603 Internal error` a *tudo* — inclusive a um
     * prompt numa sessão válida — porque a montagem dos MCP rebenta na validação da sessão. O turno
     * não chega a correr, por isso vale a pena repetir sem eles. Sem MCP configurado, um
     * `Internal error` é outra coisa qualquer e não se mexe.
     */
    fun suspeitaDeMcp(mensagem: String?, temMcp: Boolean): Boolean {
        if (!temMcp) return false
        val m = mensagem.orEmpty().lowercase()
        return m.contains("internal error") || m.contains("mcp")
    }

    /**
     * A mensagem que o utilizador vê quando o daemon **deste** modo não arranca.
     *
     * Diz o que fazer, não o que falhou por dentro: «o motor (Shizuku) não está a responder: o motor
     * devolveu um erro: token inválido» era a faixa antiga e não apontava para nada.
     */
    fun explicar(modo: String, causa: String): String =
        if (portaSemDaemon(causa)) {
            "o daemon do modo $modo não está a correr — Ajustes → Motor → Ligar"
        } else {
            causa
        }
}
