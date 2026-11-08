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
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.TouchpadSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingEngineTest {
    @Test fun controllerButtonsAndSticks() {
        val e = MappingEngine(controllerProfile)
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
        val e = MappingEngine(wasdProfile)
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

    @Test fun touchControlsAndGyroRatchet() {
        val e = MappingEngine(controllerProfile)
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
        val p = profileOf(listOf(Mapping(InputSource.Key(AndroidKey.BUTTON_THUMBR), OutputTarget.GyroToggle)),
            gyro = GyroSettings(mode = GyroMode.HOLD_OR_TOGGLE))
        val e = MappingEngine(p)
        e.setKey(AndroidKey.BUTTON_THUMBR, true); e.setKey(AndroidKey.BUTTON_THUMBR, false)
        assertTrue(e.gyroActive)
        e.setKey(AndroidKey.BUTTON_THUMBR, true)
        assertFalse(e.gyroActive)
    }

    @Test fun blackScreenFiresOncePerPress() {
        val p = profileOf(listOf(Mapping(InputSource.Key(AndroidKey.BUTTON_SELECT), OutputTarget.BlackScreen)))
        val e = MappingEngine(p)
        var fired = 0
        e.onBlackScreen = Runnable { fired++ }
        e.setKey(AndroidKey.BUTTON_SELECT, true)
        e.setKey(AndroidKey.BUTTON_SELECT, false)
        assertEquals(1, fired)
        e.setKey(AndroidKey.BUTTON_SELECT, true)
        assertEquals(2, fired)
    }

    @Test fun levelDisplaysFiresOncePerPress() {
        val p = profileOf(listOf(Mapping(InputSource.Key(AndroidKey.BUTTON_SELECT), OutputTarget.LevelDisplays)))
        val e = MappingEngine(p)
        var fired = 0
        e.onLevelDisplays = Runnable { fired++ }
        e.setKey(AndroidKey.BUTTON_SELECT, true)
        e.setKey(AndroidKey.BUTTON_SELECT, false)
        assertEquals(1, fired)
        e.setKey(AndroidKey.BUTTON_SELECT, true)
        assertEquals(2, fired)
    }

    @Test fun axisSourcesFireEventTargetsOncePerPress() {
        val p = profileOf(listOf(
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
            assertEquals(p, ProfileJson.decode(ProfileJson.encode(p)))
        }
    }

    @Test fun onChangeFiresOncePerMutationOutsideTheLock() {
        val e = MappingEngine(wasdProfile)
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

    @Test fun cameraSinkGetsEveryMouseCountButTheLiveGyros() {
        val e = MappingEngine(single(BuiltinProfiles.controllerWorkspace))
        var got = 0 to 0
        e.cameraSink = { dx, dy -> got = got.first + dx to got.second + dy }
        e.addMouseMotion(5, 6, fromGyro = true)
        e.addMouseMotion(3, -4)
        assertEquals(8, e.snapshot().mouse.dx)
        assertEquals(3 to -4, got)
        e.addMouseMotion(7, 7, fromGyro = true)
        e.snapshot()
        assertEquals(3 to -4, got)
    }

    private val MS = 1_000_000L

    private fun gyroOff(now: () -> Long) =
        MappingEngine(single(BuiltinProfiles.controllerWorkspace.copy(gyro = GyroSettings(mode = GyroMode.OFF))), now)
            .apply { touchpadPxPerDp = 1f }

    @Test fun touchpadClicksWithGyroOff() {
        var now = 0L
        val e = gyroOff { now }
        e.touchpadDown(1, 100f, 100f)
        now = 60 * MS
        e.touchpadUp(1)
        // The tap waits out the Tap time for a double tap (Windows touchpad double-clicks or drags then).
        assertEquals(0, e.snapshot().mouse.buttons and 1)
        now = 370 * MS
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
        val e = MappingEngine(wasdProfile) { now }
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
        assertEquals(0, e.snapshot().mouse.buttons)
        // The second touch moves: a drag at once.
        set(120 * MS); e.touchpadDown(1, 100f, 100f)
        set(140 * MS); e.touchpadMove(1, 150f, 100f)
        assertEquals(1, e.snapshot().mouse.buttons)
        return e
    }

    /** The touchpad's left button, polled every 5 ms from 0 to [untilMs] with [events] (ms) along the way: "ms:buttons" at each change. */
    private fun leftEdges(untilMs: Long, vararg events: Pair<Long, MappingEngine.() -> Unit>): List<String> {
        var now = 0L
        val e = gyroOff { now }
        val out = ArrayList<String>()
        var last = -1
        var i = 0
        var t = 0L
        while (t <= untilMs) {
            now = t * MS
            while (i < events.size && events[i].first <= t) e.(events[i++].second)()
            val b = e.snapshot().mouse.buttons and 1
            if (b != last) out.add("$t:$b").also { last = b }
            t += 5
        }
        return out
    }

    private val down: MappingEngine.() -> Unit = { touchpadDown(1, 100f, 100f) }
    private val up: MappingEngine.() -> Unit = { touchpadUp(1) }

    // Windows touchpad: Tap = Left, Double tap = Left Repeat x2 and Left Held, all within one Tap time (300 ms).

    @Test fun touchpadTapClicksOnceTheTapTimeHasPassed() {
        assertEquals(listOf("0:0", "365:1", "405:0"), leftEdges(800, 0L to down, 60L to up))
    }

    @Test fun touchpadQuickDoubleTapIsTwoClicksAtTheSecondLift() {
        // Each click lasts Hold time (80 ms by default).
        assertEquals(listOf("0:0", "170:1", "250:0", "330:1", "410:0"), leftEdges(800, 0L to down, 60L to up, 120L to down, 170L to up))
    }

    @Test fun touchpadTapThenHoldDragsFromTheTapTime() {
        assertEquals(listOf("0:0", "420:1", "700:0"), leftEdges(1000, 0L to down, 60L to up, 120L to down, 700L to up))
    }

    @Test fun touchpadTapThenMoveDragsAtOnceAndKeepsTheMotion() {
        var now = 0L
        val e = gyroOff { now }
        e.touchpadDown(1, 100f, 100f); now = 60 * MS; e.touchpadUp(1)
        now = 120 * MS; e.touchpadDown(1, 100f, 100f)
        var dx = 0
        for (k in 1..10) {
            now = (120 + k * 10L) * MS; e.touchpadMove(1, 100f + k * 10f, 100f)
            val s = e.snapshot()
            // Held once the finger leaves the tap slop, long before the Tap time.
            if (k >= 4) assertEquals("at ${120 + k * 10} ms", 1, s.mouse.buttons and 1)
            dx += s.mouse.dx
        }
        now = 300 * MS; e.touchpadUp(1)
        now = 340 * MS
        assertEquals(0, e.snapshot().mouse.buttons and 1)
        assertTrue(dx > 0)
    }

    @Test fun touchpadCancelReleasesADrag() {
        var now = 0L
        val e = dragging({ now }) { now = it }
        e.touchpadCancel()
        now = 230 * MS // past the drag's 80 ms Hold time
        assertEquals(0, e.snapshot().mouse.buttons)
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
        e.profile = e.profile.updateWorkspace(0) { it.copy(touchpad = TouchpadSettings(sensitivity = 2f)) }
        now = 150 * MS // L shown since 60 ms, longer than the minimum pulse: released at once
        assertEquals(0, e.snapshot().mouse.buttons)
        e.touchpadMove(1, 300f, 100f) // finger forgotten
        assertEquals(0, e.snapshot().mouse.dx)
    }

    @Test fun onChangeFiresForTouchpad() {
        val e = MappingEngine(controllerProfile)
        var calls = 0
        e.onChange = Runnable { calls++ }
        e.touchpadDown(1, 0f, 0f); e.touchpadMove(1, 50f, 0f); e.touchpadUp(1)
        assertEquals(3, calls)
    }
}
