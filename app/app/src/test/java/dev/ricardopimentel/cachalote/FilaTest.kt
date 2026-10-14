package dev.ricardopimentel.cachalote

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * A fila de tarefas: "faz isto e avisa-me".
 *
 * O que interessa testar não é a UI: é a ordem (uma de cada vez, pela ordem em que foram
 * entregues), o que acontece quando uma acaba, e que a fila **sobrevive a fechar a app** — sem
 * isso, lançar trabalho e sair não servia para nada.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FilaTest {

    private fun ficheiro() = File.createTempFile("fila", ".json").also { it.delete() }

    @Test
    fun corre_pela_ordem_em_que_foram_entregues() {
        val fila = Fila(ficheiro())
        fila.adicionar("primeira")
        fila.adicionar("segunda")
        fila.adicionar("terceira")

        assertEquals("primeira", fila.proxima()?.texto)
        fila.concluir(fila.proxima()!!.id)
        assertEquals("segunda", fila.proxima()?.texto)
        fila.concluir(fila.proxima()!!.id)
        assertEquals("terceira", fila.proxima()?.texto)
        fila.concluir(fila.proxima()!!.id)
        assertNull("a fila devia estar vazia", fila.proxima())
        assertTrue(fila.vazia)
    }

    @Test
    fun uma_tarefa_que_falhou_nao_volta_a_correr() {
        val fila = Fila(ficheiro())
        fila.adicionar("vai falhar")
        fila.concluir(fila.proxima()!!.id, falhou = true)
        assertTrue("uma falhada não se repete sozinha", fila.vazia)
        assertNull(fila.proxima())
        assertEquals(1, fila.todas.size)
    }

    @Test
    fun a_fila_sobrevive_a_fechar_a_app() {
        val f = ficheiro()
        val primeira = Fila(f)
        primeira.adicionar("tarefa que fica")
        primeira.adicionar("outra")
        primeira.concluir(primeira.proxima()!!.id)

        // Nova instância, como se a app tivesse sido fechada e reaberta.
        val depois = Fila(f).carregar()
        assertEquals(2, depois.todas.size)
        assertEquals("outra", depois.proxima()?.texto)
        assertEquals(1, depois.todas.count { it.estado == Tarefa.FEITA })
    }

    @Test
    fun o_que_estava_a_correr_volta_a_pendente() {
        // Se a app morrer a meio de um turno não se sabe se acabou: é melhor repetir do que perder.
        val f = ficheiro()
        Fila(f).adicionar("interrompida")
        val depois = Fila(f).carregar()
        assertEquals(Tarefa.PENDENTE, depois.todas.single().estado)
    }

    @Test
    fun limpar_concluidas_nao_toca_nas_pendentes() {
        val fila = Fila(ficheiro())
        fila.adicionar("feita")
        fila.adicionar("falhada")
        fila.adicionar("à espera")
        fila.concluir(fila.todas[0].id)
        fila.concluir(fila.todas[1].id, falhou = true)

        assertEquals(2, fila.limparConcluidas())
        assertEquals(1, fila.todas.size)
        assertEquals("à espera", fila.todas.single().texto)
    }

    @Test
    fun remover_tira_uma_tarefa_da_fila() {
        val fila = Fila(ficheiro())
        val a = fila.adicionar("fica")!!
        val b = fila.adicionar("sai")!!
        assertTrue(fila.remover(b.id))
        assertFalse(fila.remover("id-que-nao-existe"))
        assertEquals(listOf(a.id), fila.todas.map { it.id })
    }

    @Test
    fun texto_vazio_nao_entra_na_fila() {
        val fila = Fila(ficheiro())
        assertNull(fila.adicionar("   "))
        assertNull(fila.adicionar(""))
        assertTrue(fila.vazia)
    }
}
