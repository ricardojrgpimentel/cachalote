package dev.ricardopimentel.cachalote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Build

/**
 * «Parar tudo» — o botão que faltava.
 *
 * O Cachalote deixa coisas a correr **fora da app**: o daemon root (em `/data/adb`, arranca no boot
 * pelo `service.d` do Magisk), o daemon do Shizuku (em `/data/local/tmp`) e o motor embutido. Fechar
 * a app **não** os pára — e isso é por desenho (o agente tem de trabalhar com o ecrã apagado), mas
 * o dono tem de poder desligá-los. Sem isto, um processo a consumir bateria ficava sem botão
 * (aconteceu em 2026-10-21: um `tr` a 100 % e o telefone quente na mão, sem forma de o parar).
 *
 * Aqui não se adivinha: pára-se o que existir, diz-se o que se parou, e o arranque no boot é
 * desligado **também** — senão o próximo reboot trazia tudo de volta e «parar» era mentira.
 */
object PararTudo {

    /** O que se conseguiu parar, para se poder dizer no ecrã. */
    data class Resultado(val parados: List<String>, val falhas: List<String>) {
        val tudoParado: Boolean get() = falhas.isEmpty()
    }

    /** Para os daemons (root e Shizuku), o motor embutido e o serviço de agente. */
    suspend fun parar(ctx: Context): Resultado = withContext(Dispatchers.IO) {
        val parados = ArrayList<String>()
        val falhas = ArrayList<String>()

        // 1. Daemon root (em /data/adb). Pode não existir, ou o `su` pode estar negado.
        runCatching { RootShell.run("${RootShell.BASE}/bin/cachalote stop", 90).getOrThrow() }
            .onSuccess { parados += "daemon root" }
            .onFailure { if (RootShell.run("test -x ${RootShell.BASE}/bin/cachalote").isSuccess) falhas += "daemon root" }

        // 2. Daemon do Shizuku (em /data/local/tmp).
        runCatching {
            if (ShizukuBridge.available() && ShizukuBridge.hasPermission()) {
                ShizukuBridge.stopDaemon()
                parados += "daemon Shizuku"
            }
        }.onFailure { falhas += "daemon Shizuku" }

        // 3. O que vive dentro da app: o motor embutido (o serviço de agente pára-o por tabela).
        CachaloteAgentService.stop(ctx)
        parados += "serviço da app"

        Resultado(parados, falhas)
    }

    // ---------------------------------------------------------------- arranque no boot

    /** O `service.d` do Magisk está instalado **e ativo**? */
    suspend fun arranqueInstalado(): Boolean = runCatching {
        RootShell.run("test -f /data/adb/service.d/cachalote.sh && echo ok", 20)
            .getOrThrow().contains("ok")
    }.getOrDefault(false)

    /**
     * Liga/desliga o arranque no boot, **tirando o script da pasta**.
     *
     * ⚠ O Magisk corre **todos** os ficheiros de `service.d/`, com qualquer extensão — renomear
     * para `.off` não desliga nada (descoberto a 2026-10-22: o `cachalote.sh.off` de ontem foi
     * executado no arranque de hoje). Por isso o script sai para `$BASE/arranque/`, e volta de lá.
     */
    suspend fun definirArranque(ligar: Boolean): Boolean = runCatching {
        val cmd = if (ligar) {
            "mkdir -p /data/adb/service.d; " +
                "if [ -f ${RootShell.BASE}/arranque/cachalote.sh ]; then " +
                "mv ${RootShell.BASE}/arranque/cachalote.sh /data/adb/service.d/cachalote.sh; fi; " +
                "if [ -f /data/adb/service.d/cachalote.sh.off ]; then mv /data/adb/service.d/cachalote.sh.off /data/adb/service.d/cachalote.sh; fi; " +
                "chmod 755 /data/adb/service.d/cachalote.sh 2>/dev/null; test -f /data/adb/service.d/cachalote.sh && echo ok"
        } else {
            "mkdir -p ${RootShell.BASE}/arranque; " +
                "if [ -f /data/adb/service.d/cachalote.sh ]; then mv /data/adb/service.d/cachalote.sh ${RootShell.BASE}/arranque/cachalote.sh; fi; " +
                // Um `.off` antigo (do tempo em que se renomeava) sai também para `arranque/`:
                // o script nunca é apagado, senão o interruptor não o voltava a conseguir ligar.
                "if [ -f /data/adb/service.d/cachalote.sh.off ]; then mv /data/adb/service.d/cachalote.sh.off ${RootShell.BASE}/arranque/cachalote.sh; fi; " +
                "test ! -f /data/adb/service.d/cachalote.sh && test ! -f /data/adb/service.d/cachalote.sh.off && echo ok"
        }
        RootShell.run(cmd, 30).getOrThrow().contains("ok")
    }.getOrDefault(false)

    // ---------------------------------------------------------------- notificação

    const val CANAL_MOTOR = "cachalote_motor"
    const val ID_MOTOR = 43
    const val ACAO_PARAR = "dev.ricardopimentel.cachalote.PARAR_TUDO"

    /** Canal próprio, com importância baixa: é estado, não é alarme. */
    fun canal(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CANAL_MOTOR, ctx.getString(R.string.notif_canal_motor), NotificationManager.IMPORTANCE_LOW)
                .apply { description = ctx.getString(R.string.notif_canal_motor_desc) },
        )
    }

    /** PendingIntent do botão «Parar» da notificação (o receiver abaixo é que faz o trabalho). */
    fun intentParar(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        7,
        Intent(ctx, AcaoPararReceiver::class.java).setAction(ACAO_PARAR),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * O botão «Parar» da notificação. Corre num `goAsync` porque parar o daemon é I/O (um processo
 * `su`/Shizuku) e o `BroadcastReceiver` não pode bloquear o main thread.
 */
class AcaoPararReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PararTudo.ACAO_PARAR) return
        val pendente = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val r = PararTudo.parar(context)
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(PararTudo.ID_MOTOR)
                // Dizer o que se fez, sem abrir a app: uma notificação passageira.
                Notificacoes.fimDeTurno(
                    context,
                    context.getString(R.string.parar_tudo_feito_titulo),
                    if (r.tudoParado) context.getString(R.string.parar_tudo_feito_texto)
                    else context.getString(R.string.parar_tudo_parcial, r.falhas.joinToString(", ")),
                )
            } finally {
                pendente.finish()
            }
        }
    }
}
