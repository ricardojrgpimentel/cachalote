package dev.ricardopimentel.cachalote

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A telemetria do turno: o que o daemon devolve (`{turns:[…]}`, lido do registo da sessão) e como
 * isso se escreve.
 *
 * Esta é a linha do desenho («Em directo · 2/6 · … · 3s») que o motor **não** manda pelo ACP
 * (STATUS §64). Como passou a ser medida do disco (§82), o contrato do parsing e da formatação
 * fica fixado aqui — se o leitor do daemon mudar de forma, estes testes avisam.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "pt-rPT")
class TurnoTest {

    @Test
    fun le_os_turnos_que_o_daemon_devolve() {
        val json = JSONObject(
            """
            {"sessionId":"abc","turns":[
              {"turn":1,"steps":2,"tools":1,"ms":3454,"reason":"completed"},
              {"turn":2,"steps":1,"tools":0,"ms":null,"reason":null}
            ]}
            """.trimIndent(),
        )
        val turnos = Turno.lista(json)
        assertEquals(2, turnos.size)
        assertEquals(1, turnos[0].numero)
        assertEquals(2, turnos[0].passos)
        assertEquals(1, turnos[0].ferramentas)
        assertEquals(3454L, turnos[0].ms)
        assertEquals(true, turnos[0].concluido)
        // Turno aberto: sem `ms` e sem veredicto — não se inventa nenhum dos dois.
        assertNull(turnos[1].ms)
        assertNull(turnos[1].concluido)
    }

    @Test
    fun sem_turnos_nao_e_erro() {
        assertTrue(Turno.lista(JSONObject("""{"sessionId":"abc","turns":[]}""")).isEmpty())
        // Um daemon antigo (ou o embutido antes desta versão) pode não ter o campo.
        assertTrue(Turno.lista(JSONObject("""{"sessionId":"abc"}""")).isEmpty())
    }

    @Test
    fun a_duracao_escreve_se_como_em_portugues() {
        val antigo = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-PT"))
            assertEquals("3,5 s", Turno(1, 1, 0, 3454, "completed").duracao())
            assertEquals("45 s", Turno(1, 1, 0, 45_000, "completed").duracao())
            assertEquals("1 m 05 s", Turno(1, 1, 0, 65_000, "completed").duracao())
            // Turno que não acabou: sem duração, em vez de um zero enganador.
            assertEquals("", Turno(1, 1, 0, null, null).duracao())
        } finally {
            Locale.setDefault(antigo)
        }
    }

    @Test
    fun um_turno_interrompido_nao_e_concluido() {
        val t = Turno(3, 1, 0, 1784, "cancelled")
        assertEquals(false, t.concluido)
        assertEquals("1,8 s", t.duracao())
    }
}
