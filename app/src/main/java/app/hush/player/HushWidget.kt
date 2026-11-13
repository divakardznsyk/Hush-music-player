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
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.RemoteViews

/**
 * 1x1 subway-sign widget. Tap opens Hush. Easter egg: every song is a different subway line
 * (its own letter and MTA colour). Playing = lit bullet, "LOCAL" (or "EXPRESS" if sped up).
 * Paused = dark bullet, "NOT IN SERVICE". Never polls; the service refreshes it on changes.
 */
class HushWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        manager.updateAppWidget(appWidgetIds, build(context))
    }

    companion object {
        private const val LINES = "ACEBDFMNQRWGJZL1234567"

        private fun lineColor(ch: Char): Int = when (ch) {
            'A', 'C', 'E' -> 0xFF0039A6
            'B', 'D', 'F', 'M' -> 0xFFFF6319
            'N', 'Q', 'R', 'W' -> 0xFFFCCC0A
            'G' -> 0xFF6CBE45
            'J', 'Z' -> 0xFF996633
            'L' -> 0xFFA7A9AC
            '7' -> 0xFFB933AD
            '4', '5', '6' -> 0xFF00933C
            else -> 0xFFEE352E
        }.toInt()

        fun build(context: Context): RemoteViews {
            val prefs = context.getSharedPreferences("hush", Context.MODE_PRIVATE)
            val playing = prefs.getBoolean("isPlaying", false) || prefs.getBoolean("isPlayingB", false)
            val id = prefs.getString("lastId", "") ?: ""
            val line = LINES[Math.floorMod(id.hashCode(), LINES.length)]
            val express = prefs.getFloat("speed", 1f) > 1.05f

            val rv = RemoteViews(context.packageName, R.layout.widget_play)
            rv.setImageViewBitmap(R.id.wImg, draw(playing, line, express))
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            rv.setOnClickPendingIntent(
                R.id.wRoot,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            return rv
        }

        private fun draw(playing: Boolean, line: Char, express: Boolean): Bitmap {
            val s = 288
            val cx = s / 2f
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            // black station sign with a thin edge
            p.color = Color.BLACK
            c.drawRoundRect(RectF(4f, 4f, s - 4f, s - 4f), 72f, 72f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 3f
            p.color = 0xFF262626.toInt()
            c.drawRoundRect(RectF(5f, 5f, s - 5f, s - 5f), 71f, 71f, p)
            p.style = Paint.Style.FILL

            // the line bullet: lit while playing, dark when not
            val col = lineColor(line)
            p.color = if (playing) col else 0xFF1E1E1E.toInt()
            c.drawCircle(cx, 112f, 68f, p)
            p.color = if (playing) (if (line in "NQRWGL") Color.BLACK else Color.WHITE) else 0xFF5A5A5A.toInt()
            p.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            p.textAlign = Paint.Align.CENTER
            p.textSize = 100f
            c.drawText(line.toString(), cx, 112f + 35f, p)

            // service status
            p.color = if (playing) 0xFFF2F2F2.toInt() else 0xFF8C8C8C.toInt()
            p.textSize = 27f
            p.letterSpacing = 0.08f
            c.drawText(if (!playing) "NOT IN SERVICE" else if (express) "EXPRESS" else "LOCAL", cx, 218f, p)

            // stops along the line
            for (i in 0 until 5) {
                p.color = if (playing) col else 0xFF262626.toInt()
                c.drawCircle(cx + (i - 2) * 36f, 252f, 7f, p)
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
