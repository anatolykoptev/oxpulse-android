package run.krolik.oxpulse.callreliability

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import java.util.concurrent.Executor

/**
 * Detects mid-call RECORD_AUDIO permission revocation via OS-pushed AppOps events.
 *
 * Phase 4 §2.1 REVERSAL of Phase 1 plan §5 (5s checkPermission poll):
 * Polling fails because Android 23+ revokes by killing the process. AppOps
 * listener is OS-pushed, fires synchronously, and re-checking checkSelfPermission
 * inside the callback distinguishes true revocation from audio-focus loss.
 *
 * Reference: Signal-Android pattern (no poll); Element-Android same.
 *
 * API 30+ only — pre-30 cohort misses mid-call revocation detection (no-op guard).
 * Per plan §7: API 23-29 cohort noted as follow-up (Phase 5 sub-task).
 */
class MicWatch(
    private val context: Context,
    private val executor: Executor,
    private val onRevoked: () -> Unit,
) {
    private var listener: AppOpsManager.OnOpActiveChangedListener? = null

    fun install() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return  // API 30+
        val appOps = context.getSystemService<AppOpsManager>() ?: return
        listener = AppOpsManager.OnOpActiveChangedListener { op, _, _, active ->
            if (op != AppOpsManager.OPSTR_RECORD_AUDIO) return@OnOpActiveChangedListener
            if (active) return@OnOpActiveChangedListener  // active=true = no revocation
            // active=false → re-check actual permission to distinguish from focus loss
            val granted = ActivityCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w("MicWatch", "RECORD_AUDIO revoked mid-call")
                onRevoked()
            }
        }
        appOps.startWatchingActive(
            arrayOf(AppOpsManager.OPSTR_RECORD_AUDIO),
            executor,
            listener!!,
        )
    }

    fun uninstall() {
        val appOps = context.getSystemService<AppOpsManager>() ?: return
        listener?.let { appOps.stopWatchingActive(it) }
        listener = null
    }
}
