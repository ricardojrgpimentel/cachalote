package dev.ricardopimentel.cachalote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Notificações da própria app (não as que o agente pede).
 *
 * Serve para uma coisa concreta: quando um turno acaba e a app **não está à frente**, o
 * utilizador tem de saber — é o que torna o agente usável "em paralelo" (lanças o trabalho e
 * voltas ao que estavas a fazer). Se a app estiver visível, não se notifica nada: seria ruído.
 */
object Notificacoes {

    private const val CANAL = "cachalote-turno"
    const val ID_FIM_DE_TURNO = 1001

    /** Marcado pelo `MainActivity` (onResume/onPause) — é o que decide se há notificação. */
    var appEmPrimeiroPlano: Boolean = false

    private fun canal(ctx: Context, nome: String, descricao: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CANAL, nome, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = descricao
            },
        )
    }

    /** Avisa que o turno acabou. `resumo` é o início da resposta do agente (ou o erro). */
    fun fimDeTurno(ctx: Context, titulo: String, resumo: String) {
        if (appEmPrimeiroPlano) return
        val nome = ctx.getString(R.string.notif_canal_nome)
        canal(ctx, nome, ctx.getString(R.string.notif_canal_descricao))
        val abrir = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(titulo)
            .setContentText(resumo.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(resumo.take(600)))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ID_FIM_DE_TURNO, n)
        }
    }
}
