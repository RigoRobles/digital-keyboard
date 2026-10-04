package com.example.btkeyboard.bluetooth

enum class HidConnectionState(val label: String) {
    NOT_CONNECTED("Not connected"),
    WAITING_FOR_BLUETOOTH("Waiting for Bluetooth"),
    HID_REGISTERED("HID registered"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    ERROR("Error")
}
