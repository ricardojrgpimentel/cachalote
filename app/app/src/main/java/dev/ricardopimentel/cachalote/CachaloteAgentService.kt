package dev.ricardopimentel.cachalote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Serviço em primeiro plano: mantém a app viva (e portanto a ligação ao daemon e as
 * capacidades Android disponíveis para o agente) durante turnos longos, com o ecrã
 * apagado e a app em segundo plano.
 *
 * Sem isto, o Android pode matar o processo a meio de uma tarefa: o agente perde as
 * "mãos" a meio e o turno fica pendurado. O serviço de acessibilidade ajuda, mas não
 * é garantido — e este é explícito.
 *
 * Tipo `specialUse` de propósito: não é sincronização de dados nem media; é "manter uma
 * ligação de agente", que é exactamente o caso de uso documentado para `specialUse`.
 * (O `dataSync` tem limite de ~6 h por 24 h no Android 15+.)
 */
class CachaloteAgentService : Service() {

    companion object {
        const val CHANNEL_ID = "cachalote_agent_service"
        const val NOTIF_ID = 42
        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CachaloteAgentService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CachaloteAgentService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_canal), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.notif_canal_desc) },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        PararTudo.canal(this)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        // O botão que faltava: **parar o motor daqui**, sem abrir a app. Fechar a app não pára o
        // daemon (ele é outro processo) — e sem este botão o dono não tinha como o desligar
        // (2026-10-21: telefone quente e nenhum sítio para parar).
        b.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                getString(R.string.parar_motor),
                PararTudo.intentParar(this),
            ).build(),
        )
        val notif: Notification = b
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_titulo_servico))
            .setContentText(getString(R.string.notif_texto_servico))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
