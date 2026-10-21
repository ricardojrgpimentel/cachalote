package dev.ricardopimentel.cachalote

/**
 * A regra dos portos do canal TCP — num sítio só, e testável sem aparelho.
 *
 * Porquê uma regra e não um valor: no mesmo telefone podem coexistir **dois** daemons (o root, em
 * `/data/adb/cachalote`, e o do Shizuku, em `/data/local/tmp/cachalote`). Se os dois ouvirem no
 * mesmo porto, a app liga-se ao primeiro que responder — e recebe «token inválido», porque o token
 * é por modo. Foi exactamente o que se viu no S10+ em 2026-10-21, depois de um reboot: o daemon
 * root subiu pelo Magisk no 39731 e a app, em modo Shizuku, falou com ele.
 *
 * O porto vem da **base** e fica escrito em `$BASE/home/port` (é o que o `bridge.cjs` lê, e o que
 * o `cachalote start` garante). Assim nenhuma das pontas tem de o adivinhar.
 */
object Portos {

    /** O daemon root (`/data/adb/cachalote`) — o valor histórico, e o que o `install.sh` usa. */
    const val ROOT = 39731

    /** Um daemon fora de `/data/adb` (o do Shizuku, em `/data/local/tmp/cachalote`). */
    const val SHELL = 39733

    const val BASE_ROOT = "/data/adb/cachalote"

    /**
     * O porto que corresponde a uma base. A regra é a mesma que o `cachalote` aplica no telefone:
     * `/data/adb/cachalote` → [ROOT]; qualquer outra base → [SHELL].
     */
    fun paraBase(base: String): Int =
        if (base.trimEnd('/') == BASE_ROOT) ROOT else SHELL
}
