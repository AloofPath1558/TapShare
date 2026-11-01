package app.tapshare

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * The yellow dot with spreading ripples (same motif as the website).
 * [active] makes the ripples run; [error] turns the dot red. When the system has animations
 * turned off, the ripples are drawn still.
 */
class PulseView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }

    private var phase = 0f
    private var animator: ValueAnimator? = null

    var active = false
        set(value) {
            if (field == value) return
            field = value
            syncAnimation()
            invalidate()
        }

    var error = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private fun syncAnimation() {
        animator?.cancel()
        animator = null
        if (active && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val accent = ContextCompat.getColor(context, if (error) R.color.danger else R.color.primary)
        val maxR = min(width, height) / 2f - 2 * density

        if (active) {
            val moving = animator != null
            val rings = if (moving) 3 else 2
            for (i in 0 until rings) {
                val p = if (moving) (phase + i / 3f) % 1f else 0.45f + 0.25f * i
                val strength = if (moving) 1f - p else 0.4f - 0.18f * i
                ringPaint.color = accent
                ringPaint.alpha = (strength * 0.85f * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, maxR * (0.18f + 0.82f * p), ringPaint)
            }
        }

        dotPaint.color = ContextCompat.getColor(context, if (error) R.color.danger else R.color.spark)
        canvas.drawCircle(cx, cy, 13 * density, dotPaint)
        haloPaint.color = accent
        canvas.drawCircle(cx, cy, 15 * density, haloPaint)
    }
}
