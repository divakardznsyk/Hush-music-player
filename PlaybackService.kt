package app.hush.player

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the player so music keeps going with the screen off / app closed.
 *
 * player.volume is a gain applied to THIS app's audio only. It is independent of the
 * phone's media volume, so phone at 100% + Hush at 20% = quiet music next to a loud video.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("hush", MODE_PRIVATE)

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false // don't pause when a YouTube video starts; just mix
            )
            .setHandleAudioBecomingNoisy(true) // pause if headphones get unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)    // keep CPU awake for local files
            .build()

        player.volume = prefs.getFloat("volume", 1f)
        player.addListener(object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                prefs.edit().putFloat("volume", volume).apply()
            }
        })

        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music playing; if nothing is playing, shut down.
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
