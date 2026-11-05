package dev.ricardopimentel.cachalote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** O primeiro «Continuar» não pode ficar preso à espera do kernel que só se vê no passo 2. */
class AssistenteTest {

    @Test
    fun o_primeiro_continuar_nao_fica_preso_no_kernel() {
        assertTrue("no passo 0 tem de deixar avançar para ver o progresso", podeContinuar(0, true))
        assertFalse("nos passos seguintes espera pelo kernel", podeContinuar(1, true))
        assertTrue("sem preparação, todos os passos avançam", podeContinuar(1, false))
    }
}
