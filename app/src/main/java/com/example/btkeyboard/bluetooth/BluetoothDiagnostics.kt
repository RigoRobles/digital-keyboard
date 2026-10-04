package com.example.btkeyboard.bluetooth

data class BluetoothDiagnostics(
    val bluetoothEnabled: Boolean = false,
    val hidProfileRequested: Boolean = false,
    val hidAppName: String = "none",
    val hidSubclass: String = "none",
    val registerAppResult: String = "none",
    val onAppStatusChangedRegistered: String = "none",
    val hidAppRegistered: Boolean = false,
    val selectedDevice: String = "none",
    val connectButtonPressed: Boolean = false,
    val connectRequestedFor: String = "none",
    val lastRawConnectionState: String = "none",
    val lastConnectionCallbackState: String = "none",
    val lastConnectResult: String = "none"
) {
    fun asText(): String {
        return buildString {
            appendLine("Bluetooth HID diagnostics")
            appendLine("Bluetooth enabled: ${bluetoothEnabled.yesNo()}")
            appendLine("HID profile requested: ${hidProfileRequested.yesNo()}")
            appendLine("HID app name used: $hidAppName")
            appendLine("HID subclass used: $hidSubclass")
            appendLine("registerApp returned: $registerAppResult")
            appendLine("onAppStatusChanged registered: $onAppStatusChangedRegistered")
            appendLine("HID app registered: ${hidAppRegistered.yesNo()}")
            appendLine("Selected device: $selectedDevice")
            appendLine("Connect button pressed: ${connectButtonPressed.yesNo()}")
            appendLine("Connect requested for: $connectRequestedFor")
            appendLine("Last raw connection state: $lastRawConnectionState")
            appendLine("Last connection callback state: $lastConnectionCallbackState")
            appendLine("connect() returned: $lastConnectResult")
        }
    }
}

private fun Boolean.yesNo(): String = if (this) "yes" else "no"
