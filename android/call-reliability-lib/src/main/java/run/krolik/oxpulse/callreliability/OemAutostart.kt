package run.krolik.oxpulse.callreliability

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * OEM-specific autostart / battery-whitelist settings deep-links (Phase 4 Task 4.3).
 *
 * Per plan §2.3 (NEW anti-pattern): EVERY Intent MUST be guarded with
 * pm.resolveActivity(intent, 0) != null before startActivity. Unguarded vendor
 * ComponentNames crash with ActivityNotFoundException on ROM rename (MIUI 12→13→HyperOS).
 *
 * ComponentName table reimplemented from documented OEM class names.
 * DO NOT vendor DoNotNotify (GPL-3.0 — license incompatible with closed source).
 *
 * Per-vendor lists have 2-3 entries to handle ROM version drift. First resolvable
 * entry wins; telemetry surfaces outcome=not_applicable when entire list fails.
 *
 * Sub-brands (POCO/Redmi/Black Shark → xiaomi; HONOR → huawei; iQOO → vivo) are
 * normalized to their parent vendor so the existing UI/screenshots and intent
 * tables still apply. Realme, Transsion (Tecno/Infinix/Itel), ASUS, Nokia, Letv,
 * Meizu and stock-with-aggressive-killers (Motorola/Sony/LG) are returned as
 * distinct labels and added to the TS kill list.
 *
 * samsung: tracked via currentVendor() but NO autostart intent and NOT in the
 * client kill list — battery opt works fine on stock Samsung.
 */
object OemAutostart {

    private const val TAG = "OemAutostart"

    // Each vendor maps to an ordered list of ComponentNames (most-recent ROM first).
    // resolveActivity() selects the first one that the device recognizes.
    // Vendors with no reliable autostart screen (motorola/sony/lg) are omitted from
    // this map; openSettings returns false for them and the UI falls back to text.
    private val VENDOR_INTENTS: Map<String, List<ComponentName>> = mapOf(
        "xiaomi" to listOf(
            // MIUI 13+ / HyperOS — Security app autostart manager
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            // Older MIUI (≤12) fallback
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartActivity",
            ),
            // MIUI powerkeeper (used by some wake-lock helpers)
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HideAppsContainerManagementActivity",
            ),
        ),
        "huawei" to listOf(
            // EMUI 10+ / HarmonyOS — startup manager list
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            // Older EMUI fallback — process optimizer / protect
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
            // Startup app control (some EMUI/HarmonyOS builds)
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
        ),
        "oppo" to listOf(
            // ColorOS 12+ — safe center permission startup list
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            // Older ColorOS fallback
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            // OPPO legacy safe package
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        ),
        "oneplus" to listOf(
            // OxygenOS — chain-launch (background auto-start) manager
            ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
        ),
        "vivo" to listOf(
            // iQOO secure — background app whitelist
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            ),
            // Vivo permission manager — background start manager (fallback)
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
            // iQOO / Vivo BgStartUpManager alternate class
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
        ),
        "realme" to listOf(
            // realme UI / realme Safe — autostart permission list
            ComponentName(
                "com.realme.safecenter",
                "com.realme.safecenter.permission.startup.StartupAppListActivity",
            ),
            // realme UI on older ColorOS base
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            // OPPO legacy fallback (realme UI is ColorOS-derived)
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        ),
        "transsion" to listOf(
            // Tecno / Infinix / Itel — PhoneMaster autostart manager
            ComponentName(
                "com.transsion.phonemaster",
                "com.cyin.himgr.autostart.AutoStartActivity",
            ),
            // Itel-specific manager
            ComponentName(
                "com.transsion.phonemanager",
                "com.itel.autobootmanager.activity.AutoBootMgrActivity",
            ),
            // Transsion HiManager alternate autostart activity
            ComponentName(
                "com.transsion.phonemanager",
                "com.cyin.himgr.autostart.AutoStartActivity",
            ),
        ),
        "asus" to listOf(
            // ASUS Mobile Manager — power saver / auto-start settings
            ComponentName(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.autostart.AutoStartActivity",
            ),
            // ASUS ROG / ZenUI fallback
            ComponentName(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.powersaver.PowerSaverSettings",
            ),
            // ASUS Mobile Manager main entry
            ComponentName(
                "com.asus.mobilemanager",
                "com.asus.mobilemanager.MainActivity",
            ),
        ),
        "nokia" to listOf(
            // HMD/Nokia Evenwell power saver exception list
            ComponentName(
                "com.evenwell.powersaving.g3",
                "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity",
            ),
        ),
        "letv" to listOf(
            // LeEco / EUI autoboot manager
            ComponentName(
                "com.letv.android.letvsafe",
                "com.letv.android.letvsafe.AutobootManageActivity",
            ),
        ),
        "meizu" to listOf(
            // Flyme — app security / auto-start permission screen
            ComponentName(
                "com.meizu.safe",
                "com.meizu.safe.security.SHOW_APPSEC",
            ),
        ),
    )

    /**
     * Attempts to open the OEM autostart settings for the given vendor.
     *
     * Iterates the per-vendor ComponentName list, skipping entries that
     * pm.resolveActivity() cannot find (ROM rename / wrong vendor). Starts
     * the first resolvable activity and returns true.
     *
     * Returns false if: vendor not in table, OR all ComponentNames fail to resolve.
     * The caller should fall back to in-app text instructions and emit
     * client_oem_autostart_guide_total{outcome=not_applicable}.
     *
     * @param activity  Calling Activity — required for startActivity.
     * @param vendor    Vendor string from [currentVendor] (e.g. "xiaomi").
     */
    fun openSettings(activity: Activity, vendor: String): Boolean {
        val components = VENDOR_INTENTS[vendor] ?: return false
        val pm = activity.packageManager
        val packageName = activity.packageName
        for (component in components) {
            val intent = Intent()
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("packageName", packageName)
            if (pm.resolveActivity(intent, 0) != null) {
                try {
                    activity.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    // resolveActivity returned non-null but startActivity threw —
                    // rare (e.g. concurrent ROM update), try next ComponentName.
                    Log.w(TAG, "startActivity threw despite resolveActivity ok for $component", e)
                }
            }
        }
        return false
    }

    /**
     * Derives the OEM vendor string from Build.MANUFACTURER and Build.BRAND.
     *
     * Returns a closed canonical label ∈ {xiaomi, huawei, oppo, oneplus, vivo,
     * realme, transsion, asus, nokia, letv, meizu, motorola, sony, lg, samsung}
     * or null for unknown/stock Android.
     *
     * Sub-brands are normalized to the parent vendor (xiaomi/huawei/vivo) to reuse
     * the matching VENDOR_INTENTS and screenshots. Distinct labels (realme, etc.)
     * have their own VENDOR_INTENTS entries where a reliable autostart screen is
     * known; motorola/sony/lg have no autostart screen and rely on the battery
     * exemption flow and text instructions.
     */
    fun currentVendor(): String? {
        val mfr = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val combined = "$mfr $brand"
        return when {
            combined.containsAny("xiaomi", "redmi", "poco", "blackshark") -> "xiaomi"
            combined.containsAny("huawei", "honor") -> "huawei"
            combined.containsAny("oppo") -> "oppo"
            combined.containsAny("oneplus") -> "oneplus"
            combined.containsAny("vivo", "iqoo") -> "vivo"
            combined.containsAny("realme") -> "realme"
            combined.containsAny("tecno", "infinix", "itel", "transsion") -> "transsion"
            combined.containsAny("asus") -> "asus"
            combined.containsAny("nokia") -> "nokia"
            combined.containsAny("letv", "leeco") -> "letv"
            combined.containsAny("meizu", "flyme") -> "meizu"
            combined.containsAny("motorola", "moto") -> "motorola"
            combined.containsAny("sony", "xperia") -> "sony"
            combined.containsAny("lg", "lge") -> "lg"
            combined.containsAny("samsung") -> "samsung"  // tracked, no autostart intent, not in kill list
            else -> null
        }
    }

    /** Convenience helper for the multi-keyword checks above. */
    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }
}
