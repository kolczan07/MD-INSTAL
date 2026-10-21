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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

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
    private var currentStationName: String = "Radio"
    
    // MediaSession wysyła tylko metadane (dla BT i ekranu blokady)
    private var mediaSession: MediaSession? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE) {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                
                if (title.isNotEmpty()) currentStationName = title
                isPlayingState = playing
                
                updateMediaSession()
                updateNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession(this, "MD-INSTAL").apply {
            isActive = true
        }

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
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            ensureForeground()
            return START_STICKY
        }

        val name = intent.getStringExtra("name") ?: "Radio"
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
        
        currentStationName = name
        isPlayingState = playing

        ensureForeground()
        updateMediaSession()

        return START_STICKY
    }

    private fun ensureForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Radio", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = buildNotification(pi)
        startForeground(NOTIF_ID, notification)

        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mdinstal:radio").apply {
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun buildNotification(contentPi: PendingIntent): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)

        // Tylko nazwa stacji i MD-INSTAL Radio. Żadnych statusów "Gra/Wstrzymane".
        return builder
            .setContentTitle(currentStationName)
            .setContentText("MD-INSTAL Radio")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateMediaSession() {
        if (mediaSession == null) return

        // Wysyła tylko nazwę stacji na Bluetooth i ekran blokady
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentStationName)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "MD-INSTAL Radio")
            .build()
        mediaSession?.setMetadata(metadata)

        // To ustawienie mówi systemowi, że "coś gra" (żeby nie ucinał transmisji),
        // ale nie wyświetla przycisków ani statusów w powiadomieniu.
        val state = if (isPlayingState) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val playbackState = PlaybackState.Builder()
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(NOTIF_ID, buildNotification(pi))
    }

    override fun onDestroy() {
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
