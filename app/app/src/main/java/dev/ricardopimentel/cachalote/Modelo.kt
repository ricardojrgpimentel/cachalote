package dev.ricardopimentel.cachalote

/**
 * O nome do modelo, em curto — para o cabeçalho da conversa.
 *
 * O rótulo que o motor devolve é «fornecedor: nome» (ex.: `deepseek-official: DeepSeek-V4-Flash`) e,
 * no cabeçalho de um chat, ocupava a linha toda e acabava em «co…». Quem escolheu o modelo sabe qual
 * é; o que precisa de reconhecer de relance é qual está a responder. O nome completo continua no menu
 * de overflow, onde há espaço para ele.
 *
 * Lógica pura, para se poder testar sem aparelho (como [`FalhasDeLigacao`] e [`Uso`]).
 */
object Modelo {

    /**
     * `deepseek-official: DeepSeek-V4-Flash` → `V4-Flash`.
     *
     * - corta o fornecedor (o que está antes do último `": "`);
     * - tira o prefixo da marca, que é o nome da app e não do modelo;
     * - devolve `""` quando não há nada — o chamador não desenha a linha.
     */
    fun curto(label: String?): String {
        val s = label?.trim().orEmpty()
        if (s.isBlank()) return ""
        val nome = s.substringAfterLast(": ", s).trim().ifBlank { s }
        val semMarca = nome.removePrefix("DeepSeek-").removePrefix("deepseek-").trim()
        val base = semMarca.ifBlank { nome }
        // O rótulo tanto vem `DeepSeek-V4-Flash` como `deepseek-v4-flash` (depende de onde a opção
        // nasceu). Sem nenhuma maiúscula, põe-se uma em cada palavra: «V4-Flash» lê-se, «v4-flash»
        // parece um id de base de dados.
        if (base.any { it.isUpperCase() }) return base
        return base.split('-').joinToString("-") { p -> p.replaceFirstChar { it.uppercaseChar() } }
    }
}
