package dev.ricardopimentel.cachalote

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.rule.GrantPermissionRule

/**
 * Testes do que a app faz sozinha: os dados das licenças e a notificação de fim de turno.
 *
 * A notificação era o caso típico que eu andava a testar com `uiautomator` e toques cegos
 * (e que falhava por o campo não ter foco, o teclado tapar o botão, etc.). Aqui chama-se o
 * código e pergunta-se ao sistema se a notificação existe — sem tocar no ecrã.
 */
@RunWith(AndroidJUnit4::class)
class RecursosTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Sem esta permissão o sistema engole a notificação e o teste daria um falso negativo. */
    @get:Rule
    val notificacoesPermitidas: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun as_licencas_carregam_com_texto_integral() {
        val licencas = Licencas.carregar(ctx)
        assertTrue("esperava pelo menos 10 licenças, vi ${licencas.size}", licencas.size >= 10)
        for (l in licencas) {
            assertTrue("licença sem nome", l.nome.isNotBlank())
            assertTrue("licença sem tipo (${l.nome})", l.licenca.isNotBlank())
            assertTrue("licença sem texto (${l.nome})", l.texto.length > 60)
        }
        val nomes = licencas.map { it.nome }
        assertTrue("falta o Node", nomes.any { it.contains("Node") })
        assertTrue("falta o Apache-2.0 (AndroidX)", nomes.any { it.contains("AndroidX") })
        assertTrue("falta o SQLite", nomes.any { it.contains("SQLite") })
    }

    @Test
    fun os_textos_das_licencas_sao_os_verdadeiros() {
        val porNome = Licencas.carregar(ctx).associateBy { it.nome }
        val apache = porNome.values.first { it.licenca == "Apache-2.0" }
        assertTrue("o texto do Apache-2.0 não parece o Apache-2.0", apache.texto.contains("Apache License"))
        val mit = porNome.values.first { it.nome.contains("Cachalote") }
        assertTrue("o texto MIT não parece MIT", mit.texto.contains("MIT License"))
        assertTrue("o MIT não tem a cláusula de garantia", mit.texto.contains("WITHOUT WARRANTY"))
        val unicode = porNome.values.first { it.nome.contains("ICU") }
        assertTrue("o texto da Unicode não parece a Unicode", unicode.texto.contains("UNICODE LICENSE"))
        val zlib = porNome.values.first { it.nome.contains("zlib") }
        // O texto do zlib não diz "zlib" em lado nenhum — a frase canónica é outra.
        assertTrue("o texto do zlib não parece o do zlib", zlib.texto.contains("as-is"))
    }

    @Test
    fun a_notificacao_de_fim_de_turno_aparece_com_a_app_atras() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(Notificacoes.ID_FIM_DE_TURNO)
        Notificacoes.appEmPrimeiroPlano = false

        Notificacoes.fimDeTurno(ctx, "título de teste", "resumo do turno de teste")

        val activas = nm.activeNotifications
        val nossa = activas.firstOrNull { it.id == Notificacoes.ID_FIM_DE_TURNO }
        assertNotNull("a notificação de fim de turno não apareceu", nossa)
        val texto = nossa!!.notification.extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue("a notificação não leva o resumo (veio: '$texto')", texto.contains("resumo do turno"))
        nm.cancel(Notificacoes.ID_FIM_DE_TURNO)
    }

    @Test
    fun nao_se_notifica_com_a_app_a_frente() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(Notificacoes.ID_FIM_DE_TURNO)
        Notificacoes.appEmPrimeiroPlano = true

        Notificacoes.fimDeTurno(ctx, "não deve aparecer", "…")

        val nossa = nm.activeNotifications.firstOrNull { it.id == Notificacoes.ID_FIM_DE_TURNO }
        assertFalse("notificou com a app à frente — seria ruído", nossa != null)
        Notificacoes.appEmPrimeiroPlano = false
    }
}
