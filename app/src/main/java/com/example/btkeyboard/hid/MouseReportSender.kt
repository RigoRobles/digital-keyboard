package com.example.btkeyboard.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log

/*
 * Sends mouse input reports matching report ID 2 in HidConstants.REPORT_DESCRIPTOR:
 * byte 0 = button bits, byte 1 = X delta, byte 2 = Y delta, byte 3 = vertical wheel.
 * Deltas are relative and clamped to the signed 8-bit range the descriptor declares.
 */
@SuppressLint("MissingPermission")
class MouseReportSender(
    private val service: BluetoothHidDevice,
    private val device: BluetoothDevice
) {
    private val tag = "MouseReportSender"
    private var pressedButtons = 0

    fun move(dx: Int, dy: Int): Boolean = sendReport(pressedButtons, dx, dy, 0)

    fun scroll(amount: Int): Boolean = sendReport(pressedButtons, 0, 0, amount)

    fun click(button: Int): Boolean {
        val down = sendReport(button, 0, 0, 0)
        val up = sendReport(0, 0, 0, 0)
        return down && up
    }

    fun press(button: Int): Boolean {
        pressedButtons = pressedButtons or button
        return sendReport(pressedButtons, 0, 0, 0)
    }

    fun releaseButtons(): Boolean {
        pressedButtons = 0
        return sendReport(0, 0, 0, 0)
    }

    private fun sendReport(buttons: Int, dx: Int, dy: Int, wheel: Int): Boolean {
        val report = byteArrayOf(
            (buttons and 0x07).toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte()
        )
        val sent = try {
            service.sendReport(device, HidConstants.MOUSE_REPORT_ID.toInt(), report)
        } catch (e: Exception) {
            Log.e(tag, "sendReport threw buttons=$buttons dx=$dx dy=$dy wheel=$wheel", e)
            false
        }
        if (!sent) {
            Log.w(tag, "sendReport failed buttons=$buttons dx=$dx dy=$dy wheel=$wheel")
        }
        return sent
    }

    companion object {
        const val BUTTON_LEFT = 0x01
        const val BUTTON_RIGHT = 0x02
        const val BUTTON_MIDDLE = 0x04
    }
}
