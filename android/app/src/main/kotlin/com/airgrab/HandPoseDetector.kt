package com.airgrab

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.airgrab.core.Pose
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

/**
 * Turning camera frames into hand poses. Nothing else.
 *
 * Ported from `desktop/airgrab/handpose.py`, and deliberately just as dumb: it
 * answers one question per frame — open palm, closed fist, something else, or
 * no hand — and holds no memory. All debouncing, timing and interpretation
 * belongs to `GrabStateMachine` in core, which is tested exhaustively without
 * a camera.
 *
 * IMAGE mode rather than LIVE_STREAM, matching the desktop. IMAGE mode is
 * stateless, so behaviour is identical frame to frame and reproducible;
 * temporal smoothing is the state machine's job, and the tracking LIVE_STREAM
 * adds would only duplicate it while making the two platforms disagree about
 * what a given frame means.
 */
class HandPoseDetector private constructor(
    private val recognizer: GestureRecognizer,
    private val minConfidence: Float,
) {
    companion object {
        const val TAG = "AirGrabPose"
        private const val MODEL_ASSET = "gesture_recognizer.task"

        /**
         * MediaPipe's canned gesture names. Anything not listed is a hand we
         * do not act on, which is distinct from no hand at all — the state
         * machine treats the two differently.
         */
        private val POSE_BY_GESTURE = mapOf(
            "Open_Palm" to Pose.OPEN_PALM,
            "Closed_Fist" to Pose.CLOSED_FIST,
        )

        /** Returns null if the model cannot be loaded, rather than throwing. */
        /**
         * 0.35, not the 0.5 the desktop uses.
         *
         * A webcam sees a hand large, evenly lit and side-on. A phone's front
         * camera sees it small, often backlit, and at whatever angle the arm
         * holding the phone allows, so the recognizer's confidence sits lower
         * for the same gesture. At 0.5 a real, clearly-made fist was scoring
         * below the bar and arriving as OTHER.
         */
        fun create(context: Context, minConfidence: Float = 0.35f): HandPoseDetector? = try {
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .build()
            HandPoseDetector(
                GestureRecognizer.createFromOptions(context, options),
                minConfidence,
            )
        } catch (exc: Throwable) {
            // A missing or uncompressible model asset fails here. Returning
            // null lets the caller keep the rest of the app working and say so,
            // rather than taking the process down over a feature that has not
            // started yet.
            Log.e(TAG, "could not load the gesture model", exc)
            null
        }
    }

    /**
     * Classify one frame. A camera glitch reads as "no hand".
     *
     * The frame is checked BEFORE MediaPipe sees it, which is prevention
     * rather than error handling, and the distinction is load-bearing: on the
     * desktop a malformed frame made MediaPipe's graph fail internally and
     * permanently, so every later frame silently returned nothing and the
     * process hung on exit. Catching the exception afterwards did not undo
     * any of it. The only working strategy is to never hand it a frame it
     * cannot process.
     */
    fun classify(bitmap: Bitmap?): Pose {
        if (!isUsable(bitmap)) return Pose.NONE

        val result = try {
            recognizer.recognize(BitmapImageBuilder(bitmap).build())
        } catch (exc: Throwable) {
            Log.w(TAG, "recognition failed: ${exc.message}")
            return Pose.NONE
        }

        val gestures = result.gestures()
        if (gestures.isEmpty() || gestures[0].isEmpty()) return Pose.NONE

        val top = gestures[0][0]
        if (top.score() < minConfidence) return Pose.OTHER
        if (top.categoryName() in setOf("None", "none", "")) return Pose.OTHER

        return POSE_BY_GESTURE[top.categoryName()] ?: Pose.OTHER
    }

    private fun isUsable(bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.isRecycled) return false
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        // ARGB_8888 is what BitmapImageBuilder expects. A hardware bitmap has
        // no readable pixels at all and fails inside the native layer.
        if (bitmap.config != Bitmap.Config.ARGB_8888) return false
        return true
    }

    fun close() {
        // MediaPipe defers graph errors, so a frame this class already handled
        // can resurface here seconds later. Letting that escape would break
        // shutdown over a frame that caused no actual harm.
        runCatching { recognizer.close() }
    }
}
