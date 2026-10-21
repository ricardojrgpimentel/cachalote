package dev.ricardopimentel.cachalote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A regra do porto: **cada base tem o seu**, e a app não o adivinha.
 *
 * Porque é que isto merece teste: o bug que ele fecha (ROADMAP G1) não deu erro nenhum — a app
 * ligava-se ao daemon *errado* e dizia «token inválido». Uma regra pura, testada, é a forma de não
 * voltar a depender de dois sítios a concordarem por acaso.
 */
class PortosTest {

    @Test
    fun a_base_root_usa_o_porto_de_sempre() {
        assertEquals(39731, Portos.paraBase("/data/adb/cachalote"))
    }

    @Test
    fun a_base_do_shizuku_usa_outro_porto() {
        assertEquals(39733, Portos.paraBase("/data/local/tmp/cachalote"))
    }

    @Test
    fun a_barra_final_nao_muda_a_regra() {
        assertEquals(39731, Portos.paraBase("/data/adb/cachalote/"))
    }

    @Test
    fun uma_base_desconhecida_nao_cole_no_porto_do_root() {
        // Qualquer base fora de /data/adb é um daemon "de shell": tem de poder coexistir.
        assertFalse(Portos.paraBase("/sdcard/cachalote") == Portos.ROOT)
        assertEquals(Portos.SHELL, Portos.paraBase("/sdcard/cachalote"))
    }

    @Test
    fun os_dois_portos_sao_diferentes() {
        // Se algum dia forem iguais, os dois daemons voltam a lutar pelo mesmo porto.
        assertTrue(Portos.ROOT != Portos.SHELL)
    }
}
