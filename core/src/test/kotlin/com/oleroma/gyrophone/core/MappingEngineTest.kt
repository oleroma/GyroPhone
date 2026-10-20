package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.Hat
import com.oleroma.gyrophone.core.hid.HidState
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.TouchpadSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingEngineTest {
    @Test fun controllerButtonsAndSticks() {
        val e = MappingEngine(BuiltinProfiles.controllerPassthrough)
        assertTrue(e.setKey(AndroidKey.BUTTON_A, true))
        e.setAxis(AndroidAxis.X, -1f)
        e.setAxis(AndroidAxis.HAT_Y, -1f)
        e.setAxis(AndroidAxis.RTRIGGER, 0.5f)
        val g = e.snapshot().gamepad
        assertEquals(1 shl GamepadButton.A.bit, g.buttons)
        assertEquals(-32767, g.lx)
        assertEquals(Hat.UP, g.hat)
        assertEquals(511, g.rt)
    }

    @Test fun anyButtonToKeyboardAndMouse() {
        val e = MappingEngine(BuiltinProfiles.keyboardMouse)
        e.setKey(AndroidKey.BUTTON_THUMBL, true) // Shift
        e.setAxis(AndroidAxis.Y, -0.9f) // W
        e.setKey(AndroidKey.BUTTON_R2, true) // left click
        e.setKey(AndroidKey.DPAD_UP, true) // wheel up
        val s = e.snapshot()
        assertEquals(0x02, s.keyboard.modifiers)
        assertEquals(listOf(0x1A), s.keyboard.keys)
        assertEquals(1, s.mouse.buttons)
        assertEquals(120, s.mouse.wheel)
        assertEquals(0, e.snapshot().mouse.wheel) // drained
    }

    @Test fun touchControlsAndGyroHold() {
        val e = MappingEngine(BuiltinProfiles.virtualGamepad)
        assertFalse(e.gyroActive)
        e.setTouch("gyro", true)
        assertTrue(e.gyroActive)
        e.setTouch("lt", true)
        e.setTouchStick("rs", 0.5f, -1f)
        val g = e.snapshot().gamepad
        assertEquals(1023, g.lt)
        assertEquals(16383, g.rx)
        assertEquals(-32767, g.ry)
    }

    @Test fun gyroToggle() {
        val p = Profile("t", "t", listOf(Mapping(InputSource.Key(AndroidKey.BUTTON_THUMBR), OutputTarget.GyroToggle)),
            gyro = GyroSettings(mode = GyroMode.HOLD_OR_TOGGLE))
        val e = MappingEngine(p)
        e.setKey(AndroidKey.BUTTON_THUMBR, true); e.setKey(AndroidKey.BUTTON_THUMBR, false)
        assertTrue(e.gyroActive)
        e.setKey(AndroidKey.BUTTON_THUMBR, true)
        assertFalse(e.gyroActive)
    }

    @Test fun axisSourcesFireEventTargetsOncePerPress() {
        val p = Profile("t", "t", listOf(
            Mapping(InputSource.Axis(AndroidAxis.X, AxisHalf.NEGATIVE), OutputTarget.Wheel(1)),
            Mapping(InputSource.Axis(AndroidAxis.RTRIGGER, AxisHalf.FULL), OutputTarget.GyroToggle),
            Mapping(InputSource.Key(AndroidKey.BUTTON_R2), OutputTarget.GyroToggle),
            Mapping(InputSource.TouchStick("s", StickAxis.Y, AxisHalf.NEGATIVE), OutputTarget.Wheel(-1)),
        ), gyro = GyroSettings(mode = GyroMode.HOLD_OR_TOGGLE))
        val e = MappingEngine(p)
        e.setAxis(AndroidAxis.X, -1f)
        assertEquals(120, e.snapshot().mouse.wheel)
        e.setAxis(AndroidAxis.X, -0.9f)
        assertEquals(0, e.snapshot().mouse.wheel)
        e.setTouchStick("s", 0f, -1f)
        assertEquals(-120, e.snapshot().mouse.wheel)
        // Trigger reported as both axis and key toggles once.
        e.setAxis(AndroidAxis.RTRIGGER, 1f); e.setKey(AndroidKey.BUTTON_R2, true)
        assertTrue(e.gyroActive)
        e.setAxis(AndroidAxis.RTRIGGER, 0f); e.setKey(AndroidKey.BUTTON_R2, false)
        assertTrue(e.gyroActive)
    }

    @Test fun profilesRoundTripAsJson() {
        val json = Json { prettyPrint = true }
        for (p in BuiltinProfiles.all) {
            val text = json.encodeToString(Profile.serializer(), p)
            assertEquals(p, json.decodeFromString(Profile.serializer(), text))
        }
    }

    @Test fun onChangeFiresOncePerMutationOutsideTheLock() {
        val e = MappingEngine(BuiltinProfiles.keyboardMouse)
        var calls = 0
        e.onChange = Runnable {
            calls++
            // Another thread must be able to take the engine lock: the callback runs after it is released.
            var snap: HidState? = null
            val t = Thread { snap = e.snapshot() }
            t.start(); t.join(2000)
            assertFalse("snapshot blocked: onChange ran under the lock", t.isAlive)
            assertTrue(snap != null)
        }
        assertTrue(e.setKey(AndroidKey.BUTTON_R2, true))
        e.setAxis(AndroidAxis.Y, -0.9f)
        e.setTouch("x", true)
        e.setTouchStick("s", 0.1f, 0.2f)
        e.addMouseMotion(3, 4)
        assertEquals(5, calls)
        e.onChange = null
        e.setKey(AndroidKey.BUTTON_R2, false)
        assertEquals(5, calls)
    }

    private val MS = 1_000_000L

    private fun gyroOff(now: () -> Long) =
        MappingEngine(BuiltinProfiles.virtualGamepad.copy(gyro = GyroSettings(mode = GyroMode.OFF)), now)
            .apply { touchpadPxPerDp = 1f }

    @Test fun touchpadClicksWithGyroOff() {
        var now = 0L
        val e = gyroOff { now }
        e.touchpadDown(1, 100f, 100f)
        now = 60 * MS
        e.touchpadUp(1)
        assertEquals(1, e.snapshot().mouse.buttons and 1)
        assertFalse(e.gyroActive)
    }

    @Test fun touchpadMotionBypassesGyroGate() {
        var now = 0L
        val e = gyroOff { now }
        e.touchpadDown(1, 100f, 100f)
        now = 20 * MS
        e.touchpadMove(1, 200f, 100f)
        assertFalse(e.gyroActive)
        assertTrue(e.snapshot().mouse.dx > 0)
        assertEquals(0, e.snapshot().mouse.dx) // drained
    }

    @Test fun touchpadButtonsOrWithMappedMouse() {
        var now = 0L
        val e = MappingEngine(BuiltinProfiles.keyboardMouse) { now }
        e.touchpadPxPerDp = 1f
        e.setKey(AndroidKey.BUTTON_R2, true) // mouse left
        e.touchpadDown(1, 100f, 100f); e.touchpadDown(2, 200f, 100f)
        now = 50 * MS
        e.touchpadUp(1); e.touchpadUp(2)
        assertEquals(0b11, e.snapshot().mouse.buttons)
    }

    private fun dragging(now: () -> Long, set: (Long) -> Unit): MappingEngine {
        val e = gyroOff(now)
        e.touchpadDown(1, 100f, 100f); set(60 * MS); e.touchpadUp(1)
        assertEquals(1, e.snapshot().mouse.buttons)
        set(120 * MS); e.touchpadDown(1, 100f, 100f)
        set(140 * MS); e.touchpadMove(1, 150f, 100f)
        assertEquals(1, e.snapshot().mouse.buttons)
        return e
    }

    @Test fun resetReleasesTouchpad() {
        var now = 0L
        val e = dragging({ now }) { now = it }
        e.reset()
        assertEquals(0, e.snapshot().mouse.buttons)
    }

    @Test fun profileChangeCancelsTouchpad() {
        var now = 0L
        val e = dragging({ now }) { now = it }
        e.profile = e.profile.copy(touchpad = TouchpadSettings(sensitivity = 2f))
        now = 150 * MS // L shown since 60 ms, longer than the minimum pulse: released at once
        assertEquals(0, e.snapshot().mouse.buttons)
        e.touchpadMove(1, 300f, 100f) // finger forgotten
        assertEquals(0, e.snapshot().mouse.dx)
    }

    @Test fun onChangeFiresForTouchpad() {
        val e = MappingEngine(BuiltinProfiles.virtualGamepad)
        var calls = 0
        e.onChange = Runnable { calls++ }
        e.touchpadDown(1, 0f, 0f); e.touchpadMove(1, 50f, 0f); e.touchpadUp(1)
        assertEquals(3, calls)
    }
}
