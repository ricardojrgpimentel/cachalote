package dev.ricardopimentel.cachalote

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Duas línguas a sério: inglês (omissão) e **português europeu** (pt-PT).
 *
 * Estes testes substituem a tentativa (falhada) de verificar idiomas trocando o idioma do
 * aparelho: aqui cria-se um `Context` com a configuração forçada, o que é determinístico.
 * O `cmd locale set-app-locales` não pegou nesta LineageOS — isto pega sempre.
 */
@RunWith(AndroidJUnit4::class)
class I18nTest {

    private val base: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun contexto(locale: String): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(locale))
        return base.createConfigurationContext(config)
    }

    @Test
    fun ingles_e_a_omissao_e_esta_em_ingles() {
        val en = contexto("en")
        assertEquals("Chat", en.getString(R.string.aba_conversa))
        assertEquals("Status", en.getString(R.string.aba_estado))
        assertEquals("Powers", en.getString(R.string.aba_poderes))
        assertTrue(en.getString(R.string.aviso_root).startsWith("Warning:"))
    }

    @Test
    fun portugues_europeu_esta_em_pt_pt() {
        val pt = contexto("pt-PT")
        assertEquals("Conversa", pt.getString(R.string.aba_conversa))
        assertEquals("Estado", pt.getString(R.string.aba_estado))
        assertEquals("Poderes", pt.getString(R.string.aba_poderes))
        // pt-PT, não pt-BR: "ecrã" (não "tela") e "a carregar" (não "carregando").
        val titulo = pt.getString(R.string.vscreen_titulo)
        assertTrue("titulo veio '$titulo'", titulo == "Ecrã virtual")
        val vscreen = pt.getString(R.string.vscreen_texto)
        assertTrue("vscreen_texto veio '$vscreen'", vscreen.contains("ecrã"))
        assertTrue("vscreen_texto tem 'tela': '$vscreen'", !vscreen.contains("tela"))
        assertTrue("vscreen_texto tem 'carregando'", !vscreen.contains("carregando"))
    }

    @Test
    fun um_aparelho_em_pt_br_nunca_ve_portugues_do_brasil() {
        // Decisão do projecto: pt-PT e inglês, mais nada. Um aparelho em pt-BR resolve para os
        // recursos pt-PT (o Android não tem `values-pt-rBR` para escolher) — o que interessa é
        // que **nunca** apareçam formas brasileiras, em nenhum idioma.
        val br = contexto("pt-BR")
        assertEquals("Conversa", br.getString(R.string.aba_conversa))

        val marcadores = listOf("tela", "celular", "arquivo", "carregando", "você", "aplicativo", "registro")
        for (campo in R.string::class.java.fields) {
            val texto = br.getString(campo.getInt(null)).lowercase()
            for (m in marcadores) {
                assertTrue("pt-BR-ismo '$m' em ${campo.name}: $texto", !texto.contains(m))
            }
        }
    }

    @Test
    fun nenhuma_string_fica_vazia_em_nenhum_idioma() {
        val en = contexto("en")
        val pt = contexto("pt-PT")
        var verificadas = 0
        for (campo in R.string::class.java.fields) {
            val id = campo.getInt(null)
            val textoEn = en.getString(id)
            val textoPt = pt.getString(id)
            assertTrue("string vazia em inglês: ${campo.name}", textoEn.isNotBlank())
            assertTrue("string vazia em pt-PT: ${campo.name}", textoPt.isNotBlank())
            verificadas++
        }
        assertTrue("esperava pelo menos 90 strings, vi $verificadas", verificadas >= 90)
    }

    @Test
    fun a_maioria_das_strings_esta_traduzida() {
        val en = contexto("en")
        val pt = contexto("pt-PT")
        var iguais = 0
        var total = 0
        for (campo in R.string::class.java.fields) {
            val id = campo.getInt(null)
            total++
            if (en.getString(id) == pt.getString(id)) iguais++
        }
        // Algumas são iguais de propósito (nome da app, exemplos, identificadores como
        // DEEPSEEK_API_KEY); se quase tudo fosse igual, o pt-PT não estaria traduzido.
        assertTrue("demasiadas strings iguais ($iguais de $total): falta tradução?", iguais < total / 3)
        assertNotEquals("nenhuma string igual — suspeito", total, iguais)
    }
}
