package com.airgrab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
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
import com.airgrab.core.GestureState
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
private const val PICK_HINT =
    "Make a fist to send this. Tap another photo to choose it, or share " +
        "any file to AirGrab from another app."

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    private lateinit var stateDot: View
    private lateinit var stateHeadline: TextView
    private lateinit var stateDetail: TextView

    private lateinit var contentText: TextView
    private lateinit var photoStrip: LinearLayout
    private lateinit var photoHint: TextView
    private lateinit var devicesCard: LinearLayout
    private lateinit var devicesList: LinearLayout

    private lateinit var warningCard: LinearLayout
    private lateinit var warningText: TextView

    private lateinit var overlayCard: LinearLayout

    private lateinit var diagnosticsText: TextView
    private lateinit var diagnosticsToggle: Button
    private var diagnosticsOpen = false

    /** Peers already offered a pairing dialog, so it is not reopened each tick. */
    private val pairingInFlight = mutableSetOf<String>()

    /**
     * Both permissions in one request.
     *
     * Asking for them one after another does not work: Android answers the
     * second with "Can request only one set of permissions at a time" and
     * silently drops it. On the first real handset that left the camera
     * permission never requested at all, and the service waiting behind a
     * dialog the user had not reached yet.
     */
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Started after the answer either way. The service checks the camera
        // permission itself, and without the notification permission Android
        // may stop it later — but refusing to start at all helps nobody.
        AirGrabService.start(this)
        refreshPhotos()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        acceptShare(intent)

        // The camera is bound to this activity's lifecycle, so the screen
        // going dark stops it. On a phone whose screensaver kicks in after
        // thirty seconds that means the gesture simply stops working while
        // the user is still standing there trying it, with nothing to say
        // why. Only while this screen is actually in front of them.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val wanted = listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
            PhotoLibrary.permission,
        )
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            AirGrabService.start(this)
        } else {
            requestPermissions.launch(missing.toTypedArray())
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
        refreshPhotos()
        // Checked on every resume: the user grants this in Settings and comes
        // back, so there is no result to listen for.
        overlayCard.visibility =
            if (GestureOverlay.permitted(this)) View.GONE else View.VISIBLE
    }

    private fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            Toast.makeText(
                this, "Allow AirGrab to display over other apps in Settings", Toast.LENGTH_LONG
            ).show()
        }
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

        contentText = Style.body(this, "Your most recent photo")
        photoStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        photoHint = Style.body(this, PICK_HINT)

        val contentCard = Style.card(this).apply {
            addView(Style.title(this@MainActivity, "READY TO SEND"))
            addView(Style.spacer(this@MainActivity, 8))
            addView(contentText)
            addView(Style.spacer(this@MainActivity, 12))
            addView(
                HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(photoStrip)
                }
            )
            addView(Style.spacer(this@MainActivity, 12))
            addView(photoHint)
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

        overlayCard = Style.card(this).apply {
            visibility = View.GONE
            addView(Style.title(this@MainActivity, "ON-SCREEN FEEDBACK"))
            addView(Style.spacer(this@MainActivity, 8))
            addView(
                Style.body(
                    this@MainActivity,
                    "Show what a gesture did on top of whatever you are using, " +
                        "so you know it worked without opening AirGrab.",
                )
            )
            addView(Style.spacer(this@MainActivity, 12))
            addView(plainButton("Allow") { requestOverlayPermission() })
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
            addView(overlayCard)
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

            service.stateMachine.state == GestureState.HOLDING -> {
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
        } ?: PhotoLibrary.latest(this)?.let {
            "${it.name}  (${humanSize(it.size)})  - your latest photo"
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

        val (seen, classified) = service?.cameraStats() ?: (0L to 0L)
        diagnosticsText.text = buildString {
            appendLine("hand      ${service?.livePose ?: Pose.NONE}")
            appendLine("gesture   ${service?.stateMachine?.state}")
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

            val events = service?.recentEvents?.let { synchronized(it) { it.toList() } }.orEmpty()
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

    // ---------------------------------------------------------------- photos

    /**
     * A strip of recent photos, so choosing a different one is a single tap.
     *
     * Rebuilt on resume rather than cached: the user has just come back from
     * another app, quite possibly having taken the very photo they want to
     * send, and a cached strip would not contain it.
     */
    private fun refreshPhotos() {
        if (!::photoStrip.isInitialized) return
        photoStrip.removeAllViews()

        if (!PhotoLibrary.permitted(this)) {
            photoHint.text = "Allow access to your photos to send them with a gesture."
            photoStrip.addView(
                plainButton("Allow photos") {
                    requestPermissions.launch(arrayOf(PhotoLibrary.permission))
                }
            )
            return
        }

        photoHint.text = PICK_HINT
        val edge = Style.dp(this, 76)

        lifecycleScope.launch(Dispatchers.IO) {
            val photos = PhotoLibrary.recent(this@MainActivity, limit = 12)
            val thumbnails = photos.map {
                it to PhotoLibrary.thumbnail(this@MainActivity, it, edge)
            }

            runOnUiThread {
                if (thumbnails.isEmpty()) {
                    photoStrip.addView(Style.body(this@MainActivity, "No photos found."))
                    return@runOnUiThread
                }
                thumbnails.forEach { (photo, bitmap) ->
                    photoStrip.addView(thumbnailView(photo, bitmap, edge))
                }
            }
        }
    }

    private fun thumbnailView(
        photo: PhotoLibrary.Photo,
        bitmap: android.graphics.Bitmap?,
        edge: Int,
    ): ImageView {
        val radius = Style.dp(this, 10).toFloat()
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(edge, edge).apply {
                rightMargin = Style.dp(this@MainActivity, 8)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Style.divider(this@MainActivity))
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            if (bitmap != null) setImageBitmap(bitmap)
            setOnClickListener { choose(photo) }
        }
    }

    /** Copy the chosen photo into the outgoing slot, off the main thread. */
    private fun choose(photo: PhotoLibrary.Photo) {
        lifecycleScope.launch(Dispatchers.IO) {
            val held = PendingContent.accept(this@MainActivity, photo.uri)
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    if (held == null) "Could not read that photo"
                    else "Ready to send ${held.name}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // ---------------------------------------------------------------- pairing

    private fun pairWith(peer: DiscoveredPeer) {
        val node = AirGrabService.current?.node ?: return
        if (!pairingInFlight.add(peer.fingerprint)) return

        lifecycleScope.launch(Dispatchers.IO) {
            // The reason is logged, not swallowed. "Pairing failed" with
            // nothing behind it is unactionable for the user and undebuggable
            // for anyone else, and pairing has several distinct ways to fail
            // that look identical from the outside.
            val outcome = runCatching {
                node.pairWith(peer.host, peer.port) { sas -> confirmCode(sas, peer.name) }
            }
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                android.util.Log.e(AirGrabService.TAG, "pairing with ${peer.name} threw", failure)
            } else if (outcome.getOrNull() != true) {
                android.util.Log.w(
                    AirGrabService.TAG,
                    "pairing with ${peer.name} was refused (no exception)",
                )
            }
            val granted = outcome.getOrNull() == true

            if (granted) {
                // Link immediately. Discovery only fires on CHANGE, and this
                // peer was already known — it was simply untrusted when it
                // arrived, so connectTo declined it. Without this the devices
                // are paired but hold no control channel, and the first
                // gesture after pairing goes nowhere.
                AirGrabService.current?.gestures?.connectTo(peer)
            }

            pairingInFlight.remove(peer.fingerprint)
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    when {
                        granted -> "Paired with ${peer.name}"
                        failure != null -> "Pairing failed: ${failure.message ?: failure.javaClass.simpleName}"
                        else -> "Pairing was declined"
                    },
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
