package dev.ricardopimentel.cachalote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** O subconjunto de markdown que o agente escreve. */
class MarkdownTest {

    @Test
    fun a_enfase_sai_do_texto() {
        val spans = analisarInline("Olá **mundo** e *adeus* com `código`")
        assertEquals("Olá ", spans[0].texto)
        assertEquals("mundo", spans[1].texto)
        assertTrue(spans[1].negrito)
        assertEquals(" e ", spans[2].texto)
        assertEquals("adeus", spans[3].texto)
        assertTrue(spans[3].italico)
        assertEquals(" com ", spans[4].texto)
        assertEquals("código", spans[5].texto)
        assertTrue(spans[5].codigo)
    }

    @Test
    fun o_que_nao_fecha_fica_literal() {
        // Melhor mostrar os asteriscos do que engolir texto.
        val spans = analisarInline("2 * 3 = 6")
        assertEquals("2 * 3 = 6", spans.joinToString("") { it.texto })
        assertTrue(spans.none { it.italico })
    }

    @Test
    fun separa_titulos_listas_e_codigo() {
        val blocos = analisarMarkdown(
            """
            ## Resultado

            - um
            - dois

            ```sh
            ping -c 3 127.0.0.1
            ```
            """.trimIndent(),
        )
        assertEquals(Bloco.Titulo::class, blocos[0]::class)
        assertEquals(2, (blocos[0] as Bloco.Titulo).nivel)
        assertEquals(Bloco.ItemDeLista::class, blocos[1]::class)
        assertEquals("•", (blocos[1] as Bloco.ItemDeLista).marcador)
        assertEquals(Bloco.ItemDeLista::class, blocos[2]::class)
        assertEquals(Bloco.Codigo::class, blocos[3]::class)
        assertEquals(listOf("ping -c 3 127.0.0.1"), (blocos[3] as Bloco.Codigo).linhas)
    }

    @Test
    fun reconhece_uma_tabela_e_nao_mostra_o_separador() {
        val blocos = analisarMarkdown(
            """
            | App | Instalada |
            |---|---|
            | Brave | 1.96.60 |
            """.trimIndent(),
        )
        assertEquals(1, blocos.size)
        val t = blocos[0] as Bloco.Tabela
        assertEquals(listOf("App", "Instalada"), t.linhas[0])
        assertEquals(listOf("Brave", "1.96.60"), t.linhas[1])
        // As duas linhas da tabela, não as tres (o |---|---| e separador).
        assertEquals(2, t.linhas.size)
    }

    @Test
    fun um_paragrafo_junta_as_linhas_e_as_listas_numeradas_contam() {
        val blocos = analisarMarkdown("uma linha\noutra linha\n\n1. primeiro\n2. segundo")
        assertEquals(Bloco.Paragrafo::class, blocos[0]::class)
        assertEquals("uma linha outra linha", (blocos[0] as Bloco.Paragrafo).spans.joinToString("") { it.texto })
        assertEquals("1.", (blocos[1] as Bloco.ItemDeLista).marcador)
        assertEquals("2.", (blocos[2] as Bloco.ItemDeLista).marcador)
    }

    @Test
    fun texto_sem_markdown_fica_um_paragrafo() {
        val blocos = analisarMarkdown("Olá! Estou pronto para ajudar.")
        assertEquals(1, blocos.size)
        assertEquals(Bloco.Paragrafo::class, blocos[0]::class)
    }
}
