package app.fireremote.phone

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the connection alive while you switch to other apps, with a notification to get back or disconnect.
 * Disconnects by itself after the background time set in Settings.
 */
class RemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TvLink.init(this)
        if (intent?.action == ACTION_STOP) {
            TvLink.stop(byUser = true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_KEY) {
            if (TvLink.state.value.phase == TvLink.Phase.Off) TvLink.start(Store.host(this), Store.port(this))
            TvLink.key(intent.getIntExtra(EXTRA_CODE, 0))
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(TvLink.state.value), type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            watching = true
            scope.launch {
                TvLink.state.collect { st ->
                    if (st.phase == TvLink.Phase.Off) stopSelf()
                    else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(st))
                }
            }
            scope.launch {
                TvLink.foreground.collectLatest { visible ->
                    val minutes = UiPrefs.idleMinutes
                    if (!visible && minutes > 0) {
                        delay(minutes * 60_000L)
                        TvLink.stop(byUser = false)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    private fun build(st: TvLink.State): Notification {
        val host = st.target.removeSuffix(":5555")
        val text = when (st.phase) {
            TvLink.Phase.Online -> getString(if (st.instant) R.string.notif_connected else R.string.notif_connected_basic, host)
            TvLink.Phase.Authorizing -> getString(R.string.status_authorizing)
            TvLink.Phase.Waiting -> getString(R.string.notif_reconnecting, host)
            else -> getString(R.string.notif_connecting, host)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemoteService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun keyIntent(code: Int) = PendingIntent.getService(
            this, 100 + code, Intent(this, RemoteService::class.java).setAction(ACTION_KEY).putExtra(EXTRA_CODE, code),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle("Fire Remote")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.key_play), keyIntent(K.PLAY))
            .addAction(0, getString(R.string.key_back), keyIntent(K.BACK))
            .addAction(0, getString(R.string.menu_disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "link"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.fireremote.phone.STOP"
        private const val ACTION_KEY = "app.fireremote.phone.KEY"
        private const val EXTRA_CODE = "code"

        fun start(c: Context) {
            runCatching { ContextCompat.startForegroundService(c, Intent(c, RemoteService::class.java)) }
        }
    }
}
