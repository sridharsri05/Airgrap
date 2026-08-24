package com.airgrab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.airgrab.core.DiscoveredPeer
import com.airgrab.core.GestureEvent
import com.airgrab.core.GestureState
import com.airgrab.core.GrabStateMachine
import com.airgrab.core.Pose
import com.airgrab.ui.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one screen.
 *
 * Ordered by what the user needs to know, not by how the app is built: what is
 * happening now, what a grab would send, which devices are around, and only
 * then the technical detail. The diagnostics stay — they are what makes a
 * gesture that did not work explainable rather than mysterious — but they sit
 * at the bottom, folded away.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    private lateinit var stateDot: View
    private lateinit var stateHeadline: TextView
    private lateinit var stateDetail: TextView

    private lateinit var contentText: TextView
    private lateinit var devicesCard: LinearLayout
    private lateinit var devicesList: LinearLayout

    private lateinit var warningCard: LinearLayout
    private lateinit var warningText: TextView

    private lateinit var diagnosticsText: TextView
    private lateinit var diagnosticsToggle: Button
    private var diagnosticsOpen = false

    private var detector: HandPoseDetector? = null
    private var camera: GestureCamera? = null
    private val stateMachine = GrabStateMachine()

    @Volatile
    private var livePose: Pose = Pose.NONE
    private val recentEvents = ArrayDeque<String>()

    /** Peers already offered a pairing dialog, so it is not reopened each tick. */
    private val pairingInFlight = mutableSetOf<String>()

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startGestureCamera()
        // Refusing is legitimate: receiving still works, and the app says so
        // in the status line rather than nagging.
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this, "AirGrab needs its notification to keep running", Toast.LENGTH_LONG
            ).show()
        }
        AirGrabService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        acceptShare(intent)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            AirGrabService.start(this)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startGestureCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }

        watch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshWarning()
    }

    override fun onDestroy() {
        camera?.stop()
        detector?.close()
        camera = null
        detector = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ views

    private fun buildLayout(): ScrollView {
        val pad = Style.dp(this, 20)

        stateDot = Style.dot(this, Style.muted(this))
        stateHeadline = Style.headline(this, "Starting")
        stateDetail = Style.body(this, "")

        val stateCard = Style.card(this).apply {
            addView(Style.row(this@MainActivity).apply {
                addView(stateDot)
                addView(Style.title(this@MainActivity, "AIRGRAB"))
            })
            addView(stateHeadline)
            addView(stateDetail)
        }

        contentText = Style.body(this, "Nothing selected")
        val contentCard = Style.card(this).apply {
            addView(Style.title(this@MainActivity, "READY TO SEND"))
            addView(Style.spacer(this@MainActivity, 8))
            addView(contentText)
            addView(Style.spacer(this@MainActivity, 12))
            addView(
                Style.body(
                    this@MainActivity,
                    "Share a photo or file to AirGrab, then make a fist at the camera.",
                )
            )
        }

        devicesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devicesCard = Style.card(this).apply {
            addView(Style.title(this@MainActivity, "DEVICES"))
            addView(Style.spacer(this@MainActivity, 8))
            addView(devicesList)
        }

        warningText = Style.body(this, "", Style.warn(this))
        val fixButton = plainButton("Open the setting") { openBackgroundSettings() }
        warningCard = Style.card(this).apply {
            visibility = View.GONE
            addView(Style.title(this@MainActivity, "BACKGROUND ACCESS"))
            addView(Style.spacer(this@MainActivity, 8))
            addView(warningText)
            addView(Style.spacer(this@MainActivity, 12))
            addView(fixButton)
        }

        diagnosticsText = Style.mono(this, "").apply { visibility = View.GONE }
        diagnosticsToggle = plainButton("Show details") { toggleDiagnostics() }
        val diagnosticsCard = Style.card(this).apply {
            addView(diagnosticsToggle)
            addView(diagnosticsText)
        }

        val stopButton = plainButton("Stop AirGrab") {
            AirGrabService.stop(this)
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show()
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.background(this@MainActivity))
            setPadding(pad, Style.dp(this@MainActivity, 32), pad, pad)
            addView(stateCard)
            addView(contentCard)
            addView(devicesCard)
            addView(warningCard)
            addView(diagnosticsCard)
            addView(stopButton)
        }

        return ScrollView(this).apply {
            setBackgroundColor(Style.background(this@MainActivity))
            addView(root)
        }
    }

    private fun plainButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Style.accent(this@MainActivity))
            background = GradientDrawable().apply {
                cornerRadius = Style.dp(this@MainActivity, 10).toFloat()
                setColor(Style.background(this@MainActivity))
                setStroke(Style.dp(this@MainActivity, 1), Style.divider(this@MainActivity))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onClick() }
        }

    private fun toggleDiagnostics() {
        diagnosticsOpen = !diagnosticsOpen
        diagnosticsText.visibility = if (diagnosticsOpen) View.VISIBLE else View.GONE
        diagnosticsToggle.text = if (diagnosticsOpen) "Hide details" else "Show details"
    }

    // ----------------------------------------------------------------- update

    private fun watch() {
        lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                render()
                delay(300)
            }
        }
    }

    private fun render() {
        val service = AirGrabService.current
        val node = service?.node

        when {
            service == null || node == null -> {
                stateDot.setBackgroundColourOf(Style.muted(this))
                stateHeadline.text = "Starting"
                stateDetail.text = "Setting up the link."
            }

            stateMachine.state == GestureState.HOLDING -> {
                stateDot.setBackgroundColourOf(Style.accent(this))
                stateHeadline.text = "Holding"
                stateDetail.text = "Open your palm at the other device to drop it."
            }

            service.activity != "Ready" -> {
                stateDot.setBackgroundColourOf(Style.accent(this))
                stateHeadline.text = service.activity
                stateDetail.text = ""
            }

            service.peers.isEmpty() -> {
                stateDot.setBackgroundColourOf(Style.warn(this))
                stateHeadline.text = "Looking for your PC"
                stateDetail.text =
                    "Both devices need the same Wi-Fi, with AirGrab running on the PC."
            }

            else -> {
                val paired = service.peers.count { node.trust.isTrusted(it.fingerprint) }
                stateDot.setBackgroundColourOf(
                    if (paired > 0) Style.good(this) else Style.warn(this)
                )
                stateHeadline.text = if (paired > 0) "Ready" else "Pair to continue"
                stateDetail.text = if (paired > 0) {
                    "Make a fist to pick something up."
                } else {
                    "A device is nearby but not paired yet."
                }
            }
        }

        contentText.text = PendingContent.current()?.let {
            "${it.name}  (${humanSize(it.length())})"
        } ?: "Nothing selected"

        renderDevices(service, node)
        renderDiagnostics(service, node)
    }

    private fun renderDevices(service: AirGrabService?, node: com.airgrab.core.Node?) {
        devicesList.removeAllViews()

        val peers = service?.peers.orEmpty()
        if (peers.isEmpty() || node == null) {
            devicesList.addView(Style.body(this, "None found yet."))
            return
        }

        peers.forEach { peer ->
            val trusted = node.trust.isTrusted(peer.fingerprint)
            devicesList.addView(Style.row(this).apply {
                addView(Style.dot(this@MainActivity,
                    if (trusted) Style.good(this@MainActivity) else Style.warn(this@MainActivity)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(Style.body(this@MainActivity, peer.name,
                        Style.text(this@MainActivity)))
                    addView(Style.mono(this@MainActivity,
                        "${peer.host}  ${if (trusted) "paired" else "not paired"}"))
                })
            })

            if (!trusted) {
                devicesList.addView(plainButton("Pair with ${peer.name}") { pairWith(peer) })
            }
            devicesList.addView(Style.spacer(this, 12))
        }
    }

    private fun renderDiagnostics(service: AirGrabService?, node: com.airgrab.core.Node?) {
        if (!diagnosticsOpen) return

        val (seen, classified) = camera?.stats() ?: (0L to 0L)
        diagnosticsText.text = buildString {
            appendLine("hand      $livePose")
            appendLine("gesture   ${stateMachine.state}")
            appendLine("frames    $classified of $seen")
            appendLine("port      ${node?.port ?: "-"}")
            appendLine("identity  ${node?.identity?.fingerprint?.take(16) ?: "-"}")
            appendLine("holding   ${service?.gestures?.holding ?: false}")

            val trusted = node?.trust?.all().orEmpty()
            if (trusted.isNotEmpty()) {
                appendLine()
                appendLine("paired")
                trusted.forEach { appendLine("  ${it.name} (${it.platform})") }
            }

            val events = synchronized(recentEvents) { recentEvents.toList() }
            if (events.isNotEmpty()) {
                appendLine()
                appendLine("recent")
                events.forEach { appendLine("  $it") }
            }
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
        else -> "$bytes bytes"
    }

    private fun View.setBackgroundColourOf(colour: Int) {
        (background as? GradientDrawable)?.setColor(colour)
    }

    // ------------------------------------------------------------------ share

    /** A file shared to AirGrab becomes what the next grab picks up. */
    private fun acceptShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: return

        val held = PendingContent.accept(this, uri)
        Toast.makeText(
            this,
            if (held == null) "Could not read that file" else "Ready to send ${held.name}",
            Toast.LENGTH_LONG,
        ).show()
    }

    // ----------------------------------------------------------------- camera

    private fun startGestureCamera() {
        val loaded = HandPoseDetector.create(this)
        if (loaded == null) {
            Toast.makeText(this, "Gesture recognition unavailable", Toast.LENGTH_LONG).show()
            return
        }
        detector = loaded

        camera = GestureCamera(
            context = this,
            detector = loaded,
            stateMachine = stateMachine,
            onEvent = { event ->
                record(event)
                // The service owns the coordinator, because a gesture has to
                // work when this screen is not on top.
                AirGrabService.current?.onGesture(event)
            },
            onPose = { livePose = it },
        ).also { it.start(this, front = true) }
    }

    private fun record(event: GestureEvent) {
        synchronized(recentEvents) {
            recentEvents.addFirst("${event.type} ${event.fromState} -> ${event.toState}")
            while (recentEvents.size > 6) recentEvents.removeLast()
        }
    }

    // ---------------------------------------------------------------- pairing

    private fun pairWith(peer: DiscoveredPeer) {
        val node = AirGrabService.current?.node ?: return
        if (!pairingInFlight.add(peer.fingerprint)) return

        lifecycleScope.launch(Dispatchers.IO) {
            val granted = runCatching {
                node.pairWith(peer.host, peer.port) { sas -> confirmCode(sas, peer.name) }
            }.getOrElse { false }

            pairingInFlight.remove(peer.fingerprint)
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    if (granted) "Paired with ${peer.name}" else "Pairing failed",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Show the six digits and wait for the user.
     *
     * Blocking is the point: this runs on the pairing coroutine, and answering
     * before the user has looked would defeat the check entirely. The digits
     * are the only defence before trust exists — a machine in the middle must
     * present its own certificate to each side, so the two screens would
     * disagree.
     */
    private fun confirmCode(sas: String, peerName: String): Boolean {
        val answer = java.util.concurrent.SynchronousQueue<Boolean>()

        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Pair with $peerName?")
                .setMessage(
                    "Code on this phone:\n\n    $sas\n\n" +
                        "Only continue if your PC is showing the same six digits."
                )
                .setCancelable(false)
                .setPositiveButton("They match") { _, _ -> offer(answer, true) }
                .setNegativeButton("Cancel") { _, _ -> offer(answer, false) }
                .show()
        }

        // A dismissed dialog would otherwise hang the pairing coroutine and,
        // with it, the control channel it is holding open.
        return answer.poll(60, java.util.concurrent.TimeUnit.SECONDS) ?: false
    }

    private fun offer(queue: java.util.concurrent.SynchronousQueue<Boolean>, value: Boolean) {
        // Offered rather than put: if the wait already timed out there is
        // nobody to receive it, and blocking here would freeze the UI thread.
        queue.offer(value)
    }

    // ------------------------------------------------- background permission

    private fun refreshWarning() {
        val exempt = BatteryPolicy.isExempt(this)
        val manufacturer = BatteryPolicy.needsManufacturerAllowance()

        // The manufacturer note stays even once Android's own optimiser is
        // satisfied: on these phones they are separate settings, and the
        // second is the one that actually stops the app.
        if (exempt && !manufacturer) {
            warningCard.visibility = View.GONE
            return
        }

        warningText.text = buildString {
            if (!exempt) {
                appendLine("This phone will stop AirGrab in the background.")
                appendLine()
            }
            if (manufacturer) append(BatteryPolicy.instructions())
        }.trim()
        warningCard.visibility = View.VISIBLE
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
        // versions, so failure here is expected rather than exceptional.
        BatteryPolicy.manufacturerSettings()
            ?.takeIf { BatteryPolicy.canOpen(this, it) }
            ?.let { runCatching { startActivity(it) }.onSuccess { return } }

        startActivity(BatteryPolicy.appSettings(this))
    }
}
