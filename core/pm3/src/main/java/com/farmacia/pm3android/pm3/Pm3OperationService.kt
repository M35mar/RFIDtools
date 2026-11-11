// Keeps a long PM3 command alive while the app is in the background and
// exposes a notification action that asks the client to stop.
//
// The service itself does not own the command: it only keeps the process in
// the foreground. The ViewModel starts it before a native/Python command and
// stops it when the command returns.
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.farmacia.pm3android.pm3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.farmacia.pm3android.R

class Pm3OperationService : Service() {

    companion object {
        private const val ACTION_START = "com.farmacia.pm3android.action.START_OPERATION"
        private const val ACTION_STOP = "com.farmacia.pm3android.action.STOP_OPERATION"
        private const val CHANNEL_ID = "pm3_operation"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            // The command is accepted at this point: clear a stop left by the
            // previous one before its stop window opens. Resetting later would
            // drop a stop pressed while the script was still starting.
            Pm3PythonRunner.resetStop()
            val intent = Intent(context, Pm3OperationService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stops the foreground service. This is a plain stopService() call, so
         * it also works when the app is in the background; startService() with
         * an ACTION_STOP intent would be subject to background limits.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, Pm3OperationService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stop both paths: native commands poll g_user_interrupt, while
            // Python scripts poll Pm3Native.stopRequested between instructions.
            Pm3Native.stopRequested = true
            Pm3Native.nativeAbort()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, Pm3OperationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pm3)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_stop),
                stopIntent,
            )
            .build()
        // A missing runtime permission or an OS restriction must not crash the
        // app: the command still runs in the ViewModel, only the notification
        // is lost.
        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
