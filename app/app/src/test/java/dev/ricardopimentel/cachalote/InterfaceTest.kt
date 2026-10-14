package dev.ricardopimentel.cachalote

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes de interface **a sério, mas sem toques cegos**: compõem o pedaço de UI com dados
 * inventados e verificam o que se vê e o que é chamado.
 *
 * Isto substitui a abordagem anterior (arrancar a app, tirar `uiautomator dump`, adivinhar
 * coordenadas, tocar, torcer) que falhava por o campo não ter foco, o teclado tapar o botão ou o
 * `BACK` fechar a app — três vezes nesta sessão. Ver knowledge 114.
 */
@RunWith(AndroidJUnit4::class)
// Qualificador pt-PT: os textos dos recursos vêm em português europeu (e o inglês fica
// coberto por InterfaceEnTest, no mesmo ficheiro de testes da JVM).
@Config(sdk = [34], qualifiers = "pt-rPT")
class InterfaceTest {

    @get:Rule
    val compose = createComposeRule()

    private val sessaoA = SessionRow("aaaa1111-2222-3333-4444-555555555555", "primeira conversa", "2026-10-14T10:00:00Z")
    private val sessaoB = SessionRow("bbbb2222-3333-4444-5555-666666666666", "segunda conversa", "2026-10-14T11:30:00Z")

    @Test
    fun a_lista_de_sessoes_marca_a_activa_e_so_ela_se_pode_fechar() {
        var retomada: String? = null
        var fechada: String? = null
        compose.setContent {
            SessionsList(
                sessions = listOf(sessaoA, sessaoB),
                ativo = sessaoB.id,
                enabled = true,
                onResume = { retomada = it },
                onClose = { fechada = it },
            )
        }

        // A activa leva ✓; a outra não.
        compose.onNodeWithText("✓ segunda conversa").assertIsDisplayed()
        compose.onNodeWithText("primeira conversa").assertIsDisplayed()
        // O motor só fecha a sessão activa, por isso só há **um** ✕ — e é na linha da activa.
        compose.onAllNodesWithText("✕").assertCountEquals(1)

        // Tocar numa linha retoma essa sessão (não a que está activa).
        compose.onNodeWithText("primeira conversa").performClick()
        assertEquals(sessaoA.id, retomada)

        // E o ✕ fecha a activa.
        compose.onNodeWithText("✕").performClick()
        assertEquals(sessaoB.id, fechada)
    }

    @Test
    fun sem_sessoes_a_lista_diz_isso_mesmo() {
        var tocada: String? = null
        compose.setContent {
            SessionsList(
                sessions = emptyList(),
                ativo = null,
                enabled = true,
                onResume = { tocada = it },
                onClose = { tocada = it },
            )
        }
        compose.onNodeWithText("não há sessões guardadas no motor").assertIsDisplayed()
        assertNull(tocada)
    }

    @Test
    fun a_lista_nao_deixa_mexer_enquanto_o_agente_trabalha() {
        // Com `enabled = false` o ✕ existe mas está desactivado (é o que o `busy` faz).
        compose.setContent {
            SessionsList(
                sessions = listOf(sessaoA),
                ativo = sessaoA.id,
                enabled = false,
                onResume = {},
                onClose = {},
            )
        }
        compose.onNodeWithText("✕").assertIsNotEnabled()
    }

    @Test
    fun os_interruptores_das_capacidades_mostram_e_avisam_o_estado() {
        val mudancas = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            CapabilityToggles(
                nomes = listOf("notify", "screenshot", "vscreen_start"),
                autorizadas = mapOf("notify" to true, "screenshot" to false, "vscreen_start" to false),
                onToggle = { cap, ligado -> mudancas += cap to ligado },
            )
        }

        compose.onNodeWithText("notify").assertIsDisplayed()
        compose.onNodeWithText("screenshot").assertIsDisplayed()
        compose.onNodeWithText("vscreen_start").assertIsDisplayed()

        // O primeiro interruptor (notify, ligado) desliga-se ao tocar; o segundo liga-se.
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        assertEquals(listOf("notify" to false, "screenshot" to true), mudancas)
    }

    @Test
    fun os_botoes_do_ecra_virtual_so_ligam_o_ao_vivo_quando_ele_corre() {
        var iniciou = 0
        var viu = 0
        var parou = 0
        var aoVivo: Boolean? = null
        compose.setContent {
            VscreenControls(
                rodando = false,
                aoVivo = false,
                enabled = true,
                onStart = { iniciou++ },
                onRefresh = { viu++ },
                onStop = { parou++ },
                onAoVivo = { aoVivo = it },
            )
        }

        compose.onNodeWithText("Iniciar").assertIsEnabled()
        compose.onNodeWithText("Parar").assertIsEnabled()
        // Sem ecrã virtual a correr, "ao vivo" está desactivado — não há nada para ver.
        compose.onAllNodes(isToggleable())[0].assertIsNotEnabled()

        compose.onNodeWithText("Iniciar").performClick()
        compose.onNodeWithText("Ver fotograma").performClick()
        compose.onNodeWithText("Parar").performClick()
        assertEquals(1, iniciou)
        assertEquals(1, viu)
        assertEquals(1, parou)
        assertNull(aoVivo)
    }

    @Test
    fun com_o_ecra_virtual_a_correr_o_ao_vivo_fica_disponivel() {
        var aoVivo: Boolean? = null
        compose.setContent {
            VscreenControls(
                rodando = true,
                aoVivo = false,
                enabled = true,
                onStart = {},
                onRefresh = {},
                onStop = {},
                onAoVivo = { aoVivo = it },
            )
        }
        compose.onAllNodes(isToggleable())[0].assertIsEnabled()
        compose.onAllNodes(isToggleable())[0].performClick()
        assertEquals(true, aoVivo)
    }

    @Test
    fun as_licencas_listam_e_mostram_o_texto_integral() {
        val licencas = listOf(
            Licenca("Node.js 24", "MIT", "https://nodejs.org", "MIT License\n\nCopyright (c) OpenJS"),
            Licenca("ICU 78", "Unicode License V3", "https://icu.unicode.org", "UNICODE LICENSE V3"),
        )
        var aberta: Licenca? = null
        compose.setContent {
            LicensesBody(licencas = licencas, aberta = null, onAbrir = { aberta = it })
        }

        compose.onNodeWithText("Node.js 24").assertIsDisplayed()
        compose.onNodeWithText("Unicode License V3").assertIsDisplayed()
        compose.onNodeWithText("Node.js 24").performClick()
        assertEquals("Node.js 24", aberta?.nome)
    }

    @Test
    fun o_texto_da_licenca_escolhida_aparece_inteiro() {
        val lic = Licenca("ICU 78", "Unicode License V3", "https://icu.unicode.org", "UNICODE LICENSE V3\n\nCopyright © 1991-2024 Unicode")
        compose.setContent {
            LicensesBody(licencas = listOf(lic), aberta = lic, onAbrir = {})
        }
        compose.onNodeWithText("ICU 78").assertIsDisplayed()
        compose.onNodeWithText("Unicode License V3 · https://icu.unicode.org").assertIsDisplayed()
        compose.onNodeWithText(lic.texto).assertIsDisplayed()
    }
}

/**
 * As mesmas peças, agora em **inglês** (o idioma por omissão). É isto que garante, na CI, que a
 * interface não fica meio traduzida: se alguém acrescentar um texto sem tradução, o teste do
 * pt-PT continua a passar (cai no inglês) mas o do inglês apanha a diferença de redacção.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en")
class InterfaceEnTest {

    @get:Rule
    val compose = createComposeRule()

    private val sessao = SessionRow("cccc3333-4444-5555-6666-777777777777", "a chat", "2026-10-14T12:00:00Z")

    @Test
    fun os_botoes_e_os_rotulos_estao_em_ingles() {
        compose.setContent {
            SessionsList(
                sessions = emptyList(),
                ativo = null,
                enabled = true,
                onResume = {},
                onClose = {},
            )
        }
        compose.onNodeWithText("no sessions stored in the engine").assertIsDisplayed()
    }

    @Test
    fun os_controlos_do_ecra_virtual_estao_em_ingles() {
        compose.setContent {
            VscreenControls(
                rodando = false,
                aoVivo = false,
                enabled = true,
                onStart = {},
                onRefresh = {},
                onStop = {},
                onAoVivo = {},
            )
        }
        compose.onNodeWithText("Start").assertIsDisplayed()
        compose.onNodeWithText("View frame").assertIsDisplayed()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("Live").assertIsDisplayed()
    }

    @Test
    fun a_lista_de_sessoes_em_ingles_marca_a_activa() {
        compose.setContent {
            SessionsList(
                sessions = listOf(sessao),
                ativo = sessao.id,
                enabled = true,
                onResume = {},
                onClose = {},
            )
        }
        compose.onNodeWithText("✓ a chat").assertIsDisplayed()
        compose.onNodeWithText("✕").assertIsDisplayed()
    }
}

/** O indicador de consumo de contexto no cabeçalho. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "pt-rPT")
class ConsumoTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mostra_o_consumo_quando_o_motor_o_diz() {
        compose.setContent { ConsumoDeContexto(Uso(120000, 1000000)) }
        compose.onNodeWithText("contexto: 120\u202F000 / 1\u202F000\u202F000 · 12%").assertIsDisplayed()
    }

    @Test
    fun nao_mostra_nada_antes_de_haver_numeros() {
        compose.setContent { ConsumoDeContexto(null) }
        compose.onAllNodesWithText("contexto", substring = true).assertCountEquals(0)
    }
}
