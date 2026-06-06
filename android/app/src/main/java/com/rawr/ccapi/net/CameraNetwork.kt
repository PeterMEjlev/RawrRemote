package com.rawr.ccapi.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Routes the app's traffic over the camera's Wi-Fi.
 *
 * When the phone joins the camera's access point, that network has **no
 * internet**, so Android will not route the app's requests over it by default —
 * it prefers a network with internet, or drops them entirely. The fix is to
 * *acquire* the Wi-Fi network via [ConnectivityManager.requestNetwork] and pin
 * our sockets to it.
 *
 * We acquire the network via [ConnectivityManager.requestNetwork] (keeping the
 * request alive for the whole session) and pin the process to it with
 * [ConnectivityManager.bindProcessToNetwork]. We deliberately do NOT use
 * per-socket binding (`Network.socketFactory` / `Network.bindSocket`): it throws
 * EPERM on some devices. NOTE: an always-on VPN or local-VPN firewall/ad-blocker
 * will block the camera entirely regardless — it must be turned off on the phone.
 */
object CameraNetwork {

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var isBound: Boolean = false
        private set

    /** The acquired camera Wi-Fi network. Pin HTTP sockets to this. */
    @Volatile
    var boundNetwork: Network? = null
        private set

    /**
     * Acquire and bind to a Wi-Fi network that need not have internet access.
     * Returns true once acquired, false if none appeared within [timeoutMs].
     * The phone must already be connected to the camera's Wi-Fi.
     */
    suspend fun bindToCameraWifi(context: Context, timeoutMs: Int = 10000): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Already acquired from a previous connect in this session.
        if (isBound && boundNetwork != null) return true

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Match the camera AP even though it lacks an internet connection.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        return suspendCancellableCoroutine { cont ->
            val cb = object : ConnectivityManager.NetworkCallback() {
                private var resumed = false
                override fun onAvailable(network: Network) {
                    if (resumed) return
                    resumed = true
                    // Pin the whole process to the acquired camera network.
                    // We use this (not per-socket network.bindSocket) because
                    // bindSocket throws EPERM on some devices, whereas
                    // bindProcessToNetwork does not. Routing works because the
                    // network was acquired via requestNetwork and the request
                    // stays alive for the session.
                    cm.bindProcessToNetwork(network)
                    boundNetwork = network
                    isBound = true
                    if (cont.isActive) cont.resume(true)
                }

                override fun onUnavailable() {
                    if (resumed) return
                    resumed = true
                    if (cont.isActive) cont.resume(false)
                }
            }
            callback = cb
            try {
                cm.requestNetwork(request, cb, timeoutMs)
            } catch (e: SecurityException) {
                // CHANGE_NETWORK_STATE missing or denied; proceed unbound.
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
        }
    }

    /** Release the binding so normal connectivity resumes. */
    fun unbind(context: Context) {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.bindProcessToNetwork(null)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        boundNetwork = null
        isBound = false
    }
}
