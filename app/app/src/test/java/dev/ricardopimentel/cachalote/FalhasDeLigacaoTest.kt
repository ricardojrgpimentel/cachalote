package dev.ricardopimentel.cachalote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Distinguir «o daemon deste modo está parado» de «o motor respondeu mal».
 *
 * Foi a diferença entre a app se curar sozinha depois de um reboot (S10+, 2026-10-21) e ficar dois
 * dias a dizer «token inválido» — que era verdade, mas sobre o daemon **errado**.
 */
class FalhasDeLigacaoTest {

    @Test
    fun a_porta_fechada_e_a_porta_de_outro_daemon_contam_como_sem_daemon() {
        assertTrue(FalhasDeLigacao.portaSemDaemon("failed to connect to /127.0.0.1:39733"))
        assertTrue(FalhasDeLigacao.portaSemDaemon("Connection refused"))
        assertTrue(FalhasDeLigacao.portaSemDaemon("o motor devolveu um erro: token inválido"))
        assertTrue(FalhasDeLigacao.portaSemDaemon("ECONNREFUSED"))
    }

    @Test
    fun um_erro_do_motor_nao_e_tratado_como_daemon_parado() {
        assertFalse(FalhasDeLigacao.portaSemDaemon("sem DEEPSEEK_API_KEY em home/.env"))
        assertFalse(FalhasDeLigacao.portaSemDaemon("session is already active: 40ff160f"))
        assertFalse(FalhasDeLigacao.portaSemDaemon(null))
    }

    @Test
    fun a_mensagem_para_o_dono_diz_o_que_fazer() {
        val m = FalhasDeLigacao.explicar("Shizuku", "failed to connect to /127.0.0.1:39733")
        assertTrue(m.contains("Shizuku"))
        assertTrue("tinha de dizer onde se liga", m.contains("Motor"))
        assertFalse("não se mostra o jargão do socket", m.contains("127.0.0.1"))
    }

    @Test
    fun um_erro_que_nao_e_de_ligacao_passa_intacto() {
        val causa = "sem DEEPSEEK_API_KEY em home/.env"
        assertTrue(FalhasDeLigacao.explicar("root", causa) == causa)
    }

    @Test
    fun um_mcp_em_baixo_e_reconhecido_pelo_erro_generico() {
        // knowledge 134: com MCP configurado, o motor responde isto a tudo.
        assertTrue(
            FalhasDeLigacao.suspeitaDeMcp(
                "Internal error: mcp-client(stitch): initial connection or tool synchronization failed",
                temMcp = true,
            ),
        )
        assertTrue(FalhasDeLigacao.suspeitaDeMcp("-32603 Internal error", temMcp = true))
    }

    @Test
    fun sem_mcp_configurado_um_internal_error_nao_e_mcp() {
        assertFalse(FalhasDeLigacao.suspeitaDeMcp("Internal error", temMcp = false))
    }

    @Test
    fun um_erro_que_nao_e_do_mcp_nao_dispara_a_recuperacao() {
        assertFalse(FalhasDeLigacao.suspeitaDeMcp("sem DEEPSEEK_API_KEY em home/.env", temMcp = true))
        assertFalse(FalhasDeLigacao.suspeitaDeMcp(null, temMcp = true))
    }
}
