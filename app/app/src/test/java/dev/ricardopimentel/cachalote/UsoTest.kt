package dev.ricardopimentel.cachalote

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O consumo de contexto: parsing do que o motor envia e a formatação que se vê no cabeçalho.
 *
 * O motor já mandava isto em cada turno (`usage_update`) e a app deitava fora — estes testes
 * fixam o contrato, para não se perder outra vez.
 */
// Robolectric porque o `org.json` do Android é um stub nos testes JVM simples (só existe a
// sério num aparelho ou sob Robolectric).
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UsoTest {

    @Test
    fun le_o_que_o_motor_envia() {
        val update = JSONObject("""{"sessionUpdate":"usage_update","used":6290,"size":1000000}""")
        val uso = Uso.de(update)
        assertEquals(6290, uso?.usado)
        assertEquals(1000000, uso?.limite)
        assertEquals(1, uso?.percentagem)   // 0,629% arredonda para 1 (arredondamento normal)
    }

    @Test
    fun ignora_o_que_nao_e_consumo() {
        assertNull(Uso.de(JSONObject("""{"sessionUpdate":"agent_message_chunk","text":"olá"}""")))
        // Um `usage_update` sem números também não serve para nada.
        assertNull(Uso.de(JSONObject("""{"sessionUpdate":"usage_update"}""")))
    }

    @Test
    fun formata_o_texto_como_se_usa_em_portugues() {
        assertEquals("contexto: 120\u202F000 / 1\u202F000\u202F000 · 12%", Uso(120000, 1000000).texto())
        assertEquals("contexto: 1\u202F000 / 1\u202F000 · 100%", Uso(1000, 1000).texto())
    }

    @Test
    fun sem_limite_conhecido_mostra_so_o_usado() {
        assertEquals("contexto: 500", Uso(500, 0).texto())
        assertEquals(0, Uso(500, 0).percentagem)
    }

    @Test
    fun separa_milhares() {
        assertEquals("1", Uso.milhares(1))
        assertEquals("999", Uso.milhares(999))
        assertEquals("1\u202F000", Uso.milhares(1000))
        assertEquals("12\u202F345\u202F678", Uso.milhares(12345678))
        assertEquals("-1\u202F000", Uso.milhares(-1000))
    }
}
