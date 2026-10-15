package com.codecsrayo.presionbp.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.codecsrayo.presionbp.MainActivity
import com.codecsrayo.presionbp.R
import com.codecsrayo.presionbp.analysis.Finding
import com.codecsrayo.presionbp.analysis.Level

object Notifier {
    private const val CH_URGENT = "urgente"
    private const val CH_FINDINGS = "hallazgos"
    private const val CH_SYNC = "sincronizacion_v2"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_URGENT, "Valores urgentes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Mediciones en rango de crisis hipertensiva"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_FINDINGS, "Hallazgos", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Presión alta o baja, pulso fuera de rango, latido irregular y tendencias"
            },
        )
        nm.deleteNotificationChannel("sincronizacion") // canal antiguo, era silencioso
        nm.createNotificationChannel(
            NotificationChannel(CH_SYNC, "Sincronización automática", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Aviso cuando llega una medición nueva sin abrir la app"
            },
        )
    }

    /** Aviso discreto de que llegó una medición por sincronización automática. */
    fun newReading(context: Context, sys: Int, dia: Int, pulse: Int, count: Int) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CH_SYNC)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Nueva medición: $sys/$dia · $pulse lpm")
            .setContentText(if (count > 1) "$count mediciones nuevas sincronizadas" else "Sincronizada automáticamente")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(1, n)
    }

    fun notify(context: Context, findings: List<Finding>) {
        if (findings.isEmpty()) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val nm = NotificationManagerCompat.from(context)
        findings.forEachIndexed { i, f ->
            val urgent = f.level == Level.URGENT
            val n = NotificationCompat.Builder(context, if (urgent) CH_URGENT else CH_FINDINGS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(f.title)
                .setContentText(f.detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(f.detail))
                .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            @Suppress("MissingPermission")
            nm.notify((f.title.hashCode() * 31 + i), n)
        }
    }
}
