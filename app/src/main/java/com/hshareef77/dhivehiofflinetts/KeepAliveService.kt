package com.hshareef77.dhivehiofflinetts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the app process alive while a download or speech
 * generation runs, so the work continues when the screen is off or another app is open.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Working…"
        val n = notification(this, text)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        } catch (e: Exception) {
            // Starting in the foreground can be refused in rare background states; the work continues anyway.
        }
        if ((application as TtsApp).job == TtsApp.Job.NONE) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "work"
        private const val EXTRA_TEXT = "text"
        private var lastUpdate = 0L

        private fun notification(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)!!
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads and speech generation", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            return b.setContentTitle("Dhivehi Offline TTS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        fun start(ctx: Context, text: String) {
            val i = Intent(ctx, KeepAliveService::class.java).putExtra(EXTRA_TEXT, text.ifEmpty { "Working…" })
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { /* app in background without permission: work continues while possible */ }
        }

        fun update(ctx: Context, text: String) {
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 2000) return
            lastUpdate = now
            try { ctx.getSystemService(NotificationManager::class.java)?.notify(ID, notification(ctx, text)) } catch (e: Exception) {}
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }
}
