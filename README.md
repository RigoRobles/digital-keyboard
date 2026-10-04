# Bluetooth HID Keyboard

Native Android/Kotlin project for turning an Android phone into a Bluetooth HID keyboard and mouse.

This project is intentionally being built in milestones. Milestone 1 proved that the phone can access Android's Bluetooth HID Device profile, register a HID app, list paired devices, and start a connection, with diagnostics to tell whether the phone registered as a HID device and whether the target rejected the keyboard/mouse connection. Milestone 2 adds real keyboard and mouse input: once connected, an input screen offers a text box, special keys, arrow keys, a touchpad, and mouse buttons.

## Milestone 1 files

- `app/src/main/java/com/example/btkeyboard/MainActivity.kt` builds the first connection screen, requests runtime Bluetooth permissions where needed, checks whether Bluetooth is enabled, lists paired devices, and calls the HID manager.
- `app/src/main/java/com/example/btkeyboard/bluetooth/BluetoothHidManager.kt` is where the Bluetooth HID Device profile is requested with `BluetoothAdapter.getProfileProxy`, where HID registration happens with `BluetoothHidDevice.registerApp`, and where every `BluetoothHidDevice.Callback` event is logged.
- `app/src/main/java/com/example/btkeyboard/bluetooth/HidConnectionState.kt` defines the user-visible connection states.
- `app/src/main/java/com/example/btkeyboard/bluetooth/BluetoothDiagnostics.kt` defines the diagnostics text shown in the app and copied by the `Copy Diagnostics` button.
- `app/src/main/java/com/example/btkeyboard/hid/HidConstants.kt` contains the first keyboard and mouse HID report descriptor.

## Milestone 2 files

- `app/src/main/java/com/example/btkeyboard/hid/KeyboardReportSender.kt` sends keyboard input reports (report ID 1): key press/release for any HID usage, plus `typeText` which maps characters to US-layout usages with shift handling.
- `app/src/main/java/com/example/btkeyboard/hid/MouseReportSender.kt` sends mouse input reports (report ID 2): relative movement, left/right/middle click, press/hold, and vertical scroll.
- `BluetoothHidManager` creates both senders when the HID connection reaches `STATE_CONNECTED` and clears them on disconnect, reset, or stop. They are exposed as `keyboardSender` and `mouseSender`.
- `MainActivity` opens the input screen automatically as soon as a device connects: a text box + Send, special keys (Esc, Tab, Backspace, Delete, Enter), arrow keys + Space, a touchpad area (drag to move the cursor, tap to left click), and mouse buttons (left click, right click, scroll up/down). Back returns to the connection screen; Disconnect drops the link.

## Simplified connection flow (earbuds-style)

- On launch the app registers the HID profile, then automatically asks Android to make the phone visible for pairing (5 minutes) unless it is already reconnecting.
- The app remembers the last connected device and reconnects to it automatically the next time it opens.
- Tapping a paired device in the list connects in one step — no separate select/connect buttons.
- The keyboard and touchpad screen appears by itself the moment the connection is established, and the app returns to the connection screen if the link drops.

## How to test milestone 1

1. Open this folder in Android Studio.
2. Let Gradle sync the project.
3. Install the app on the Android 11 phone.
4. Pair the phone with the target device first using Android Bluetooth settings.
5. Open the app.
6. Confirm the status changes from `Waiting for Bluetooth` to `HID registered`.
7. Select the paired target device and tap `Connect Selected Device`.
8. Watch Android Studio Logcat for the `BluetoothHidManager` tag.
9. Tap `Copy Diagnostics` and paste the text into ChatGPT when debugging.

The most important fields are:

- `HID app registered: yes` means the phone successfully entered HID Device mode.
- `Last connect() result: true` means Android accepted the connection request and is waiting for callback state changes.
- `Last raw connection state: 0 (STATE_DISCONNECTED)` after a connect attempt usually means the target rejected or closed the HID keyboard/mouse connection.

## Important Android limitation

`BluetoothHidDevice` is a public Android API, but real support still depends on the phone's Bluetooth stack and vendor build. Some phones expose the API but fail `registerApp`, reject the HID profile, or block connections. Android phones and tablets are often poor targets for this test because they may pair over Bluetooth but reject HID keyboard/mouse connections. Prefer testing against Windows, Linux, Raspberry Pi, or Android TV.

## How to test milestone 2

1. Open the app and accept the visibility prompt, then pair from the computer (or tap an already-paired device in the list).
2. The keyboard and touchpad screen opens by itself once the status reaches `Connected`.
3. Type text in the box and tap `Send Text` — it should appear on the target.
4. Try the special keys, arrow keys, and Space.
5. Drag a finger on the touchpad area — the target cursor should move. Tap for a left click.
6. Try `Left Click`, `Right Click`, and the scroll buttons.
7. Press the system back button to return to the connection screen.

Text typing assumes the target uses a US keyboard layout. Unmappable characters (emoji, accents) are skipped with a toast warning.

## Next milestone

- Full on-screen QWERTY keyboard layout with modifier keys (Ctrl, Alt, Win/Cmd combos).
- Key repeat on long press.
- Smoother touchpad movement (sub-pixel accumulation) and drag (press-and-hold) support.
- Media keys (volume, play/pause) via a consumer-control report.
