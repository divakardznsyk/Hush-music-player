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
 * Pure vector drawing (circles and gradients), so it stays razor sharp at any size. Static: no animation.
 */
class DiscView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4F4F4.toInt() }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = 0x30000000
    }
    private val grooveFine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * d
        color = 0x1A000000
    }
    private val grooveBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        color = 0x14000000
    }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt() }
    private val labelRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * d
        color = 0x40000000
    }
    private val labelDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val holeCore = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4F4F4.toInt() }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val artMatrix = Matrix()

    /** Colour of the plain label (follows the app accent). */
    var labelColor: Int
        get() = labelPaint.color
        set(value) {
            labelPaint.color = value
            invalidate()
        }

    /** Album art shown on the label. */
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
        // light/dark wedges that make the rotation visible
        sheen.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                0x00000000, 0x26000000, 0x00000000, 0x00000000,
                0x26000000, 0x00000000, 0x00000000
            ),
            floatArrayOf(0f, 0.12f, 0.26f, 0.5f, 0.62f, 0.76f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 1.5f * d
        if (r <= 0f) return

        canvas.drawCircle(cx, cy, r, fill)

        // grooves: fine lines, with a bolder ring every few lines like track gaps
        var gr = r * 0.43f
        var i = 0
        while (gr < r * 0.96f) {
            canvas.drawCircle(cx, cy, gr, if (i % 7 == 0) grooveBold else grooveFine)
            gr += r * 0.026f
            i++
        }
        canvas.drawCircle(cx, cy, r, sheen)
        canvas.drawCircle(cx, cy, r, rim)

        // label
        val lr = r * 0.38f
        canvas.drawCircle(cx, cy, lr, labelPaint)
        val bmp = art
        if (bmp != null) {
            val s = (2f * lr) / minOf(bmp.width, bmp.height)
            artMatrix.setScale(s, s)
            artMatrix.postTranslate(cx - bmp.width * s / 2f, cy - bmp.height * s / 2f)
            artPaint.shader?.setLocalMatrix(artMatrix)
            canvas.drawCircle(cx, cy, lr, artPaint)
        } else {
            // three little dots on the plain red label
            val dr = lr * 0.07f
            canvas.drawCircle(cx, cy - lr * 0.55f, dr, labelDot)
            canvas.drawCircle(cx - lr * 0.48f, cy + lr * 0.3f, dr, labelDot)
            canvas.drawCircle(cx + lr * 0.48f, cy + lr * 0.3f, dr, labelDot)
        }
        canvas.drawCircle(cx, cy, lr, labelRing)

        // spindle hole
        canvas.drawCircle(cx, cy, r * 0.05f, hole)
        canvas.drawCircle(cx, cy, r * 0.018f, holeCore)
    }
}
