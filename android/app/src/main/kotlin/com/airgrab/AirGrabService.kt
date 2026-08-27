package com.airgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.airgrab.core.DiscoveredPeer
import com.airgrab.core.FileIdentity
import com.airgrab.core.GestureEvent
import com.airgrab.core.GrabStateMachine
import com.airgrab.core.Node
import com.airgrab.core.NodeConfig
import com.airgrab.core.Pose
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Keeps AirGrab reachable while the user is doing something else.
 *
 * A transfer is initiated by a gesture, which means the app is almost never
 * the thing in the foreground when it matters: the user is in their gallery,
 * or their file manager, or has the screen off entirely. Android will
 * otherwise stop the process within minutes, and the desktop would see the
 * phone vanish for no reason the user could explain.
 *
 * ## Why `connectedDevice` and not `dataSync`
 *
 * `dataSync` is the obvious-looking choice and is wrong here. Since Android
 * 15 it is capped at six hours in any twenty-four, after which the system
 * stops the service. The failure would appear as the phone becoming
 * undiscoverable roughly once a day, recovering when the app was reopened,
 * with nothing in the UI to explain it. `connectedDevice` carries no such cap
 * and honestly describes what this is: a live link to another device.
 *
 * ## The part Android cannot promise
 *
 * A foreground service is a request, not a guarantee. Manufacturer battery
 * management — vivo's especially, and this is a vivo handset — will still
 * stop apps it considers idle, regardless of foreground status. That is not
 * something code can defeat; it needs the user to allow background activity
 * once, which [BatteryPolicy] exists to ask for.
 */
class AirGrabService : LifecycleService() {

    companion object {
        const val TAG = "AirGrabService"
        private const val CHANNEL_ID = "airgrab-link"
        private const val NOTIFICATION_ID = 1

        /**
         * How long a one-off event stays in the headline before the card
         * goes back to saying what to do next.
         */
        const val ACTIVITY_HEADLINE_MILLIS = 6_000L

        const val ACTION_START = "com.airgrab.START"
        const val ACTION_STOP = "com.airgrab.STOP"

        /** The running instance, so the UI can read state without binding. */
        @Volatile
        var current: AirGrabService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, AirGrabService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AirGrabService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var node: Node? = null
        private set
    private var discovery: NsdDiscovery? = null

    var gestures: GestureOrchestrator? = null
        private set

    /**
     * The camera lives here, not in the activity.
     *
     * A gesture is made when the user is looking at something else entirely —
     * their gallery, another app, or nothing at all. Binding the camera to an
     * activity meant it stopped the moment the screen dimmed, so the feature
     * worked only while the user was staring at the app that exists to be
     * ignored.
     */
    private var detector: HandPoseDetector? = null
    private var camera: GestureCamera? = null
    private val overlay by lazy { GestureOverlay(this) }
    val stateMachine = GrabStateMachine()

    @Volatile
    var livePose: Pose = Pose.NONE
        private set

    /** Frames captured and frames classified, for the diagnostics panel. */
    fun cameraStats(): Pair<Long, Long> = camera?.stats() ?: (0L to 0L)

    /** The most recent gesture events, newest first. */
    val recentEvents = ArrayDeque<String>()

    /** A file that arrived, kept so the screen can say where it went. */
    data class Arrival(val file: File, val size: Long, val at: Long)

    /**
     * Files received this run, newest first.
     *
     * The app otherwise forgets every transfer the moment its notification is
     * dismissed, which leaves the user with a file somewhere on their phone
     * and no way to find out where. Capped, and deliberately not persisted:
     * this is a record of what just happened, not a file manager.
     */
    val received = ArrayDeque<Arrival>()

    /** The last thing that happened, for the screen and the notification. */
    @Volatile
    var activity: String = "Ready"
        private set

    /**
     * When [activity] last changed, so the screen can stop repeating it.
     *
     * The state card showed this string whenever it was not the word "Ready",
     * and "Connected to D_L_AR-AEEV03J" is not an event -- it is a status
     * that never gets superseded. So the card sat on it permanently, and the
     * one sentence the user needs, telling them to make a fist, was never
     * shown at all after the first link came up.
     *
     * The notification still says it, which is where a status belongs.
     */
    @Volatile
    var activityAt: Long = 0L
        private set

    /** True while [activity] is recent enough to be worth the headline. */
    fun activityIsFresh(now: Long = System.currentTimeMillis()): Boolean =
        now - activityAt < ACTIVITY_HEADLINE_MILLIS

    /**
     * Whoever can show six digits right now, or nobody.
     *
     * Set by the activity while it is in front of the user, cleared when it
     * leaves. Inbound pairing with nobody to ask is REFUSED: the fallback
     * used to be silent acceptance, which meant a phone would pair with
     * anything that knocked while its screen was off. The user found it by
     * pairing from the other phone and noticing this one never asked.
     */
    @Volatile
    var pairPrompt: ((sas: String, peerName: String) -> Boolean)? = null

    /** Peers currently visible, for the UI to render. */
    val peers: List<DiscoveredPeer> get() = discovery?.peers().orEmpty()

    var onPeersChanged: (() -> Unit)? = null

    @Volatile
    private var status: String = "Starting"

    /**
     * Claimed before any work begins, not after.
     *
     * `if (node == null)` looks like it guards this and does not: bringUp is
     * suspending, so two callers both see null long before either assigns.
     * That happens routinely — the boot receiver and the activity can start
     * the service within the same second — and the second attempt died with
     * "Address already in use" while the first was already serving.
     */
    private val starting = AtomicBoolean(false)

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Promoted immediately, before any slow work. Android allows only a
        // few seconds between startForegroundService and startForeground, and
        // missing that window crashes the app rather than merely failing.
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Starting"),
            // Both types: the link and the camera are equally the point, and
            // declaring only one would have Android stop the other.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )

        if (starting.compareAndSet(false, true)) scope.launch { bringUp() }

        // START_STICKY so the system restarts us if it reclaims memory. Not a
        // guarantee, but the difference between recovering in a minute and
        // staying dead until the user notices.
        return START_STICKY
    }

    private suspend fun bringUp() {
        try {
            val identity = FileIdentity.loadOrCreate(filesDir, deviceLabel())
            val started = Node(
                NodeConfig(
                    dataDir = filesDir,
                    downloadDir = downloadDirectory(),
                    displayName = deviceLabel(),
                    platform = "android",
                ),
                identity,
            )
            started.start()
            node = started
            Log.i(TAG, "listening on ${started.port} as ${identity.fingerprint.take(16)}")

            // Notification only. Whether the file was allowed at all was
            // decided by the node, which refuses anything from a device that
            // is not paired.
            started.onPairRequest = { sas, peerName ->
                pairPrompt?.invoke(sas, peerName) ?: false
            }

            started.onIncomingFile = { file ->
                activity = "Received ${file.name}"
                activityAt = System.currentTimeMillis()
                // Tell the media scanner, so a received photo appears in the
                // gallery now rather than after the next reboot.
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(file.absolutePath), null, null
                )
                synchronized(received) {
                    received.addFirst(Arrival(file, file.length(), System.currentTimeMillis()))
                    while (received.size > 20) received.removeLast()
                }
                Haptics.received(this)
                overlay.show(GestureOverlay.Kind.RECEIVED, file.name)
                updateStatus()
            }

            val orchestrator = GestureOrchestrator(
                node = started,
                scope = scope,
                machine = stateMachine,
                peerLookup = { fingerprint ->
                    // Discovery first; failing that, any live connection has
                    // already taught the node where this peer answers. On a
                    // direct link discovery NEVER knows, and consulting only
                    // it turned every gesture into "Lost the other device".
                    discovery?.peers()?.firstOrNull { it.fingerprint == fingerprint }
                        ?: started.addressOf(fingerprint)?.let { address ->
                            val known = started.trust.all()
                                .firstOrNull { it.fingerprint == fingerprint }
                            DiscoveredPeer(
                                fingerprint = fingerprint,
                                name = known?.name ?: "Device",
                                platform = known?.platform ?: "unknown",
                                host = address,
                                port = com.airgrab.core.DEFAULT_PORT,
                            )
                        }
                },
                onContentWanted = {
                    // Shared something? Send exactly that — any file, any
                    // type. Otherwise fall back to the most recent photo,
                    // which is the case worth having no steps at all: take a
                    // photo, make a fist, it is on the PC.
                    PendingContent.current() ?: PhotoLibrary.holdLatest(this)
                },
                onStatus = { text ->
                    activity = text
                    activityAt = System.currentTimeMillis()
                    // The one outcome the user most needs to feel: the file
                    // actually left. A grab that picked nothing up is worth
                    // knowing about immediately too.
                    when {
                        text.startsWith("Sent ") -> {
                            Haptics.sent(this)
                            overlay.show(
                                GestureOverlay.Kind.SENT, text.removePrefix("Sent ")
                            )
                        }
                        text.startsWith("Send failed") -> {
                            Haptics.cancelled(this)
                            overlay.show(GestureOverlay.Kind.CANCELLED, "Send failed")
                        }
                        text.startsWith("Nothing to send") -> {
                            Haptics.cancelled(this)
                            overlay.show(
                                GestureOverlay.Kind.CANCELLED, "Share a file to AirGrab first"
                            )
                        }
                        else -> Unit
                    }
                    updateStatus()
                },
            )
            orchestrator.start()
            gestures = orchestrator

            val nsd = NsdDiscovery(
                context = this,
                fingerprint = identity.fingerprint,
                displayName = deviceLabel(),
                onFound = { peer ->
                    Log.i(TAG, "found ${peer.name} at ${peer.host}:${peer.port}")
                    // Held open rather than dialled per gesture: the receiving
                    // device has to already be listening when a release
                    // happens, and a handshake would add its latency to an
                    // interaction measured in tenths of a second.
                    orchestrator.connectTo(peer)
                    updateStatus()
                    onPeersChanged?.invoke()
                },
                onLost = { fingerprint ->
                    orchestrator.peerLost(fingerprint)
                    updateStatus()
                    onPeersChanged?.invoke()
                },
            )
            nsd.startAdvertising(started.port)
            nsd.startBrowsing()
            discovery = nsd

            startCamera(orchestrator)
            updateStatus()
        } catch (exc: Throwable) {
            // Reported in the notification rather than swallowed. A service
            // that is running but dead is worse than one that says so.
            Log.e(TAG, "could not start", exc)
            status = "Not running: ${exc.message ?: exc.javaClass.simpleName}"
            notify(status)
            // Released so a later start can retry. Without this a transient
            // failure — no network yet at boot, say — would leave the service
            // permanently dead but still showing its notification.
            starting.set(false)
        }
    }

    private fun updateStatus() {
        val count = peers.size
        status = when {
            node == null -> "Starting"
            // While something is happening, say what: a progress percentage is
            // more use than the name of a device the user can already see.
            activity != "Ready" -> activity
            count == 0 -> "Waiting for your PC"
            count == 1 -> "Connected to ${peers.first().name}"
            else -> "$count devices nearby"
        }
        notify(status)
    }

    /**
     * Start watching for gestures, if the user allowed the camera.
     *
     * Refusing is a legitimate choice: everything except making a gesture on
     * THIS device still works, including receiving files.
     */
    private fun startCamera(orchestrator: GestureOrchestrator) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "no camera permission; gestures disabled on this device")
            return
        }

        val loaded = HandPoseDetector.create(this)
        if (loaded == null) {
            Log.e(TAG, "gesture model unavailable")
            return
        }
        detector = loaded

        camera = GestureCamera(
            context = this,
            detector = loaded,
            stateMachine = stateMachine,
            onEvent = { event ->
                synchronized(recentEvents) {
                    recentEvents.addFirst("${event.type} ${event.fromState} -> ${event.toState}")
                    while (recentEvents.size > 6) recentEvents.removeLast()
                }
                // Felt, not shown. At this moment the phone is face down or in
                // a pocket and the screen tells the user nothing.
                when (event.type) {
                    com.airgrab.core.GestureEventType.GRABBED -> {
                        Haptics.grabbed(this)
                        overlay.show(
                            GestureOverlay.Kind.HOLDING,
                            PendingContent.current()?.name ?: "",
                        )
                    }
                    com.airgrab.core.GestureEventType.CANCELLED -> {
                        Haptics.cancelled(this)
                        overlay.show(GestureOverlay.Kind.CANCELLED, "Let go")
                    }
                    else -> Unit
                }
                orchestrator.onLocalEvent(event)
            },
            onPose = { livePose = it },
        ).also { it.start(this) }
    }

    override fun onDestroy() {
        // The direct-link hotspot dies with the app that conjured it, or it
        // would go on broadcasting a network nothing is listening on.
        runCatching { DirectLink.stop() }
        runCatching { DirectJoin.leave() }
        runCatching { overlay.hideNow() }
        runCatching { camera?.stop() }
        runCatching { detector?.close() }
        camera = null
        detector = null
        runCatching { discovery?.stop() }
        // Blocking on purpose: onDestroy does not wait for coroutines, and a
        // node left listening would hold the port against the next start.
        runCatching { runBlocking { node?.stop() } }
        scope.cancel()
        node = null
        discovery = null
        gestures = null
        current = null
        starting.set(false)
        super.onDestroy()
    }

    // -------------------------------------------------------- notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AirGrab link",
            // LOW: this notification exists because the platform requires one,
            // not because the user needs telling. IMPORTANCE_DEFAULT would
            // make a sound every time the status text changed.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows that AirGrab is reachable from your other devices"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AirGrabService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirGrab")
            .setContentText(text)
            // A silhouette, not the launcher icon: Android draws notification
            // icons as a flat mask, so anything with colour or a filled
            // background arrives as a white blob.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            // An always-present notification the user cannot dismiss should
            // always offer a way out.
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun notify(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    // -------------------------------------------------------------- helpers

    /** What the desktop shows for this phone. Cosmetic; identity is the key. */
    private fun deviceLabel(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android phone" }
            .replaceFirstChar { it.uppercase() }

    /**
     * Where received files land: the user's choice, Downloads by default.
     *
     * Public storage on purpose. The private folder needed no permission and
     * was invisible -- modern Android will not let a file manager browse
     * Android/data, so a received photo existed somewhere the user could
     * never navigate to. Partial files stay unindexable regardless: the
     * transfer layer writes them as dot-prefixed temp names and renames only
     * after the hash verifies.
     */
    fun downloadDirectory(): File = ReceiveFolder.directory(this)
}
