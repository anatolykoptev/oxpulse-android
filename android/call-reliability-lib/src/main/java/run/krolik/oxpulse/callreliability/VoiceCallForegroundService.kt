package run.krolik.oxpulse.callreliability

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

class VoiceCallForegroundService : Service() {

    private var audioManager: AudioManager? = null
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var audioModeApplied: Boolean = false
    private var startedAtMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService()
        powerManager = getSystemService()
        if (audioManager == null) {
            Log.w(TAG, "AudioManager is null — audio mode configuration will be skipped (OEM ROM issue?)")
        }
        if (powerManager == null) {
            Log.w(TAG, "PowerManager is null — wakeLock will be skipped (OEM ROM issue?)")
        }
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 12+ (API 31+) requires startForeground() within 5s of startForegroundService()
        // or raises ForegroundServiceDidNotStartInTimeException. ALWAYS call promoteToForeground()
        // first, even on ACTION_HANGUP, to satisfy this window on all API levels.
        // EXTRA_VIDEO is the source of truth for whether the camera FGS type should be
        // requested. Defaults to true so the existing startCall path keeps working.
        val videoEnabled = intent?.getBooleanExtra(EXTRA_VIDEO, true) ?: true
        try {
            promoteToForeground(videoEnabled)
        } catch (e: Exception) {
            Log.e(TAG, "FGS startup failed at promoteToForeground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_HANGUP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Update-video is a foreground-service-type refresh. Validate that the
        // service is in an active call state before updating — if the service was
        // killed and restarted, resources (audio/wake/focus) may not be held.
        // Without this, updateVideo() could promote to foreground without the
        // core call resources, leaving the call unprotected (issues #15, #20).
        if (intent?.action == ACTION_UPDATE_VIDEO) {
            if (!audioModeApplied || wakeLock?.isHeld != true) {
                Log.w(TAG, "ACTION_UPDATE_VIDEO received but call resources not held (audioModeApplied=$audioModeApplied, wakeLockHeld=${wakeLock?.isHeld}) — re-acquiring")
                try {
                    configureAudioMode()
                    requestAudioFocus()
                    acquireWakeLock()
                } catch (e: Exception) {
                    Log.e(TAG, "Resource re-acquisition failed during ACTION_UPDATE_VIDEO", e)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            return START_NOT_STICKY
        }

        startedAtMillis = System.currentTimeMillis()
        try {
            configureAudioMode()
            requestAudioFocus()
            acquireWakeLock()
        } catch (e: Exception) {
            Log.e(TAG, "FGS startup failed", e)
            // Roll back whatever was acquired before stopSelf — without this,
            // audioModeApplied could be true while wakeLock/audioFocusRequest are
            // null, leaving the invariant audioModeApplied↔wakeLock↔focus broken.
            // onDestroy would eventually clean up, but the state is inconsistent
            // between the failure and onDestroy, and a concurrent updateVideo
            // could observe it.
            try { abandonAudioFocus() } catch (_: Exception) {}
            try { restoreAudioMode() } catch (_: Exception) {}
            try { releaseWakeLock() } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }
        // START_REDELIVER_INTENT: if Android kills the service mid-call (low memory),
        // restart it with the original intent so audio/wake/focus are re-acquired.
        // Without this, a kill leaves the JS layer thinking the call is active but
        // with no native protection — phone sleeps, call drops (issue #9).
        // ACTION_HANGUP and ACTION_UPDATE_VIDEO still return START_NOT_STICKY above.
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        val lifetimeSec = (System.currentTimeMillis() - startedAtMillis) / 1000L
        // Telemetry: notify plugin via static callback (Phase 2 wires this)
        VoiceCallForegroundServiceCallbacks.onLifetimeEnded(lifetimeSec, RESULT_NORMAL_END)
        try { abandonAudioFocus() } catch (_: Exception) {}
        try { restoreAudioMode() } catch (_: Exception) {}
        try { releaseWakeLock() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15 (API 35) FGS time-limit callback. NOT expected to fire on
    // phoneCall type (no 6h limit), but defensive: tear down cleanly + emit
    // telemetry so we catch the contract violation if Android docs change.
    @Suppress("UNUSED_PARAMETER")
    fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS onTimeout fired — fgsType=$fgsType")
        VoiceCallForegroundServiceCallbacks.onTimeoutFired(fgsType)
        stopSelf()
    }

    private fun promoteToForeground(videoEnabled: Boolean = true) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 (API 34) requires foreground service types to match the
            // work the service performs. The camera type is gated by the CAMERA
            // runtime permission AND the current video intent — only include it
            // when both the user has granted camera and the call is using video.
            // Signal/OpenTok do the same: the service is declared with all relevant
            // types in the manifest, but the runtime bitmask only includes camera
            // when the permission is actually held and video is active.
            // This is also called for ACTION_HANGUP to satisfy the 5s startForeground
            // window; the permission check here is harmless and stopSelf() runs right after.
            var fgsType: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (videoEnabled &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIF_ID, notification, fgsType)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val mainActivity = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = PendingIntent.getActivity(
            this, 0, mainActivity,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangupIntent = Intent(this, VoiceCallForegroundService::class.java)
            .setAction(ACTION_HANGUP)
        val hangupPi = PendingIntent.getService(
            this, 1, hangupIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val callerName = "Call"  // i18n-aware override via plugin-passed extra in real impl
        val caller = Person.Builder().setName(callerName).setKey("ox-active-call").build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call_ongoing)
            .setContentTitle(callerName)
            .setContentText("Tap to return to the call")
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setColorized(true)
            .setOngoing(true)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, hangupPi))
            // pre-S OEM defensive — some ROMs strip CallStyle action
            .addAction(R.drawable.ic_call_ongoing, "Hang up", hangupPi)
            .build()
    }

    private fun createChannelIfNeeded() {
        val nm = getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active calls",
            NotificationManager.IMPORTANCE_LOW,  // silent; CallStyle promotes regardless
        ).apply {
            description = "Keeps your call running when the app is in the background"
            setSound(null, null)
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    @Synchronized
    private fun configureAudioMode() {
        val am = audioManager ?: return
        if (audioModeApplied) return
        previousAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        audioModeApplied = true
    }

    @Synchronized
    private fun restoreAudioMode() {
        if (!audioModeApplied) return
        audioManager?.mode = previousAudioMode
        audioModeApplied = false
    }

    @Synchronized
    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener { focus ->
            Log.d(TAG, "audio focus change: $focus")
            // Don't auto-mute mic here — WebRTC ADM owns that
        }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(listener)
            .build()
        am.requestAudioFocus(req)
        audioFocusRequest = req
    }

    @Synchronized
    private fun abandonAudioFocus() {
        val req = audioFocusRequest ?: return
        audioManager?.abandonAudioFocusRequest(req)
        audioFocusRequest = null
    }

    @Synchronized
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = powerManager ?: return
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        wl.setReferenceCounted(false)
        wl.acquire(WAKE_LOCK_SAFETY_MILLIS)  // 1h safety cap
        wakeLock = wl
    }

    @Synchronized
    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val TAG = "VoiceCallFgs"
        const val CHANNEL_ID = "ox_call_active"
        const val NOTIF_ID = 0xCA11
        const val ACTION_HANGUP = "run.krolik.oxpulse.callreliability.HANGUP"
        const val ACTION_UPDATE_VIDEO = "run.krolik.oxpulse.callreliability.UPDATE_VIDEO"
        const val EXTRA_VIDEO = "run.krolik.oxpulse.callreliability.EXTRA_VIDEO"
        const val WAKE_LOCK_TAG = "oxpulse:voice-call"
        const val WAKE_LOCK_SAFETY_MILLIS = 60L * 60 * 1000  // 1h cap

        const val RESULT_NORMAL_END = "normal_end"
        const val RESULT_KILLED = "killed"
        const val RESULT_TIMEOUT = "timeout"

        fun start(context: Context, videoEnabled: Boolean = true) {
            val intent = Intent(context, VoiceCallForegroundService::class.java).apply {
                putExtra(EXTRA_VIDEO, videoEnabled)
            }
            // startForegroundService requires API 26 (Android 8.0); minSdkVersion=22.
            // On API 22-25 fall back to startService (FGS promotion is unavailable but
            // the audio/wakelock path still runs; backgrounding limits apply per-OEM).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateVideo(context: Context, videoEnabled: Boolean) {
            // Re-deliver to the running service so onStartCommand can recompute the
            // foreground service type. Using startService is safe: the service is
            // already foreground and onStartCommand calls startForeground immediately.
            val intent = Intent(context, VoiceCallForegroundService::class.java).apply {
                action = ACTION_UPDATE_VIDEO
                putExtra(EXTRA_VIDEO, videoEnabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceCallForegroundService::class.java))
        }
    }
}

// Bridge object — plugin sets these callbacks at registerPlugin time.
// Service emits telemetry through them; plugin forwards to JS via notifyListeners.
object VoiceCallForegroundServiceCallbacks {
    var onLifetimeEnded: (Long, String) -> Unit = { _, _ -> }
    var onTimeoutFired: (Int) -> Unit = { _ -> }
}
