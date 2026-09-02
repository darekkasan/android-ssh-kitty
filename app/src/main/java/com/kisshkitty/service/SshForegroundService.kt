package com.kisshkitty.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kisshkitty.KisshKittyApp
import com.kisshkitty.MainActivity
import com.kisshkitty.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SshForegroundService : Service() {

    @Inject
    lateinit var sshConnectionManager: com.kisshkitty.core.ssh.SshConnectionManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundService()
            ACTION_STOP -> stopForegroundService()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, KisshKittyApp.CHANNEL_ID)
            .setContentTitle("KisshKitty SSH")
            .setContentText("SSH connection active")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundService() {
        sshConnectionManager.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        sshConnectionManager.disconnect()
    }

    companion object {
        const val ACTION_START = "com.kisshkitty.service.START"
        const val ACTION_STOP = "com.kisshkitty.service.STOP"
        const val NOTIFICATION_ID = 1001
    }
}
