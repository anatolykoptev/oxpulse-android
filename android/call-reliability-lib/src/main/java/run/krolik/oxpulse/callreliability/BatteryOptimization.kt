package run.krolik.oxpulse.callreliability

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

/**
 * Battery-optimization exemption helper (Phase 4 Task 4.2).
 *
 * Per plan §2.4 UX anti-pattern: do NOT request at onboarding — triggers Play Store
 * review for non-communication apps. Request lazily, ONLY on first call-failure where
 * FGS is killed AND the vendor is in the known-killer set AND exemption is not yet granted.
 *
 * oxpulse-chat qualifies as a "communication" app in Play Console, so the Intent is
 * permitted. Document rationale in Play Store update notes per plan §7.
 *
 * API 23+ (minSdk) — isIgnoringBatteryOptimizations available since API 23.
 */
object BatteryOptimization {

    /**
     * Builds the Intent for the system battery-optimization exemption dialog.
     * Caller is responsible for launching via startActivity or startActivityForResult.
     *
     * FLAG_ACTIVITY_NEW_TASK required: caller may be a non-Activity Context (FGS).
     */
    fun buildIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Launches the system battery-optimization exemption dialog for this app.
     * Dialog outcome is NOT synchronous — verify via [isIgnoringBatteryOptimizations]
     * on the next onResume.
     *
     * @deprecated Prefer [buildIntent] + startActivityForResult so that the
     * PluginCall resolves AFTER the user dismisses the dialog. This overload
     * remains for callers outside the Capacitor plugin (e.g., from FGS context).
     */
    fun requestExemption(activity: Activity) {
        activity.startActivity(buildIntent(activity))
    }

    /**
     * Returns true if this app is already on the battery-optimization ignore list.
     * API 23+ — always available for our minSdk.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
