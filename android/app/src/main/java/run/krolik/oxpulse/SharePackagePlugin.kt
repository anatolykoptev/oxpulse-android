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
import java.util.concurrent.Executors

@CapacitorPlugin(name = "SharePackage")
class SharePackagePlugin : Plugin() {

    private companion object {
        /**
         * Capacitor 6's Bridge exposes no public executor — `bridge.executor` does
         * not resolve. One shared single-thread executor for the app's lifetime
         * keeps the APK copy off the main thread, which is the property the caller
         * below actually needs.
         */
        val IO_EXECUTOR = Executors.newSingleThreadExecutor { r ->
            Thread(r, "oxpulse-share-apk").apply { isDaemon = true }
        }
    }

    /**
     * Called by Capacitor when the bridge activity is destroyed — shuts down the
     * shared IO_EXECUTOR. Without this the executor thread lives for the app's
     * lifetime even after the plugin is destroyed (issue #10). The threads are
     * daemon, so this is defence-in-depth rather than a hard leak, but shutdown
     * is the correct lifecycle behaviour.
     */
    override fun handleOnDestroy() {
        super.handleOnDestroy()
        IO_EXECUTOR.shutdownNow()
    }

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
        IO_EXECUTOR.execute {
            val out: File
            try {
                val outDir = File(ctx.cacheDir, "apk-share").apply { mkdirs() }
                out = File(outDir, "oxpulse.apk")
                File(apkPath).copyTo(out, overwrite = true)
            } catch (e: Exception) {
                // Log the exception type + message before rejecting — without this,
                // "apk-copy-failed" gives no indication of root cause (disk full,
                // permission denied, read-only filesystem, etc.) (issue #17).
                android.util.Log.e("SharePackagePlugin", "APK copy failed: ${e.javaClass.simpleName}: ${e.message}", e)
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
