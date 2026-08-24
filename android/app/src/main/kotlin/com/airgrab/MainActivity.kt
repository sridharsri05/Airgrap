package com.airgrab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airgrab.core.GestureEvent
import com.airgrab.core.GrabStateMachine
import com.airgrab.core.Pose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The status screen.
 *
 * Deliberately plain for now: this exists so the link can be started, watched
 * and diagnosed on a real handset. The gesture UI replaces it.
 *
 * The one part here that is not scaffolding is the background-permission
 * prompt. On this manufacturer's software an app that has not been allowed to
 * run in the background is stopped within minutes of the screen going off,
 * and the user discovers it by making a gesture that does nothing. Asking
 * plainly, once, is the only fix available.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var gesture: TextView
    private lateinit var warning: TextView
    private lateinit var fixButton: Button

    private var detector: HandPoseDetector? = null
    private var camera: GestureCamera? = null
    private val stateMachine = GrabStateMachine()

    @Volatile
    private var livePose: Pose = Pose.NONE
    private val recentEvents = ArrayDeque<String>()

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startGestureCamera()
        } else {
            // Refused is a legitimate choice: everything except the gesture
            // itself still works, and the app should say so rather than nag.
            gesture.text = "Camera not allowed.\nTransfers still work from the PC."
        }
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // The service needs a notification to run in the foreground at all.
        // Refused, it will still work until Android next decides otherwise.
        if (!granted) {
            Toast.makeText(this, "AirGrab needs its notification to stay running", Toast.LENGTH_LONG)
                .show()
        }
        AirGrabService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            AirGrabService.start(this)
        }

        watchStatus()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startGestureCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        camera?.stop()
        detector?.close()
        camera = null
        detector = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshWarning()
    }

    // ----------------------------------------------------------------- views

    private fun buildLayout(): ScrollView {
        val padding = (24 * resources.displayMetrics.density).toInt()

        val heading = TextView(this).apply {
            text = "AirGrab"
            textSize = 28f
        }

        gesture = TextView(this).apply {
            textSize = 15f
            setPadding(0, padding, 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
            text = "Camera starting..."
        }

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, padding, 0, 0)
            movementMethod = ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
        }

        warning = TextView(this).apply {
            textSize = 14f
            setPadding(0, padding, 0, 0)
            visibility = android.view.View.GONE
        }

        fixButton = Button(this).apply {
            text = "Open the setting"
            visibility = android.view.View.GONE
            setOnClickListener { openBackgroundSettings() }
        }

        val stopButton = Button(this).apply {
            text = "Stop AirGrab"
            setOnClickListener {
                AirGrabService.stop(this@MainActivity)
                Toast.makeText(context, "Stopped", Toast.LENGTH_SHORT).show()
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(padding, padding * 2, padding, padding)
            addView(heading)
            addView(gesture)
            addView(status)
            addView(warning)
            addView(fixButton)
            addView(stopButton)
        }

        return ScrollView(this).apply { addView(column) }
    }

    // ---------------------------------------------------------------- status

    private fun watchStatus() {
        lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                status.text = describe()
                gesture.text = describeGesture()
                delay(250)
            }
        }
    }

    private fun describe(): String {
        val service = AirGrabService.current
            ?: return "Service not running.\n\nTap the app icon again, or check\nbattery settings below."

        val node = service.node
            ?: return "Starting the link..."

        return buildString {
            appendLine("Listening on port ${node.port}")
            appendLine("This device: ${node.identity.fingerprint.take(16)}")
            appendLine()

            val peers = service.peers
            if (peers.isEmpty()) {
                appendLine("No other devices found yet.")
                appendLine()
                appendLine("Both devices must be on the same")
                appendLine("Wi-Fi network, and AirGrab must be")
                appendLine("running on the PC.")
            } else {
                appendLine("Devices nearby:")
                peers.forEach { peer ->
                    val paired = if (node.trust.isTrusted(peer.fingerprint)) "paired" else "not paired"
                    appendLine("  ${peer.name} (${peer.platform})")
                    appendLine("    ${peer.host}:${peer.port} - $paired")
                }
            }

            val trusted = node.trust.all()
            if (trusted.isNotEmpty()) {
                appendLine()
                appendLine("Paired devices:")
                trusted.forEach { appendLine("  ${it.name} (${it.platform})") }
            }
        }
    }

    // --------------------------------------------------------------- camera

    private fun startGestureCamera() {
        val loaded = HandPoseDetector.create(this)
        if (loaded == null) {
            gesture.text = "Gesture model failed to load."
            return
        }
        detector = loaded

        camera = GestureCamera(
            context = this,
            detector = loaded,
            stateMachine = stateMachine,
            onEvent = { event -> recordEvent(event) },
            onPose = { pose -> livePose = pose },
        ).also { it.start(this, front = true) }
    }

    private fun recordEvent(event: GestureEvent) {
        synchronized(recentEvents) {
            recentEvents.addFirst("${event.type} (${event.fromState} -> ${event.toState})")
            // A short window: the point is to see what just happened, not to
            // keep a log.
            while (recentEvents.size > 5) recentEvents.removeLast()
        }
    }

    private fun describeGesture(): String {
        val loaded = camera ?: return "Camera not running."
        val (seen, classified) = loaded.stats()

        return buildString {
            appendLine("Hand: ${livePose}")
            appendLine("State: ${stateMachine.state}")
            appendLine("Frames: $classified of $seen")
            val events = synchronized(recentEvents) { recentEvents.toList() }
            if (events.isNotEmpty()) {
                appendLine()
                appendLine("Recent:")
                events.forEach { appendLine("  $it") }
            }
        }
    }

    // ------------------------------------------------- background permission

    private fun refreshWarning() {
        val exempt = BatteryPolicy.isExempt(this)
        val manufacturer = BatteryPolicy.needsManufacturerAllowance()

        // The manufacturer note stays even once Android's own optimiser is
        // satisfied, because on these phones the two are separate settings and
        // the second one is the one that actually stops the app.
        if (exempt && !manufacturer) {
            warning.visibility = android.view.View.GONE
            fixButton.visibility = android.view.View.GONE
            return
        }

        warning.text = buildString {
            if (!exempt) {
                appendLine("This phone will stop AirGrab in the background.")
                appendLine()
            }
            if (manufacturer) {
                appendLine(BatteryPolicy.instructions())
            }
        }.trim()

        warning.visibility = android.view.View.VISIBLE
        fixButton.visibility = android.view.View.VISIBLE
    }

    private fun openBackgroundSettings() {
        if (!BatteryPolicy.isExempt(this)) {
            val request = BatteryPolicy.requestExemption(this)
            if (BatteryPolicy.canOpen(this, request)) {
                startActivity(request)
                return
            }
        }

        // Manufacturer screens are undocumented and vanish between firmware
        // versions, so a failure here is expected rather than exceptional.
        val manufacturer = BatteryPolicy.manufacturerSettings()
        if (manufacturer != null && BatteryPolicy.canOpen(this, manufacturer)) {
            runCatching { startActivity(manufacturer) }.onSuccess { return }
        }

        startActivity(BatteryPolicy.appSettings(this))
    }
}
