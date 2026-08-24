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
 * A small design system, defined once.
 *
 * The screen is built in code rather than XML because the whole app is four
 * views and a list; an XML layout plus a theme plus a styles file would be
 * more moving parts than the thing they describe. What XML would have given
 * for free — consistent spacing, colours that follow the system theme — is
 * what this file provides instead.
 *
 * Both themes are defined explicitly. A phone left in dark mode showing black
 * text on a black card is not a subtle bug to the person holding it.
 */
object Style {

    fun dark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun background(context: Context): Int =
        if (dark(context)) Color.parseColor("#0F1113") else Color.parseColor("#F6F7F9")

    fun surface(context: Context): Int =
        if (dark(context)) Color.parseColor("#1A1D21") else Color.WHITE

    fun text(context: Context): Int =
        if (dark(context)) Color.parseColor("#ECEDEE") else Color.parseColor("#16181A")

    fun muted(context: Context): Int =
        if (dark(context)) Color.parseColor("#9BA1A6") else Color.parseColor("#61676D")

    fun accent(context: Context): Int =
        if (dark(context)) Color.parseColor("#6EA8FF") else Color.parseColor("#1B62D6")

    fun good(context: Context): Int =
        if (dark(context)) Color.parseColor("#5FD08A") else Color.parseColor("#1E7C4A")

    fun warn(context: Context): Int =
        if (dark(context)) Color.parseColor("#F0B36B") else Color.parseColor("#9A5B12")

    fun divider(context: Context): Int =
        if (dark(context)) Color.parseColor("#2A2F35") else Color.parseColor("#E3E6EA")

    fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics
    ).toInt()

    /** A rounded panel. Everything on the screen sits in one of these. */
    fun card(context: Context, tint: Int? = null): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 16).toFloat()
                setColor(tint ?: surface(context))
                setStroke(dp(context, 1), divider(context))
            }
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
            textSize = 13f
            letterSpacing = 0.08f
            setTextColor(muted(context))
            typeface = Typeface.DEFAULT_BOLD
        }

    fun headline(context: Context, value: String): TextView =
        TextView(context).apply {
            text = value
            textSize = 22f
            setTextColor(text(context))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(context, 6), 0, 0)
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
            val size = dp(context, 10)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = dp(context, 8)
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colour)
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
