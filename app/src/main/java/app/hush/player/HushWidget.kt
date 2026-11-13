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
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils

/**
 * 1x1 home-screen widget: one tap toggles play / pause on the main player (Deck 1).
 * If Hush isn't running it resumes your last song. Subway bullet in a dotted ring: white while paused, Deck 1 colour while playing.
 * It never polls (updatePeriodMillis = 0); the service refreshes it only when playback changes.
 */
class HushWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        manager.updateAppWidget(appWidgetIds, build(context))
    }

    companion object {
        fun build(context: Context): RemoteViews {
            val prefs = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
            val playing = prefs.getBoolean("isPlaying", false)
            val accent = prefs.getInt("accentD1", prefs.getInt("accent", 0xFFD71921.toInt()))

            val rv = RemoteViews(context.packageName, R.layout.widget_play)
            rv.setImageViewBitmap(R.id.wImg, draw(playing, accent))

            // A media-button "play/pause" press: Hush's service handles it (and resumes if needed).
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, PlaybackService::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            val pi = PendingIntent.getForegroundService(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.wRoot, pi)
            return rv
        }

        private fun draw(playing: Boolean, accent: Int): Bitmap {
            val s = 288
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            p.color = 0xFF000000.toInt()
            c.drawRoundRect(RectF(2f, 2f, s - 2f, s - 2f), 90f, 90f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = 0xFF262626.toInt()
            c.drawRoundRect(RectF(3f, 3f, s - 3f, s - 3f), 89f, 89f, p)
            p.style = Paint.Style.FILL

            // dot-matrix glyph (Nothing style): pause lit in the deck colour while playing, play in white when paused
            val step = s * 0.07f
            val x0 = s * 0.31f
            val y0 = s * 0.31f
            val lit = HashSet<Int>()
            for (r in 1..7) {
                if (playing) {
                    for (col in intArrayOf(2, 3, 5, 6)) lit.add(r * 9 + col)
                } else {
                    val w = if (r <= 4) r - 1 else 7 - r
                    for (col in 3..3 + w) lit.add(r * 9 + col)
                }
            }
            for (r in 0 until 9) for (col in 0 until 9) {
                val on = lit.contains(r * 9 + col)
                p.color = if (on) (if (playing) accent else 0xFFF2F2F2.toInt()) else 0xFF222222.toInt()
                c.drawCircle(x0 + col * step - 4 * step + 4 * step, y0 + r * step, step * 0.3f, p)
            }

            // little subway bullet "A"
            val br = s * 0.1f
            val bx = s * 0.2f
            val by = s * 0.2f
            p.color = accent
            c.drawCircle(bx, by, br, p)
            p.color = if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE
            p.textAlign = Paint.Align.CENTER
            p.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL)
            p.textSize = br * 1.25f
            c.drawText("A", bx, by + br * 0.44f, p)
            return bmp
        }

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, HushWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(context))
        }
    }
}
