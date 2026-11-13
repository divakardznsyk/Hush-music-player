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
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils

/**
 * 1x1 dark Braun-style widget. Swipe up / down for deck A or B; tap to play / pause it.
 * Never polls (updatePeriodMillis = 0); the service refreshes it only when playback changes.
 */
class HushWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        manager.updateAppWidget(appWidgetIds, build(context))
    }

    companion object {
        fun build(context: Context): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_play)
            rv.setRemoteAdapter(R.id.wStack, Intent(context, HushWidgetService::class.java))
            // Tap = play / pause the deck on screen. Swipe up / down = switch deck.
            val tpl = Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_TOGGLE)
            rv.setPendingIntentTemplate(
                R.id.wStack,
                PendingIntent.getService(context, 0, tpl, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            )
            return rv
        }

        fun draw(playing: Boolean, accent: Int, letter: String): Bitmap {
            val s = 288
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            // light warm-grey body
            p.color = 0xFF121212.toInt()
            c.drawRoundRect(RectF(4f, 4f, s - 4f, s - 4f), 72f, 72f, p)

            // deck letter
            p.color = 0xFF8C8C8C.toInt()
            p.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            p.textSize = 34f
            c.drawText(letter, 36f, 64f, p)

            // big key: deck colour while playing, grey when paused
            val cx = s / 2f
            val cy = 124f
            val r = 62f
            p.color = if (playing) accent else 0xFF2E2E2E.toInt()
            c.drawCircle(cx, cy, r, p)
            p.color = if (playing) (if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE) else 0xFFF2F2F2.toInt()
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

            // page dots (swipe up / down for the other deck)
            p.color = 0xFF8C8C8C.toInt()
            c.drawCircle(s - 30f, 120f, if (letter == "A") 8f else 5f, p)
            c.drawCircle(s - 30f, 150f, if (letter == "B") 8f else 5f, p)
            p.color = 0xFF262626.toInt()

            // speaker grille
            p.color = 0xFF262626.toInt()
            for (row in 0 until 3) for (i in 0 until 7) {
                c.drawCircle(60f + i * 28f, 214f + row * 22f, 5f, p)
            }
            return bmp
        }

        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, HushWidget::class.java))
            if (ids.isNotEmpty()) {
                mgr.updateAppWidget(ids, build(context))
                mgr.notifyAppWidgetViewDataChanged(ids, R.id.wStack)
            }
        }
    }
}
