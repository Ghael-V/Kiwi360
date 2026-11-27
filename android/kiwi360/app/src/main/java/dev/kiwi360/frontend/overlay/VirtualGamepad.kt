// SPDX-FileCopyrightText: Copyright 2026 Ghael-V (on-screen gamepad from CapyPS5, by its author)
// SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0

package dev.kiwi360.frontend.overlay

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/** XInput button bits, as EmulatorActivity sends them to the emulator. */
object PadButtons {
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_THUMB = 0x0040
    const val RIGHT_THUMB = 0x0080
    const val LEFT_SHOULDER = 0x0100
    const val RIGHT_SHOULDER = 0x0200
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000
}

/** The on-screen gamepad's state, in the same units as a physical pad. */
data class VirtualPadState(
    val buttons: Int = 0,
    /** 0..255 */
    val leftTrigger: Int = 0,
    val rightTrigger: Int = 0,
    /** -32767..32767, up positive. */
    val leftX: Int = 0,
    val leftY: Int = 0,
    val rightX: Int = 0,
    val rightY: Int = 0,
)

/**
 * Whether a physical gamepad is there: a pad plugged in or paired, or the
 * built-in controls of a handheld (they report as a gamepad that isn't
 * virtual). Tracked as devices come and go.
 */
class PhysicalGamepadWatcher(context: Context, private val onChange: (Boolean) -> Unit) {
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = update()
        override fun onInputDeviceRemoved(deviceId: Int) = update()
        override fun onInputDeviceChanged(deviceId: Int) = update()
    }
    var connected = hasPhysicalGamepad()
        private set

    init {
        inputManager?.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
    }

    private fun update() {
        val now = hasPhysicalGamepad()
        if (now != connected) {
            connected = now
            onChange(now)
        }
    }

    fun close() {
        inputManager?.unregisterInputDeviceListener(listener)
    }

    companion object {
        fun hasPhysicalGamepad(): Boolean = InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id) ?: return@any false
            !device.isVirtual &&
                ((device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
        }
    }
}

private val BASE = Color(0x66222A22)
private val BASE_PRESSED = Color(0x9986C232)
private val EDGE = Color(0x44FFFFFF)
private val EDGE_PRESSED = Color(0xFFCFE8A8)

/**
 * On-screen Xbox 360 gamepad: triggers and bumpers, d-pad, both sticks (a quick
 * tap on a stick clicks it), face buttons, Back / Start and the game menu.
 * Several buttons and both sticks can be held at once (Compose gives each
 * finger to the control it landed on).
 */
@Composable
fun VirtualGamepad(
    onState: (VirtualPadState) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    alpha: Float = 0.7f,
) {
    var buttons by remember { mutableIntStateOf(0) }
    val axes = remember { IntArray(6) } // lt, rt, lx, ly, rx, ry
    val emit by rememberUpdatedState(onState)

    fun send() = emit(VirtualPadState(buttons, axes[0], axes[1], axes[2], axes[3], axes[4], axes[5]))
    fun button(mask: Int, pressed: Boolean) {
        buttons = if (pressed) buttons or mask else buttons and mask.inv()
        send()
    }
    // Nothing stays held when the pad goes away (menu opened, hidden).
    DisposableEffect(Unit) {
        onDispose { emit(VirtualPadState()) }
    }

    Box(modifier.fillMaxSize().alpha(alpha).padding(horizontal = 20.dp, vertical = 14.dp)) {
        // Left: LT / LB, d-pad, left stick.
        Column(
            Modifier.align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton("LT", 60.dp, 36.dp, RoundedCornerShape(12.dp)) {
                    axes[0] = if (it) 255 else 0
                    send()
                }
                PadButton("LB", 60.dp, 36.dp, RoundedCornerShape(12.dp)) { button(PadButtons.LEFT_SHOULDER, it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                Stick(onClick = { button(PadButtons.LEFT_THUMB, it) }) { x, y ->
                    axes[2] = x
                    axes[3] = y
                    send()
                }
                DPad(::button)
            }
        }

        // Right: RB / RT, face buttons, right stick.
        Column(
            Modifier.align(Alignment.BottomEnd),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PadButton("RB", 60.dp, 36.dp, RoundedCornerShape(12.dp)) { button(PadButtons.RIGHT_SHOULDER, it) }
                PadButton("RT", 60.dp, 36.dp, RoundedCornerShape(12.dp)) {
                    axes[1] = if (it) 255 else 0
                    send()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                FaceButtons(::button)
                Stick(onClick = { button(PadButtons.RIGHT_THUMB, it) }) { x, y ->
                    axes[4] = x
                    axes[5] = y
                    send()
                }
            }
        }

        // Top centre: Back, the game menu, Start.
        Row(
            Modifier.align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PadButton("BACK", 64.dp, 30.dp, RoundedCornerShape(15.dp), textSize = 10) { button(PadButtons.BACK, it) }
            PadButton("☰", 40.dp, 30.dp, RoundedCornerShape(15.dp), textSize = 15) { if (it) onMenu() }
            PadButton("START", 64.dp, 30.dp, RoundedCornerShape(15.dp), textSize = 10) { button(PadButtons.START, it) }
        }
    }
}

@Composable
private fun DPad(onButton: (Int, Boolean) -> Unit) {
    Box(Modifier.size(128.dp)) {
        Box(Modifier.align(Alignment.TopCenter)) { PadButton("▲", 44.dp, 44.dp) { onButton(PadButtons.DPAD_UP, it) } }
        Box(Modifier.align(Alignment.BottomCenter)) { PadButton("▼", 44.dp, 44.dp) { onButton(PadButtons.DPAD_DOWN, it) } }
        Box(Modifier.align(Alignment.CenterStart)) { PadButton("◀", 44.dp, 44.dp) { onButton(PadButtons.DPAD_LEFT, it) } }
        Box(Modifier.align(Alignment.CenterEnd)) { PadButton("▶", 44.dp, 44.dp) { onButton(PadButtons.DPAD_RIGHT, it) } }
    }
}

@Composable
private fun FaceButtons(onButton: (Int, Boolean) -> Unit) {
    Box(Modifier.size(132.dp)) {
        Box(Modifier.align(Alignment.TopCenter)) {
            PadButton("Y", 46.dp, 46.dp, color = Color(0xFFE6B52C)) { onButton(PadButtons.Y, it) }
        }
        Box(Modifier.align(Alignment.BottomCenter)) {
            PadButton("A", 46.dp, 46.dp, color = Color(0xFF5DB12F)) { onButton(PadButtons.A, it) }
        }
        Box(Modifier.align(Alignment.CenterStart)) {
            PadButton("X", 46.dp, 46.dp, color = Color(0xFF2F7ED0)) { onButton(PadButtons.X, it) }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            PadButton("B", 46.dp, 46.dp, color = Color(0xFFD8463E)) { onButton(PadButtons.B, it) }
        }
    }
}

/**
 * Analog stick: the knob follows the finger inside the base and recentres on
 * release. A short tap without moving clicks the stick (L3 / R3).
 */
@Composable
private fun Stick(size: Dp = 120.dp, onClick: (Boolean) -> Unit, onMove: (Int, Int) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val click by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(size)
            .pointerInput(Unit) {
                val radius = this.size.width / 2f
                val centre = Offset(radius, radius)
                var finger: PointerId? = null
                var downTime = 0L
                var moved = false
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // Follow the finger that grabbed the stick; another one
                        // takes over only after the first lifts.
                        val change = event.changes.firstOrNull { it.id == finger }
                            ?: event.changes.firstOrNull { it.pressed }
                            ?: event.changes.firstOrNull()
                            ?: continue
                        if (change.pressed && finger == null) {
                            downTime = change.uptimeMillis
                            moved = false
                        }
                        finger = if (change.pressed) change.id else null
                        if (change.pressed) {
                            var delta = change.position - centre
                            val length = hypot(delta.x, delta.y)
                            if (length > radius * 0.25f) moved = true
                            if (length > radius) delta *= radius / length
                            knob = delta
                            // Up is positive for the game.
                            onMove(
                                (delta.x / radius * 32767f).roundToInt(),
                                (-delta.y / radius * 32767f).roundToInt(),
                            )
                        } else {
                            knob = Offset.Zero
                            onMove(0, 0)
                            if (!moved && change.uptimeMillis - downTime < 220) {
                                click(true)
                                scope.launch {
                                    delay(90)
                                    click(false)
                                }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f
            drawCircle(BASE, radius)
            drawCircle(EDGE, radius, style = Stroke(width = 2f))
            drawCircle(Color(0xAACFE8A8), radius * 0.42f, center = center + knob)
        }
    }
}

/** Pressed while any finger that went down on it stays down. */
@Composable
private fun PadButton(
    label: String,
    width: Dp,
    height: Dp,
    shape: Shape = CircleShape,
    color: Color = Color.White,
    textSize: Int = 16,
    onPressChanged: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val onChange by rememberUpdatedState(onPressChanged)
    DisposableEffect(Unit) {
        onDispose { if (pressed) onChange(false) }
    }
    Box(
        Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(if (pressed) BASE_PRESSED else BASE)
            .border(1.dp, if (pressed) EDGE_PRESSED else EDGE, shape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.any { it.pressed }
                        if (down != pressed) {
                            pressed = down
                            onChange(down)
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (pressed) Color.White else color, fontWeight = FontWeight.Bold, fontSize = textSize.sp)
    }
}
