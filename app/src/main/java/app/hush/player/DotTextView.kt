package app.hush.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Text drawn as a dot-matrix display. The text is rasterised once into a tiny bitmap and every lit
 * pixel becomes one round dot. Static between text changes, so it costs nothing to keep on screen.
 */
class DotTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var pitch = 3.4f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private var lit: BooleanArray = BooleanArray(0)
    private var cols = 0
    private var rows = 0

    var text: String = ""
        set(value) {
            if (value != field) {
                field = value
                rebuild()
                requestLayout()
                invalidate()
            }
        }

    fun configure(pitchDp: Float, color: Int) {
        pitch = pitchDp * density
        paint.color = color
        requestLayout()
        invalidate()
    }

    private fun rebuild() {
        if (text.isEmpty()) {
            cols = 0; rows = 0; lit = BooleanArray(0)
            return
        }
        val p = Paint().apply {
            isAntiAlias = false
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 11f
        }
        val fm = p.fontMetrics
        val w = Math.ceil(p.measureText(text).toDouble()).toInt() + 2
        val h = Math.ceil((fm.descent - fm.ascent).toDouble()).toInt() + 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, 1f, 1f - fm.ascent, p)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()

        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 110) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) {
            cols = 0; rows = 0; lit = BooleanArray(0)
            return
        }
        cols = maxX - minX + 1
        rows = maxY - minY + 1
        lit = BooleanArray(cols * rows)
        for (y in 0 until rows) for (x in 0 until cols) {
            lit[y * cols + x] = (px[(y + minY) * w + (x + minX)] ushr 24) > 110
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((cols * pitch).toInt(), widthMeasureSpec),
            resolveSize((rows * pitch).toInt(), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = pitch * 0.38f
        for (y in 0 until rows) for (x in 0 until cols) {
            if (lit[y * cols + x]) {
                canvas.drawCircle(x * pitch + pitch / 2f, y * pitch + pitch / 2f, r, paint)
            }
        }
    }
}
