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
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * What the user sees when they are not looking at AirGrab.
 *
 * The notification and the app's own screen are both useless at the moment
 * that matters: the phone is face down, in a pocket, or held up at arm's
 * length. Haptics tell the user that *something* happened; this tells them
 * *what*, without them having to open anything.
 *
 * ## Why a ring
 *
 * Huawei's own transfer draws a glowing hollow ring over whatever is on
 * screen — no app opens, nothing else moves, one circle of light appears and
 * the photo lands into it. It reads as the device itself responding rather
 * than an app posting a notification, and that is the feeling this feature
 * sells. The first version here was a pill with text, which was honest and
 * looked like a status bar; this is the ring.
 *
 * It draws over other apps, which needs a permission the user grants by hand.
 * That is a real cost, so the overlay is optional: without it everything
 * still works, and the feedback is the vibration alone.
 */
class GestureOverlay(private val context: Context) {

    companion object {
        private const val VISIBLE_MILLIS = 2100L

        /** True when the user has allowed drawing over other apps. */
        fun permitted(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    enum class Kind { HOLDING, SENT, RECEIVED, CANCELLED }

    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: RingView? = null
    private val main = android.os.Handler(context.mainLooper)

    fun show(kind: Kind, label: String) {
        if (!permitted(context)) return
        main.post { present(kind, label) }
    }

    private fun present(kind: Kind, label: String) {
        hideNow()

        val ring = RingView(context, kind, label)
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
        }

        runCatching { windows.addView(ring, params) }.onFailure { return }
        view = ring
        ring.play()

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
     * A glowing hollow ring in the upper third of the screen.
     *
     * Drawn rather than assembled from views because the whole thing is one
     * animation over one surface. The glow is three concentric strokes of the
     * same circle — wide and faint, narrower and stronger, then a bright
     * near-white core — because that reads as light on any background,
     * where a blur filter behaves differently across hardware canvases.
     *
     * The animation has three acts in one sweep of progress: the ring blooms
     * out of the top of the screen (where the camera that saw the hand
     * lives), breathes while it holds, then resolves — collapsing into
     * itself for a finished transfer, fading in place for a hold, shrinking
     * out apologetically for a cancel.
     */
    private class RingView(
        context: Context,
        private val kind: Kind,
        label: String,
    ) : View(context) {

        private val density = resources.displayMetrics.density
        private fun dp(value: Float) = value * density

        // Matching desktop/airgrab/ui/overlay.py and the palette in
        // ui/Style.kt: dark-ground variants, because this floats over
        // whatever the user happens to be looking at and cannot borrow a
        // ground it does not control.
        private val accent = when (kind) {
            Kind.HOLDING -> Color.parseColor("#6E9BFF")
            Kind.SENT -> Color.parseColor("#5FD08A")
            Kind.RECEIVED -> Color.parseColor("#5FD08A")
            Kind.CANCELLED -> Color.parseColor("#F0724F")
        }

        /** The core of the ring: the accent lifted most of the way to white,
         *  so the circle reads as light rather than as a coloured line. */
        private val core = blendTowardWhite(accent, 0.65f)

        private val glowWide = strokePaint(accent, dp(16f))
        private val glowMid = strokePaint(accent, dp(7f))
        private val coreStroke = strokePaint(core, dp(3f))

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F2F3F5")
            textSize = dp(13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC1B1C1E")
        }

        private val text = when (kind) {
            Kind.HOLDING -> if (label.isBlank()) "Holding" else "Holding · $label"
            Kind.SENT -> "Sent · $label"
            Kind.RECEIVED -> "Received · $label"
            Kind.CANCELLED -> label.ifBlank { "Nothing was sent" }
        }

        private var progress = 0f
        private var animator: ValueAnimator? = null

        fun play() {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1900
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
                dp(300f).toInt(),
            )
        }

        override fun onDraw(canvas: Canvas) {
            val centreX = width / 2f
            val centreY = dp(128f)
            val full = dp(72f)

            // Three acts on one clock.
            val bloom = smooth(min(1f, progress / 0.22f))
            val resolving = if (progress > 0.78f) smooth((progress - 0.78f) / 0.22f) else 0f

            // While holding, the ring breathes — the sign of something live
            // rather than a stamp.
            val breathe = if (resolving == 0f) {
                (sin(progress * 5f * PI).toFloat()) * dp(3.5f)
            } else 0f

            val radius: Float
            val alpha: Float
            when (kind) {
                Kind.SENT, Kind.RECEIVED -> {
                    // The transfer resolves by the ring collapsing into
                    // itself — the file "lands" through it.
                    radius = (dp(18f) + (full - dp(18f)) * bloom) * (1f - resolving) +
                        dp(6f) * resolving + breathe
                    alpha = bloom * (1f - resolving * resolving)
                }
                Kind.HOLDING -> {
                    radius = dp(18f) + (full - dp(18f)) * bloom + breathe
                    alpha = bloom * (1f - resolving)
                }
                Kind.CANCELLED -> {
                    radius = (dp(18f) + (full - dp(18f)) * bloom) * (1f - resolving * 0.4f)
                    alpha = bloom * (1f - resolving)
                }
            }

            if (alpha > 0.01f) {
                glowWide.alpha = (alpha * 46).toInt().coerceIn(0, 255)
                glowMid.alpha = (alpha * 110).toInt().coerceIn(0, 255)
                coreStroke.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(centreX, centreY, radius, glowWide)
                canvas.drawCircle(centreX, centreY, radius, glowMid)
                canvas.drawCircle(centreX, centreY, radius, coreStroke)
            }

            // The name sits under the ring, on a soft backdrop so it stays
            // readable over whatever the user was looking at. It fades with
            // the ring: the ring is the message, this is the caption.
            val textAlpha = (alpha * 255).toInt().coerceIn(0, 255)
            if (textAlpha > 8) {
                val textY = centreY + full + dp(40f)
                val halfWidth = textPaint.measureText(text) / 2f + dp(14f)
                backdropPaint.alpha = (alpha * 204).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(
                    RectF(
                        centreX - halfWidth, textY - dp(17f),
                        centreX + halfWidth, textY + dp(11f),
                    ),
                    dp(14f), dp(14f), backdropPaint,
                )
                textPaint.alpha = textAlpha
                canvas.drawText(text, centreX, textY, textPaint)
            }
        }

        private fun strokePaint(colour: Int, width: Float) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                color = colour
            }

        private fun blendTowardWhite(colour: Int, strength: Float): Int {
            fun mix(part: Int) = (part + (255 - part) * strength).toInt()
            return Color.rgb(
                mix(Color.red(colour)), mix(Color.green(colour)), mix(Color.blue(colour))
            )
        }

        /** Ease-out, so motion arrives instead of stopping. */
        private fun smooth(value: Float): Float {
            val clamped = value.coerceIn(0f, 1f)
            return 1f - (1f - clamped) * (1f - clamped)
        }
    }
}
