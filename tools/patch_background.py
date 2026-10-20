from pathlib import Path

base = Path("upstream")
src = base / "app/src/main/java/com/pgratz/artouchpad"
res = base / "app/src/main/res"

service = r'''package com.pgratz.artouchpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * System accessibility service: Android 14+ can capture SOURCE_JOYSTICK MotionEvents
 * regardless of which application is in the foreground. Buttons are received using
 * FLAG_REQUEST_FILTER_KEY_EVENTS. Cursor injections still go through Shizuku/uinput.
 *
 * The service only captures joystick motion while the external display AND Shizuku
 * mouse are available. Otherwise, games continue receiving native controller input.
 */
class GamepadAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val mouse = ShizukuMouseController()
    private val displays by lazy {
        getSystemService(DISPLAY_SERVICE) as DisplayManager
    }
    private var enabled = true
    private var selectHeld = false
    private var dragHeld = false
    private var displayId = -1
    private var motionCaptured: Boolean? = null
    private var leftX = 0f
    private var leftY = 0f
    private var rightY = 0f

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = configure()
        override fun onDisplayRemoved(displayId: Int) = configure()
        override fun onDisplayChanged(displayId: Int) = configure()
    }

    private val connectionTick = object : Runnable {
        override fun run() {
            if (!mouse.isConnected) mouse.bind()
            configure()
            handler.postDelayed(this, 2500)
        }
    }

    private val cursorTick = object : Runnable {
        override fun run() {
            if (ready()) {
                fun dz(value: Float): Float =
                    if (kotlin.math.abs(value) < 0.17f) 0f else value
                val x = dz(leftX)
                val y = dz(leftY)
                if (x != 0f || y != 0f) {
                    val magnitude = kotlin.math.hypot(x, y).coerceAtMost(1f)
                    val speed = 2f + 18f * magnitude
                    mouse.moveMouse(x * speed, y * speed)
                }
                val scroll = dz(rightY)
                if (scroll != 0f) mouse.scroll(0f, scroll * 4f)
            }
            handler.postDelayed(this, 16)
        }
    }

    private fun ready() = enabled && displayId >= 0 && mouse.isConnected

    override fun onServiceConnected() {
        super.onServiceConnected()
        mouse.init { handler.post { configure() } }
        displays.registerDisplayListener(displayListener, handler)
        mouse.bind()
        configure()
        handler.post(connectionTick)
        handler.post(cursorTick)
    }

    private fun configure() {
        val ext = displays.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        val newId = ext?.displayId ?: -1
        if (mouse.isConnected && ext != null && (newId != displayId || displayId < 0)) {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            ext.getMetrics(metrics)
            mouse.setDisplay(ext.displayId, metrics.widthPixels, metrics.heightPixels)
        }
        // Reset after a disconnect so setDisplay is called again when binding resumes.
        displayId = if (mouse.isConnected) newId else -1
        if (!ready()) {
            leftX = 0f
            leftY = 0f
            rightY = 0f
        }
        updateCapture()
    }

    private fun updateCapture() {
        val desired = ready()
        if (motionCaptured == desired) return
        val config = serviceInfo ?: return
        config.flags = config.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        config.setMotionEventSources(if (desired) InputDevice.SOURCE_JOYSTICK else 0)
        serviceInfo = config
        motionCaptured = desired
    }

    override fun onMotionEvent(event: MotionEvent) {
        if (!ready() ||
            (event.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return
        leftX = event.getAxisValue(MotionEvent.AXIS_X)
        leftY = event.getAxisValue(MotionEvent.AXIS_Y)
        val device = event.device
        rightY = when {
            device?.getMotionRange(MotionEvent.AXIS_RZ, InputDevice.SOURCE_JOYSTICK) != null ->
                event.getAxisValue(MotionEvent.AXIS_RZ)
            device?.getMotionRange(MotionEvent.AXIS_RY, InputDevice.SOURCE_JOYSTICK) != null ->
                event.getAxisValue(MotionEvent.AXIS_RY)
            else -> event.getAxisValue(MotionEvent.AXIS_Z)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val fromGamepad =
            (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!fromGamepad) return false
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            selectHeld = event.action == KeyEvent.ACTION_DOWN
            return ready() // SELECT + START toggles while in mouse mode
        }
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START &&
            event.action == KeyEvent.ACTION_DOWN && selectHeld && event.repeatCount == 0) {
            enabled = !enabled
            leftX = 0f; leftY = 0f; rightY = 0f
            if (!enabled && dragHeld) { mouse.mouseUp(); dragHeld = false }
            updateCapture()
            Toast.makeText(this,
                if (enabled) "Souris manette active" else "Mode souris desactive",
                Toast.LENGTH_SHORT).show()
            return true
        }
        if (!ready()) return false

        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            if (event.action == KeyEvent.ACTION_DOWN && !dragHeld) {
                mouse.mouseDown(); dragHeld = true
            } else if (event.action == KeyEvent.ACTION_UP && dragHeld) {
                mouse.mouseUp(); dragHeld = false
            }
            return true
        }

        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 1
            KeyEvent.KEYCODE_BUTTON_B -> 2
            KeyEvent.KEYCODE_BUTTON_X -> 3
            KeyEvent.KEYCODE_BUTTON_Y -> 4
            KeyEvent.KEYCODE_BUTTON_R1 -> 5
            else -> 0
        }
        if (mapped == 0) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (mapped) {
                1 -> mouse.click(0f, 0f)
                2 -> mouse.pressKey(KeyEvent.KEYCODE_BACK)
                3 -> mouse.rightClick(0f, 0f)
                4 -> mouse.pressKey(KeyEvent.KEYCODE_HOME)
                5 -> mouse.doubleClick(0f, 0f)
            }
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    override fun onDestroy() {
        handler.removeCallbacks(connectionTick)
        handler.removeCallbacks(cursorTick)
        displays.unregisterDisplayListener(displayListener)
        if (dragHeld) mouse.mouseUp()
        mouse.destroy()
        super.onDestroy()
    }
}
'''
(src / "GamepadAccessibilityService.kt").write_text(service)

xml_dir = res / "xml"
xml_dir.mkdir(parents=True, exist_ok=True)
(xml_dir / "gamepad_accessibility.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="100"
    android:canRequestFilterKeyEvents="true"
    android:description="@string/gamepad_background_description"
    android:accessibilityFlags="flagRequestFilterKeyEvents" />
''')

manifest = base / "app/src/main/AndroidManifest.xml"
s = manifest.read_text()
marker = "        <!-- Single-activity app;"
assert marker in s, "Manifest changed"
s = s.replace(marker, '''        <!-- Android 14+ service for gamepad input when this app is not in foreground. -->
        <service
            android:name=".GamepadAccessibilityService"
            android:label="Manette souris - arriere-plan"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService"/>
            </intent-filter>
            <meta-data android:name="android.accessibilityservice"
                android:resource="@xml/gamepad_accessibility" />
        </service>

''' + marker)
manifest.write_text(s)

strings = res / "values/strings.xml"
s = strings.read_text().replace("</resources>", '''
    <string name="gamepad_background_description">Controle le pointeur sur l\\'ecran externe avec une manette, meme lorsque l\\'application est reduite. Necessite Shizuku.</string>
</resources>''')
strings.write_text(s)

# Add a direct link to Android Accessibility settings for the one-time permission.
activity = src / "MainActivity.kt"
s = activity.read_text()
s = s.replace("import android.os.Bundle", "import android.content.Intent\\nimport android.provider.Settings\\nimport android.os.Bundle")
s = s.replace("import androidx.compose.foundation.layout.Box",
    "import androidx.compose.foundation.layout.Box\\nimport androidx.compose.foundation.layout.padding")
s = s.replace("import androidx.compose.ui.Modifier",
    "import androidx.compose.ui.Alignment\\nimport androidx.compose.ui.Modifier\\nimport androidx.compose.ui.unit.dp\\nimport androidx.compose.material3.Button\\nimport androidx.compose.material3.Text")
s = s.replace("                    TouchpadScreen(viewModel = viewModel)",
'''                    TouchpadScreen(viewModel = viewModel)
                    Button(
                        onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
                    ) { Text("Activer manette en arriere-plan") }''')
activity.write_text(s)

build = base / "app/build.gradle.kts"
s = build.read_text().replace('versionCode = 2', 'versionCode = 3')
s = s.replace('versionName = "0.1-gamepad"', 'versionName = "0.2-background"')
build.write_text(s)

print("Background joystick and button accessibility service added.")
