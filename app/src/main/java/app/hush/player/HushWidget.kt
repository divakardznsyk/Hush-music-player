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
            val cx = s / 2f
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            // black sign with a thin edge
            p.color = 0xFF0B0B0B.toInt()
            c.drawRoundRect(RectF(2f, 2f, s - 2f, s - 2f), 64f, 64f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = 0xFF2A2A2A.toInt()
            c.drawRoundRect(RectF(3f, 3f, s - 3f, s - 3f), 63f, 63f, p)
            p.style = Paint.Style.FILL

            // dot-matrix ring: lit in the accent colour while playing
            val n = 36
            val ringR = s * 0.405f
            for (i in 0 until n) {
                val a = i * 2.0 * Math.PI / n - Math.PI / 2
                val x = cx + (ringR * Math.cos(a)).toFloat()
                val y = cx + (ringR * Math.sin(a)).toFloat()
                if (i == 0) {
                    p.color = 0xFFF2F2F2.toInt()
                    c.drawCircle(x, y, 6.5f, p)
                } else {
                    p.color = if (playing) accent else 0xFF3A3A3A.toInt()
                    c.drawCircle(x, y, 4.6f, p)
                }
            }

            // subway-style bullet with a play / pause glyph
            val r = s * 0.27f
            p.color = if (playing) accent else 0xFFF2F2F2.toInt()
            c.drawCircle(cx, cx, r, p)
            val fg = if (playing && ColorUtils.calculateLuminance(accent) <= 0.5) Color.WHITE else Color.BLACK
            p.color = fg
            if (playing) {
                val w = r * 0.24f
                val h = r * 0.62f
                c.drawRoundRect(RectF(cx - r * 0.42f, cx - h, cx - r * 0.42f + w, cx + h), 5f, 5f, p)
                c.drawRoundRect(RectF(cx + r * 0.42f - w, cx - h, cx + r * 0.42f, cx + h), 5f, 5f, p)
            } else {
                val path = Path().apply {
                    moveTo(cx - r * 0.30f, cx - r * 0.56f)
                    lineTo(cx + r * 0.58f, cx)
                    lineTo(cx - r * 0.30f, cx + r * 0.56f)
                    close()
                }
                c.drawPath(path, p)
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
