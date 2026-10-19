package run.krolik.oxpulse.callreliability

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Audio output routing for calls rendered inside the WebView.
 *
 * ## Why this can work at all
 *
 * Our WebRTC audio is produced by Chromium's own stack inside the WebView, not by a
 * native WebRTC lib we own — so it is fair to ask whether a native AudioManager call
 * reaches it. It does, conditionally: Chromium's `WebRtcAudioTrack.java` renders on
 * `STREAM_VOICE_CALL` / `USAGE_VOICE_COMMUNICATION` **while `audioManager.mode ==
 * MODE_IN_COMMUNICATION`**, and falls back to `STREAM_MUSIC` otherwise. That mode is the
 * embedding app's job; Chromium never sets it. [VoiceCallForegroundService.configureAudioMode]
 * already holds it for the whole call, which is what makes the route addressable here.
 *
 * The same construction ships in `element-hq/element-x-android`'s `WebViewAudioManager.kt`,
 * which routes Element Call's WebView audio with `setCommunicationDevice` — the closest
 * production precedent to our architecture that exists.
 *
 * ## Mode ownership — deliberately NOT here
 *
 * This class never writes `audioManager.mode`. The foreground service owns it
 * (`configureAudioMode` / `restoreAudioMode`), and a second writer to that field is the
 * failure class where two components each believe they are authoritative and the last one
 * to run decides. When the mode has to be re-asserted — MIUI/HyperOS silently reverts it
 * to `MODE_NORMAL` after 5-6 s with no audio flowing (element-call#4063, reproduced on
 * Mi 9 and Poco F6) — the request goes through the owner.
 *
 * ## Bluetooth below API 31
 *
 * Excluded from the route list, following element-x, which disables it there because the
 * legacy `startBluetoothSco` path breaks in a WebView context. Offering a route that
 * cannot be honoured is the bug this whole change removes, so it is not offered.
 */
class AudioRouteManager(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The route the user last asked for; null means the OS decides. */
    private var desired: String? = null

    /** Fired when the OS reports a route, whether we asked for it or not. */
    var onRouteChanged: ((String?) -> Unit)? = null

    private var deviceListener: Any? = null

    companion object {
        private const val TAG = "AudioRouteManager"

        const val EARPIECE = "earpiece"
        const val SPEAKER = "speaker"
        const val WIRED = "wired"
        const val BLUETOOTH = "bluetooth"

        /**
         * Map an Android device type to the vocabulary shared with the web layer
         * (`web/src/lib/audio/audio-route-types.ts`). Types we do not model return null
         * rather than a plausible guess — the web side drops unknown values, so guessing
         * here would surface a route the user cannot actually get.
         */
        fun routeName(type: Int): String? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET -> WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> BLUETOOTH
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                type == AudioDeviceInfo.TYPE_BLE_HEADSET
            ) BLUETOOTH else null
        }
    }

    // ── Query ─────────────────────────────────────────────────────────────────────

    /**
     * Routes the user can actually select right now.
     *
     * The web layer treats fewer than two as "no choice" and hides the control, so a
     * device that reports one route correctly gets no button.
     */
    fun availableRoutes(): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return am.availableCommunicationDevices
                .mapNotNull { routeName(it.type) }
                .distinct()
        }
        // Legacy: no enumeration API for communication devices. The two built-ins always
        // exist on a phone; a wired headset is detectable. Bluetooth is deliberately
        // absent — see the class comment.
        val routes = mutableListOf(EARPIECE, SPEAKER)
        @Suppress("DEPRECATION")
        if (am.isWiredHeadsetOn) routes.add(WIRED)
        return routes
    }

    /**
     * What the OS reports as the active route RIGHT NOW.
     *
     * This is a read of OS state, never an echo of what was requested — the client records
     * it as `applied` and a divergence from `requested` is the alertable signal.
     *
     * On API < 31 the read-back is weaker: there is no `getCommunicationDevice()`, so it is
     * derived from `isSpeakerphoneOn` plus the wired-headset flag. Still OS state, but it
     * cannot distinguish earpiece from an unreported route, and it is why the legacy path
     * deserves the more suspicious eye in the metrics.
     */
    fun activeRoute(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return am.communicationDevice?.let { routeName(it.type) }
        }
        @Suppress("DEPRECATION")
        return when {
            am.isSpeakerphoneOn -> SPEAKER
            am.isWiredHeadsetOn -> WIRED
            else -> EARPIECE
        }
    }

    // ── Apply ─────────────────────────────────────────────────────────────────────

    /**
     * Request a route; return what the OS reports afterwards.
     *
     * The return value is read back rather than assumed: `setCommunicationDevice` returning
     * true means "request accepted", not "route active", and on some OEM builds those differ.
     */
    fun setRoute(route: String): String? {
        desired = route
        applyDesired()
        return activeRoute()
    }

    /** Drop the user override and hand routing back to the OS. */
    fun clearRoute() {
        desired = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
    }

    private fun applyDesired() {
        val target = desired ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = am.availableCommunicationDevices.firstOrNull {
                routeName(it.type) == target
            }
            if (device == null) {
                // The route vanished between the user's tap and this call (headset pulled).
                // Drop the override rather than holding a selection that cannot be honoured;
                // the OS fallback is better than pinning to something absent.
                Log.w(TAG, "route $target no longer available; clearing override")
                desired = null
                am.clearCommunicationDevice()
                return
            }
            val accepted = am.setCommunicationDevice(device)
            if (!accepted) Log.w(TAG, "setCommunicationDevice($target) refused by the OS")
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = target == SPEAKER
        }
    }

    // ── Drift ─────────────────────────────────────────────────────────────────────

    /**
     * Watch for the OS moving the route out from under us and re-assert the user's choice.
     *
     * Two things cause it: a headset connecting or disconnecting, and an OEM audio policy
     * demoting the session. Signal-Android and element-x both re-assert on this callback;
     * without it the user's selection survives until the first route event and then quietly
     * stops being true.
     *
     * API 31+ only — the legacy path has no equivalent callback, so on those devices a
     * drift is reported by the metrics but not corrected.
     */
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (deviceListener != null) return
        val l = AudioManager.OnCommunicationDeviceChangedListener { device ->
            val active = device?.let { routeName(it.type) }
            val target = desired
            if (target != null && active != null && active != target) {
                // Re-assert, then let the next callback confirm. Guarded on inequality so
                // our own write does not re-trigger this branch forever.
                Log.i(TAG, "OS moved route to $active, re-asserting $target")
                applyDesired()
            }
            onRouteChanged?.invoke(activeRoute())
        }
        am.addOnCommunicationDeviceChangedListener({ it.run() }, l)
        deviceListener = l
    }

    fun stop() {
        val l = deviceListener
        deviceListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            l is AudioManager.OnCommunicationDeviceChangedListener
        ) {
            am.removeOnCommunicationDeviceChangedListener(l)
        }
        clearRoute()
    }
}
