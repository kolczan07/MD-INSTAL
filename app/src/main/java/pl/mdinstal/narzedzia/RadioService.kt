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
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
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

        const val ACTION_TOGGLE = "pl.mdinstal.narzedzia.TOGGLE"
        const val ACTION_STOP = "pl.mdinstal.narzedzia.STOP"
        const val EXTRA_TOGGLE_PLAY = "toggle_play"

        const val ACTION_UPDATE = "pl.mdinstal.narzedzia.UPDATE_SESSION"
        const val EXTRA_TITLE = "update_title"
        const val EXTRA_ARTIST = "update_artist"
        const val EXTRA_PLAYING = "update_playing"

        const val CHANNEL_ID = "radio"
        const val NOTIF_ID = 1
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null
    private var isPlayingState: Boolean = false

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE) {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: "MD-INSTAL Radio"
                val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                updateSession(title, artist, playing)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "RadioService onCreate")

        mediaSession = MediaSession(this, "MD-INSTAL").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSession.Callback onPlay")
                    isPlayingState = true
                    sendJsCall("onMediaPlayPause(true)")
                    updateSessionInternal("", "MD-INSTAL Radio", true)
                }
                override fun onPause() {
                    Log.d(TAG, "MediaSession.Callback onPause")
                    isPlayingState = false
                    sendJsCall("onMediaPlayPause(false)")
                    updateSessionInternal("", "MD-INSTAL Radio", false)
                }
                override fun onStop() {
                    Log.d(TAG, "MediaSession.Callback onStop")
                    isPlayingState = false
                    sendJsCall("onMediaStop()")
                }
            })
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
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        Toast.makeText(this, "onStartCommand: ${intent?.action}", Toast.LENGTH_LONG).show()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Brak POST_NOTIFICATIONS — stopSelf")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_TOGGLE -> {
                val play = intent.getBooleanExtra(EXTRA_TOGGLE_PLAY, !isPlayingState)
                Log.d(TAG, "ACTION_TOGGLE play=$play")
                Toast.makeText(this, "ACTION_TOGGLE play=$play", Toast.LENGTH_LONG).show()
                isPlayingState = play
                sendJsCall("onMediaPlayPause($play)")
                updateSessionInternal("", "MD-INSTAL Radio", play)
                return START_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP")
                Toast.makeText(this, "ACTION_STOP", Toast.LENGTH_LONG).show()
                isPlayingState = false
                sendJsCall("onMediaStop()")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (intent == null) {
            ensureForeground("Radio")
            return START_STICKY
        }

        val name = intent.getStringExtra("name") ?: "Radio"
        val title = intent.getStringExtra(EXTRA_TITLE)
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
        isPlayingState = playing

        ensureForeground(name)
        if (title != null) {
            updateSession(title, "MD-INSTAL Radio", playing)
        }

        return START_STICKY
    }

    private fun ensureForeground(name: String) {
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
        val notification = buildNotification(name, pi)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mdinstal:radio").apply {
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun playPausePendingIntent(play: Boolean): PendingIntent {
        val intent = Intent(this, ActionProxyActivity::class.java).apply {
            action = ACTION_TOGGLE
            putExtra(EXTRA_TOGGLE_PLAY, play)
        }
        return PendingIntent.getActivity(
            this, if (play) 100 else 101, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, ActionProxyActivity::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getActivity(
            this, 102, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(name: String, contentPi: PendingIntent): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)

        val playPauseIcon = if (isPlayingState)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play

        val playPauseAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, playPauseIcon),
            if (isPlayingState) "Pauza" else "Play",
            if (isPlayingState) playPausePendingIntent(false) else playPausePendingIntent(true)
        ).build()

        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop",
            stopPendingIntent()
        ).build()

        return builder
            .setContentTitle(name)
            .setContentText("MD-INSTAL Radio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .addAction(playPauseAction)
            .addAction(stopAction)
            .build()
    }

    private fun updateSession(title: String, artist: String, playing: Boolean) {
        isPlayingState = playing
        updateSessionInternal(title, artist, playing)
    }

    private fun updateSessionInternal(title: String, artist: String, playing: Boolean) {
        isPlayingState = playing

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .build()
        mediaSession?.setMetadata(metadata)

        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val playbackState = PlaybackState.Builder()
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP
            )
            .build()
        mediaSession?.setPlaybackState(playbackState)

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(NOTIF_ID, buildNotification(title.ifEmpty { "Radio" }, pi))
    }

    private fun sendJsCall(call: String) {
        Log.d(TAG, "sendJsCall: $call")
        sendBroadcast(Intent(ACTION_JS_CALL).putExtra(EXTRA_JS_CALL, call))
    }

    override fun onDestroy() {
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        mediaSession?.release()
        mediaSession = null
        Log.d(TAG, "RadioService onDestroy")
        super.onDestroy()
    }
}
