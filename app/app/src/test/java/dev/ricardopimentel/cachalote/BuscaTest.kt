package dev.ricardopimentel.cachalote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A busca da lista de conversas (o que substituiu os filtros `Todas / Activas / Fechadas` do
 * desenho, que não filtravam nada — `STATUS` §68.1 e §82).
 *
 * Sem Robolectric: isto é texto puro. O que se fixa é o que o dono espera quando escreve à pressa
 * no telefone: sem maiúsculas e **sem acentos**.
 */
class BuscaTest {

    private fun conversa(titulo: String, previa: String = "") = ItemConversa(
        id = "id",
        titulo = titulo,
        previa = previa,
        quando = "22/10 10:00",
        activa = false,
        falhou = false,
    )

    @Test
    fun encontra_sem_acentos_e_sem_maiusculas() {
        val c = conversa("Verificar correção de script bash")
        assertTrue(conversaCorresponde(c, "correcao"))
        assertTrue(conversaCorresponde(c, "CORREÇÃO"))
        assertTrue(conversaCorresponde(c, "corr"))
    }

    @Test
    fun procura_no_titulo_e_na_previa() {
        val c = conversa("Telemetria", "lê os turnos do registo")
        assertTrue(conversaCorresponde(c, "registo"))
        assertFalse(conversaCorresponde(c, "shizuku"))
    }

    @Test
    fun uma_busca_vazia_mostra_tudo() {
        val c = conversa("qualquer")
        assertTrue(conversaCorresponde(c, ""))
        // Só espaços também não esconde nada (é o que se escreve sem querer).
        assertTrue(conversaCorresponde(c, "   "))
    }

    @Test
    fun os_acentos_saem_do_texto_procurado() {
        assertEquals("correcao", semAcentos("Correção"))
        assertEquals("ola mundo", semAcentos("OLÁ  mundo".replace("  ", " ")))
        assertEquals("", semAcentos(""))
    }
}
