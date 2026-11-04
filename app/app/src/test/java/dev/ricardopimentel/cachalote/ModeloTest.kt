package dev.ricardopimentel.cachalote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * O nome do modelo no cabeçalho da conversa.
 *
 * O que isto protege: a linha `root · deepseek-official: DeepSeek-V4-Flash · contexto: …` enchia a
 * largura toda e acabava em «co…» (S10+, 2026-11-04). O fornecedor e a marca da casa não ajudam a
 * reconhecer qual modelo está a responder — o resto do nome sim.
 */
class ModeloTest {

    @Test
    fun corta_o_fornecedor_e_a_marca() {
        assertEquals("V4-Flash", Modelo.curto("deepseek-official: DeepSeek-V4-Flash"))
        assertEquals("V4-Flash", Modelo.curto("DeepSeek-V4-Flash"))
        assertEquals("V4-Flash", Modelo.curto("deepseek-official: deepseek-v4-flash"))
    }

    @Test
    fun um_nome_sem_fornecedor_fica_igual() {
        assertEquals("GPT-X", Modelo.curto("GPT-X"))
        assertEquals("O3-Mini", Modelo.curto("  o3-mini  "))
    }

    @Test
    fun sem_modelo_nao_se_desenha_nada() {
        assertEquals("", Modelo.curto(null))
        assertEquals("", Modelo.curto(""))
        assertEquals("", Modelo.curto("   "))
    }
}
