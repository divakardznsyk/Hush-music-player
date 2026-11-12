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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the player so music keeps going with the screen off / app closed.
 *
 * - player.volume is a gain applied to THIS app's audio only (independent of phone volume).
 * - Speed / pitch / volume are saved so they survive restarts.
 * - Battery: hardware audio offload when the phone supports it, and the player's main loop sleeps
 *   while the screen is off.
 * - There is no INTERNET permission in the manifest, so this service cannot use data or Wi-Fi.
 */
class PlaybackService : MediaSessionService() {

    companion object {
        const val ACTION_SLEEP = "app.hush.player.SLEEP"
        const val EXTRA_MINUTES = "minutes"
    }

    private var session: MediaSession? = null
    private var exo: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("hush", MODE_PRIVATE) }

    private val sleepRunnable = Runnable {
        session?.player?.pause()
        prefs.edit().putLong("sleepEnd", 0L).apply()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            exo?.experimentalSetOffloadSchedulingEnabled(intent.action == Intent.ACTION_SCREEN_OFF)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs.edit().putLong("sleepEnd", 0L).apply()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false // don't pause when a YouTube video starts; just mix
            )
            .setHandleAudioBecomingNoisy(true) // pause if headphones get unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)    // keep CPU awake only while playing local files
            .build()
        exo = player

        // Battery: let the phone's audio DSP decode when it can (falls back automatically).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    )
                    .setIsSpeedChangeSupportRequired(true)
                    .build()
            )
            .build()

        // Battery: with the screen off, the player's loop sleeps between audio buffers.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        player.experimentalSetOffloadSchedulingEnabled(!pm.isInteractive)
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
        player.playbackParameters = PlaybackParameters(
            prefs.getFloat("speed", 1f),
            prefs.getFloat("pitch", 1f)
        )
        player.addListener(object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                prefs.edit().putFloat("volume", volume).apply()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                prefs.edit()
                    .putFloat("speed", playbackParameters.speed)
                    .putFloat("pitch", playbackParameters.pitch)
                    .apply()
            }
        })

        session = MediaSession.Builder(this, player).build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_notif) }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
        session?.run {
            player.release()
            release()
        }
        session = null
        exo = null
        super.onDestroy()
    }
}
