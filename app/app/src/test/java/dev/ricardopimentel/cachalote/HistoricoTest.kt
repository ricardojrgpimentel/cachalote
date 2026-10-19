package dev.ricardopimentel.cachalote

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O histórico de uma conversa antiga: o que o daemon devolve (`cachalote.sessions.history`) tem
 * de entrar no transcript na ordem certa, com o estado das ferramentas — é isto que faz abrir uma
 * sessão antiga mostrar a conversa em vez de «sessão X retomada».
 *
 * Robolectric porque o `org.json` do Android é um stub nos testes JVM simples.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoricoTest {

    private val resposta = JSONObject(
        """
        {
          "sessionId": "aaaa1111",
          "title": "o título",
          "totalEvents": 7,
          "truncated": true,
          "events": [
            {"role": "user", "text": "vê o estado"},
            {"role": "thought", "text": "vou chamar a ferramenta"},
            {"role": "tool", "text": "Android capabilities", "toolId": "call_1", "estado": "completed", "done": true},
            {"role": "assistant", "text": "está tudo bem"},
            {"role": "tool", "text": "android_tap", "toolId": "call_2", "estado": "in_progress", "done": false},
            {"role": "", "text": "sem papel não entra"},
            {"role": "system", "text": ""}
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun le_os_eventos_na_ordem_e_com_estado() {
        val h = Historico.ler(resposta)
        assertEquals(listOf("user", "thought", "tool", "assistant", "tool"), h.linhas.map { it.role })
        assertEquals("vê o estado", h.linhas[0].text)
        assertEquals("call_1", h.linhas[2].toolId)
        assertEquals("completed", h.linhas[2].estado)
        assertTrue(h.linhas[2].done)
        assertFalse(h.linhas[4].done)
        assertEquals("in_progress", h.linhas[4].estado)
    }

    @Test
    fun linhas_vazias_ou_sem_papel_ficam_de_fora() {
        val h = Historico.ler(resposta)
        assertEquals(5, h.linhas.size)
    }

    @Test
    fun diz_que_o_historico_foi_truncado_e_quantos_eventos_havia() {
        val h = Historico.ler(resposta)
        assertTrue(h.truncado)
        assertEquals(7, h.total)
    }

    @Test
    fun sem_eventos_da_lista_vazia_e_nao_rebenta() {
        val h = Historico.ler(JSONObject("{}"))
        assertTrue(h.linhas.isEmpty())
        assertFalse(h.truncado)
        assertEquals(0, h.total)
    }

    @Test
    fun o_null_do_json_nao_vira_toolid() {
        val h = Historico.ler(
            JSONObject("""{"events":[{"role":"assistant","text":"olá","toolId":null,"estado":null}]}"""),
        )
        assertEquals(null, h.linhas[0].toolId)
        assertEquals(null, h.linhas[0].estado)
    }
}
