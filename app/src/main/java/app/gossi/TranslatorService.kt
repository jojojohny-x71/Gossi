package app.gossi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import app.gossi.core.Engine
import app.gossi.ui.MainActivity

/** Keeps Gossi listening with the screen off. */
class TranslatorService : Service() {

    companion object {
        private const val CHANNEL = "gossi_live"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "app.gossi.STOP"

        fun start(context: Context) {
            val i = Intent(context, TranslatorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TranslatorService::class.java))
        }
    }

    private val listener = Engine.Listener { updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live translation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Gossi is listening"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Engine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Engine.addListener(listener)
        Engine.start()
        if (!Engine.running) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Engine.removeListener(listener)
        Engine.stop()
        super.onDestroy()
    }

    private var lastShown: String? = null

    private fun updateNotification() {
        if (!Engine.running) return
        val text = Engine.segments.lastOrNull { it.english != null }?.english ?: Engine.status
        if (text == lastShown) return
        lastShown = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val last = Engine.segments.lastOrNull { it.english != null }?.english
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_gossi)
            .setContentTitle("Gossi is translating to English")
            .setContentText(last ?: Engine.status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as Icon?, "Stop", stop).build())
            .build()
    }
}
