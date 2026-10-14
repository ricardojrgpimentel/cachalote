package dev.ricardopimentel.cachalote

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Avisos: o vigia da acessibilidade e as notificações (fim de turno, autorização pendente).
 *
 * A notificação de **autorização pendente** é a que mais falta fazia: sem ela, o agente fica
 * parado à espera de uma decisão que ninguém sabe que é precisa, porque o diálogo só existe
 * dentro da app.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AvisosTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm get() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun limpar() {
        Notificacoes.appEmPrimeiroPlano = false
        nm.cancelAll()
    }

    @After
    fun repor() {
        Notificacoes.appEmPrimeiroPlano = false
        nm.cancelAll()
    }

    // ---------------------------------------------------------------- vigia

    @Test
    fun o_vigia_so_avisa_quando_cai() {
        val vigia = VigiaAcessibilidade(inicial = true)
        assertNull("ligada → ligada não é queda", vigia.observar(true))
        assertNotNull("ligada → desligada é queda", vigia.observar(false))
        assertNull("desligada → desligada não repete o aviso", vigia.observar(false))
        assertNull("desligada → ligada não é queda", vigia.observar(true))
        assertEquals(1, vigia.quedas)
        assertTrue(vigia.ligada)
    }

    @Test
    fun o_vigia_conta_quedas_sucesivas() {
        val vigia = VigiaAcessibilidade(inicial = true)
        vigia.observar(false)
        vigia.observar(true)
        vigia.observar(false)
        assertEquals(2, vigia.quedas)
    }

    @Test
    fun quem_comeca_desligado_nao_gera_aviso_de_queda() {
        val vigia = VigiaAcessibilidade(inicial = false)
        assertNull(vigia.observar(false))
        assertNull(vigia.observar(true))
        assertEquals(0, vigia.quedas)
    }

    // ---------------------------------------------------------------- notificações

    @Test
    fun o_pedido_de_autorizacao_avisa_com_a_app_atras() {
        Notificacoes.pedidoDeAutorizacao(ctx, "O agente precisa da tua autorização", "está à espera: bash")
        val nossa = nm.activeNotifications.firstOrNull { it.id == Notificacoes.ID_AUTORIZACAO }
        assertNotNull("sem aviso, o turno fica pendurado em silêncio", nossa)
        val texto = nossa!!.notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue("o aviso devia dizer o que se pede (veio: '$texto')", texto.contains("bash"))
    }

    @Test
    fun responder_ao_pedido_tira_o_aviso() {
        Notificacoes.pedidoDeAutorizacao(ctx, "título", "detalhe")
        assertNotNull(nm.activeNotifications.firstOrNull { it.id == Notificacoes.ID_AUTORIZACAO })
        Notificacoes.cancelarPedidoDeAutorizacao(ctx)
        assertNull(
            "respondida a autorização, o aviso não pode ficar pendurado",
            nm.activeNotifications.firstOrNull { it.id == Notificacoes.ID_AUTORIZACAO },
        )
    }

    @Test
    fun com_a_app_a_frente_o_pedido_nao_notifica() {
        Notificacoes.appEmPrimeiroPlano = true
        Notificacoes.pedidoDeAutorizacao(ctx, "título", "detalhe")
        assertNull(
            "o diálogo está à frente: notificar seria ruído",
            nm.activeNotifications.firstOrNull { it.id == Notificacoes.ID_AUTORIZACAO },
        )
    }

    @Test
    fun a_queda_da_acessibilidade_notifica_com_a_app_atras() {
        Notificacoes.acessibilidadeDesligada(ctx, "A acessibilidade desligou-se", "liga em Definições")
        val nossa = nm.activeNotifications.firstOrNull { it.id == Notificacoes.ID_ACESSIBILIDADE }
        assertNotNull(nossa)
    }

    @Test
    fun as_notificacoes_sao_independentes() {
        Notificacoes.fimDeTurno(ctx, "acabou", "resposta")
        Notificacoes.pedidoDeAutorizacao(ctx, "autorização", "detalhe")
        val ids = nm.activeNotifications.map { it.id }
        assertTrue("o fim de turno devia estar lá", Notificacoes.ID_FIM_DE_TURNO in ids)
        assertTrue("o pedido devia estar lá", Notificacoes.ID_AUTORIZACAO in ids)
        // Uma não apaga a outra (foi por isso que cada uma ganhou um id próprio).
        assertFalse("as notificações não podem partilhar id", Notificacoes.ID_FIM_DE_TURNO == Notificacoes.ID_AUTORIZACAO)
    }
}
