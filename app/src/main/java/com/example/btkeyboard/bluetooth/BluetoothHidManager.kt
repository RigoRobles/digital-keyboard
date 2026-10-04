package com.example.btkeyboard.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.btkeyboard.hid.HidConstants
import com.example.btkeyboard.hid.KeyboardReportSender
import com.example.btkeyboard.hid.MouseReportSender
import java.util.concurrent.Executor

class BluetoothHidManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStateChanged(state: HidConnectionState, message: String)
        fun onDevicesChanged(devices: List<BluetoothDevice>)
        fun onDiagnosticsChanged(diagnostics: BluetoothDiagnostics)
    }

    private val tag = "BluetoothHidManager"
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val mainExecutor = Executor { command -> context.mainExecutor.execute(command) }
    private val timeoutHandler = Handler(Looper.getMainLooper())

    private var hidDevice: BluetoothHidDevice? = null
    private var selectedDevice: BluetoothDevice? = null

    var keyboardSender: KeyboardReportSender? = null
        private set
    var mouseSender: MouseReportSender? = null
        private set

    private var registrationFinished = false
    private var hidProfileRequested = false
    private var hidAppRegistered = false
    private var registerAppResult = "none"
    private var onAppStatusChangedRegistered = "none"
    private var lastRawConnectionState = "none"
    private var lastConnectionCallbackState = "none"
    private var lastConnectResult = "none"
    private var connectButtonPressed = false
    private var connectRequestedFor = "none"
    private var disconnectRequestedByUser = false
    private var autoConnectAttempted = false

    private val prefs = context.getSharedPreferences("bt_keyboard", Context.MODE_PRIVATE)

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            Log.i(tag, "ServiceListener.onServiceConnected profile=$profile")
            if (profile != BluetoothProfile.HID_DEVICE) return

            hidDevice = proxy as BluetoothHidDevice
            timeoutHandler.removeCallbacksAndMessages(null)
            Log.i(tag, "HID Device profile proxy connected")
            registerHidApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            Log.w(tag, "ServiceListener.onServiceDisconnected profile=$profile")
            if (profile != BluetoothProfile.HID_DEVICE) return

            Log.w(tag, "HID Device profile proxy disconnected")
            hidDevice = null
            hidAppRegistered = false
            publishDiagnostics()
            listener.onStateChanged(
                HidConnectionState.ERROR,
                "Bluetooth HID service disconnected"
            )
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            registrationFinished = true
            hidAppRegistered = registered
            onAppStatusChangedRegistered = registered.toString()
            Log.i(
                tag,
                "Callback.onAppStatusChanged registered=$registered " +
                    "pluggedDevice=${pluggedDevice.describeForLog()}"
            )
            publishDiagnostics()

            val state = if (registered) HidConnectionState.HID_REGISTERED else HidConnectionState.ERROR
            val message = if (registered) {
                "Ready to connect. Pair from your computer, or tap a device below."
            } else {
                "Failed to register HID app. This phone may block HID Device mode."
            }
            listener.onStateChanged(state, message)
            refreshPairedDevices()
            if (registered) {
                autoConnectToLastDevice()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val stateName = state.readableConnectionState()
            lastRawConnectionState = "$state ($stateName)"
            lastConnectionCallbackState = "$state ($stateName)"
            Log.i(
                tag,
                "Callback.onConnectionStateChanged state=$state stateName=$stateName " +
                    "device=${device.describeForLog()}"
            )
            publishDiagnostics()

            val deviceName = device?.safeName() ?: "target device"
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    createReportSenders(device)
                    rememberLastDevice(device)
                    if (device != null) selectedDevice = device
                    listener.onStateChanged(
                        HidConnectionState.CONNECTED,
                        "Connected to $deviceName"
                    )
                }
                BluetoothProfile.STATE_CONNECTING -> listener.onStateChanged(
                    HidConnectionState.CONNECTING,
                    "Connecting to $deviceName"
                )
                BluetoothProfile.STATE_DISCONNECTING -> listener.onStateChanged(
                    HidConnectionState.HID_REGISTERED,
                    "Disconnecting from $deviceName"
                )
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clearReportSenders()
                    listener.onStateChanged(
                        HidConnectionState.HID_REGISTERED,
                        disconnectedMessage(deviceName)
                    )
                }
                else -> listener.onStateChanged(
                    HidConnectionState.HID_REGISTERED,
                    targetRejectedMessage()
                )
            }
        }

        override fun onGetReport(
            device: BluetoothDevice?,
            type: Byte,
            id: Byte,
            bufferSize: Int
        ) {
            Log.i(
                tag,
                "Callback.onGetReport type=$type id=$id bufferSize=$bufferSize " +
                    "device=${device.describeForLog()}"
            )
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            Log.i(
                tag,
                "Callback.onSetReport type=$type id=$id dataSize=${data?.size ?: 0} " +
                    "device=${device.describeForLog()}"
            )
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            Log.i(tag, "Callback.onSetProtocol protocol=$protocol device=${device.describeForLog()}")
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            Log.i(
                tag,
                "Callback.onInterruptData reportId=$reportId dataSize=${data?.size ?: 0} " +
                    "device=${device.describeForLog()}"
            )
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice?) {
            Log.i(tag, "Callback.onVirtualCableUnplug device=${device.describeForLog()}")
        }
    }

    /*
     * Every Bluetooth SDK call below can throw (SecurityException when a runtime
     * permission is missing on Android 12+, IllegalStateException if the stack is
     * mid-teardown, or vendor-specific errors). Route those to an ERROR state so a
     * single failing call never takes the whole app down.
     */
    private inline fun <T> safeBt(action: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            Log.e(tag, "Bluetooth call failed: $action", e)
            listener.onStateChanged(
                HidConnectionState.ERROR,
                "Bluetooth error while $action: ${e.message ?: e.javaClass.simpleName}"
            )
            fallback
        }
    }

    fun start() {
        publishDiagnostics()
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            Log.e(tag, "Bluetooth adapter is not available")
            listener.onStateChanged(HidConnectionState.ERROR, "Bluetooth is not available")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w(tag, "Bluetooth is disabled")
            listener.onStateChanged(
                HidConnectionState.WAITING_FOR_BLUETOOTH,
                "Turn Bluetooth on, then tap Refresh"
            )
            return
        }

        listener.onStateChanged(
            HidConnectionState.WAITING_FOR_BLUETOOTH,
            "Checking Bluetooth HID Device profile support"
        )
        registrationFinished = false
        hidProfileRequested = true
        publishDiagnostics()

        val requested = safeBt("requesting HID Device profile", false) {
            bluetoothAdapter.getProfileProxy(
                context,
                serviceListener,
                BluetoothProfile.HID_DEVICE
            )
        }

        Log.i(tag, "getProfileProxy(HID_DEVICE) requested=$requested")
        if (!requested) {
            Log.e(tag, "HID Device profile proxy request was rejected")
            publishDiagnostics()
            listener.onStateChanged(
                HidConnectionState.ERROR,
                "This phone does not expose the Bluetooth HID Device profile"
            )
            return
        }

        timeoutHandler.postDelayed({
            if (hidDevice == null && !registrationFinished) {
                Log.e(tag, "Timed out while waiting for HID Device profile proxy")
                listener.onStateChanged(
                    HidConnectionState.ERROR,
                    "Bluetooth HID Device profile did not respond. This phone may not support HID mode."
                )
            }
        }, PROFILE_PROXY_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun resetHidRegistration() {
        Log.i(tag, "Reset HID Registration requested")
        timeoutHandler.removeCallbacksAndMessages(null)

        hidDevice?.let { service ->
            Log.i(tag, "Unregistering HID app before force re-register")
            safeBt("resetting HID registration", Unit) {
                service.unregisterApp()
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, service)
            }
        }

        hidDevice = null
        clearReportSenders()
        registrationFinished = false
        hidProfileRequested = false
        hidAppRegistered = false
        registerAppResult = "none"
        onAppStatusChangedRegistered = "none"
        lastRawConnectionState = "none"
        lastConnectionCallbackState = "none"
        lastConnectResult = "none"
        connectButtonPressed = false
        connectRequestedFor = "none"
        disconnectRequestedByUser = false
        autoConnectAttempted = false
        publishDiagnostics()

        listener.onStateChanged(
            HidConnectionState.WAITING_FOR_BLUETOOTH,
            "Reset HID registration. Requesting HID profile again with the Windows SDP name."
        )
        start()
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        val devices = safeBt("reading paired devices", emptyList<BluetoothDevice>()) {
            adapter?.bondedDevices?.sortedBy { it.safeName() }.orEmpty()
        }
        Log.i(tag, "Found ${devices.size} paired Bluetooth device(s)")
        devices.forEach { Log.i(tag, "Paired device: ${it.describeForLog()}") }
        publishDiagnostics()
        listener.onDevicesChanged(devices)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        selectedDevice = device
        disconnectRequestedByUser = false
        connectButtonPressed = true
        connectRequestedFor = device.describeNameAndAddress()

        val service = hidDevice
        Log.i(tag, "connect() called")
        Log.i(tag, "connect() selected device=${device.describeNameAndAddress()}")
        Log.i(tag, "connect() hidDevice is null=${service == null}")
        if (service == null) {
            lastConnectResult = "false"
            Log.i(tag, "connect() returned false")
            publishDiagnostics()
            listener.onStateChanged(
                HidConnectionState.ERROR,
                "HID service is null. Registration may have been lost. Press Refresh."
            )
            return
        }

        Log.i(tag, "Connecting HID profile to ${device.safeName()} (${device.address})")
        listener.onStateChanged(HidConnectionState.CONNECTING, "Connecting to ${device.safeName()}")
        val connectResult = safeBt("connecting to ${device.safeName()}", false) {
            service.connect(device)
        }
        lastConnectResult = connectResult.toString()
        Log.i(tag, "connect() returned $connectResult")
        Log.i(tag, "connect(device) returned $connectResult for ${device.describeForLog()}")
        publishDiagnostics()

        if (connectResult) {
            listener.onStateChanged(
                HidConnectionState.CONNECTING,
                "HID connect request sent. Waiting for connection state callback."
            )
        } else {
            listener.onStateChanged(
                HidConnectionState.HID_REGISTERED,
                "HID registered, but connect() returned false. This target may reject Bluetooth keyboard/mouse mode."
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun selectDevice(device: BluetoothDevice) {
        selectedDevice = device
        Log.i(tag, "Selected device: ${device.describeForLog()}")
        publishDiagnostics()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val service = hidDevice ?: return
        val device = selectedDevice ?: return

        Log.i(tag, "Disconnecting from ${device.safeName()}")
        disconnectRequestedByUser = true
        safeBt("disconnecting", Unit) { service.disconnect(device) }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        timeoutHandler.removeCallbacksAndMessages(null)
        val service = hidDevice ?: return
        safeBt("stopping HID", Unit) {
            service.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, service)
        }
        hidDevice = null
        clearReportSenders()
        hidAppRegistered = false
        hidProfileRequested = false
        publishDiagnostics()
    }

    @SuppressLint("MissingPermission")
    private fun rememberLastDevice(device: BluetoothDevice?) {
        val address = device?.address ?: return
        prefs.edit().putString(PREF_LAST_DEVICE_ADDRESS, address).apply()
    }

    @SuppressLint("MissingPermission")
    private fun autoConnectToLastDevice() {
        if (autoConnectAttempted) return
        val address = prefs.getString(PREF_LAST_DEVICE_ADDRESS, null) ?: return
        val device = adapter?.bondedDevices?.firstOrNull { it.address == address }
        if (device == null) {
            Log.i(tag, "Last device $address is no longer paired; skipping auto-connect")
            return
        }
        autoConnectAttempted = true
        Log.i(tag, "Auto-connecting to last device ${device.describeForLog()}")
        listener.onStateChanged(
            HidConnectionState.CONNECTING,
            "Reconnecting to ${device.safeName()}"
        )
        connect(device)
    }

    private fun createReportSenders(device: BluetoothDevice?) {
        val service = hidDevice
        if (service == null || device == null) {
            Log.w(tag, "Cannot create report senders: service or device is null")
            return
        }
        keyboardSender = KeyboardReportSender(service, device)
        mouseSender = MouseReportSender(service, device)
        Log.i(tag, "Keyboard and mouse report senders ready for ${device.describeForLog()}")
    }

    private fun clearReportSenders() {
        keyboardSender = null
        mouseSender = null
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val service = hidDevice ?: return

        val sdp = BluetoothHidDeviceAppSdpSettings(
            HID_APP_NAME,
            HID_APP_DESCRIPTION,
            HID_APP_PROVIDER,
            HID_APP_SUBCLASS,
            HidConstants.REPORT_DESCRIPTOR
        )

        val ok = safeBt("registering HID app", false) {
            service.registerApp(
                sdp,
                null as BluetoothHidDeviceAppQosSettings?,
                null as BluetoothHidDeviceAppQosSettings?,
                mainExecutor,
                hidCallback
            )
        }

        Log.i(tag, "registerApp returned $ok")
        registerAppResult = ok.toString()
        publishDiagnostics()
        if (!ok) {
            registrationFinished = true
            hidAppRegistered = false
            publishDiagnostics()
            listener.onStateChanged(
                HidConnectionState.ERROR,
                "Bluetooth HID registration failed"
            )
        }
    }

    private fun disconnectedMessage(deviceName: String): String {
        return if (disconnectRequestedByUser) {
            disconnectRequestedByUser = false
            "Disconnected from $deviceName"
        } else if (hidAppRegistered) {
            targetRejectedMessage()
        } else {
            "Bluetooth HID registration failed"
        }
    }

    private fun targetRejectedMessage(): String {
        return "HID registered, but this target rejected the keyboard/mouse connection. " +
            "Try Windows, Linux, Raspberry Pi, or Android TV."
    }

    private fun publishDiagnostics() {
        listener.onDiagnosticsChanged(
            BluetoothDiagnostics(
                bluetoothEnabled = adapter?.isEnabled == true,
                hidProfileRequested = hidProfileRequested,
                hidAppName = HID_APP_NAME,
                hidSubclass = HID_APP_SUBCLASS_LABEL,
                registerAppResult = registerAppResult,
                onAppStatusChangedRegistered = onAppStatusChangedRegistered,
                hidAppRegistered = hidAppRegistered,
                selectedDevice = selectedDevice.describeForDiagnostics(),
                connectButtonPressed = connectButtonPressed,
                connectRequestedFor = connectRequestedFor,
                lastRawConnectionState = lastRawConnectionState,
                lastConnectionCallbackState = lastConnectionCallbackState,
                lastConnectResult = lastConnectResult
            )
        )
    }

    companion object {
        private const val PROFILE_PROXY_TIMEOUT_MS = 5000L
        private const val PREF_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val HID_APP_NAME = "Rigo Bluetooth Keyboard Mouse"
        private const val HID_APP_DESCRIPTION = "Bluetooth keyboard and mouse"
        private const val HID_APP_PROVIDER = "Rigo"
        private val HID_APP_SUBCLASS = BluetoothHidDevice.SUBCLASS1_COMBO
        private const val HID_APP_SUBCLASS_LABEL = "SUBCLASS1_COMBO"
    }
}

@SuppressLint("MissingPermission")
fun BluetoothDevice.safeName(): String = name ?: address ?: "Unknown device"

@SuppressLint("MissingPermission")
private fun BluetoothDevice?.describeForDiagnostics(): String {
    if (this == null) return "none"
    return "${describeNameAndAddress()} / " +
        "bond=${bondState.readableBondState()} / " +
        "class=${bluetoothClass.readableBluetoothClass()} / " +
        "type=${type.readableDeviceType()}"
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.describeNameAndAddress(): String {
    return "${safeName()} / ${address ?: "unknown address"}"
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice?.describeForLog(): String {
    if (this == null) return "none"
    return "name=${safeName()}, address=${address ?: "unknown"}, " +
        "bondState=${bondState.readableBondState()}($bondState), " +
        "class=${bluetoothClass.readableBluetoothClass()}, " +
        "type=${type.readableDeviceType()}($type)"
}

private fun Int.readableConnectionState(): String {
    return when (this) {
        BluetoothProfile.STATE_DISCONNECTED -> "STATE_DISCONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "STATE_CONNECTING"
        BluetoothProfile.STATE_CONNECTED -> "STATE_CONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "STATE_DISCONNECTING"
        else -> "UNKNOWN_STATE_$this"
    }
}

private fun Int.readableBondState(): String {
    return when (this) {
        BluetoothDevice.BOND_NONE -> "BOND_NONE"
        BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
        BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
        else -> "UNKNOWN_BOND_$this"
    }
}

private fun Int.readableDeviceType(): String {
    return when (this) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "DEVICE_TYPE_CLASSIC"
        BluetoothDevice.DEVICE_TYPE_LE -> "DEVICE_TYPE_LE"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "DEVICE_TYPE_DUAL"
        BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "DEVICE_TYPE_UNKNOWN"
        else -> "UNKNOWN_TYPE_$this"
    }
}

private fun BluetoothClass?.readableBluetoothClass(): String {
    if (this == null) return "unknown"
    return "major=$majorDeviceClass, device=$deviceClass"
}
