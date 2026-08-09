package run.krolik.oxpulse.mesh

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Standard CCCD descriptor UUID required for NOTIFY subscription handshake.
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@CapacitorPlugin(
    name = "MeshGattServer",
    permissions = [
        Permission(
            strings = [
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ],
            alias = "bluetooth",
        ),
    ],
)
class MeshGattServerPlugin : Plugin() {

    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    // Prefer bluetoothManager.adapter over deprecated BluetoothAdapter.getDefaultAdapter() (API 31+).
    private val bluetoothAdapter get() = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // Map<deviceAddress, BluetoothDevice> — thread-safe for concurrent binder callbacks.
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()

    private var advertiseCallback: AdvertiseCallback? = null

    /**
     * Called by Capacitor when the bridge activity is destroyed — the guaranteed
     * cleanup path for BLE resources that would otherwise leak. Without this:
     *   - gattServer leaks an open BluetoothGattServer in the Bluetooth stack
     *     (issue #24);
     *   - advertiseCallback leaks an active LE advertising session (issue #10);
     *   - connectedDevices holds stale BluetoothDevice references.
     */
    override fun handleOnDestroy() {
        super.handleOnDestroy()
        // Stop advertising first — stops new connections before we tear down the server.
        val cb = advertiseCallback
        if (cb != null) {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
            advertiseCallback = null
        }
        val server = gattServer
        if (server != null) {
            for (device in connectedDevices.values.toList()) {
                server.cancelConnection(device)
            }
            connectedDevices.clear()
            server.close()
            gattServer = null
            txCharacteristic = null
        }
    }

    // -------------------------------------------------------------------------
    // Advertising
    // -------------------------------------------------------------------------

    @PluginMethod
    fun startAdvertising(call: PluginCall) {
        // B1.9: compare via PermissionState enum, not .name string.
        if (getPermissionState("bluetooth") != PermissionState.GRANTED) {
            call.reject("missing-bluetooth-permission")
            return
        }

        val peerId = call.getString("peerId")
        if (peerId == null || peerId.length != 16) {
            call.reject("peerId must be exactly 16 hex characters (8 bytes)")
            return
        }

        val peerIdBytes: ByteArray
        try {
            peerIdBytes = ByteArray(8) { i ->
                peerId.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            call.reject("peerId contains invalid hex characters")
            return
        }

        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            call.reject("LE advertising not supported on this device")
            return
        }

        // B1.4: stop prior callback before creating a new one — prevents AdvertiseCallback leak
        // when startAdvertising is called again (e.g. MAC rotation or retry).
        if (advertiseCallback != null) {
            advertiser.stopAdvertising(advertiseCallback)
            advertiseCallback = null
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(MeshConstants.SERVICE_UUID))
            .addServiceData(ParcelUuid(MeshConstants.SERVICE_UUID), peerIdBytes)
            .setIncludeDeviceName(false)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                // Advertising started; nothing to notify — startAdvertising call already resolved.
            }

            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                    else -> "UNKNOWN_$errorCode"
                }
                val payload = JSObject().apply {
                    put("reason", reason)
                    put("code", errorCode)
                }
                bridge.activity.runOnUiThread {
                    notifyListeners("advertisingFailed", payload)
                }
            }
        }

        advertiseCallback = cb
        advertiser.startAdvertising(settings, data, cb)
        call.resolve()
    }

    @PluginMethod
    fun stopAdvertising(call: PluginCall) {
        val cb = advertiseCallback
        if (cb != null) {
            bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
            advertiseCallback = null
        }
        call.resolve()
    }

    // -------------------------------------------------------------------------
    // GATT Server
    // -------------------------------------------------------------------------

    @PluginMethod
    fun startGattServer(call: PluginCall) {
        // B1.9: compare via PermissionState enum, not .name string.
        if (getPermissionState("bluetooth") != PermissionState.GRANTED) {
            call.reject("missing-bluetooth-permission")
            return
        }

        if (gattServer != null) {
            call.resolve()
            return
        }

        val rxChar = BluetoothGattCharacteristic(
            MeshConstants.RX_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val cccdDescriptor = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        val txChar = BluetoothGattCharacteristic(
            MeshConstants.TX_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        txChar.addDescriptor(cccdDescriptor)

        val service = BluetoothGattService(
            MeshConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)

        txCharacteristic = txChar

        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val connected = newState == BluetoothProfile.STATE_CONNECTED
                if (connected) {
                    connectedDevices[device.address] = device
                } else {
                    connectedDevices.remove(device.address)
                }
                val payload = JSObject().apply {
                    put("deviceAddress", device.address)
                    put("connected", connected)
                }
                bridge.activity.runOnUiThread {
                    notifyListeners("connection", payload)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                if (characteristic.uuid == MeshConstants.RX_CHARACTERISTIC_UUID) {
                    val encoded = Base64.encodeToString(value ?: ByteArray(0), Base64.NO_WRAP)
                    val payload = JSObject().apply {
                        put("deviceAddress", device.address)
                        put("data", encoded)
                    }
                    bridge.activity.runOnUiThread {
                        notifyListeners("rx", payload)
                    }
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        val server = bluetoothManager.openGattServer(context, serverCallback)
        if (server == null) {
            call.reject("Failed to open GATT server")
            return
        }
        server.addService(service)
        gattServer = server
        call.resolve()
    }

    @PluginMethod
    fun stopGattServer(call: PluginCall) {
        val server = gattServer
        if (server != null) {
            for (device in connectedDevices.values) {
                server.cancelConnection(device)
            }
            connectedDevices.clear()
            server.close()
            gattServer = null
            txCharacteristic = null
        }
        call.resolve()
    }

    // -------------------------------------------------------------------------
    // TX Notify
    // -------------------------------------------------------------------------

    @PluginMethod
    fun notifyTx(call: PluginCall) {
        val address = call.getString("deviceAddress")
        val dataB64 = call.getString("data")

        if (address == null || dataB64 == null) {
            call.reject("deviceAddress and data are required")
            return
        }

        val device = connectedDevices[address]
        if (device == null) {
            call.reject("device not connected: $address")
            return
        }

        val server = gattServer
        val txChar = txCharacteristic
        if (server == null || txChar == null) {
            call.reject("GATT server not started")
            return
        }

        val bytes: ByteArray
        try {
            bytes = Base64.decode(dataB64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            call.reject("data is not valid base64")
            return
        }

        // B1.7: synchronize value-set + notify to prevent races when multiple
        // coroutines call notifyTx concurrently on the same txCharacteristic.
        // B1.8: wrap in try/catch for IllegalArgumentException (device disconnected mid-notify).
        val notified: Boolean = try {
            synchronized(txChar) {
                txChar.value = bytes
                server.notifyCharacteristicChanged(device, txChar, false)
            }
        } catch (e: IllegalArgumentException) {
            call.reject("device-disconnected", e)
            return
        }

        if (!notified) {
            call.reject("notify-failed")
            return
        }

        call.resolve()
    }

    // -------------------------------------------------------------------------
    // Settings navigation helpers (D2)
    // -------------------------------------------------------------------------

    /**
     * Open the system Bluetooth settings screen. Used when BLE is off and
     * the user needs a recovery CTA.
     */
    @PluginMethod
    fun openBluetoothSettings(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("open-settings-failed", e)
        }
    }

    /**
     * Open the per-app permission settings screen. Used when BLE permission
     * was denied and the user needs a recovery CTA.
     */
    @PluginMethod
    fun openAppPermissionSettings(call: PluginCall) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            call.resolve()
        } catch (e: Exception) {
            call.reject("open-settings-failed", e)
        }
    }
}
