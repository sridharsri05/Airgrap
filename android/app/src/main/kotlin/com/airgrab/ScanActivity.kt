package com.airgrab

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.airgrab.ui.Style
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Point this phone at the other one's QR, and join its network.
 *
 * The system camera can do the scan too, but not every manufacturer's camera
 * offers to join a Wi-Fi QR, and the user should not have to know whose does.
 * This screen scans AND connects: the join uses Android's network-specifier
 * request, which asks the system for exactly that one network, app-scoped,
 * no saved-networks entry left behind.
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val TAG = "AirGrabScan"
    }

    private lateinit var status: TextView
    private val handled = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preview = PreviewView(this)
        status = TextView(this).apply {
            text = "Point at the QR on the other phone"
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#CC1B1C1E"))
            gravity = android.view.Gravity.CENTER
            val pad = Style.dp(this@ScanActivity, 16)
            setPadding(pad, pad, pad, pad)
        }

        setContentView(FrameLayout(this).apply {
            addView(preview)
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM,
            ))
        })

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewUse = androidx.camera.core.Preview.Builder().build().also {
                it.surfaceProvider = preview.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(executor) { frame -> decode(frame) } }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis
                )
            }.onFailure { finishWith("Could not open the camera.") }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    /** One analysis frame: the Y plane is exactly the luminance ZXing wants. */
    private fun decode(frame: ImageProxy) {
        frame.use {
            if (handled.get()) return
            val plane = frame.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

            val source = PlanarYUVLuminanceSource(
                bytes, plane.rowStride, frame.height,
                0, 0, frame.width, frame.height, false,
            )
            val text = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            }.getOrNull() ?: run { reader.reset(); return }
            reader.reset()

            val link = parseWifiQr(text)
            runOnUiThread {
                if (link == null) {
                    status.text = "That QR is not a Wi-Fi code"
                } else if (handled.compareAndSet(false, true)) {
                    join(link)
                }
            }
        }
    }

    private fun join(link: DirectLink.Link) {
        status.text = "Joining ${link.ssid}…"

        // A phone that is scanning wants to be a guest. If it is hosting its
        // own direct link, the radio is busy being a router and the join
        // request goes unanswered -- observed live when both phones had
        // tapped "show a QR". Scanning wins; hosting stops.
        DirectLink.stop()

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(link.ssid)
            .setWpa2Passphrase(link.passphrase)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // A direct link has no internet behind it, and saying so is what
            // stops the system from abandoning it for one that does.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                // Route this app's sockets over the direct link; other apps
                // are untouched. Undone when the link is left.
                connectivity.bindProcessToNetwork(network)
                DirectJoin.remember(connectivity, this)
                DirectJoin.hostAddress = hostAddressOf(connectivity, network)
                runOnUiThread { finishWith("Connected to ${link.ssid}") }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    handled.set(false)
                    status.text = "No answer from ${link.ssid}. Keep the QR " +
                        "open on the other phone and scan again."
                }
            }
        }
        DirectJoin.replace(connectivity, callback)
        connectivity.requestNetwork(request, callback, 30_000)
    }

    /** The host's address: the DHCP server, or the default route, or .1. */
    private fun hostAddressOf(
        connectivity: ConnectivityManager,
        network: android.net.Network,
    ): String? {
        val properties = connectivity.getLinkProperties(network) ?: return null

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            properties.dhcpServerAddress?.hostAddress?.let { return it }
        }
        properties.routes.firstOrNull { it.isDefaultRoute }
            ?.gateway?.hostAddress?.takeIf { it != "0.0.0.0" }?.let { return it }

        // A hotspot host puts itself at .1 of the subnet it hands out.
        val own = properties.linkAddresses
            .firstOrNull { it.address is java.net.Inet4Address }
            ?.address?.hostAddress ?: return null
        return own.substringBeforeLast('.') + ".1"
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    /** `WIFI:T:WPA;S:name;P:pass;;`, with the format's escaping undone. */
    private fun parseWifiQr(text: String): DirectLink.Link? {
        if (!text.startsWith("WIFI:", ignoreCase = true)) return null

        fun field(key: String): String? {
            val match = Regex("$key:((?:\\\\.|[^;])*)").find(text) ?: return null
            return match.groupValues[1]
                .replace("\\;", ";").replace("\\,", ",")
                .replace("\\:", ":").replace("\\\\", "\\")
        }

        val ssid = field("S") ?: return null
        val pass = field("P") ?: return null
        if (ssid.isBlank() || pass.isBlank()) return null
        return DirectLink.Link(ssid, pass)
    }
}

/**
 * The joined network's lifeline. Releasing the callback disconnects the
 * link, so it is held here until the app stops or a new join replaces it.
 */
object DirectJoin {
    private var connectivity: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * The host phone's address on the direct link.
     *
     * mDNS does not reliably cross a local-only hotspot -- both phones were
     * connected and neither could see the other. But a guest never needed
     * discovery: the host IS the network's gateway. Remembering it lets the
     * Devices card offer the host directly.
     */
    @Volatile
    var hostAddress: String? = null

    fun replace(manager: ConnectivityManager, next: ConnectivityManager.NetworkCallback) {
        leave()
        connectivity = manager
        callback = next
    }

    fun remember(manager: ConnectivityManager, active: ConnectivityManager.NetworkCallback) {
        connectivity = manager
        callback = active
    }

    fun leave() {
        runCatching {
            connectivity?.bindProcessToNetwork(null)
            callback?.let { connectivity?.unregisterNetworkCallback(it) }
        }
        callback = null
        hostAddress = null
    }
}
