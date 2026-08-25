package com.airgrab

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * What the user sees when they are not looking at AirGrab.
 *
 * The notification and the app's own screen are both useless at the moment
 * that matters: the phone is face down, in a pocket, or held up at arm's
 * length. Haptics tell the user that *something* happened; this tells them
 * *what*, without them having to open anything.
 *
 * It draws over other apps, which needs a permission the user grants by hand.
 * That is a real cost, so the overlay is optional: without it everything still
 * works, and the feedback is the vibration alone.
 *
 * Deliberately small and brief. An overlay that lingers, or covers content, is
 * worse than none — this is a status light, not a window.
 */
class GestureOverlay(private val context: Context) {

    companion object {
        private const val VISIBLE_MILLIS = 1600L

        /** True when the user has allowed drawing over other apps. */
        fun permitted(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    enum class Kind { HOLDING, SENT, RECEIVED, CANCELLED }

    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: RippleView? = null
    private val main = android.os.Handler(context.mainLooper)

    fun show(kind: Kind, label: String) {
        if (!permitted(context)) return
        main.post { present(kind, label) }
    }

    private fun present(kind: Kind, label: String) {
        hideNow()

        val ripple = RippleView(context, kind, label)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable and not touchable: the user is mid-gesture and
            // must never have a tap swallowed by a status indicator.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (24 * context.resources.displayMetrics.density).toInt()
        }

        runCatching { windows.addView(ripple, params) }.onFailure { return }
        view = ripple
        ripple.play { hideNow() }

        main.postDelayed({ hideNow() }, VISIBLE_MILLIS)
    }

    fun hideNow() {
        view?.let { current ->
            runCatching { windows.removeView(current) }
            current.stop()
        }
        view = null
    }

    /**
     * A pill with a ripple washing outward from it.
     *
     * Drawn rather than assembled from views because the whole thing is one
     * animation over one surface; a layout would add hierarchy for nothing.
     */
    private class RippleView(
        context: Context,
        private val kind: Kind,
        private val label: String,
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private fun dp(value: Float) = value * density

        // Matching desktop/airgrab/ui/overlay.py, so a user who has seen one
        // device recognises the other. These are the dark-ground variants of
        // the palette in ui/Style.kt, and stay dark in both system themes:
        // the pill sits over whatever the user happens to be looking at, so
        // it cannot borrow a ground it does not control.
        private val accent = when (kind) {
            Kind.HOLDING -> Color.parseColor("#6E9BFF")
            Kind.SENT -> Color.parseColor("#5FD08A")
            Kind.RECEIVED -> Color.parseColor("#5FD08A")
            Kind.CANCELLED -> Color.parseColor("#F0724F")
        }

        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Night Black's surface, at 95% so what is underneath still reads
            // as being underneath.
            color = Color.parseColor("#F21B1C1E")
        }
        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F2F3F5")
            textSize = dp(14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private var progress = 0f
        private var animator: ValueAnimator? = null

        private val text = when (kind) {
            Kind.HOLDING -> if (label.isBlank()) "Holding" else "Holding  $label"
            Kind.SENT -> "Sent  $label"
            Kind.RECEIVED -> "Received  $label"
            Kind.CANCELLED -> label.ifBlank { "Nothing to send" }
        }

        fun play(onEnd: () -> Unit) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(
                MeasureSpec.getSize(widthSpec),
                dp(72f).toInt(),
            )
        }

        override fun onDraw(canvas: Canvas) {
            val centreY = height / 2f
            val padding = dp(16f)
            val textWidth = textPaint.measureText(text)
            val pillWidth = min(width - dp(32f), textWidth + dp(72f))
            val left = (width - pillWidth) / 2f
            val pillHeight = dp(44f)

            // The ripple washes outward from the dot and fades as it goes,
            // so the eye is drawn to the indicator rather than the edge.
            val rippleAlpha = ((1f - progress) * 150).toInt().coerceIn(0, 255)
            if (rippleAlpha > 0) {
                ripplePaint.alpha = rippleAlpha
                ripplePaint.strokeWidth = dp(2.5f) * (1f - progress * 0.6f)
                val radius = dp(14f) + progress * dp(46f)
                canvas.drawCircle(left + padding + dp(10f), centreY, radius, ripplePaint)
            }

            // The pill fades in quickly and holds, so the text is readable for
            // most of the animation rather than only at its peak.
            val appear = min(1f, progress * 5f)
            pillPaint.alpha = (appear * 242).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(
                RectF(left, centreY - pillHeight / 2, left + pillWidth, centreY + pillHeight / 2),
                pillHeight / 2, pillHeight / 2, pillPaint,
            )

            dotPaint.alpha = (appear * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(left + padding + dp(10f), centreY, dp(7f), dotPaint)

            textPaint.alpha = (appear * 255).toInt().coerceIn(0, 255)
            canvas.drawText(
                text,
                left + padding + dp(30f),
                centreY - (textPaint.descent() + textPaint.ascent()) / 2,
                textPaint,
            )
        }
    }
}
