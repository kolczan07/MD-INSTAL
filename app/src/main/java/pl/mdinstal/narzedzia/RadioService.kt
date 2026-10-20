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
import android.view.KeyEvent

/**
 * Serwis radia w tle.
 *
 * Architektura:
 *  - Dźwięk gra w WebView (<audio>) w MainActivity — ten serwis NIE gra dźwięku.
 *  - Serwis trzyma:
 *      • MediaSession (metadata + playback state + przyciski play/pause/stop),
 *      • notyfikację MediaStyle (widoczna na ekranie blokady i w panelu),
 *      • wake lock (proces nie jest usypiany w tle).
 *  - Gdy użytkownik klika play/pause/stop w notyfikacji lub na słuchawkach,
 *    MediaSession.Callback wysyła broadcast ACTION_JS_CALL do MainActivity,
 *    a MainActivity wywołuje odpowiednią funkcję w JS (onMediaPlayPause/onMediaStop).
 *  - JS nigdy nie woła updateMediaSession z powrotem przy obsłudze onMediaPlayPause,
 *    dzięki czemu nie ma pętli.
 */
class RadioService : Service() {

    companion object {
        // Broadcast do MainActivity (polecenia play/pause/stop z MediaSession)
        const val ACTION_JS_CALL = "pl.mdinstal.narzedzia.JS_CALL"
        const val EXTRA_JS_CALL = "call"

        // Broadcast do serwisu (aktualizacja metadanych bez restartu)
        const val ACTION_UPDATE = "pl.mdinstal.narzedzia.UPDATE_SESSION"
        const val EXTRA_TITLE = "update_title"
        const val EXTRA_ARTIST = "update_artist"
        const val EXTRA_PLAYING = "update_playing"

        // Notyfikacja
        const val CHANNEL_ID = "radio"
        const val NOTIF_ID = 1
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSession? = null

    /** Stan odtwarzania trzymany w polu — NIE czytamy z MediaSession.playbackState. */
    private var isPlayingState: Boolean = false

    /** Odbiera polecenia z MainActivity, żeby zaktualizować metadane bez restartu serwisu. */
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

        mediaSession = MediaSession(this, "MD-INSTAL").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPlay()  { sendJsCall("onMediaPlayPause(true)") }
                override fun onPause() { sendJsCall("onMediaPlayPause(false)") }
                override fun onStop()  { sendJsCall("onMediaStop()") }
            })
            isActive = true
        }

        val filter = IntentFilter(ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updateReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 13+ wymaga zgody na notyfikacje, inaczej startForeground rzuci SecurityException.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Jeśli serwis został zrestartowany przez system (intent == null),
        // nie mamy nazwy stacji — ale mamy żyć dalej.
        if (intent == null) {
            ensureForeground("Radio")
            return START_STICKY
        }

        val name = intent.getStringExtra("name") ?: "Radio"
        val title = intent.getStringExtra(EXTRA_TITLE)
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)

        ensureForeground(name)

        if (title != null) {
            updateSession(title, "MD-INSTAL Radio", playing)
        }

        return START_STICKY
    }

    /** Tworzy kanał, notyfikację MediaStyle i wywołuje startForeground. */
    private fun ensureForeground(name: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Radio", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
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

    /** Buduje notyfikację MediaStyle z akcjami play/pause/stop. */
    private fun buildNotification(name: String, contentPi: PendingIntent): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)

        val playPauseAction = Notification.Action.Builder(
            null,
            if (isPlayingState) "Pauza" else "Play",
            mediaButtonPendingIntent(if (isPlayingState) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY)
        ).build()

        val stopAction = Notification.Action.Builder(
            null,
            "Stop",
            mediaButtonPendingIntent(PlaybackState.ACTION_STOP)
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

    /** PendingIntent, który trafia do MediaSession jako akcja transportowa. */
    private fun mediaButtonPendingIntent(action: Long): PendingIntent {
        val keyCode = when (action) {
            PlaybackState.ACTION_PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            PlaybackState.ACTION_PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            PlaybackState.ACTION_STOP -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            setPackage(packageName)
        }
        return PendingIntent.getBroadcast(
            this, action.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Aktualizuje metadane + playback state + odświeża notyfikację. */
    private fun updateSession(title: String, artist: String, playing: Boolean) {
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

        // Odśwież notyfikację (bez restartu serwisu).
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(NOTIF_ID, buildNotification(title.ifEmpty { "Radio" }, pi))
    }

    /** Wysyła broadcast do MainActivity → MainActivity woła JS. */
    private fun sendJsCall(call: String) {
        sendBroadcast(Intent(ACTION_JS_CALL).putExtra(EXTRA_JS_CALL, call))
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
