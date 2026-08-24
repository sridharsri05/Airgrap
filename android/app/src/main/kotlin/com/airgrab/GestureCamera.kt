package com.airgrab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.airgrab.core.GestureEvent
import com.airgrab.core.GrabStateMachine
import com.airgrab.core.Pose
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The camera loop: frames in, gesture events out.
 *
 * Everything interesting about *interpreting* those frames lives in
 * [GrabStateMachine] in core, which is tested without a camera. This file is
 * only the plumbing that keeps it fed.
 *
 * ## Frames are dropped on purpose
 *
 * `STRATEGY_KEEP_ONLY_LATEST` means a frame arriving while the previous one is
 * still being classified is discarded rather than queued. That is correct
 * here: a gesture is about what the hand is doing *now*, and a backlog would
 * make the app respond to a fist the user has already opened. Queued frames
 * would also grow without bound, since recognition is slower than capture.
 *
 * ## Why the resolution is low
 *
 * The recognizer does not need detail — it needs a hand shape. A 4K frame
 * costs battery and heat to capture, convert and classify, and produces the
 * same answer as a small one. Heat matters especially: a phone that thermally
 * throttles drops the frame rate, which on the desktop was exactly what made
 * frame-count thresholds unusable.
 */
class GestureCamera(
    private val context: Context,
    private val detector: HandPoseDetector,
    private val stateMachine: GrabStateMachine,
    private val onEvent: (GestureEvent) -> Unit,
    private val onPose: (Pose) -> Unit = {},
) {
    companion object {
        const val TAG = "AirGrabCamera"

        /**
         * Ample for a hand at arm's length, and small enough that conversion
         * and classification stay cheap.
         */
        private val ANALYSIS_SIZE = Size(640, 480)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    private val framesSeen = AtomicLong(0)
    private val framesClassified = AtomicLong(0)

    /** Frames captured and frames actually classified, for diagnostics. */
    fun stats(): Pair<Long, Long> = framesSeen.get() to framesClassified.get()

    @SuppressLint("UnsafeOptInUsageError")
    fun start(owner: LifecycleOwner, front: Boolean = true) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val cameraProvider = try {
                future.get()
            } catch (exc: Throwable) {
                Log.e(TAG, "no camera available", exc)
                return@addListener
            }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                ANALYSIS_SIZE,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                // Discard rather than queue: a gesture is about the hand now.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // RGBA directly from CameraX, so there is no YUV conversion to
                // write, get wrong per device, and pay for on every frame.
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(executor) { proxy -> analyse(proxy) }

            val selector = if (front) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(owner, selector, analysis)
                provider = cameraProvider
                Log.i(TAG, "camera started")
            } catch (exc: Throwable) {
                Log.e(TAG, "could not bind the camera", exc)
            }
        }, ContextCompatExecutor(context))
    }

    private fun analyse(proxy: ImageProxy) {
        framesSeen.incrementAndGet()
        try {
            val bitmap = toBitmap(proxy)
            val pose = detector.classify(bitmap)
            framesClassified.incrementAndGet()

            onPose(pose)
            // The clock is the state machine's own. Thresholds are durations,
            // not frame counts, precisely because this rate is not stable:
            // it falls with poor light and again when the phone gets warm.
            stateMachine.observe(pose).forEach(onEvent)
        } catch (exc: Throwable) {
            Log.w(TAG, "frame dropped: ${exc.message}")
        } finally {
            // Always, on every path. A proxy that is not closed stalls the
            // pipeline permanently after a couple of frames, and the camera
            // simply appears to stop with no error.
            proxy.close()
        }
    }

    /**
     * Copy the frame into an ARGB_8888 bitmap.
     *
     * The copy is not avoidable: the proxy's buffer is reused as soon as
     * `close` is called, and MediaPipe reads the pixels after that point.
     * Sharing it produces intermittent garbage frames rather than a clean
     * failure.
     */
    private fun toBitmap(proxy: ImageProxy): Bitmap? {
        val plane = proxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        buffer.rewind()

        // CameraX pads each row to a hardware-friendly stride, so the buffer
        // is usually wider than the image. Ignoring that shears the picture
        // diagonally, and a sheared hand classifies as no hand at all.
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * proxy.width
        val paddedWidth = proxy.width + rowPadding / pixelStride

        val bitmap = Bitmap.createBitmap(paddedWidth, proxy.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, proxy.width, proxy.height)
        }
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        provider = null
        executor.shutdown()
    }
}

/** Main-thread executor without pulling in a second dependency for it. */
private class ContextCompatExecutor(private val context: Context) : java.util.concurrent.Executor {
    private val handler = android.os.Handler(context.mainLooper)
    override fun execute(command: Runnable) {
        handler.post(command)
    }
}
