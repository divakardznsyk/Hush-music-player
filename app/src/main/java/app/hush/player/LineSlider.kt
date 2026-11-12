package app.hush.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * A clean, thin, solid slider: a slim track, an accent fill and a small round thumb.
 * Used for seeking, app volume, deck volume and playback speed.
 * Set [hapticSteps] to get a tick every 1/steps of the way (firmer at both ends).
 */
class LineSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val padX = 14f * d

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A2A.toInt()
        strokeWidth = 3f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD71921.toInt()
        strokeWidth = 3f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A5A5A.toInt()
        strokeWidth = 1.5f * d
    }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD71921.toInt() }

    var fraction = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    /** Optional marks along the line (fractions 0..1). */
    var ticks: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    /** 0 = no haptics. Otherwise a tick each time the thumb crosses one of this many steps. */
    var hapticSteps = 0

    var isDragging = false
        private set

    /** Called with (fraction, isFinal). isFinal is true when the finger lifts. */
    var onChange: ((Float, Boolean) -> Unit)? = null

    private var lastStep = -1

    fun setAccent(color: Int) {
        fill.color = color
        thumb.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width - 2 * padX
        if (w <= 0f) return
        val cy = height / 2f
        val x = padX + fraction * w

        canvas.drawLine(padX, cy, padX + w, cy, track)
        ticks?.forEach { t ->
            val tx = padX + t * w
            canvas.drawLine(tx, cy - 8f * d, tx, cy + 8f * d, tick)
        }
        if (x > padX) canvas.drawLine(padX, cy, x, cy, fill)
        canvas.drawCircle(x, cy, 7f * d, thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastStep = if (hapticSteps > 0) Math.round(fraction * hapticSteps) else -1
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
        if (hapticSteps > 0 && !final) {
            val step = Math.round(fraction * hapticSteps)
            if (step != lastStep) {
                lastStep = step
                performHapticFeedback(
                    if (step == 0 || step == hapticSteps) HapticFeedbackConstants.LONG_PRESS
                    else HapticFeedbackConstants.CLOCK_TICK
                )
            }
        }
        onChange?.invoke(fraction, final)
    }
}
