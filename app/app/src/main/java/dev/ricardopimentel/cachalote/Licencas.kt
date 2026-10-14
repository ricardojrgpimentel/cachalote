package dev.ricardopimentel.cachalote

import android.content.Context
import org.json.JSONObject

/**
 * Licenças de terceiros, com os **textos integrais**, lidos de `assets/licenses.json`.
 *
 * Porque é que isto existe: a app redistribui bibliotecas com licenças que obrigam a manter a
 * atribuição (e, no caso do Apache-2.0, uma cópia da licença). Ter os textos dentro da app resolve
 * isso no sítio onde o utilizador os pode ler, e o mesmo ficheiro serve para o repositório
 * (`THIRD_PARTY_NOTICES.md` aponta para aqui).
 *
 * O texto vem de fontes canónicas: MIT e Apache-2.0 (apache.org), Unicode License V3
 * (unicode.org) e a licença do zlib (madler/zlib). O SQLite é domínio público e o Termux entra
 * só como proveniência dos binários.
 */
data class Licenca(
    val nome: String,
    val licenca: String,
    val url: String,
    val texto: String,
)

object Licencas {

    fun carregar(ctx: Context): List<Licenca> = runCatching {
        val json = ctx.assets.open("licenses.json").bufferedReader().use { it.readText() }
        val itens = JSONObject(json).optJSONArray("itens") ?: return emptyList()
        (0 until itens.length()).mapNotNull { i ->
            val o = itens.optJSONObject(i) ?: return@mapNotNull null
            Licenca(
                nome = o.optString("nome"),
                licenca = o.optString("licenca"),
                url = o.optString("url"),
                texto = o.optString("texto"),
            )
        }
    }.getOrDefault(emptyList())
}
