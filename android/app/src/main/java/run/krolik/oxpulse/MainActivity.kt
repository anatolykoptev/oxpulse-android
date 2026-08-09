package run.krolik.oxpulse

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import run.krolik.oxpulse.callreliability.CallReliabilityPlugin
import run.krolik.oxpulse.mesh.MeshGattServerPlugin

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(MeshGattServerPlugin::class.java)
        registerPlugin(SharePackagePlugin::class.java)
        registerPlugin(CallReliabilityPlugin::class.java)
        super.onCreate(savedInstanceState)
        bridge.webView.settings.userAgentString =
            bridge.webView.settings.userAgentString + " OxPulseShell/1"
    }
}
