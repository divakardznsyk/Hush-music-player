package app.hush.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** A slider made of a row of dots. Used for seeking, app volume and playback speed. */
class DotSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2F2F2.toInt() }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3A.toInt() }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt() }
    private val pitch = 7f * d
    private val padX = 8f * d

    var fraction = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    var isDragging = false
        private set

    /** Called with (fraction, isFinal). isFinal is true when the finger lifts. */
    var onChange: ((Float, Boolean) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        val w = width - 2 * padX
        if (w <= 0f) return
        val n = maxOf(2, (w / pitch).toInt())
        val step = w / (n - 1)
        val cy = height / 2f
        val litUpTo = fraction * (n - 1)
        for (i in 0 until n) {
            val x = padX + i * step
            if (i <= litUpTo + 0.001f) canvas.drawCircle(x, cy, 2.2f * d, onPaint)
            else canvas.drawCircle(x, cy, 1.6f * d, offPaint)
        }
        canvas.drawCircle(padX + fraction * w, cy, 5.5f * d, headPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                update(e.x, false)
            }
            MotionEvent.ACTION_MOVE -> update(e.x, false)
            MotionEvent.ACTION_UP -> {
                update(e.x, true)
                isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return true
    }

    private fun update(x: Float, final: Boolean) {
        val w = width - 2 * padX
        if (w <= 0f) return
        fraction = ((x - padX) / w).coerceIn(0f, 1f)
        onChange?.invoke(fraction, final)
    }
}
