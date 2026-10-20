from pathlib import Path

base = Path("upstream")
p = base / "app/src/main/java/com/pgratz/artouchpad/MainActivity.kt"
s = p.read_text()
s = s.replace("import android.view.KeyEvent", "import android.view.InputDevice\nimport android.view.KeyEvent\nimport android.widget.Toast")

needle = """    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        noteInteraction()
        return super.dispatchKeyEvent(event)
    }"""
replacement = """    private var gpX = 0f
    private var gpY = 0f
    private var gpScroll = 0f
    private var gamepadEnabled = true
    private var gamepadHeld = false
    private val gamepadHandler = Handler(Looper.getMainLooper())
    private val gamepadTick = object : Runnable {
        override fun run() {
            if (gamepadEnabled && viewModel.state.value.mouseReady) {
                fun dead(v: Float): Float = if (kotlin.math.abs(v) < 0.16f) 0f else v
                val x = dead(gpX)
                val y = dead(gpY)
                if (x != 0f || y != 0f) {
                    val magnitude = kotlin.math.hypot(x, y).coerceAtMost(1f)
                    val speed = 2f + magnitude * 18f
                    viewModel.mouse.moveMouse(x * speed, y * speed)
                }
                val scroll = dead(gpScroll)
                if (scroll != 0f) viewModel.mouse.scroll(0f, scroll * 4f)
            }
            gamepadHandler.postDelayed(this, 16)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadEnabled &&
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE) {
            gpX = event.getAxisValue(MotionEvent.AXIS_X)
            gpY = event.getAxisValue(MotionEvent.AXIS_Y)
            val device = event.device
            gpScroll = if (device?.getMotionRange(MotionEvent.AXIS_RZ, InputDevice.SOURCE_JOYSTICK) != null)
                event.getAxisValue(MotionEvent.AXIS_RZ)
                else event.getAxisValue(MotionEvent.AXIS_RY)
            noteInteraction()
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        noteInteraction()
        val isGamepad = (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!isGamepad) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                gamepadEnabled = !gamepadEnabled
                gpX = 0f; gpY = 0f; gpScroll = 0f
                if (!gamepadEnabled && gamepadHeld) {
                    viewModel.mouse.mouseUp(); gamepadHeld = false
                }
                Toast.makeText(this,
                    if (gamepadEnabled) "Souris manette active" else "Souris manette desactivee",
                    Toast.LENGTH_SHORT).show()
            }
            return true
        }
        if (!gamepadEnabled) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            if (event.action == KeyEvent.ACTION_DOWN && !gamepadHeld) {
                viewModel.mouse.mouseDown(); gamepadHeld = true
            } else if (event.action == KeyEvent.ACTION_UP && gamepadHeld) {
                viewModel.mouse.mouseUp(); gamepadHeld = false
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> viewModel.performClick()
                KeyEvent.KEYCODE_BUTTON_B -> viewModel.pressKey(KeyEvent.KEYCODE_BACK)
                KeyEvent.KEYCODE_BUTTON_X -> viewModel.performRightClick()
                KeyEvent.KEYCODE_BUTTON_Y -> viewModel.pressKey(KeyEvent.KEYCODE_HOME)
                KeyEvent.KEYCODE_BUTTON_R1 -> viewModel.performDoubleClick()
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_R1 -> true
            else -> super.dispatchKeyEvent(event)
        }
    }"""
if needle not in s:
    raise SystemExit("Upstream MainActivity.kt layout changed: injection failed")
s = s.replace(needle, replacement)
s = s.replace("        super.onResume()\n        viewModel.refresh()",
              "        super.onResume()\n        gamepadHandler.removeCallbacks(gamepadTick)\n        gamepadHandler.post(gamepadTick)\n        viewModel.refresh()")
s = s.replace("        super.onPause()\n        idleHandler.removeCallbacks(dimRunnable)",
              "        super.onPause()\n        gamepadHandler.removeCallbacks(gamepadTick)\n        gpX = 0f; gpY = 0f; gpScroll = 0f\n        if (gamepadHeld) { viewModel.mouse.mouseUp(); gamepadHeld = false }\n        idleHandler.removeCallbacks(dimRunnable)")
p.write_text(s)

controller = base / "app/src/main/java/com/pgratz/artouchpad/ShizukuMouseController.kt"
s = controller.read_text().replace('ComponentName("com.pgratz.artouchpad", MouseService::class.java.name)',
    'ComponentName("fr.externalscreen.gamepadpointer", MouseService::class.java.name)')
controller.write_text(s)

build = base / "app/build.gradle.kts"
s = build.read_text().replace('applicationId = "com.pgratz.artouchpad"',
   'applicationId = "fr.externalscreen.gamepadpointer"')
s = s.replace('versionName = "1.1"', 'versionName = "0.1-gamepad"')
build.write_text(s)

strings = base / "app/src/main/res/values/strings.xml"
s = strings.read_text().replace("AR Touchpad", "Manette souris ecran externe")
strings.write_text(s)

print("Applied generic-gamepad patch to upstream AR Touchpad source.")
