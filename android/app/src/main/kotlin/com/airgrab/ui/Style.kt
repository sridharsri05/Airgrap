package com.airgrab.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small design system, defined once, in HarmonyOS's own language.
 *
 * The colours are Huawei's rather than invented: Cosmic Blue as the single
 * accent, Snow Gray as the light ground, Night Black as the dark one. The app
 * copies a Huawei feature, so borrowing the palette that feature lives in
 * makes it feel like the thing it imitates rather than a lookalike.
 *
 * The screen is built in code rather than XML because the whole app is a
 * handful of cards; an XML layout plus a theme plus a styles file would be
 * more moving parts than the thing they describe. What XML would have given
 * for free — consistent spacing, colours that follow the system theme — is
 * what this file provides instead.
 *
 * Both themes are defined explicitly. A phone left in dark mode showing black
 * text on a black card is not a subtle bug to the person holding it.
 */
object Style {

    /** Cosmic Blue, Huawei's accent, on light grounds. */
    private const val COSMIC_BLUE = "#0A59F7"

    /** The same hue lifted for dark grounds, where the original goes muddy. */
    private const val COSMIC_BLUE_DARK = "#6E9BFF"

    fun dark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Snow Gray, or Night Black. */
    fun background(context: Context): Int =
        if (dark(context)) Color.parseColor("#0D0D0D") else Color.parseColor("#F1F3F5")

    fun surface(context: Context): Int =
        if (dark(context)) Color.parseColor("#1B1C1E") else Color.WHITE

    /** One step in from a card, for the thing the card is about. */
    fun raised(context: Context): Int =
        if (dark(context)) Color.parseColor("#26282B") else Color.parseColor("#F1F3F5")

    fun text(context: Context): Int =
        if (dark(context)) Color.parseColor("#F2F3F5") else Color.parseColor("#0D0D0D")

    fun muted(context: Context): Int =
        if (dark(context)) Color.parseColor("#9AA0A6") else Color.parseColor("#5E6469")

    fun accent(context: Context): Int =
        Color.parseColor(if (dark(context)) COSMIC_BLUE_DARK else COSMIC_BLUE)

    fun good(context: Context): Int =
        if (dark(context)) Color.parseColor("#5FD08A") else Color.parseColor("#12724A")

    fun warn(context: Context): Int =
        if (dark(context)) Color.parseColor("#F0B36B") else Color.parseColor("#8A4B00")

    fun divider(context: Context): Int =
        if (dark(context)) Color.parseColor("#2C2E31") else Color.parseColor("#E4E7EB")

    /**
     * A wash of a state colour, for the ground behind a chip or a badge.
     *
     * Derived from the colour it accompanies rather than listed separately,
     * so a colour and its wash cannot drift apart.
     */
    fun wash(context: Context, colour: Int): Int = Color.argb(
        if (dark(context)) 46 else 26,
        Color.red(colour), Color.green(colour), Color.blue(colour),
    )

    fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

    /**
     * A rounded panel. Everything on the screen sits in one of these.
     *
     * HarmonyOS surfaces read as light physical objects: generous radius, no
     * outline, lifted off the ground by a soft shadow rather than a line. A
     * shadow is invisible against Night Black, so in dark mode a hairline
     * carries the edge instead — the same job, done by whichever means the
     * ground allows.
     */
    fun card(context: Context, tint: Int? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 24).toFloat()
                setColor(tint ?: surface(context))
                if (dark(context)) setStroke(dp(context, 1), divider(context))
            }
            if (!dark(context)) elevation = dp(context, 2).toFloat()
            val inset = dp(context, 20)
            setPadding(inset, inset, inset, inset)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(context, 12) }
        }

    fun title(context: Context, value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(muted(context))
            typeface = Typeface.DEFAULT_BOLD
        }

    /** The state sentence, and nothing else on the screen, is set this large. */
    fun headline(context: Context, value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 24f
            setTextColor(text(context))
            typeface = Typeface.DEFAULT_BOLD
        }

    fun body(context: Context, value: String, colour: Int? = null): TextView =
        TextView(context).apply {
            text = value
            textSize = 15f
            setTextColor(colour ?: muted(context))
            setLineSpacing(dp(context, 4).toFloat(), 1f)
        }

    fun mono(context: Context, value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(muted(context))
        }

    /** A coloured dot, for state the user should read at a glance. */
    fun dot(context: Context, colour: Int): View =
        View(context).apply {
            val size = dp(context, 8)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = dp(context, 8)
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colour)
            }
        }

    /**
     * A small filled label carrying one word of state.
     *
     * Shape as well as text, so "paired" and "not paired" are separable at a
     * glance, which is the whole job of the device list.
     */
    fun chip(context: Context, value: String, colour: Int): TextView =
        TextView(context).apply {
            text = value
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colour)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 100).toFloat()
                setColor(wash(context, colour))
            }
            setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
        }

    /**
     * The hand beside the state sentence.
     *
     * Huawei's own indicator is a hand, and it is the single element that
     * tells the user the phone is watching them. Drawn on a washed ground so
     * its colour carries the state alongside its shape.
     */
    fun glyphBadge(context: Context, glyph: String, colour: Int): TextView =
        TextView(context).apply {
            text = glyph
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 18).toFloat()
                setColor(wash(context, colour))
            }
            val size = dp(context, 52)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = dp(context, 14)
            }
        }

    fun row(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    fun spacer(context: Context, height: Int): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, height)
            )
        }
}
