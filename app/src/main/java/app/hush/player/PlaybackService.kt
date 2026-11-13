package app.hush.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors

/**
 * Owns the players so music keeps going with the screen off / app closed.
 *
 * - Deck 1 (internally A) is the main player. Deck 2 (internally B) is a second, independent player that is created only when
 *   you first use it (looping, with its own volume).
 * - player.volume is a gain applied to THIS app's audio only (independent of phone volume).
 * - Volume / speed are saved so they survive restarts.
 * - Battery: hardware audio offload on Deck 1 when the screen is off, no wake-ups when idle.
 * - There is no INTERNET permission in the manifest, so this service cannot use data or Wi-Fi.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_SLEEP = "app.hush.player.SLEEP"
        const val EXTRA_MINUTES = "minutes"
        const val ID_B = "deckB"
        const val ACTION_TOGGLE = "app.hush.player.TOGGLE_B"
    }

    private var sessionA: MediaSession? = null
    private var sessionB: MediaSession? = null
    private var exoA: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("hush", MODE_PRIVATE) }

    private val sleepRunnable = Runnable {
        sessionA?.player?.pause()
        sessionB?.player?.pause()
        prefs.edit().putLong("sleepEnd", 0L).apply()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            setOffload(intent.action == Intent.ACTION_SCREEN_OFF)
        }
    }

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** Lets the widget / headset buttons / Bluetooth resume your last song when nothing is loaded. */
    private val callbackA = object : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            io.execute {
                try {
                    val items: List<MediaItem> = Library.query(this@PlaybackService)
                    if (items.isEmpty()) {
                        future.setException(UnsupportedOperationException("No music"))
                    } else {
                        val last = prefs.getString("lastId", null)
                        var idx = items.indexOfFirst { it.mediaId == last }
                        val pos = if (idx >= 0) prefs.getLong("lastPos", 0L) else 0L
                        if (idx < 0) idx = (Math.random() * items.size).toInt()
                        future.set(MediaSession.MediaItemsWithStartPosition(items, idx, pos))
                    }
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs.edit().putLong("sleepEnd", 0L).putBoolean("isPlaying", false).putBoolean("deck2Alive", false).putBoolean("isPlayingB", false).apply()
        HushWidget.refresh(this)

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, /* handleAudioFocus = */ false) // mix with other apps
            .setHandleAudioBecomingNoisy(true) // pause if headphones get unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)    // keep CPU awake only while playing local files
            .experimentalSetDynamicSchedulingEnabled(true) // sleep between audio buffers when offloading
            .build()
        exoA = player

        // Battery: with the screen off, hand decoding to the phone's audio DSP (if it supports the
        // format) and let the CPU sleep. With the screen on, decode normally.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        setOffload(!pm.isInteractive)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Restore saved settings.
        player.volume = prefs.getFloat("volume", 1f)
        player.playbackParameters = PlaybackParameters(prefs.getFloat("speed", 1f), 1f)
        player.addListener(object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                prefs.edit().putFloat("volume", volume).apply()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                prefs.edit().putFloat("speed", playbackParameters.speed).apply()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null) prefs.edit().putString("lastId", mediaItem.mediaId).apply()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val e = prefs.edit().putBoolean("isPlaying", isPlaying)
                if (!isPlaying) e.putLong("lastPos", player.currentPosition)
                e.apply()
                HushWidget.refresh(this@PlaybackService)
            }
        })

        sessionA = MediaSession.Builder(this, player).setCallback(callbackA).build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationIdProvider { s -> if (s.id == ID_B) 1002 else 1001 }
                .build()
                .apply { setSmallIcon(R.drawable.ic_notif) }
        )
    }

    /** Widget key A: play / pause, or resume your last song if nothing is loaded. */
    private fun toggleA() {
        val p = exoA ?: return
        if (p.mediaItemCount > 0) {
            if (p.isPlaying) p.pause() else p.play()
            return
        }
        io.execute {
            val items = try { Library.query(this) } catch (e: Exception) { emptyList() }
            if (items.isEmpty()) return@execute
            val last = prefs.getString("lastId", null)
            var idx = items.indexOfFirst { it.mediaId == last }
            val pos = if (idx >= 0) prefs.getLong("lastPos", 0L) else 0L
            if (idx < 0) idx = 0
            handler.post {
                exoA?.run {
                    setMediaItems(items, idx, pos)
                    prepare()
                    play()
                }
            }
        }
    }

    private fun setOffload(enabled: Boolean) {
        val p = exoA ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        if (enabled) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                        else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                    )
                    .setIsSpeedChangeSupportRequired(true)
                    .build()
            )
            .build()
    }

    /** Deck 2: created the first time the app connects to it. Loops, own volume, own notification. */
    private fun ensureDeckB(): MediaSession {
        sessionB?.let { return it }
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttrs, false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.volume = prefs.getFloat("volumeB", 1f)
        p.addListener(object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                prefs.edit().putFloat("volumeB", volume).apply()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                prefs.edit().putBoolean("isPlayingB", isPlaying).apply()
                HushWidget.refresh(this@PlaybackService)
            }
        })
        prefs.edit().putBoolean("deck2Alive", true).apply()
        return MediaSession.Builder(this, p).setId(ID_B).build().also { sessionB = it }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.connectionHints.getString("deck") == "B") ensureDeckB() else sessionA

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            if (intent.getIntExtra("deck", 1) == 2) {
                val p = sessionB?.player
                if (p != null && p.mediaItemCount > 0) {
                    if (p.isPlaying) p.pause() else p.play()
                } else if ((sessionA?.player?.mediaItemCount ?: 0) == 0) stopSelf()
            } else toggleA()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SLEEP) {
            val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
            handler.removeCallbacks(sleepRunnable)
            if (minutes > 0) handler.postDelayed(sleepRunnable, minutes * 60_000L)
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music playing; if nothing is playing, shut down.
        val busy = listOfNotNull(sessionA?.player, sessionB?.player)
            .any { it.playWhenReady && it.mediaItemCount > 0 }
        if (!busy) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        prefs.edit().putBoolean("isPlaying", false).putBoolean("deck2Alive", false).putBoolean("isPlayingB", false).apply()
        HushWidget.refresh(this)
        sessionA?.run {
            player.release()
            release()
        }
        sessionB?.run {
            player.release()
            release()
        }
        sessionA = null
        sessionB = null
        exoA = null
        io.shutdown()
        super.onDestroy()
    }
}
