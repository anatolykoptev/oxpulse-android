package run.krolik.oxpulse

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File

@CapacitorPlugin(name = "SharePackage")
class SharePackagePlugin : Plugin() {

    @PluginMethod
    fun share(call: PluginCall) {
        val ctx = context
        val pkg = ctx.packageName
        val apkPath = try {
            ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir
        } catch (e: PackageManager.NameNotFoundException) {
            call.reject("apk-not-found", e)
            return
        }

        // APK copy runs on Capacitor's background executor — never on the main
        // thread. APKs can be 30–100 MB; a synchronous copy on main = ANR.
        // The <root-path> provider was replaced (CVE-552 narrow) because it
        // granted URI access to / — any app with FLAG_GRANT_READ_URI_PERMISSION
        // on the Intent could construct arbitrary paths under that prefix.
        bridge.executor.execute {
            val out: File
            try {
                val outDir = File(ctx.cacheDir, "apk-share").apply { mkdirs() }
                out = File(outDir, "oxpulse.apk")
                File(apkPath).copyTo(out, overwrite = true)
            } catch (e: Exception) {
                call.reject("apk-copy-failed", e)
                return@execute
            }

            val uri = FileProvider.getUriForFile(ctx, "$pkg.fileprovider", out)

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Поделиться OxPulse").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // startActivityForResult must run on the main thread. Calling it from
            // a background executor without runOnUiThread causes crashes on API 30+.
            // Use startActivityForResult so the plugin resolves AFTER the chooser
            // dismisses. Note: many ACTION_SEND chooser implementations return
            // RESULT_CANCELED even on success — "shared: false" means the user
            // dismissed without picking any app, not that the transfer failed.
            bridge.activity.runOnUiThread {
                startActivityForResult(call, chooser, "shareResult")
            }
        }
    }

    @ActivityCallback
    fun shareResult(call: PluginCall, result: ActivityResult) {
        val shared = result.resultCode == Activity.RESULT_OK
        call.resolve(JSObject().put("shared", shared))
    }
}
