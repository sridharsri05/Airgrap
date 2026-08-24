package com.airgrab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.airgrab.core.DiscoveredPeer
import com.airgrab.core.FileIdentity
import com.airgrab.core.Node
import com.airgrab.core.NodeConfig
import java.io.File
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
class AirGrabService : Service() {

    companion object {
        const val TAG = "AirGrabService"
        private const val CHANNEL_ID = "airgrab-link"
        private const val NOTIFICATION_ID = 1

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

    /** Peers currently visible, for the UI to render. */
    val peers: List<DiscoveredPeer> get() = discovery?.peers().orEmpty()

    var onPeersChanged: (() -> Unit)? = null

    @Volatile
    private var status: String = "Starting"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        if (node == null) scope.launch { bringUp() }

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

            val nsd = NsdDiscovery(
                context = this,
                fingerprint = identity.fingerprint,
                displayName = deviceLabel(),
                onFound = { peer ->
                    Log.i(TAG, "found ${peer.name} at ${peer.host}:${peer.port}")
                    updateStatus()
                    onPeersChanged?.invoke()
                },
                onLost = {
                    updateStatus()
                    onPeersChanged?.invoke()
                },
            )
            nsd.startAdvertising(started.port)
            nsd.startBrowsing()
            discovery = nsd

            updateStatus()
        } catch (exc: Throwable) {
            // Reported in the notification rather than swallowed. A service
            // that is running but dead is worse than one that says so.
            Log.e(TAG, "could not start", exc)
            status = "Not running: ${exc.message ?: exc.javaClass.simpleName}"
            notify(status)
        }
    }

    private fun updateStatus() {
        val count = peers.size
        status = when {
            node == null -> "Starting"
            count == 0 -> "Waiting for your PC"
            count == 1 -> "Connected to ${peers.first().name}"
            else -> "$count devices nearby"
        }
        notify(status)
    }

    override fun onDestroy() {
        runCatching { discovery?.stop() }
        // Blocking on purpose: onDestroy does not wait for coroutines, and a
        // node left listening would hold the port against the next start.
        runCatching { runBlocking { node?.stop() } }
        scope.cancel()
        node = null
        discovery = null
        current = null
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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
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
     * Where received files land.
     *
     * App-specific external storage, which needs no permission and survives
     * uninstall cleanly. Putting files straight into the shared Downloads
     * folder would need MediaStore work and would make a hash-mismatched
     * partial file visible to the gallery, which is exactly what the transfer
     * layer goes to lengths to prevent.
     */
    private fun downloadDirectory(): File =
        File(getExternalFilesDir(null) ?: filesDir, "AirGrab").apply { mkdirs() }
}
