package run.krolik.oxpulse.callreliability

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.concurrent.Executors

@CapacitorPlugin(name = "CallReliability")
class CallReliabilityPlugin : Plugin() {

    // Lazily instantiated in startNetworkMonitor(); nulled in stopNetworkMonitor().
    // NOT wired in load() — registration costs resources; lifecycle matches call lifetime.
    private var networkManager: NetworkCallbackManagerImpl? = null

    // Phase 4 Task 4.1: MicWatch — installed on startCall(), removed on endCall().
    // Single-threaded executor keeps AppOps callback off the main thread.
    private var micWatch: MicWatch? = null
    private val micWatchExecutor = Executors.newSingleThreadExecutor()

    override fun load() {
        super.load()
        // Wire telemetry callbacks ONCE at plugin load time, not per-startCall().
        // Reassigning per-startCall risks stale-closure cross-room mixing: if a
        // previous call's onLifetimeEnded fires after the next startCall() wires
        // new closures, the wrong closure fires with the previous room's context.
        VoiceCallForegroundServiceCallbacks.onLifetimeEnded = { lifetimeSec, result ->
            val data = JSObject().apply {
                put("lifetimeSec", lifetimeSec)
                put("result", result)
            }
            notifyListeners("fgsLifetimeEnded", data)
        }
        VoiceCallForegroundServiceCallbacks.onTimeoutFired = { fgsType ->
            val data = JSObject().apply { put("fgsType", fgsType) }
            notifyListeners("fgsTimeout", data)
        }
    }

    /**
     * Called by Capacitor when the bridge activity is destroyed — the guaranteed
     * cleanup path for resources that would otherwise leak. Without this:
     *   - networkManager leaks two ConnectivityManager.NetworkCallbacks toward
     *     the 100-callback-per-UID hard limit (issue #8);
     *   - micWatch leaks an AppOpsManager.startWatchingActive listener that can
     *     transitively leak the Context (issue #23);
     *   - micWatchExecutor leaks a non-daemon thread (issue #10).
     */
    override fun handleOnDestroy() {
        super.handleOnDestroy()
        networkManager?.close()
        networkManager = null
        micWatch?.uninstall()
        micWatch = null
        micWatchExecutor.shutdownNow()
    }

    @PluginMethod
    fun ping(call: PluginCall) {
        val result = JSObject().apply {
            put("ok", true)
            put("phase", 1)
        }
        call.resolve(result)
    }

    @PluginMethod
    fun startCall(call: PluginCall) {
        try {
            // Video flag tells the FGS whether to include the camera type.
            // Audio-only calls (or camera-denial fallback) should not request it.
            val videoEnabled = call.getBoolean("video", true) ?: true
            VoiceCallForegroundService.start(context, videoEnabled)
            // Phase 4 Task 4.1: install mic-revoke watcher after FGS is started.
            // onRevoked fires on the micWatchExecutor thread (not main).
            val vendor = OemAutostart.currentVendor() ?: "other"
            micWatch = MicWatch(context, micWatchExecutor) {
                // D3 Task 2: add signal_source so the Rust counter breaks down
                // {oem, signal_source} at 8×5=40 cardinality. Without this field,
                // the handler defaults to "" → "unknown" bucket — Android AppOps
                // revocations would be invisible in their own signal_source bucket.
                notifyListeners(
                    "micRevoked",
                    JSObject().apply {
                        put("oem", vendor)
                        put("signal_source", "native_appops")
                    },
                )
            }
            micWatch?.install()
            val result = JSObject().apply {
                put("started", true)
                put("reason", "ok")
            }
            call.resolve(result)
        } catch (e: SecurityException) {
            val reason = when {
                e.message?.contains("MANAGE_OWN_CALLS") == true -> "missing_manage_own_calls"
                e.message?.contains("while in use") == true -> "missing_runtime_perm"
                else -> "not_allowed_background"
            }
            val result = JSObject().apply {
                put("started", false)
                put("reason", reason)
            }
            call.resolve(result)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException is a subclass
            val result = JSObject().apply {
                put("started", false)
                put("reason", "not_allowed_background")
            }
            call.resolve(result)
        } catch (e: Exception) {
            val result = JSObject().apply {
                put("started", false)
                put("reason", "oem_blocked")
            }
            call.resolve(result)
        }
    }

    @PluginMethod
    fun updateVideo(call: PluginCall) {
        try {
            val videoEnabled = call.getBoolean("enabled", true) ?: true
            VoiceCallForegroundService.updateVideo(context, videoEnabled)
            call.resolve()
        } catch (e: Exception) {
            call.reject("update_video_failed", e)
        }
    }

    @PluginMethod
    fun endCall(call: PluginCall) {
        // Phase 4 Task 4.1: remove mic-revoke watcher before FGS stops.
        micWatch?.uninstall()
        micWatch = null
        try {
            VoiceCallForegroundService.stop(context)
        } catch (_: Exception) {
            // best-effort — Service may have already stopped
        }
        call.resolve()
    }

    // ── Phase 4 Task 4.2 — Battery-optimization exemption ────────────────────

    /**
     * Fires the system battery-optimization exemption dialog for this app.
     *
     * Uses startActivityForResult + @ActivityCallback so the PluginCall resolves
     * AFTER the user dismisses the system dialog, not before. The old pattern
     * (startActivity + immediate call.resolve()) resolved while the dialog was
     * still open — polling isIgnoringBatteryOptimizations() at that point always
     * returned the pre-dialog state (granted outcome wrong; R1 BLOCKER B-G + M-Poll).
     *
     * Precedent: SharePackagePlugin.kt:share() → shareResult() callback.
     *
     * Resolves { granted: 'ok' | 'denied' } — outcome polled in callback after
     * user dismisses. Rejects on startActivity failure.
     */
    @PluginMethod
    fun requestBatteryOptimizationExemption(call: PluginCall) {
        try {
            val intent = BatteryOptimization.buildIntent(context)
            startActivityForResult(call, intent, "onBatteryExemptionResult")
        } catch (e: Exception) {
            call.reject("battery_exemption_request_failed", e)
        }
    }

    /**
     * Called by Capacitor after the battery-optimization dialog Activity returns.
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS does not deliver a meaningful
     * resultCode — poll PowerManager.isIgnoringBatteryOptimizations() instead.
     * This is the correct moment: the user has already dismissed the dialog.
     */
    @ActivityCallback
    fun onBatteryExemptionResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val ctx = context ?: run {
            call.resolve(JSObject().put("granted", "not_applicable"))
            return
        }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: run {
            call.resolve(JSObject().put("granted", "not_applicable"))
            return
        }
        val granted = if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) "ok" else "denied"
        call.resolve(JSObject().put("granted", granted))
    }

    /**
     * Returns { ignoring: Boolean } — whether this app is exempt from battery optimization.
     * API 23+ (minSdk). Still available for direct status checks (e.g., pre-flight guard).
     */
    @PluginMethod
    fun isIgnoringBatteryOptimizations(call: PluginCall) {
        val result = JSObject().apply {
            put("ignoring", BatteryOptimization.isIgnoringBatteryOptimizations(context))
        }
        call.resolve(result)
    }

    // ── Phase 4 Task 4.3 — OEM autostart settings deep-link ─────────────────

    /**
     * Looks up the OEM vendor and launches the autostart settings activity.
     *
     * Per plan §2.3: every Intent is guarded with pm.resolveActivity() — unguarded
     * vendor Intents crash with ActivityNotFoundException on ROM rename.
     *
     * Resolves { opened: Boolean, vendor: String?, reason: String }.
     * reason ∈ {"ok", "no_activity", "not_applicable"}.
     */
    @PluginMethod
    fun openOemAutostartSettings(call: PluginCall) {
        val vendor = call.getString("vendor") ?: OemAutostart.currentVendor()
        if (vendor == null) {
            call.resolve(JSObject().apply {
                put("opened", false)
                put("reason", "not_applicable")
            })
            return
        }
        val opened = OemAutostart.openSettings(activity, vendor)
        call.resolve(JSObject().apply {
            put("opened", opened)
            put("vendor", vendor)
            put("reason", if (opened) "ok" else "no_activity")
        })
    }

    /**
     * Returns { vendor: String } from the closed OEM enum.
     * Returns "other" when Build.MANUFACTURER is unrecognized (stock Android / emulator).
     */
    @PluginMethod
    fun getVendor(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("vendor", OemAutostart.currentVendor() ?: "other")
        })
    }

    /**
     * Registers TWO ConnectivityManager.NetworkCallbacks that fire JS events on
     * every network transition. Idempotent — backed by AtomicBoolean dedup in
     * NetworkCallbackManagerImpl (guards against the 100-callback Android hard limit).
     *
     * Phase 3 (INTERNET callback) emits "networkChange" events:
     *   { type: "connect", connected: true,  connectionType: "wifi"|"cellular"|"unknown" }
     *   { type: "connect", connected: false, connectionType: "none" }
     *
     * Phase 4 Task 4.4 (VALIDATED callback) emits:
     *   { type: "validated", connected: Boolean, connectionType: String }
     *   De-spammed via string-compare (Signal pattern) — ignores ~30s bandwidth-estimator
     *   re-fires that carry identical NetworkCapabilities state.
     */
    @PluginMethod
    fun startNetworkMonitor(call: PluginCall) {
        if (networkManager != null) {
            // Idempotent — already monitoring. Resolve OK without re-registering.
            call.resolve()
            return
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val registry = NetworkCallbackRegistryImpl(cm)
        networkManager = NetworkCallbackManagerImpl(
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    notifyListeners("networkChange", JSObject().apply {
                        put("type", "connect")
                        put("connected", true)
                        put("connectionType", inferTransport(cm, network))
                    })
                }

                override fun onLost(network: Network) {
                    notifyListeners("networkChange", JSObject().apply {
                        put("type", "connect")
                        put("connected", false)
                        put("connectionType", "none")
                    })
                }
            },
            connectivityManager = registry,
            // Phase 4 Task 4.4: captive-portal detection via VALIDATED callback.
            onValidatedChanged = { network, validated ->
                notifyListeners("networkChange", JSObject().apply {
                    put("type", "validated")
                    put("connected", validated)
                    put("connectionType", inferTransport(cm, network))
                })
            },
        )
        networkManager?.register()
        call.resolve()
    }

    /**
     * Unregisters the network callback and releases the manager reference.
     * Safe to call even if startNetworkMonitor was not called.
     */
    @PluginMethod
    fun stopNetworkMonitor(call: PluginCall) {
        networkManager?.close()
        networkManager = null
        call.resolve()
    }

    /**
     * Opens the system app-permission settings page for this application.
     *
     * Allows the user to re-grant RECORD_AUDIO from within the app when the
     * permission was revoked mid-call (MicRevokedModal CTA path).
     *
     * Pattern lifted from MeshGattServerPlugin.kt:365 openAppPermissionSettings().
     * Uses ACTION_APPLICATION_DETAILS_SETTINGS + package URI so the user lands
     * directly on this app's permissions screen rather than the global settings root.
     *
     * Phase 4 Task 4.9 (Fix 2a, code-quality round 1).
     */
    @PluginMethod
    fun openMicSettings(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("open_mic_settings_failed", e)
        }
    }

    /**
     * Infers the transport type of the given network from its capabilities.
     * Returns "wifi" | "cellular" | "unknown". Does NOT return "none" —
     * "none" is reserved for onLost (no network available).
     */
    private fun inferTransport(cm: ConnectivityManager, network: Network): String {
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "unknown"
        }
    }
}
