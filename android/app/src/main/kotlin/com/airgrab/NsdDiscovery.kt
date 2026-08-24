package com.airgrab

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.airgrab.core.DiscoveredPeer
import com.airgrab.core.Discovery
import com.airgrab.core.PeerRegistry
import com.airgrab.core.SERVICE_TYPE_SHORT
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Peer discovery on Android, over `NsdManager`.
 *
 * The desktop half of this uses python-zeroconf. What both must agree on —
 * the service type, the TXT record, and what counts as a usable peer — lives
 * in `core/Discovery.kt` and is tested without a network. This file is only
 * the Android plumbing, and the plumbing is where the traps are.
 *
 * ## The multicast lock
 *
 * Android drops multicast packets before they reach an app unless a
 * [WifiManager.MulticastLock] is held. Without it, registration succeeds,
 * discovery starts, no error is reported anywhere, and no peer is ever found.
 * It is the single most likely reason for a discovery implementation that
 * looks correct and finds nothing.
 *
 * It also costs battery, which is why the lock is released the moment
 * discovery stops rather than held for the process lifetime.
 *
 * ## Resolution
 *
 * `resolveService` is deprecated from API 34 and, worse, has never tolerated
 * concurrent calls: a second resolve while one is in flight fails with
 * `FAILURE_ALREADY_ACTIVE`. With several devices on a network that means peers
 * that intermittently fail to appear. API 34 and later use
 * `registerServiceInfoCallback`, which has neither problem; older releases go
 * through a serialised queue.
 */
class NsdDiscovery(
    context: Context,
    private val fingerprint: String,
    private val displayName: String,
    private val onFound: (DiscoveredPeer) -> Unit,
    private val onLost: (fingerprint: String) -> Unit,
) {
    companion object {
        const val TAG = "AirGrabNsd"
        private const val PLATFORM = "android"
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val executor = Executors.newSingleThreadExecutor()
    private val registry = PeerRegistry(onFound = { onFound(it) }, onLost = { onLost(it) })

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Active per-service resolution callbacks on API 34+, so they can be freed. */
    private val serviceCallbacks = ConcurrentHashMap<String, Any>()

    // ----------------------------------------------------------- advertising

    fun startAdvertising(port: Int) {
        if (registrationListener != null) return

        val info = NsdServiceInfo().apply {
            serviceName = Discovery.instanceName(fingerprint)
            serviceType = SERVICE_TYPE_SHORT
            setPort(port)
            Discovery.properties(fingerprint, displayName, PLATFORM)
                .forEach { (key, value) -> setAttribute(key, value) }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Android may rename on collision; the name is only a label,
                // and the real identity travels in the TXT record.
                Log.i(TAG, "advertising as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "advertising failed: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "stopped advertising")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregistration failed: $errorCode")
            }
        }

        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopAdvertising() {
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        registrationListener = null
    }

    // ------------------------------------------------------------- browsing

    fun startBrowsing() {
        if (discoveryListener != null) return
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "browsing for $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                // Skip our own advertisement before spending a resolve on it.
                if (info.serviceName == Discovery.instanceName(fingerprint)) return
                resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Log.i(TAG, "lost ${info.serviceName} (browse)")
                stopResolving(info.serviceName)
                registry.lost(info.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "stopped browsing")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "browsing failed to start: $errorCode")
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "browsing failed to stop: $errorCode")
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE_SHORT, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopBrowsing() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        serviceCallbacks.keys.toList().forEach { stopResolving(it) }
        registry.clear()
        releaseMulticastLock()
    }

    fun stop() {
        stopBrowsing()
        stopAdvertising()
        executor.shutdown()
    }

    fun peers(): List<DiscoveredPeer> = registry.peers()

    // ------------------------------------------------------------ resolution

    private fun resolve(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolveModern(info)
        } else {
            resolveLegacy(info)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(info: NsdServiceInfo) {
        // Serialised onto one thread: concurrent resolves fail with
        // FAILURE_ALREADY_ACTIVE, which shows up as peers that intermittently
        // refuse to appear once more than one device is on the network.
        executor.execute {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    accept(info)
                }
            })
        }
    }

    private fun resolveModern(info: NsdServiceInfo) {
        val name = info.serviceName
        if (serviceCallbacks.containsKey(name)) return

        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "resolve registration failed for $name: $errorCode")
                serviceCallbacks.remove(name)
            }

            override fun onServiceUpdated(info: NsdServiceInfo) {
                accept(info)
            }

            override fun onServiceLost() {
                Log.i(TAG, "lost $name (resolve)")
                registry.lost(name)
            }

            override fun onServiceInfoCallbackUnregistered() {
                serviceCallbacks.remove(name)
            }
        }

        serviceCallbacks[name] = callback
        runCatching {
            nsdManager.registerServiceInfoCallback(info, executor, callback)
        }.onFailure {
            Log.w(TAG, "could not watch $name: ${it.message}")
            serviceCallbacks.remove(name)
        }
    }

    private fun stopResolving(serviceName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callback = serviceCallbacks.remove(serviceName) ?: return
        runCatching {
            nsdManager.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback)
        }
    }

    /** Turn a resolved advertisement into a peer, using the shared rules. */
    private fun accept(info: NsdServiceInfo) {
        val properties = info.attributes.orEmpty().mapValues { (_, value) ->
            // TXT values are raw bytes and may be absent or non-UTF-8. A
            // malformed record from some other application on the same service
            // type must not crash discovery.
            value?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }
        }

        val peer = Discovery.peerFrom(
            properties = properties,
            host = addressOf(info),
            port = info.port,
            ignoreFingerprint = fingerprint,
        ) ?: return

        registry.found(info.serviceName, peer)
    }

    /**
     * The peer's IPv4 address.
     *
     * IPv4 specifically: link-local IPv6 addresses carry a scope identifier
     * that has to match the receiving interface, and a URL built from one
     * fails to connect in a way that looks like the peer is unreachable.
     */
    @Suppress("DEPRECATION")
    private fun addressOf(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            listOfNotNull(info.host)
        }
        return addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
    }

    // -------------------------------------------------------- multicast lock

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        multicastLock = wifiManager.createMulticastLock("airgrab-discovery").apply {
            setReferenceCounted(false)
            runCatching { acquire() }.onFailure {
                Log.e(TAG, "could not acquire the multicast lock: ${it.message}")
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            // Held only while browsing. A permanently held lock keeps the
            // Wi-Fi chip listening to all multicast traffic and costs real
            // battery.
            runCatching { if (lock.isHeld) lock.release() }
        }
        multicastLock = null
    }
}
