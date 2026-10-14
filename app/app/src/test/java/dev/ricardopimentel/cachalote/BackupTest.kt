package dev.ricardopimentel.cachalote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Backup das definições — e, sobretudo, o que ele **não** leva.
 *
 * O teste que interessa não é "exporta e importa": é garantir que a **chave do provedor** e o
 * **token do canal** nunca entram no ficheiro. Um backup que anda por aí com segredos dentro é
 * uma fuga à espera de acontecer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun exporta_importa_e_nao_leva_segredos() {
        val prefs = Prefs(ctx)
        prefs.mode = "daemon"
        prefs.allowedRoots = "/sdcard/Download"
        prefs.kernelUrl = "https://exemplo.invalido/kernel.zip"
        prefs.mcpServersJson = """[{"name":"ping","command":"/bin/true"}]"""
        prefs.setCapabilityAllowed("screenshot", true)
        // Segredos que existem nas preferências e **não** podem sair no backup:
        prefs.token = "token-secretissimo-do-canal"
        prefs.tokenShizuku = "token-do-shizuku"

        val json = Backup.exportar(ctx, prefs).toString()

        assertFalse("o token do canal saiu no backup", json.contains("token-secretissimo-do-canal"))
        assertFalse("o token do Shizuku saiu no backup", json.contains("token-do-shizuku"))
        assertFalse("a palavra token aparece no backup", json.contains("token", ignoreCase = true))
        // Comparar pelo conteúdo e não pela formatação (o `toString(2)` do org.json varia).
        assertEquals("cachalote", org.json.JSONObject(json).optString("app"))
        assertTrue("o modo devia estar lá", json.contains("daemon"))

        // Estraga tudo e restaura.
        prefs.mode = "embedded"
        prefs.kernelUrl = ""
        prefs.allowedRoots = ""
        prefs.mcpServersJson = "[]"
        prefs.setCapabilityAllowed("screenshot", false)

        val aplicadas = Backup.importar(ctx, prefs, json)

        assertEquals("daemon", prefs.mode)
        assertEquals("https://exemplo.invalido/kernel.zip", prefs.kernelUrl)
        assertEquals("/sdcard/Download", prefs.allowedRoots)
        assertEquals(true, prefs.isCapabilityAllowed("screenshot"))
        assertTrue("o MCP devia ter voltado", prefs.mcpServersJson.contains("ping"))
        assertTrue("esperava pelo menos 4 definições aplicadas, vi $aplicadas", aplicadas >= 4)
    }

    @Test
    fun um_ficheiro_que_nao_e_nosso_e_recusado() {
        val prefs = Prefs(ctx)
        assertThrows(IllegalArgumentException::class.java) {
            Backup.importar(ctx, prefs, """{"app":"outra-coisa","modo":"daemon"}""")
        }
    }

    @Test
    fun mcp_invalido_e_recusado_antes_de_estragar_as_sessoes() {
        val prefs = Prefs(ctx)
        prefs.mcpServersJson = "[]"
        assertThrows(Exception::class.java) {
            Backup.importar(ctx, prefs, """{"app":"cachalote","mcpServers":"isto não é json"}""")
        }
        // E o que lá estava continua lá: um backup mau não pode deixar as sessões novas a falhar.
        assertEquals("[]", prefs.mcpServersJson)
    }

    @Test
    fun um_modo_desconhecido_e_ignorado() {
        val prefs = Prefs(ctx)
        prefs.mode = "daemon"
        val aplicadas = Backup.importar(ctx, prefs, """{"app":"cachalote","modo":"modo-que-nao-existe"}""")
        assertEquals("daemon", prefs.mode)
        assertEquals(0, aplicadas)
    }
}
