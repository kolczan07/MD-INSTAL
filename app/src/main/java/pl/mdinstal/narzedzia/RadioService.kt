package pl.mdinstal.narzedzia

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast

class RadioService : Service() {

    companion object {
        private const val TAG = "MDINSTAL"

        const val ACTION_JS_CALL = "pl.mdinstal.narzedzia.JS_CALL"
        const val EXTRA_JS_CALL = "call"

        const val ACTION_UPDATE = "pl.mdinstal.narzedzia.UPDATE_SESSION"
        const val EXTRA_TITLE = "update_title"
        const val EXTRA_PLAYING = "update_playing"

        const val CHANNEL_ID = "radio"
        const val NOTIF_ID = 1
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var isPlayingState: Boolean = false
    private var currentName: String = "Radio"

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE) {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Radio"
                val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                updateNotification(title, playing)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RadioService onCreate")

        val updateFilter = IntentFilter(ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, updateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updateReceiver, updateFilter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Brak POST_NOTIFICATIONS — stopSelf")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            ensureForeground("Radio", false)
            return START_STICKY
        }

        val name = intent.getStringExtra("name") ?: "Radio"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: name
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)

        currentName = title
        isPlayingState = playing

        ensureForeground(title, playing)

        return START_STICKY
    }

    private fun ensureForeground(name: String, playing: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Radio", NotificationManager.IMPORTANCE_LOW)
            )
        }
        
        val notification = buildNotification(name, playing)
        
        // Uruchamiamy zwykły foreground service (bez typu mediaPlayback)
        startForeground(NOTIF_ID, notification)

        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mdinstal:radio").apply {
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun updateNotification(name: String, playing: Boolean) {
        currentName = name
        isPlayingState = playing
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(name, playing))
    }

    private fun buildNotification(name: String, playing: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return builder
            .setContentTitle(name)
            .setContentText(if (playing) "Radio gra w tle" else "Radio wstrzymane")
            .setSmallIcon(R.mipmap.ic_launcher) // Ikona aplikacji zamiast domyślnej strzałki
            .setContentIntent(pi)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun sendJsCall(call: String) {
        Log.d(TAG, "sendJsCall: $call")
        sendBroadcast(Intent(ACTION_JS_CALL).putExtra(EXTRA_JS_CALL, call))
    }

    override fun onDestroy() {
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.d(TAG, "RadioService onDestroy")
        super.onDestroy()
    }
}
