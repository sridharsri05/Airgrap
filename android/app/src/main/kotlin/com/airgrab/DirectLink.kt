package com.airgrab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * A network conjured out of nothing, for two phones with no router.
 *
 * Android lets an app start a *local-only hotspot*: a private Wi-Fi network
 * with no internet behind it, invented for exactly this situation. This
 * device becomes the router; the system hands back a name and password,
 * which are shown as a standard Wi-Fi QR code. The other phone scans it
 * with its ordinary camera — Android recognises the format natively and
 * offers to join — and lands on the same network with nothing typed and
 * nothing installed first.
 *
 * The credentials are random and different every time the hotspot starts,
 * which is a feature: the QR on the screen is the pairing secret, and it
 * expires with the session.
 */
object DirectLink {

    const val TAG = "AirGrabDirect"

    /** The permission this Android version gates the hotspot behind. */
    val permission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    data class Link(val ssid: String, val passphrase: String) {
        /**
         * The string every phone camera understands.
         *
         * `WIFI:T:WPA;S:<ssid>;P:<pass>;;` is the same format printed on
         * router stickers; both Android's and iOS's built-in cameras offer
         * to join it. Semicolons and backslashes in credentials are escaped
         * per the format, though Android's generated ones never contain any.
         */
        fun wifiQrPayload(): String {
            fun escape(value: String) =
                value.replace("\\", "\\\\").replace(";", "\\;")
                    .replace(",", "\\,").replace(":", "\\:")
            return "WIFI:T:WPA;S:${escape(ssid)};P:${escape(passphrase)};;"
        }
    }

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val active: Boolean get() = reservation != null

    /**
     * Start hosting. Calls [onReady] with the credentials, or [onFailed]
     * with a sentence the user can act on.
     *
     * The system refuses while ordinary tethering is on, and on some
     * firmware while Wi-Fi is fully off; the failure text points at the
     * usual cause rather than reciting an error code.
     */
    fun start(
        context: Context,
        onReady: (Link) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        if (active) {
            currentLink()?.let(onReady)
            return
        }

        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(
                        started: WifiManager.LocalOnlyHotspotReservation
                    ) {
                        reservation = started
                        val link = currentLink()
                        if (link == null) {
                            stop()
                            onFailed("The hotspot started but gave no password.")
                        } else {
                            Log.i(TAG, "hosting ${link.ssid}")
                            onReady(link)
                        }
                    }

                    override fun onFailed(reason: Int) {
                        Log.w(TAG, "local hotspot failed: $reason")
                        onFailed(
                            "Could not start a direct link. Turn OFF the " +
                                "normal hotspot and turn Wi-Fi on, then try again."
                        )
                    }

                    override fun onStopped() {
                        Log.i(TAG, "direct link stopped by the system")
                        reservation = null
                    }
                },
                null,
            )
        } catch (exc: Exception) {
            Log.e(TAG, "startLocalOnlyHotspot threw", exc)
            onFailed(
                "This phone refused to start a direct link. Use the normal " +
                    "hotspot in Settings instead."
            )
        }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }

    private fun currentLink(): Link? {
        val config = reservation?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.softApConfiguration
            else null
        } ?: return legacyLink()

        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // WifiSsid.toString() wraps the name in quotes.
            config.wifiSsid?.toString()?.trim('"')
        } else {
            @Suppress("DEPRECATION") config.ssid
        }
        val pass = config.passphrase
        if (ssid.isNullOrBlank() || pass.isNullOrBlank()) return legacyLink()
        return Link(ssid, pass)
    }

    @Suppress("DEPRECATION")
    private fun legacyLink(): Link? {
        val config = reservation?.wifiConfiguration ?: return null
        val ssid = config.SSID ?: return null
        val pass = config.preSharedKey ?: return null
        return Link(ssid.trim('"'), pass.trim('"'))
    }

    /** The QR itself, black on white, big enough to scan across a desk. */
    fun qrBitmap(link: Link, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            link.wifiQrPayload(), BarcodeFormat.QR_CODE, sizePx, sizePx
        )
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(
                    x, y,
                    if (matrix.get(x, y)) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE,
                )
            }
        }
        return bitmap
    }
}
