package app.hush.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View

/**
 * A white vinyl record with a red label (or the album art as the label).
 * The disc is static; the activity rotates the whole view while music plays, which the GPU does
 * for free (no redrawing).
 */
class DiscView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt() }
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x16000000
    }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = 0x33000000
    }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val holeCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt() }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artMatrix = Matrix()

    var art: Bitmap? = null
        set(value) {
            field = value
            artPaint.shader = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val s = when {
            wMode != MeasureSpec.UNSPECIFIED && hMode != MeasureSpec.UNSPECIFIED -> minOf(w, h)
            wMode != MeasureSpec.UNSPECIFIED -> w
            hMode != MeasureSpec.UNSPECIFIED -> h
            else -> (200 * d).toInt()
        }
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cx = w / 2f
        val cy = h / 2f
        sheen.shader = SweepGradient(
            cx, cy,
            intArrayOf(0x00000000, 0x1F000000, 0x00000000, 0x1F000000, 0x00000000),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 1f
        if (r <= 0f) return

        canvas.drawCircle(cx, cy, r, fill)

        // grooves
        var gr = r * 0.44f
        while (gr < r * 0.97f) {
            canvas.drawCircle(cx, cy, gr, groove)
            gr += r * 0.032f
        }
        canvas.drawCircle(cx, cy, r, sheen)

        // label
        val lr = r * 0.38f
        val bmp = art
        if (bmp != null) {
            val s = (2f * lr) / minOf(bmp.width, bmp.height)
            artMatrix.setScale(s, s)
            artMatrix.postTranslate(cx - bmp.width * s / 2f, cy - bmp.height * s / 2f)
            artPaint.shader?.setLocalMatrix(artMatrix)
            canvas.drawCircle(cx, cy, lr, artPaint)
        } else {
            canvas.drawCircle(cx, cy, lr, labelPaint)
        }
        canvas.drawCircle(cx, cy, lr, ring)

        // spindle hole
        canvas.drawCircle(cx, cy, r * 0.045f, hole)
        canvas.drawCircle(cx, cy, r * 0.016f, holeCore)
    }
}
