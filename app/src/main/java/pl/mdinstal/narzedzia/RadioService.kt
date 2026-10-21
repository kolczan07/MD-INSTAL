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
import android.view.KeyEvent

/**
 * Serwis radia w tle.
 *
 * Architektura:
 *  - Dźwięk gra w WebView (<audio>) w MainActivity — ten serwis NIE gra dźwięku.
 *  - Serwis trzyma:
 *      • MediaSession (tylko dla „dymka" i powiadomienia MediaStyle),
 *      • notyfikację MediaStyle,
 *      • wake lock.
 *  - Przyciski w notyfikacji → PendingIntent.getService → RadioService.onStartCommand
 *    (omija MediaSession.Callback, który na Androidzie 13+ bywa zawodny w WebView).
 *  - Przyciski BT → mediaButtonReceiver (ACTION_MEDIA_BUTTON) → sendJsCall.
 *  - Oba kanały wołają sendJsCall → broadcast ACTION_JS_CALL → MainActivity → JS.
 */
class RadioService : Service() {

    companion object {
        private const val TAG = "MDINSTAL"

        // Broadcast do MainActivity (polecenia play/pause/stop)
        const val ACTION_JS_CALL = "pl.mdinstal.narzedzia.JS_CALL"
        const val EXTRA_JS_CALL = "call"

        // Akcje PendingIntent z notyfikacji (trafiają do onStartCommand)
        const val ACTION_TOGGLE = "pl.mdinstal.narzedzia.TOGGLE"
        const val ACTION_STOP = "pl.mdinstal.narzedzia.STOP"
        const val EXTRA_TOGGLE_PLAY = "toggle_play"

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

    /** Odbiera polecenia z MainActivity (aktualizacja metadanych bez restartu serwisu). */
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE) {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: "MD-INSTAL Radio"
                val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                Log.d(TAG, "updateReceiver: title=$title playing=$playing")
                updateSession(title, artist, playing)
            }
        }
    }

    /** Odbiera przyciski BT/słuchawek (ACTION_MEDIA_BUTTON). */
    private val mediaButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return

            // Android 13+: getParcelableExtra(String) jest deprecated i może zwracać null.
            val event: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }

            if (event?.action != KeyEvent.ACTION_DOWN) return

            Log.d(TAG, "mediaButton: keyCode=${event.keyCode}")

            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY ->
                    sendJsCall("onMediaPlayPause(true)")
                KeyEvent.KEYCODE_MEDIA_PAUSE ->
                    sendJsCall("onMediaPlayPause(false)")
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                    sendJsCall("onMediaPlayPause(${!isPlayingState})")
                KeyEvent.KEYCODE_MEDIA_STOP ->
                    sendJsCall("onMediaStop()")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // MediaSession — tylko dla „dymka" i MediaStyle.
        // Callback zostawiamy, ale nie polegamy na nim (na Androidzie 13+ bywa zawodny).
        mediaSession = MediaSession(this, "MD-INSTAL").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setMediaButtonReceiver(
                PendingIntent.getBroadcast(
                    this@RadioService, 0,
                    Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            setCallback(object : MediaSession.Callback() {
                override fun onPlay()  {
                    Log.d(TAG, "MediaSession.Callback onPlay")
                    sendJsCall("onMediaPlayPause(true)")
                }
                override fun onPause() {
                    Log.d(TAG, "MediaSession.Callback onPause")
                    sendJsCall("onMediaPlayPause(false)")
                }
                override fun onStop()  {
                    Log.d(TAG, "MediaSession.Callback onStop")
                    sendJsCall("onMediaStop()")
                }
            })
            isActive = true
        }

        // Rejestracja updateReceiver (aktualizacja metadanych)
        val updateFilter = IntentFilter(ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(updateReceiver, updateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updateReceiver, updateFilter)
        }

        // Rejestracja mediaButtonReceiver (przyciski BT/słuchawek)
        val mediaFilter = IntentFilter(Intent.ACTION_MEDIA_BUTTON)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mediaButtonReceiver, mediaFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(mediaButtonReceiver, mediaFilter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 13+ wymaga zgody na notyfikacje, inaczej startForeground rzuci SecurityException.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Brak POST_NOTIFICATIONS — stopSelf")
            stopSelf()
            return START_NOT_STICKY
        }

        // Akcje z notyfikacji (PendingIntent.getService)
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val play = intent.getBooleanExtra(EXTRA_TOGGLE_PLAY, !isPlayingState)
                Log.d(TAG, "onStartCommand ACTION_TOGGLE play=$play")
                sendJsCall("onMediaPlayPause($play)")
                return START_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "onStartCommand ACTION_STOP")
                sendJsCall("onMediaStop()")
                return START_STICKY
            }
        }

        // Restart po zabiciu przez system (intent == null)
        if (intent == null) {
            Log.d(TAG, "onStartCommand intent=null (restart)")
            ensureForeground("Radio")
            return START_STICKY
        }

        // Normalny start z MainActivity
        val name = intent.getStringExtra("name") ?: "Radio"
        val title = intent.getStringExtra(EXTRA_TITLE)
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)

        Log.d(TAG, "onStartCommand name=$name title=$title playing=$playing")

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

    /** PendingIntent dla przycisku play/pause w notyfikacji — trafia do onStartCommand. */
    private fun playPausePendingIntent(play: Boolean): PendingIntent {
        val intent = Intent(this, RadioService::class.java).apply {
            action = ACTION_TOGGLE
            putExtra(EXTRA_TOGGLE_PLAY, play)
        }
        return PendingIntent.getService(
            this,
            if (play) 100 else 101,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** PendingIntent dla przycisku stop w notyfikacji — trafia do onStartCommand. */
    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, RadioService::class.java).apply {
            action = ACTION_STOP
        }
        return PendingIntent.getService(
            this, 102, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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
            if (isPlayingState) playPausePendingIntent(false) else playPausePendingIntent(true)
        ).build()

        val stopAction = Notification.Action.Builder(
            null,
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
        Log.d(TAG, "sendJsCall: $call")
        sendBroadcast(Intent(ACTION_JS_CALL).putExtra(EXTRA_JS_CALL, call))
    }

    override fun onDestroy() {
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(mediaButtonReceiver) } catch (_: Exception) {}
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        mediaSession?.release()
        mediaSession = null
        Log.d(TAG, "RadioService onDestroy")
        super.onDestroy()
    }
}