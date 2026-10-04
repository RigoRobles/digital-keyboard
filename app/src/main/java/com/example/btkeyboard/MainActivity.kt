package com.example.btkeyboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.btkeyboard.bluetooth.BluetoothDiagnostics
import com.example.btkeyboard.bluetooth.BluetoothHidManager
import com.example.btkeyboard.bluetooth.HidConnectionState
import com.example.btkeyboard.bluetooth.safeName
import com.example.btkeyboard.hid.KeyboardReportSender
import com.example.btkeyboard.hid.MouseReportSender
import kotlin.math.roundToInt

class MainActivity : Activity(), BluetoothHidManager.Listener {
    private lateinit var hidManager: BluetoothHidManager
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var deviceList: LinearLayout
    private var inputStatusText: TextView? = null

    private var latestDiagnostics = BluetoothDiagnostics()
    private var currentState = HidConnectionState.WAITING_FOR_BLUETOOTH
    private var lastStateMessage = ""
    private var showingInputScreen = false
    private var discoverableRequested = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashRecorder()
        hidManager = BluetoothHidManager(this, this)
        setContentView(buildConnectionScreen())
        checkPermissionsAndStart()
    }

    /*
     * Record any uncaught crash to preferences before the process dies, then hand off
     * to the system's default handler so Android still shows its dialog. The saved
     * reason is surfaced on the next launch so the failure is visible, not silent.
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = android.util.Log.getStackTraceString(throwable)
                getSharedPreferences("bt_keyboard", Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_LAST_CRASH, "${throwable.javaClass.simpleName}: ${throwable.message}\n$trace")
                    .commit()
            } catch (_: Exception) {
                // Never let the crash recorder itself crash.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        hidManager.stop()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH_PERMISSIONS) return

        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            checkPermissionsAndStart()
        } else {
            onStateChanged(
                HidConnectionState.ERROR,
                "Bluetooth permission was denied. The app cannot use HID mode."
            )
        }
    }

    override fun onBackPressed() {
        if (showingInputScreen) {
            showConnectionScreen()
        } else {
            super.onBackPressed()
        }
    }

    override fun onStateChanged(state: HidConnectionState, message: String) {
        runOnUiThread {
            currentState = state
            lastStateMessage = message

            if (state == HidConnectionState.HID_REGISTERED && !discoverableRequested) {
                scheduleDiscoverablePrompt()
            }

            if (state == HidConnectionState.CONNECTED && !showingInputScreen) {
                showInputScreen()
                return@runOnUiThread
            }
            if (state != HidConnectionState.CONNECTED && showingInputScreen) {
                Toast.makeText(this, "Connection lost", Toast.LENGTH_SHORT).show()
                showConnectionScreen()
                return@runOnUiThread
            }

            if (showingInputScreen) {
                inputStatusText?.text = "${state.label} — $message"
            } else {
                statusText.text = state.label
                detailText.text = message
            }
        }
    }

    override fun onDevicesChanged(devices: List<BluetoothDevice>) {
        runOnUiThread {
            deviceList.removeAllViews()
            if (devices.isEmpty()) {
                deviceList.addView(
                    label("No paired devices yet. Pair from your computer and the keyboard will open by itself.")
                )
                return@runOnUiThread
            }

            devices.forEach { device ->
                val button = Button(this).apply {
                    text = device.safeName()
                    textSize = 18f
                    setPadding(20, 16, 20, 16)
                    setOnClickListener {
                        detailText.text = "Connecting to ${device.safeName()}..."
                        hidManager.connect(device)
                    }
                }
                deviceList.addView(button, matchWrap())
            }
        }
    }

    override fun onDiagnosticsChanged(diagnostics: BluetoothDiagnostics) {
        runOnUiThread {
            latestDiagnostics = diagnostics
            diagnosticsText.text = diagnostics.asText()
        }
    }

    private fun checkPermissionsAndStart() {
        val missing = requiredBluetoothPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
            return
        }

        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            onStateChanged(HidConnectionState.ERROR, "Bluetooth is not available on this device.")
            return
        }

        if (!adapter.isEnabled) {
            onStateChanged(HidConnectionState.WAITING_FOR_BLUETOOTH, "Bluetooth is off. Turn it on, then tap Refresh.")
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (e: Exception) {
                Log.w(tag, "Could not open Bluetooth settings", e)
            }
            return
        }

        hidManager.start()
    }

    private fun requiredBluetoothPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            emptyList()
        }
    }

    /*
     * Wait a moment before asking to become visible: if auto-reconnect to the
     * last device is already underway, the pairing prompt is not needed.
     */
    private fun scheduleDiscoverablePrompt() {
        discoverableRequested = true
        mainHandler.postDelayed({
            if (currentState == HidConnectionState.HID_REGISTERED) {
                requestDiscoverable()
            }
        }, DISCOVERABLE_PROMPT_DELAY_MS)
    }

    private fun requestDiscoverable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(tag, "BLUETOOTH_ADVERTISE not granted; cannot request discoverable mode")
            return
        }
        try {
            startActivity(
                Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION_SECONDS)
                }
            )
        } catch (e: Exception) {
            Log.w(tag, "Could not request discoverable mode", e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildConnectionScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF101418.toInt())
        }

        statusText = label("Waiting for Bluetooth").apply {
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
        }
        detailText = label("Starting Bluetooth checks...")

        deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val makeVisibleButton = Button(this).apply {
            text = "Make Phone Visible for Pairing"
            textSize = 18f
            setOnClickListener { requestDiscoverable() }
        }

        val refreshButton = Button(this).apply {
            text = "Refresh"
            textSize = 18f
            setOnClickListener {
                checkPermissionsAndStart()
                hidManager.refreshPairedDevices()
            }
        }

        val resetHidButton = Button(this).apply {
            text = "Reset HID Registration"
            textSize = 16f
            setOnClickListener { hidManager.resetHidRegistration() }
        }

        val copyDiagnosticsButton = Button(this).apply {
            text = "Copy Diagnostics"
            textSize = 16f
            setOnClickListener { copyDiagnostics() }
        }

        diagnosticsText = label(latestDiagnostics.asText()).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 14f
            setTextColor(0xFFBFD7EA.toInt())
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Tap your computer to connect:").apply { textSize = 18f }, matchWrap())
            addView(deviceList, matchWrap())
            addView(makeVisibleButton, matchWrap())
            addView(refreshButton, matchWrap())
            addView(label("New device? On your computer, open Bluetooth settings, add a new device, and choose this phone. The keyboard opens by itself once connected."), matchWrap())
            addView(label("Troubleshooting").apply { textSize = 18f }, matchWrap())
            addView(resetHidButton, matchWrap())
            addView(diagnosticsText, matchWrap())
            addView(copyDiagnosticsButton, matchWrap())
            addCrashReportIfPresent(this)
        }

        val scroll = ScrollView(this).apply {
            addView(scrollContent)
        }

        root.addView(statusText, matchWrap())
        root.addView(detailText, matchWrap())
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        return root
    }

    private fun showConnectionScreen() {
        showingInputScreen = false
        inputStatusText = null
        setContentView(buildConnectionScreen())
        statusText.text = currentState.label
        detailText.text = lastStateMessage
        diagnosticsText.text = latestDiagnostics.asText()
        hidManager.refreshPairedDevices()
    }

    private fun showInputScreen() {
        showingInputScreen = true
        setContentView(buildInputScreen())
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun buildInputScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF101418.toInt())
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                Button(this@MainActivity).apply {
                    text = "Back"
                    textSize = 14f
                    setOnClickListener { showConnectionScreen() }
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            addView(
                Button(this@MainActivity).apply {
                    text = "Disconnect"
                    textSize = 14f
                    setOnClickListener { hidManager.disconnect() }
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }

        val status = label("${currentState.label} — $lastStateMessage").apply {
            textSize = 14f
        }
        inputStatusText = status

        val textInput = EditText(this).apply {
            hint = "Type text to send"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8AA0B4.toInt())
            textSize = 18f
        }

        val sendTextButton = Button(this).apply {
            text = "Send Text"
            textSize = 16f
            setOnClickListener {
                val keyboard = keyboardOrWarn() ?: return@setOnClickListener
                val text = textInput.text.toString()
                if (text.isEmpty()) return@setOnClickListener
                if (keyboard.typeText(text)) {
                    textInput.text.clear()
                } else {
                    Toast.makeText(this@MainActivity, "Some characters could not be sent", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val textRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(textInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sendTextButton, LinearLayout.LayoutParams(-2, -2))
        }

        val specialKeysRow = keyButtonRow(
            "Esc" to KeyboardReportSender.USAGE_ESCAPE,
            "Tab" to KeyboardReportSender.USAGE_TAB,
            "Bksp" to KeyboardReportSender.USAGE_BACKSPACE,
            "Del" to KeyboardReportSender.USAGE_DELETE,
            "Enter" to KeyboardReportSender.USAGE_ENTER
        )

        val arrowsRow = keyButtonRow(
            "◄" to KeyboardReportSender.USAGE_ARROW_LEFT,
            "▲" to KeyboardReportSender.USAGE_ARROW_UP,
            "▼" to KeyboardReportSender.USAGE_ARROW_DOWN,
            "►" to KeyboardReportSender.USAGE_ARROW_RIGHT,
            "Space" to KeyboardReportSender.USAGE_SPACE
        )

        val touchpad = buildTouchpad()

        val mouseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(mouseButton("Left Click") { it.click(MouseReportSender.BUTTON_LEFT) })
            addView(mouseButton("Right Click") { it.click(MouseReportSender.BUTTON_RIGHT) })
            addView(mouseButton("Scroll ▲") { it.scroll(1) })
            addView(mouseButton("Scroll ▼") { it.scroll(-1) })
        }

        root.addView(topRow, matchWrap())
        root.addView(status, matchWrap())
        root.addView(textRow, matchWrap())
        root.addView(specialKeysRow, matchWrap())
        root.addView(arrowsRow, matchWrap())
        root.addView(label("Touchpad: drag to move, tap to click"), matchWrap())
        root.addView(touchpad, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(mouseRow, matchWrap())

        return root
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTouchpad(): View {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                hidManager.mouseSender?.click(MouseReportSender.BUTTON_LEFT)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                val mouse = hidManager.mouseSender ?: return true
                mouse.move(
                    (-distanceX * TOUCHPAD_SENSITIVITY).roundToInt(),
                    (-distanceY * TOUCHPAD_SENSITIVITY).roundToInt()
                )
                return true
            }
        })

        return View(this).apply {
            setBackgroundColor(0xFF1C2530.toInt())
            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }
        }
    }

    private fun keyButtonRow(vararg keys: Pair<String, Int>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            keys.forEach { (labelText, usage) ->
                val button = Button(this@MainActivity).apply {
                    text = labelText
                    textSize = 14f
                    setOnClickListener {
                        keyboardOrWarn()?.pressAndRelease(usage)
                    }
                }
                addView(button, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
    }

    private fun mouseButton(labelText: String, action: (MouseReportSender) -> Unit): Button {
        return Button(this).apply {
            text = labelText
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener {
                val mouse = hidManager.mouseSender
                if (mouse == null) {
                    Toast.makeText(this@MainActivity, "Not connected to a device", Toast.LENGTH_SHORT).show()
                } else {
                    action(mouse)
                }
            }
        }
    }

    private fun keyboardOrWarn(): KeyboardReportSender? {
        val keyboard = hidManager.keyboardSender
        if (keyboard == null) {
            Toast.makeText(this, "Not connected to a device", Toast.LENGTH_SHORT).show()
        }
        return keyboard
    }

    @SuppressLint("SetTextI18n")
    private fun addCrashReportIfPresent(container: LinearLayout) {
        val prefs = getSharedPreferences("bt_keyboard", Context.MODE_PRIVATE)
        val crash = prefs.getString(PREF_LAST_CRASH, null) ?: return

        container.addView(
            label("Last crash (please send this to the developer):").apply {
                textSize = 16f
                setTextColor(0xFFFF9E9E.toInt())
            },
            matchWrap()
        )
        container.addView(
            label(crash).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                setTextColor(0xFFFFC7C7.toInt())
            },
            matchWrap()
        )
        container.addView(
            Button(this).apply {
                text = "Copy Crash Report"
                textSize = 16f
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Crash report", crash))
                    Toast.makeText(this@MainActivity, "Crash report copied", Toast.LENGTH_SHORT).show()
                }
            },
            matchWrap()
        )
        container.addView(
            Button(this).apply {
                text = "Clear Crash Report"
                textSize = 16f
                setOnClickListener {
                    prefs.edit().remove(PREF_LAST_CRASH).apply()
                    showConnectionScreen()
                }
            },
            matchWrap()
        )
    }

    private fun copyDiagnostics() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Bluetooth HID diagnostics", latestDiagnostics.asText())
        )
        Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun label(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 16f
            setTextColor(0xFFE4EDF5.toInt())
            gravity = Gravity.START
            setPadding(0, 14, 0, 14)
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(0, 8, 0, 8)
    }

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1001
        private const val TOUCHPAD_SENSITIVITY = 1.2f
        private const val DISCOVERABLE_DURATION_SECONDS = 300
        private const val DISCOVERABLE_PROMPT_DELAY_MS = 1500L
        private const val PREF_LAST_CRASH = "last_crash"
    }
}
