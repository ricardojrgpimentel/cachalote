package dev.ricardopimentel.cachalote

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.text.Normalizer

/**
 * O subtítulo do menu dos Ajustes só pode dizer o que o **título não diz**.
 *
 * Era o último sítio com legendas a explicar o que já estava à vista: «Chave do provedor — a chave
 * do provedor, guardada no daemon» e «Capacidades autorizadas — o que o agente pode fazer no
 * telefone». Se o subtítulo repete o título, é ruído com aparência de informação — e o mesmo texto
 * ocupava uma segunda linha da lista (S10+, 2026-11-04).
 *
 * A regra é um teste, não uma nota: quem acrescentar uma entrada aos Ajustes vê isto falhar antes de
 * o utilizador ver o ruído.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "pt-rPT")
class AjustesSubtitulosTest {

    private fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()

    @Test
    fun nenhum_subtitulo_repete_o_titulo() {
        val ctx = RuntimeEnvironment.getApplication()
        ENTRADAS_DE_AJUSTE.forEach { entrada ->
            val detalhe = entrada.detalhe ?: return@forEach
            val titulo = normalizar(ctx.getString(entrada.titulo))
            val sub = normalizar(ctx.getString(detalhe))
            assertFalse(
                "o subtítulo «$sub» só repete o título «$titulo» — ou diz outra coisa, ou não existe",
                sub.contains(titulo),
            )
        }
    }

    @Test
    fun o_cartao_de_repetir_o_assistente_tambem_nao_repete_o_titulo() {
        // Este cartão não vive em `ENTRADAS_DE_AJUSTE`, e era o outro sítio com a mesma doença:
        // «Repetir a configuração inicial — Voltar a correr o assistente de boas-vindas».
        val ctx = RuntimeEnvironment.getApplication()
        val titulo = normalizar(ctx.getString(R.string.wizard_repetir))
        val sub = normalizar(ctx.getString(R.string.ajustes_repetir_detalhe))
        assertFalse("«$sub» repete «$titulo»", sub.contains(titulo))
    }

    @Test
    fun todos_os_subtitulos_que_existem_tem_texto() {
        val ctx = RuntimeEnvironment.getApplication()
        ENTRADAS_DE_AJUSTE.forEach { entrada ->
            val detalhe = entrada.detalhe ?: return@forEach
            assertTrue(
                "subtítulo vazio em «${ctx.getString(entrada.titulo)}»",
                ctx.getString(detalhe).isNotBlank(),
            )
        }
    }
}

/** A mesma regra no idioma de omissão (inglês) — a lista de entradas é uma só. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class AjustesSubtitulosEmInglesTest {

    @Test
    fun nenhum_subtitulo_repete_o_titulo() {
        val ctx = RuntimeEnvironment.getApplication()
        ENTRADAS_DE_AJUSTE.forEach { entrada ->
            val detalhe = entrada.detalhe ?: return@forEach
            val titulo = ctx.getString(entrada.titulo).lowercase().trim()
            val sub = ctx.getString(detalhe).lowercase().trim()
            assertFalse("«$sub» repete «$titulo»", sub.contains(titulo))
        }
    }
}
