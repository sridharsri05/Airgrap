package com.airgrab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the picker says when it has photos to show. */
private const val PICK_HINT =
    "Make a fist to send this. Tap another photo to choose it, or share " +
        "any file to AirGrab from another app."

/**
 * The one screen.
 *
 * Ordered by what the user needs to know, not by how the app is built: what is
 * happening now, what a grab would send, which devices are around, what has
 * arrived, and only then the technical detail. The diagnostics stay — they are
 * what makes a gesture that did not work explainable rather than mysterious —
 * but they sit at the bottom, folded away.
 *
 * The state card is deliberately the largest thing here. Everything else on
 * this screen is a setting or a record; that card is the only part the user
 * reads while actually using the app, usually at arm's length with one hand
 * held up in front of the camera.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout

    private lateinit var stateBadge: TextView
    private lateinit var stateHeadline: TextView
    private lateinit var stateDetail: TextView

    private lateinit var contentThumb: ImageView
    private lateinit var contentName: TextView
    private lateinit var contentMeta: TextView
    private lateinit var photoStrip: LinearLayout
    private lateinit var photoHint: TextView
    private lateinit var devicesList: LinearLayout

    private lateinit var receivedCard: LinearLayout
    private lateinit var receivedList: LinearLayout
    private lateinit var receivedWhere: TextView

    private lateinit var warningCard: LinearLayout
    private lateinit var warningText: TextView

    private lateinit var overlayCard: LinearLayout
    private lateinit var wifiCard: LinearLayout

    private lateinit var diagnosticsText: TextView
    private lateinit var diagnosticsToggle: Button
    private var diagnosticsOpen = false

    /** Peers already offered a pairing dialog, so it is not reopened each tick. */
    private val pairingInFlight = mutableSetOf<String>()

    /** The file the thumbnail currently shows, so it is decoded only on change. */
    private var thumbnailFor: String? = null

    /**
     * The newest photo, as of the last time the strip was built.
     *
     * Held rather than queried in [render]: that runs four times a second, and
     * a MediaStore query is a database read the main thread should not be
     * doing at all, let alone at that rate. Refreshed on resume, which is
     * exactly when a new photo can have appeared.
     */
    @Volatile
    private var latestPhoto: PhotoLibrary.Photo? = null

    /**
     * Both permissions in one request.
     *
     * Asking for them one after another does not work: Android answers the
     * second with "Can request only one set of permissions at a time" and
     * silently drops it. On the first real handset that left the camera
     * permission never requested at all, and the service waiting behind a
     * dialog the user had not reached yet.
     */
    /** Asked only when the user taps "Connect directly", not at launch. */
    private val requestDirectLink = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) hostDirectLink()
        else Toast.makeText(
            this,
            "Android needs that permission to open a direct link.",
            Toast.LENGTH_LONG,
        ).show()
    }

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
        // Re-checked on resume: this is exactly the moment the user comes
        // back from the Wi-Fi panel having turned it on.
        wifiCard.visibility =
            if (wifiIsOn() || hotspotIsOn()) View.GONE else View.VISIBLE
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

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Style.background(this@MainActivity))
            setPadding(pad, Style.dp(this@MainActivity, 28), pad, pad)
            addView(stateCard())
            addView(wifiCard())
            addView(contentCard())
            addView(devicesCard())
            addView(receivedCard())
            addView(overlayCard())
            addView(warningCard())
            addView(diagnosticsCard())
            addView(footer())
        }

        return ScrollView(this).apply {
            setBackgroundColor(Style.background(this@MainActivity))
            addView(root)
        }
    }

    /**
     * The hand, and one sentence.
     *
     * Huawei's own gesture UI answers exactly one question — is it watching me,
     * and did it see that — and answers it with a hand at the top of the
     * screen. The sentence underneath says what to do next rather than naming
     * an internal state, because "Connected" tells the user nothing they can
     * act on and "Open your palm at your PC" tells them everything.
     */
    private fun stateCard(): LinearLayout {
        stateBadge = Style.glyphBadge(this, "•", Style.muted(this))
        stateHeadline = Style.headline(this, "Starting")
        stateDetail = Style.body(this, "Setting up the link.")

        return Style.card(this).apply {
            addView(Style.title(this@MainActivity, "AIRGRAB"))
            addView(Style.spacer(this@MainActivity, 16))
            addView(Style.row(this@MainActivity).apply {
                addView(stateBadge)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                    addView(stateHeadline)
                })
            })
            addView(Style.spacer(this@MainActivity, 10))
            addView(stateDetail)
        }
    }

    /**
     * What a fist would pick up, shown rather than named.
     *
     * A filename is a claim; the picture is proof. The whole failure this
     * guards against is grabbing confidently and sending the wrong photo,
     * which is invisible until it lands on the PC.
     */
    private fun contentCard(): LinearLayout {
        contentThumb = squareImage(Style.dp(this, 64))
        contentName = Style.body(this, "Nothing selected", Style.text(this)).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        contentMeta = Style.mono(this, "")
        photoStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        photoHint = Style.body(this, PICK_HINT)

        return Style.card(this).apply {
            addView(Style.title(this@MainActivity, "READY TO SEND"))
            addView(Style.spacer(this@MainActivity, 14))
            addView(Style.row(this@MainActivity).apply {
                addView(contentThumb)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    ).apply { leftMargin = Style.dp(this@MainActivity, 14) }
                    addView(contentName)
                    addView(contentMeta)
                })
            })
            addView(Style.spacer(this@MainActivity, 16))
            addView(
                HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(photoStrip)
                }
            )
            addView(Style.spacer(this@MainActivity, 14))
            addView(photoHint)
        }
    }

    private fun devicesCard(): LinearLayout {
        devicesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        return Style.card(this).apply {
            addView(Style.title(this@MainActivity, "DEVICES"))
            addView(Style.spacer(this@MainActivity, 14))
            addView(devicesList)
            addView(Style.spacer(this@MainActivity, 12))
            // Present even on Wi-Fi: two phones on DIFFERENT networks meet
            // the same wall, and the answer is the same QR.
            addView(quietButton("Connect directly - show a QR") { hostDirectLink() })
        }
    }

    private fun receivedCard(): LinearLayout {
        receivedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        receivedWhere = Style.mono(this, "")
        receivedCard = Style.card(this).apply {
            addView(Style.title(this@MainActivity, "ARRIVED"))
            addView(Style.spacer(this@MainActivity, 14))
            addView(receivedList)
            addView(Style.spacer(this@MainActivity, 12))
            addView(receivedWhere)
            addView(Style.spacer(this@MainActivity, 12))
            addView(quietButton("Open the folder") {
                if (!ReceiveFolder.open(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "Look in ${ReceiveFolder.describe(this@MainActivity)}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            })
            addView(Style.spacer(this@MainActivity, 8))
            addView(quietButton("Change folder") { chooseReceiveFolder() })
        }
        return receivedCard
    }

    /**
     * Let the user pick where received files land.
     *
     * The service reads the folder when it starts, so it is restarted after
     * a change -- a file mid-transfer at that exact moment would be the
     * user's own doing, and the alternative (a folder setting that silently
     * applies only sometimes) is worse.
     */
    private fun chooseReceiveFolder() {
        val choices = ReceiveFolder.Choice.entries
        val current = ReceiveFolder.current(this)
        AlertDialog.Builder(this)
            .setTitle("Save received files in")
            .setSingleChoiceItems(
                choices.map { it.label }.toTypedArray(),
                choices.indexOf(current),
            ) { dialog, index ->
                dialog.dismiss()
                val chosen = choices[index]
                if (chosen != current) {
                    ReceiveFolder.set(this, chosen)
                    AirGrabService.stop(this)
                    AirGrabService.start(this)
                    Toast.makeText(
                        this,
                        "Now saving to ${ReceiveFolder.describe(this)}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shown only while Wi-Fi is actually off.
     *
     * Every file-sharing app the user knows asks for Wi-Fi the moment it
     * opens, and AirGrab silently showed "Looking for your PC" instead --
     * which is technically true and completely unhelpful when the radio the
     * search depends on is disabled. The button opens Android's own Wi-Fi
     * panel over the app, so turning it on is one tap and no navigation.
     *
     * Wi-Fi only. Bluetooth is deliberately NOT asked for: AirGrab does not
     * use it, and asking for a radio it never touches would teach the user
     * that its requests are noise.
     */
    private fun wifiCard(): LinearLayout {
        wifiCard = Style.card(this).apply {
            visibility = View.GONE
            addView(Style.title(this@MainActivity, "NO NETWORK"))
            addView(Style.spacer(this@MainActivity, 10))
            addView(
                Style.body(
                    this@MainActivity,
                    "AirGrab finds your other devices over Wi-Fi. Two ways " +
                        "to get connected:\n\n" +
                        "•  Join the same Wi-Fi as the other device, or\n" +
                        "•  No Wi-Fi around? Turn on the hotspot on ONE " +
                        "phone and connect the other device to it. No internet " +
                        "is needed — the hotspot itself is enough.",
                )
            )
            addView(Style.spacer(this@MainActivity, 14))
            addView(accentButton("Connect directly - show a QR") { hostDirectLink() })
            addView(Style.spacer(this@MainActivity, 8))
            addView(quietButton("Turn on Wi-Fi") { openWifiPanel() })
            addView(Style.spacer(this@MainActivity, 8))
            addView(quietButton("Open hotspot settings") { openHotspotSettings() })
        }
        return wifiCard
    }

    // ------------------------------------------------------------ direct link

    /**
     * Host a private network and show its QR.
     *
     * The card used to explain the hotspot recipe and leave the user to
     * carry it out by hand across two settings screens. This does the whole
     * thing: the app starts Android's local-only hotspot (a private Wi-Fi
     * with no internet, invented for exactly this), and the other phone
     * joins by pointing its ordinary camera at the code -- nothing typed,
     * nothing installed first.
     */
    private fun hostDirectLink() {
        if (!DirectLink.permitted(this)) {
            requestDirectLink.launch(DirectLink.permission)
            return
        }
        DirectLink.start(
            this,
            onReady = { link -> runOnUiThread { showDirectLinkDialog(link) } },
            onFailed = { reason ->
                runOnUiThread { Toast.makeText(this, reason, Toast.LENGTH_LONG).show() }
            },
        )
    }

    private fun showDirectLinkDialog(link: DirectLink.Link) {
        val size = Style.dp(this, 260)
        val image = ImageView(this).apply {
            setImageBitmap(DirectLink.qrBitmap(link, size))
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = Style.dp(this@MainActivity, 20)
            setPadding(pad, pad, pad, 0)
            addView(image)
            addView(Style.spacer(this@MainActivity, 12))
            addView(
                Style.body(
                    this@MainActivity,
                    "On the other phone, open the CAMERA and point it here. " +
                        "Tap the prompt to join, then open AirGrab on it.\n\n" +
                        "Network: ${link.ssid}\nPassword: ${link.passphrase}",
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Direct link")
            .setView(body)
            // Dismissing must NOT stop the network -- the other phone is on
            // it. Hosting ends explicitly, or when AirGrab itself stops.
            .setPositiveButton("Keep running") { dialog, _ -> dialog.dismiss() }
            .setNegativeButton("Stop hosting") { _, _ ->
                DirectLink.stop()
                Toast.makeText(this, "Direct link stopped", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * The tethering screen is not part of Android's public intent surface,
     * so this is a ladder: the settings screen most firmwares actually use,
     * then the generic wireless settings it lives under.
     */
    private fun openHotspotSettings() {
        val direct = Intent().setClassName(
            "com.android.settings", "com.android.settings.TetherSettings"
        )
        runCatching { startActivity(direct) }.onFailure {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
            }.onFailure {
                Toast.makeText(
                    this,
                    "Open Settings and search for ‘hotspot’",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openWifiPanel() {
        // The panel slides over the app rather than leaving it. Fall back to
        // the full settings screen where a manufacturer removed the panel.
        runCatching {
            startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI))
        }.onFailure {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            }
        }
    }

    private fun wifiIsOn(): Boolean =
        runCatching {
            (applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager)
                .isWifiEnabled
        }.getOrDefault(true) // no answer must not nag; the card is for a KNOWN-off radio

    /**
     * True while this phone is being the router itself.
     *
     * A phone whose hotspot is on has Wi-Fi "off" and is perfectly
     * reachable -- the other devices connect TO it. Showing the no-network
     * card in that state would tell the user to undo exactly the setup this
     * app suggested. The API is hidden, so reflection, and a phone that
     * refuses to answer is treated as not-a-hotspot.
     */
    private fun hotspotIsOn(): Boolean =
        runCatching {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE)
                as android.net.wifi.WifiManager
            val method = wifi.javaClass.getMethod("isWifiApEnabled")
            method.isAccessible = true
            method.invoke(wifi) as? Boolean ?: false
        }.getOrDefault(false)

    private fun overlayCard(): LinearLayout {
        overlayCard = Style.card(this).apply {
            visibility = View.GONE
            addView(Style.title(this@MainActivity, "ON-SCREEN FEEDBACK"))
            addView(Style.spacer(this@MainActivity, 10))
            addView(
                Style.body(
                    this@MainActivity,
                    "Show what a gesture did on top of whatever you are using, " +
                        "so you know it worked without opening AirGrab.",
                )
            )
            addView(Style.spacer(this@MainActivity, 14))
            addView(accentButton("Allow") { requestOverlayPermission() })
        }
        return overlayCard
    }

    private fun warningCard(): LinearLayout {
        warningText = Style.body(this, "", Style.warn(this))
        warningCard = Style.card(this).apply {
            visibility = View.GONE
            addView(Style.title(this@MainActivity, "BACKGROUND ACCESS"))
            addView(Style.spacer(this@MainActivity, 10))
            addView(warningText)
            addView(Style.spacer(this@MainActivity, 14))
            addView(accentButton("Open the setting") { openBackgroundSettings() })
        }
        return warningCard
    }

    private fun diagnosticsCard(): LinearLayout {
        diagnosticsText = Style.mono(this, "").apply { visibility = View.GONE }
        diagnosticsToggle = quietButton("Show details") { toggleDiagnostics() }
        return Style.card(this).apply {
            addView(diagnosticsToggle)
            addView(diagnosticsText)
        }
    }

    /**
     * Stopping the app is not part of using it.
     *
     * It sat directly under the gesture controls and got pressed by accident
     * mid-test, which stops the service and makes the phone vanish from the
     * PC with no obvious connection to the tap that caused it. Down here, in
     * the quiet weight, it is still one tap away and no longer in the path of
     * anything else.
     */
    private fun footer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, Style.dp(this@MainActivity, 8), 0, Style.dp(this@MainActivity, 24))
        addView(quietButton("Stop AirGrab") {
            AirGrabService.stop(this@MainActivity)
            Toast.makeText(this@MainActivity, "Stopped", Toast.LENGTH_SHORT).show()
        })
    }

    // ---------------------------------------------------------------- controls

    /** The one filled control on screen at a time: the thing to do next. */
    private fun accentButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (Style.dark(this@MainActivity)) 0xFF0D0D0D.toInt() else 0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = Style.dp(this@MainActivity, 100).toFloat()
                setColor(Style.accent(this@MainActivity))
            }
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Style.dp(this@MainActivity, 46),
            )
            setOnClickListener { onClick() }
        }

    /** Everything else: a label the colour of the accent, on the card itself. */
    private fun quietButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Style.accent(this@MainActivity))
            background = GradientDrawable().apply {
                cornerRadius = Style.dp(this@MainActivity, 100).toFloat()
                setColor(Style.wash(this@MainActivity, Style.accent(this@MainActivity)))
            }
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Style.dp(this@MainActivity, 44),
            )
            setOnClickListener { onClick() }
        }

    private fun squareImage(edge: Int): ImageView {
        val radius = Style.dp(this, 14).toFloat()
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(edge, edge)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Style.raised(this@MainActivity))
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
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
            service == null || node == null ->
                setState("•", Style.muted(this), "Starting", "Setting up the link.")

            service.stateMachine.state == GestureState.HOLDING ->
                setState(
                    "✊", Style.accent(this), "Holding",
                    "Open your palm at the other device to drop it.",
                )

            // Only while it is still news. "Connected to PADMA-PC" is a
            // status, not an event: it never gets superseded, so showing it
            // whenever it was not the word "Ready" meant the card sat on it
            // forever and the sentence telling the user to make a fist was
            // never seen again after the first link came up.
            service.activity != "Ready" && service.activityIsFresh() ->
                setState("✓", Style.good(this), service.activity, "")

            service.peers.isEmpty() ->
                setState(
                    "◌", Style.warn(this), "Looking for your PC",
                    "Both devices need the same Wi-Fi, with AirGrab running on the PC.",
                )

            else -> {
                val paired = service.peers.count { node.trust.isTrusted(it.fingerprint) }
                if (paired > 0) {
                    val where = service.peers
                        .firstOrNull { node.trust.isTrusted(it.fingerprint) }?.name
                    setState(
                        "🖐", Style.good(this), "Ready",
                        "Make a fist to pick something up" +
                            (where?.let { ", then open your palm at $it." } ?: "."),
                    )
                } else {
                    setState(
                        "◌", Style.warn(this), "Pair to continue",
                        "A device is nearby but not paired yet.",
                    )
                }
            }
        }

        renderContent()
        renderDevices(service, node)
        renderReceived(service)
        renderDiagnostics(service, node)
    }

    private fun setState(glyph: String, colour: Int, headline: String, detail: String) {
        stateBadge.text = glyph
        (stateBadge.background as? GradientDrawable)?.setColor(Style.wash(this, colour))
        stateHeadline.text = headline
        stateDetail.text = detail
        stateDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderContent() {
        val held = PendingContent.current()
        val latest = if (held == null) latestPhoto else null

        val name = held?.name ?: latest?.name
        val size = held?.length() ?: latest?.size
        val source = when {
            held != null -> "chosen"
            latest != null -> "your latest photo"
            else -> null
        }

        contentName.text = name ?: "Nothing selected"
        contentMeta.text = if (name == null) {
            "Take a photo, or share a file to AirGrab"
        } else {
            "${humanSize(size ?: 0)}  ·  $source"
        }

        // Decoded only when the file changes: this runs four times a second.
        val key = held?.absolutePath ?: latest?.uri?.toString()
        if (key != thumbnailFor) {
            thumbnailFor = key
            showThumbnail(held, latest)
        }
    }

    private fun showThumbnail(held: File?, latest: PhotoLibrary.Photo?) {
        contentThumb.setImageDrawable(null)
        val edge = Style.dp(this, 64)

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = when {
                held != null -> decodeThumbnail(held, edge)
                latest != null -> PhotoLibrary.thumbnail(this@MainActivity, latest, edge)
                else -> null
            }
            runOnUiThread { if (bitmap != null) contentThumb.setImageBitmap(bitmap) }
        }
    }

    /**
     * A thumbnail for a file the app is holding.
     *
     * Returns null for anything that is not an image — a shared PDF or video
     * has no picture to show, and an empty tile is a truthful answer to that.
     * Sampled rather than fully decoded: a 50-megapixel photo decoded at full
     * size to fill 64dp is how a gallery runs out of memory.
     */
    private fun decodeThumbnail(file: File, edge: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= edge &&
                bounds.outHeight / (sample * 2) >= edge
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

    private fun renderDevices(service: AirGrabService?, node: com.airgrab.core.Node?) {
        devicesList.removeAllViews()

        val peers = service?.peers.orEmpty()
        if (peers.isEmpty() || node == null) {
            devicesList.addView(Style.body(this, "None found yet."))
            return
        }

        peers.forEachIndexed { index, peer ->
            val trusted = node.trust.isTrusted(peer.fingerprint)
            devicesList.addView(Style.row(this).apply {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                    addView(Style.body(this@MainActivity, peer.name, Style.text(this@MainActivity)))
                    addView(Style.mono(this@MainActivity, peer.host))
                })
                addView(
                    Style.chip(
                        this@MainActivity,
                        if (trusted) "Paired" else "Not paired",
                        if (trusted) Style.good(this@MainActivity) else Style.warn(this@MainActivity),
                    )
                )
            })

            if (!trusted) {
                devicesList.addView(Style.spacer(this, 12))
                devicesList.addView(accentButton("Pair with ${peer.name}") { pairWith(peer) })
            }
            if (index != peers.lastIndex) devicesList.addView(Style.spacer(this, 16))
        }
    }

    private fun renderReceived(service: AirGrabService?) {
        val arrivals = service?.received?.let { synchronized(it) { it.toList() } }.orEmpty()

        // The card stays even when empty: the user asked, reasonably, where
        // their files would go, and the answer was nowhere to be seen.
        receivedWhere.text = "Saved in ${ReceiveFolder.describe(this)}"

        receivedList.removeAllViews()
        if (arrivals.isEmpty()) {
            receivedList.addView(Style.body(this, "Nothing yet."))
            return
        }

        arrivals.take(5).forEachIndexed { index, arrival ->
            receivedList.addView(Style.row(this).apply {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                    addView(
                        Style.body(
                            this@MainActivity, arrival.file.name, Style.text(this@MainActivity)
                        ).apply {
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        }
                    )
                    addView(
                        Style.mono(
                            this@MainActivity,
                            "${humanSize(arrival.size)}  ·  ${clockTime(arrival.at)}",
                        )
                    )
                })
            })
            if (index != minOf(arrivals.size, 5) - 1) receivedList.addView(Style.spacer(this, 14))
        }

    }

    private fun renderDiagnostics(service: AirGrabService?, node: com.airgrab.core.Node?) {
        if (!diagnosticsOpen) return

        val (seen, classified) = service?.cameraStats() ?: (0L to 0L)
        diagnosticsText.text = buildString {
            appendLine()
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

    private fun clockTime(millis: Long): String =
        android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(millis))

    // ------------------------------------------------------------------ share

    /** A file shared to AirGrab becomes what the next grab picks up. */
    private fun acceptShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: return

        Toast.makeText(this, describe(PendingContent.accept(this, uri)), Toast.LENGTH_LONG)
            .show()
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
                accentButton("Allow photos") {
                    requestPermissions.launch(arrayOf(PhotoLibrary.permission))
                }
            )
            return
        }

        photoHint.text = PICK_HINT
        val edge = Style.dp(this, 76)

        lifecycleScope.launch(Dispatchers.IO) {
            val photos = PhotoLibrary.recent(this@MainActivity, limit = 12)
            latestPhoto = photos.firstOrNull()
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
        bitmap: Bitmap?,
        edge: Int,
    ): ImageView = squareImage(edge).apply {
        layoutParams = LinearLayout.LayoutParams(edge, edge).apply {
            rightMargin = Style.dp(this@MainActivity, 8)
        }
        if (bitmap != null) setImageBitmap(bitmap)
        setOnClickListener { choose(photo) }
    }

    /**
     * What to tell the user about a file they just handed over.
     *
     * A refusal carries its own sentence, because the reasons are genuinely
     * different: a file that will not fit is not a file that could not be
     * read, and "Could not read that file" sent someone hunting for a corrupt
     * photo when their phone was simply full.
     */
    private fun describe(held: Held): String = when (held) {
        is Held.Ready -> "Ready to send ${held.file.name}"
        is Held.Refused -> held.message
    }

    /** Copy the chosen photo into the outgoing slot, off the main thread. */
    private fun choose(photo: PhotoLibrary.Photo) {
        lifecycleScope.launch(Dispatchers.IO) {
            val held = PendingContent.accept(this@MainActivity, photo.uri)
            runOnUiThread {
                Toast.makeText(this@MainActivity, describe(held), Toast.LENGTH_LONG).show()
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
