package app.hush.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils

/**
 * 2x1 home-screen widget, Braun style: two keys, A and B. Tap a key to play / pause that deck.
 * Never polls (updatePeriodMillis = 0); the service refreshes it only when playback changes.
 */
class HushWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        manager.updateAppWidget(appWidgetIds, build(context))
    }

    companion object {
        fun build(context: Context): RemoteViews {
            val prefs = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
            val rv = RemoteViews(context.packageName, R.layout.widget_play)
            val accA = prefs.getInt("accentD1", prefs.getInt("accent", 0xFFD71921.toInt()))
            val accB = prefs.getInt("accentD2", 0xFF00A859.toInt())
            rv.setImageViewBitmap(R.id.wImg, draw(prefs.getBoolean("isPlaying", false), accA, "A"))
            rv.setImageViewBitmap(R.id.wImgB, draw(prefs.getBoolean("isPlayingB", false), accB, "B"))

            // A: media-button press (resumes your last song if nothing is loaded).
            val a = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, PlaybackService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            rv.setOnClickPendingIntent(
                R.id.wImg,
                PendingIntent.getForegroundService(context, 0, a, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            // B: play / pause deck B from the home screen.
            val b = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_TOGGLE_B)
            rv.setOnClickPendingIntent(
                R.id.wImgB,
                PendingIntent.getService(context, 1, b, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            return rv
        }

        private fun draw(playing: Boolean, accent: Int, letter: String): Bitmap {
            val s = 288
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            // light warm-grey body
            p.color = 0xFFE9E8E4.toInt()
            c.drawRoundRect(RectF(4f, 4f, s - 4f, s - 4f), 72f, 72f, p)

            // deck letter
            p.color = 0xFF3A3A3A.toInt()
            p.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            p.textSize = 34f
            c.drawText(letter, 36f, 64f, p)

            // big key: deck colour while playing, grey when paused
            val cx = s / 2f
            val cy = 124f
            val r = 62f
            p.color = if (playing) accent else 0xFFC9C8C3.toInt()
            c.drawCircle(cx, cy, r, p)
            p.color = if (playing) (if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE) else 0xFF2B2B2B.toInt()
            if (playing) {
                c.drawRoundRect(RectF(cx - 24f, cy - 28f, cx - 8f, cy + 28f), 4f, 4f, p)
                c.drawRoundRect(RectF(cx + 8f, cy - 28f, cx + 24f, cy + 28f), 4f, 4f, p)
            } else {
                val path = Path().apply {
                    moveTo(cx - 16f, cy - 30f)
                    lineTo(cx + 30f, cy)
                    lineTo(cx - 16f, cy + 30f)
                    close()
                }
                c.drawPath(path, p)
            }

            // speaker grille
            p.color = 0xFFB4B3AE.toInt()
            for (row in 0 until 3) for (i in 0 until 7) {
                c.drawCircle(60f + i * 28f, 214f + row * 22f, 5f, p)
            }
            return bmp
        }

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, HushWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(context))
        }
    }
}
