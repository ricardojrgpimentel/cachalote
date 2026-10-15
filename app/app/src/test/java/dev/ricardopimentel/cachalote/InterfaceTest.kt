package dev.ricardopimentel.cachalote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private val conversas = listOf(
        ItemConversa(
            id = "aaaa1111-2222-3333-4444-555555555555",
            titulo = "corre o comando echo cachalote-teste",
            previa = "respondeu com 512 lido da imagem",
            quando = "14/10 22:57",
            activa = false,
            falhou = false,
        ),
        ItemConversa(
            id = "bbbb2222-3333-4444-5555-666666666666",
            titulo = "estado do motor e da chave",
            previa = "session bbbb2222",
            quando = "14/10 21:14",
            activa = true,
            falhou = false,
        ),
    )

    @Test
    fun a_lista_de_conversas_mostra_as_linhas_e_marca_a_activa() {
        var aberta: String? = null
        compose.setContent {
            ListaDeConversas(conversas = conversas, onAbrir = { aberta = it }, onNova = {})
        }

        compose.onNodeWithText("corre o comando echo cachalote-teste").assertIsDisplayed()
        compose.onNodeWithText("respondeu com 512 lido da imagem").assertIsDisplayed()
        compose.onNodeWithText("estado do motor e da chave").assertIsDisplayed()
        compose.onNodeWithText("14/10 22:57").assertIsDisplayed()
        // O chip do estado só existe na linha activa; as outras não inventam um.
        compose.onAllNodesWithText("ACTIVA").assertCountEquals(1)
        compose.onNodeWithText("2 conversas").assertIsDisplayed()

        // Tocar numa linha abre **essa** conversa.
        compose.onNodeWithText("corre o comando echo cachalote-teste").performClick()
        assertEquals("aaaa1111-2222-3333-4444-555555555555", aberta)
    }

    @Test
    fun a_linha_da_conversa_que_falhou_leva_o_chip_de_falha() {
        val comFalha = listOf(conversas[1].copy(falhou = true))
        compose.setContent { ListaDeConversas(conversas = comFalha, onAbrir = {}, onNova = {}) }
        compose.onNodeWithText("FALHA").assertIsDisplayed()
        compose.onAllNodesWithText("ACTIVA").assertCountEquals(0)
    }

    @Test
    fun sem_conversas_mostra_o_estado_vazio_e_o_botao_de_criar() {
        var nova = 0
        compose.setContent { ListaDeConversas(conversas = emptyList(), onAbrir = {}, onNova = { nova++ }) }
        compose.onNodeWithText("Ainda não há conversas").assertIsDisplayed()
        compose.onNodeWithText("Escreve a primeira mensagem e o agente começa a trabalhar.").assertIsDisplayed()
        compose.onNodeWithText("Nova conversa").performClick()
        assertEquals(1, nova)
    }

    @Test
    fun o_botao_de_criar_conversa_esta_sempre_a_mao() {
        var nova = 0
        compose.setContent { ListaDeConversas(conversas = conversas, onAbrir = {}, onNova = { nova++ }) }
        // Com conversas, o botão do canto («Nova») continua a criar uma nova.
        compose.onNodeWithText("Nova").performClick()
        assertEquals(1, nova)
    }

    @Test
    fun as_iniciais_do_avatar_vem_do_titulo() {
        assertEquals("CO", iniciais("corre o comando echo"))
        assertEquals("ES", iniciais("  estado do motor  "))
        assertEquals("?", iniciais("   "))
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

    // ---- o painel de autorização (a última peça do design sem teste; ver STATUS §67) ----
    //
    // O painel só aparecia com um turno real do modelo — e um turno real precisa de rede e de
    // chave. Extraído do `ModalBottomSheet` para o `UiParts`, passa a verificar-se aqui.

    private fun painel(
        titulo: String = "touch /sdcard/cachalote-guard.txt",
        detalhe: String = "fora do sandbox: /sdcard não está nas áreas permitidas",
        opcoes: List<Pair<String, String>> = listOf("once" to "Permitir uma vez", "always" to "Permitir sempre"),
        decidido: (String?) -> Unit = {},
    ) {
        // O painel vive dentro de um `ModalBottomSheet`, que rola: no teste reproduz-se isso com
        // uma coluna com scroll (o ecrã do Robolectric é pequeno e o painel é alto). Sem isto, o
        // «Recusar o turno» e o rodapé ficavam fora da janela e o teste falhava por isso — não por
        // o painel estar mal.
        compose.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PainelDeAutorizacao(
                    requestId = 7,
                    titulo = titulo,
                    detalhe = detalhe,
                    opcoes = opcoes,
                    rodape = "PID: 1234 · modo root",
                    onDecidir = decidido,
                )
            }
        }
    }

    @Test
    fun o_painel_de_autorizacao_mostra_o_pedido_e_o_aviso_do_sandbox() {
        painel()
        compose.onNodeWithText("Autorização").assertIsDisplayed()
        compose.onNodeWithText("O AGENTE QUER CORRER:").assertIsDisplayed()
        compose.onNodeWithText("touch /sdcard/cachalote-guard.txt").assertIsDisplayed()
        compose.onNodeWithText("Permitir uma vez").assertIsDisplayed()
        compose.onNodeWithText("Permitir sempre").assertIsDisplayed()
        // O chip do design só aparece quando o pedido é mesmo fora das áreas permitidas.
        compose.onNodeWithText("FORA DO SANDBOX").assertIsDisplayed()
        compose.onNodeWithText("PID: 1234 · modo root").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun o_painel_sem_pedido_fora_do_sandbox_nao_mostra_o_chip() {
        painel(detalhe = "dentro das áreas permitidas")
        compose.onAllNodesWithText("FORA DO SANDBOX").assertCountEquals(0)
    }

    @Test
    fun a_primeira_opcao_ja_vem_escolhida() {
        var decidido: String? = "nada"
        painel(decidido = { decidido = it })
        // Sem tocar em nada, autorizar decide a primeira opção — como no desenho.
        compose.onNodeWithText("Autorizar").performScrollTo().performClick()
        assertEquals("once", decidido)
    }

    @Test
    fun escolher_a_segunda_opcao_e_autorizar_decide_essa() {
        var decidido: String? = null
        painel(decidido = { decidido = it })
        compose.onNodeWithText("Permitir sempre").performClick()
        compose.onNodeWithText("Autorizar").performScrollTo().performClick()
        assertEquals("always", decidido)
    }

    @Test
    fun recusar_o_turno_no_painel_decide_null() {
        var decidido: String? = "nada"
        painel(decidido = { decidido = it })
        compose.onNodeWithText("Recusar o turno").performScrollTo().performClick()
        assertNull(decidido)
    }

    @Test
    fun as_licencas_listam_e_mostram_o_texto_integral() {        val licencas = listOf(
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

    @Test
    fun a_lista_de_conversas_esta_em_ingles() {
        compose.setContent {
            ListaDeConversas(
                conversas = listOf(
                    ItemConversa(
                        id = "cccc3333-4444-5555-6666-777777777777",
                        titulo = "a chat",
                        previa = "session cccc3333",
                        quando = "14/10 12:00",
                        activa = true,
                        falhou = false,
                    ),
                ),
                onAbrir = {},
                onNova = {},
            )
        }
        compose.onNodeWithText("1 conversation").assertIsDisplayed()
        compose.onNodeWithText("ACTIVE").assertIsDisplayed()
        compose.onNodeWithText("New").assertIsDisplayed()
    }

    @Test
    fun o_estado_vazio_esta_em_ingles() {
        compose.setContent { ListaDeConversas(conversas = emptyList(), onAbrir = {}, onNova = {}) }
        compose.onNodeWithText("No conversations yet").assertIsDisplayed()
        compose.onNodeWithText("New conversation").assertIsDisplayed()
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
    fun o_painel_de_autorizacao_esta_em_ingles() {
        compose.setContent {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PainelDeAutorizacao(
                    requestId = 1,
                    titulo = "touch /sdcard/x.txt",
                    detalhe = "outside the sandbox",
                    opcoes = listOf("once" to "Allow once"),
                    rodape = "PID: 1 · mode root",
                    onDecidir = {},
                )
            }
        }
        compose.onNodeWithText("Authorisation").assertIsDisplayed()
        compose.onNodeWithText("THE AGENT WANTS TO RUN:").assertIsDisplayed()
        compose.onNodeWithText("OUTSIDE THE SANDBOX").assertIsDisplayed()
        compose.onNodeWithText("Authorise").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reject the turn").performScrollTo().assertIsDisplayed()
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

/** O cartão da fila de tarefas. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "pt-rPT")
class FilaCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun card(
        tarefas: List<Tarefa>,
        aCorrer: Boolean = false,
        onJuntar: () -> Unit = {},
        onCorrer: () -> Unit = {},
        onParar: () -> Unit = {},
        onRemover: (String) -> Unit = {},
        onLimpar: () -> Unit = {},
    ) {
        compose.setContent {
            FilaCard(tarefas, aCorrer, enabled = true, onJuntar, onCorrer, onParar, onRemover, onLimpar)
        }
    }

    private val pendente = Tarefa("t1", "escrever o relatório")

    @Test
    fun com_a_fila_vazia_nao_se_pode_correr() {
        card(emptyList())
        compose.onNodeWithText("fila vazia").assertIsDisplayed()
        compose.onNodeWithText("Correr a fila").assertIsNotEnabled()
    }

    @Test
    fun mostra_as_pendentes_numeradas_e_conta_as_que_faltam() {
        card(listOf(pendente, Tarefa("t2", "rever o código")))
        compose.onNodeWithText("2 na fila").assertIsDisplayed()
        compose.onNodeWithText("1. escrever o relatório").assertIsDisplayed()
        compose.onNodeWithText("2. rever o código").assertIsDisplayed()
    }

    @Test
    fun juntar_e_correr_chamam_o_que_devem() {
        var juntou = 0
        var correu = 0
        card(listOf(pendente), onJuntar = { juntou++ }, onCorrer = { correu++ })
        compose.onNodeWithText("Juntar à fila").performClick()
        compose.onNodeWithText("Correr a fila").performClick()
        assertEquals(1, juntou)
        assertEquals(1, correu)
    }

    @Test
    fun a_correr_mostra_parar_em_vez_de_correr() {
        var parou = 0
        card(listOf(pendente), aCorrer = true, onParar = { parou++ })
        compose.onNodeWithText("Parar a fila").performClick()
        compose.onAllNodesWithText("Correr a fila").assertCountEquals(0)
        assertEquals(1, parou)
    }

    @Test
    fun o_x_remove_a_tarefa_certa() {
        var removida: String? = null
        card(listOf(pendente, Tarefa("t2", "outra")), onRemover = { removida = it })
        compose.onAllNodesWithText("✕")[0].performClick()
        assertEquals("t1", removida)
    }
}
