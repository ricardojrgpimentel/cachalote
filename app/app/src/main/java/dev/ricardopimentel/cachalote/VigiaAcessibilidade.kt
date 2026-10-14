package dev.ricardopimentel.cachalote

/**
 * Vigia da acessibilidade: repara quando o serviço **se desliga a meio da sessão**.
 *
 * Porque é que isto é preciso: o serviço de acessibilidade cai sozinho em reinstalações da app e
 * depois de o telefone dormir. Sem vigilância, o agente só descobre **ao falhar** — e o utilizador
 * vê um erro estranho ("capacidade screen: o serviço não está ligado") em vez de um aviso claro no
 * momento em que aconteceu. Aconteceu-me exactamente isso durante o desenvolvimento.
 *
 * A lógica de transição vive aqui, separada da UI e do ciclo de vida, para se poder testar.
 */
class VigiaAcessibilidade(inicial: Boolean = false) {

    var ligada: Boolean = inicial
        private set

    /** Quantas vezes mudou de ligada para desligada (útil nos testes e no diagnóstico). */
    var quedas: Int = 0
        private set

    /**
     * Diz o que fazer com um novo estado lido do serviço.
     *  · `null` — nada mudou, ou mudou de desligada para ligada (não se avisa de boas notícias);
     *  · `perdeu` = true — caiu agora: quem chama avisa (na conversa e, se for o caso, por
     *    notificação).
     */
    fun observar(agora: Boolean): Queda? {
        val antes = ligada
        ligada = agora
        if (antes && !agora) {
            quedas++
            return Queda
        }
        return null
    }

    /** A leitura repetida do mesmo estado não pode gerar avisos repetidos. */
    object Queda
}
