package com.example.btkeyboard.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log

/*
 * Sends keyboard input reports matching report ID 1 in HidConstants.REPORT_DESCRIPTOR:
 * byte 0 = modifier bits, byte 1 = reserved, bytes 2-7 = up to six key usages.
 * Characters are mapped assuming the target uses a US keyboard layout.
 */
@SuppressLint("MissingPermission")
class KeyboardReportSender(
    private val service: BluetoothHidDevice,
    private val device: BluetoothDevice
) {
    private val tag = "KeyboardReportSender"

    fun pressAndRelease(usage: Int, modifiers: Int = 0): Boolean {
        val down = sendReport(modifiers, usage)
        val up = sendReport(0, 0)
        return down && up
    }

    fun typeText(text: String): Boolean {
        var allSent = true
        for (char in text) {
            val key = usageForChar(char)
            if (key == null) {
                Log.w(tag, "No HID usage mapped for character: $char")
                allSent = false
                continue
            }
            if (!pressAndRelease(key.usage, key.modifiers)) {
                allSent = false
            }
        }
        return allSent
    }

    fun releaseAllKeys(): Boolean = sendReport(0, 0)

    private fun sendReport(modifiers: Int, usage: Int): Boolean {
        val report = byteArrayOf(
            modifiers.toByte(),
            0,
            usage.toByte(),
            0,
            0,
            0,
            0,
            0
        )
        val sent = try {
            service.sendReport(device, HidConstants.KEYBOARD_REPORT_ID.toInt(), report)
        } catch (e: Exception) {
            Log.e(tag, "sendReport threw for usage=$usage modifiers=$modifiers", e)
            false
        }
        if (!sent) {
            Log.w(tag, "sendReport failed for usage=$usage modifiers=$modifiers")
        }
        return sent
    }

    data class Key(val usage: Int, val modifiers: Int = 0)

    companion object {
        const val MODIFIER_LEFT_CTRL = 0x01
        const val MODIFIER_LEFT_SHIFT = 0x02
        const val MODIFIER_LEFT_ALT = 0x04
        const val MODIFIER_LEFT_GUI = 0x08

        const val USAGE_ENTER = 0x28
        const val USAGE_ESCAPE = 0x29
        const val USAGE_BACKSPACE = 0x2A
        const val USAGE_TAB = 0x2B
        const val USAGE_SPACE = 0x2C
        const val USAGE_DELETE = 0x4C
        const val USAGE_ARROW_RIGHT = 0x4F
        const val USAGE_ARROW_LEFT = 0x50
        const val USAGE_ARROW_DOWN = 0x51
        const val USAGE_ARROW_UP = 0x52

        fun usageForChar(char: Char): Key? {
            return when (char) {
                in 'a'..'z' -> Key(0x04 + (char - 'a'))
                in 'A'..'Z' -> Key(0x04 + (char - 'A'), MODIFIER_LEFT_SHIFT)
                in '1'..'9' -> Key(0x1E + (char - '1'))
                '0' -> Key(0x27)
                '\n' -> Key(USAGE_ENTER)
                '\t' -> Key(USAGE_TAB)
                ' ' -> Key(USAGE_SPACE)
                '!' -> Key(0x1E, MODIFIER_LEFT_SHIFT)
                '@' -> Key(0x1F, MODIFIER_LEFT_SHIFT)
                '#' -> Key(0x20, MODIFIER_LEFT_SHIFT)
                '$' -> Key(0x21, MODIFIER_LEFT_SHIFT)
                '%' -> Key(0x22, MODIFIER_LEFT_SHIFT)
                '^' -> Key(0x23, MODIFIER_LEFT_SHIFT)
                '&' -> Key(0x24, MODIFIER_LEFT_SHIFT)
                '*' -> Key(0x25, MODIFIER_LEFT_SHIFT)
                '(' -> Key(0x26, MODIFIER_LEFT_SHIFT)
                ')' -> Key(0x27, MODIFIER_LEFT_SHIFT)
                '-' -> Key(0x2D)
                '_' -> Key(0x2D, MODIFIER_LEFT_SHIFT)
                '=' -> Key(0x2E)
                '+' -> Key(0x2E, MODIFIER_LEFT_SHIFT)
                '[' -> Key(0x2F)
                '{' -> Key(0x2F, MODIFIER_LEFT_SHIFT)
                ']' -> Key(0x30)
                '}' -> Key(0x30, MODIFIER_LEFT_SHIFT)
                '\\' -> Key(0x31)
                '|' -> Key(0x31, MODIFIER_LEFT_SHIFT)
                ';' -> Key(0x33)
                ':' -> Key(0x33, MODIFIER_LEFT_SHIFT)
                '\'' -> Key(0x34)
                '"' -> Key(0x34, MODIFIER_LEFT_SHIFT)
                '`' -> Key(0x35)
                '~' -> Key(0x35, MODIFIER_LEFT_SHIFT)
                ',' -> Key(0x36)
                '<' -> Key(0x36, MODIFIER_LEFT_SHIFT)
                '.' -> Key(0x37)
                '>' -> Key(0x37, MODIFIER_LEFT_SHIFT)
                '/' -> Key(0x38)
                '?' -> Key(0x38, MODIFIER_LEFT_SHIFT)
                else -> null
            }
        }
    }
}
