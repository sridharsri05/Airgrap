package com.airgrab

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Telling the user what happened, when they are not looking at the screen.
 *
 * This is not decoration. The whole interaction happens with the phone face
 * down, in a pocket, or held at arm's length pointing away — so the screen and
 * the notification are both invisible at the moment they would matter. Without
 * something felt, the user makes a fist and has no idea whether anything
 * happened, which is exactly the complaint that prompted this.
 *
 * The patterns are deliberately distinguishable by feel alone rather than by
 * length: a single firm tap for picked up, two quick taps for delivered, a
 * long dull buzz for nothing happened.
 */
object Haptics {

    private fun vibrator(context: Context): Vibrator? = runCatching {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    }.getOrNull()

    /** Picked up: one firm tap. */
    fun grabbed(context: Context) = play(context, longArrayOf(0, 45), intArrayOf(0, 255))

    /** Delivered: two quick taps, the shape of something completing. */
    fun sent(context: Context) = play(context, longArrayOf(0, 30, 70, 30), intArrayOf(0, 200, 0, 255))

    /** Caught something: a rising pair. */
    fun received(context: Context) = play(context, longArrayOf(0, 25, 60, 55), intArrayOf(0, 160, 0, 255))

    /** Nothing happened: one long, dull buzz, clearly not a success. */
    fun cancelled(context: Context) = play(context, longArrayOf(0, 180), intArrayOf(0, 90))

    private fun play(context: Context, timings: LongArray, amplitudes: IntArray) {
        val device = vibrator(context) ?: return
        if (!device.hasVibrator()) return
        runCatching {
            device.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        }
    }
}
