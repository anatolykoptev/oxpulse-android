package run.krolik.oxpulse.callreliability

import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "NetworkCallbackMgr"

typealias NetworkCallbackManagerFactory = (
    networkCallback: NetworkCallback,
) -> NetworkCallbackManager

interface NetworkCallbackRegistry {
    fun registerNetworkCallback(networkRequest: NetworkRequest, networkCallback: NetworkCallback)
    fun unregisterNetworkCallback(networkCallback: NetworkCallback)
}

internal class NetworkCallbackRegistryImpl(val connectivityManager: ConnectivityManager) : NetworkCallbackRegistry {
    override fun registerNetworkCallback(networkRequest: NetworkRequest, networkCallback: NetworkCallback) {
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }
    override fun unregisterNetworkCallback(networkCallback: NetworkCallback) {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}

interface NetworkCallbackManager : Closeable {
    fun register()
    fun unregister()
}

/**
 * Manages a [ConnectivityManager.NetworkCallback] so that it is never
 * registered multiple times. A NetworkCallback is allowed to be registered
 * multiple times by the ConnectivityService, but the underlying network
 * requests will leak on 8.0 and earlier.
 *
 * There's a 100 request hard limit, so leaks here are particularly dangerous.
 *
 * Ported from livekit/client-sdk-android@46da6784:
 * livekit-android-sdk/src/main/java/io/livekit/android/room/network/NetworkCallbackManager.kt
 * (Apache-2.0). Adapted: package name, Log calls, register/unregister rename,
 * NetworkRequest uses NET_CAPABILITY_INTERNET only (no transport filter) per Phase 3 §2.1.
 *
 * Phase 4 Task 4.4: extended with a SECOND callback on NET_CAPABILITY_VALIDATED
 * to detect captive-portal post-signin pass-through. Pattern from Signal-Android
 * NetworkConnectionListener (2-callback + de-spam via string-compare).
 */
class NetworkCallbackManagerImpl(
    private val networkCallback: NetworkCallback,
    private val connectivityManager: NetworkCallbackRegistry,
    // Phase 4: optional second callback; null disables captive-portal detection.
    private val onValidatedChanged: ((network: Network, validated: Boolean) -> Unit)? = null,
) : NetworkCallbackManager {
    private val isRegistered = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    // Phase 4 M6: tracks whether validatedNetworkCallback was actually registered.
    // Separate from isRegistered so unregister() skips the 2nd callback when
    // 2nd registration threw (e.g. Android 100-callback budget exceeded).
    private val isValidatedRegistered = AtomicBoolean(false)

    // Phase 4: de-spam guard per Signal-Android pattern. The bandwidth-estimator
    // fires onCapabilitiesChanged approximately every 30s even with no real change.
    // String-compare the full capabilities repr; ignore re-fires of identical state.
    // Risk per plan §7: Android version may add fields to toString() — migrate to
    // explicit field extraction if false-positives are observed in production.
    @Volatile private var lastValidatedCapsString: String = ""

    private val validatedNetworkCallback = object : NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // De-spam: ignore identical re-fires (bandwidth-estimator updates ~30s).
            val capsString = caps.toString()
            if (capsString == lastValidatedCapsString) return
            lastValidatedCapsString = capsString

            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            onValidatedChanged?.invoke(network, validated)
        }
    }

    private val validatedRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        .build()

    @Synchronized
    override fun register() {
        if (isClosed.get()) return
        if (!isRegistered.compareAndSet(false, true)) return

        // NET_CAPABILITY_INTERNET only — NO addTransportType filter.
        // VPN flap must fire callback (calling-app design, per Phase 3 §2.1).
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: SecurityException) {
            isRegistered.set(false)
            Log.w(TAG, "SecurityException registering primary network callback, reconnection may be impaired.", e)
            throw e
        }

        // Phase 4: register VALIDATED callback only when caller provided a handler.
        // M6 invariant: "both register or none" — if 2nd throws, revert 1st + reset.
        if (onValidatedChanged != null) {
            try {
                connectivityManager.registerNetworkCallback(validatedRequest, validatedNetworkCallback)
                isValidatedRegistered.set(true)
            } catch (e: Exception) {
                // Revert primary callback to maintain "both register or none" invariant.
                // Without this, isRegistered=true but only 1 callback is live; subsequent
                // register() calls early-return and the 2nd callback is never retried.
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                } catch (inner: IllegalArgumentException) {
                    Log.w(TAG, "Unexpected IAE reverting primary callback after 2nd registration failure.", inner)
                }
                isRegistered.set(false)
                Log.w(TAG, "Exception registering validated network callback; reverted primary. Reconnection may be impaired.", e)
                throw e
            }
        }
    }

    @Synchronized
    override fun unregister() {
        if (!isRegistered.compareAndSet(true, false)) return

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // do nothing, may happen on older versions if attempting to unregister twice.
            Log.w(TAG, "NetworkCallback was unregistered multiple times?")
        }

        // Phase 4 M6: only unregister 2nd callback if it was actually registered.
        // isValidatedRegistered guards against IAE when 2nd registration previously threw.
        if (isValidatedRegistered.compareAndSet(true, false)) {
            try {
                connectivityManager.unregisterNetworkCallback(validatedNetworkCallback)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Validated NetworkCallback was unregistered multiple times?")
            }
        }
    }

    @Synchronized
    override fun close() {
        if (isClosed.get()) return
        if (isRegistered.get()) unregister()
        isClosed.set(true)
    }
}
