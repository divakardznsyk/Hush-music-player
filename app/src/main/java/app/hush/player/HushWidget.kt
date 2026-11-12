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
 * 1x1 home-screen widget: one tap toggles play / pause on the main player (Deck A).
 * If Hush isn't running it resumes your last song. White while paused, accent colour while playing.
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
            val accent = prefs.getInt("accent", 0xFFD71921.toInt())

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
            val s = 192
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val bg = if (playing) accent else 0xFFF2F2F2.toInt()
            val fg = if (playing && ColorUtils.calculateLuminance(accent) <= 0.5) Color.WHITE else Color.BLACK
            p.color = bg
            c.drawCircle(s / 2f, s / 2f, s / 2f - 2f, p)
            p.color = fg
            if (playing) {
                c.drawRoundRect(RectF(s * 0.35f, s * 0.31f, s * 0.46f, s * 0.69f), 6f, 6f, p)
                c.drawRoundRect(RectF(s * 0.54f, s * 0.31f, s * 0.65f, s * 0.69f), 6f, 6f, p)
            } else {
                val path = Path().apply {
                    moveTo(s * 0.41f, s * 0.30f)
                    lineTo(s * 0.72f, s * 0.50f)
                    lineTo(s * 0.41f, s * 0.70f)
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
