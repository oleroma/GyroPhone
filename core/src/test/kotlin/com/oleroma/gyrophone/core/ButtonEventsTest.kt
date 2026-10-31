package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.ButtonActivators
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.FireAt
import com.oleroma.gyrophone.core.model.Output
import com.oleroma.gyrophone.core.model.Taps
import com.oleroma.gyrophone.core.model.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Press, tap, double tap, long press and release mappings of touch buttons, and the ratchet's axes. */
class ButtonEventsTest {
    private val MS = 1_000_000L
    private val KEY_A = 96 // KEYCODE_BUTTON_A
    private val A = 0x04;
    private val B = 0x05;
    private val C = 0x06;
    private val D = 0x07;
    private val E = 0x08

    private var t = 0L

    private val press = Mapping(InputSource.Touch("b"), OutputTarget.Key(A))
    private val tap = Mapping(InputSource.TouchTap("b"), OutputTarget.Key(B))
    private val double = Mapping(InputSource.TouchDoubleTap("b"), OutputTarget.Key(C))
    private val long = Mapping(InputSource.TouchLongPress("b"), OutputTarget.Key(D))
    private val release = Mapping(InputSource.TouchRelease("b"), OutputTarget.Key(E))

    private fun engine(
        vararg ms: Mapping,
        control: TouchControl = button(),
        ws: (Workspace) -> Workspace = { it }
    ): MappingEngine {
        val p = single(ws(Workspace(id = "w", name = "w", touchControls = listOf(control), mappings = ms.toList())))
        return MappingEngine(p) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
    }

    private fun button() = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f)

    private fun MappingEngine.keys(): Set<Int> = snapshot().keyboard.keys.toSet()

    /** A 50 ms tap, then 20 ms for its pulses to show and end. */
    private fun MappingEngine.tap() {
        buttonDown(1, "b", 10f, 10f); keys(); t += 50 * MS; buttonUp(1); keys(); t += 20 * MS; keys()
    }

    @Test
    fun pressIsInstantAndReleaseFiresOnEveryLift() {
        val e = engine(press, release)
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A), e.keys())
        t += 900 * MS
        e.buttonUp(1)
        assertEquals(setOf(E), e.keys()) // even after a long hold
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun tapFiresOnLiftOnlyWhenShort() {
        val e = engine(press, tap)
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A), e.keys())
        t += 50 * MS
        e.buttonUp(1)
        assertEquals(setOf(B), e.keys()) // no double tap mapped: at once
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
        e.buttonDown(1, "b", 10f, 10f); e.keys()
        t += 600 * MS
        e.buttonUp(1)
        assertEquals(emptySet<Int>(), e.keys()) // past the long press time: not a tap
    }

    @Test
    fun longPressComesAtTheTimeAndHoldsUntilLift() {
        val e = engine(tap, long)
        e.buttonDown(1, "b", 10f, 10f)
        t += 390 * MS
        assertEquals(emptySet<Int>(), e.keys())
        t += 20 * MS
        assertEquals(setOf(D), e.keys())
        t += 1000 * MS
        assertEquals(setOf(D), e.keys())
        e.buttonUp(1)
        assertEquals(emptySet<Int>(), e.keys()) // and no tap
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun tapWaitsOutTheDoubleTapWindow() {
        val e = engine(tap, double)
        e.tap()
        assertEquals(emptySet<Int>(), e.keys())
        t += 200 * MS
        assertEquals(emptySet<Int>(), e.keys()) // 220 ms after the lift: still waiting
        t += 100 * MS
        assertEquals(setOf(B), e.keys())
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun doubleTapIsNeitherASingleTapNorALongPress() {
        val single = Mapping(InputSource.Touch("b"), OutputTarget.Key(B), trigger = Trigger.TAP)
        val held = Mapping(InputSource.Touch("b"), OutputTarget.Key(C), trigger = Trigger.DOUBLE_PRESS)
        val e = engine(press, single, held, long, Mapping(InputSource.Touch("b"), OutputTarget.Key(E), trigger = Trigger.DOUBLE_TAP))
        e.tap()
        t += 100 * MS
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A, C), e.keys()) // the press is raw: it presses every time
        t += 800 * MS
        assertEquals(setOf(A, C), e.keys()) // held: the double press, no long press
        e.buttonUp(1)
        repeat(5) { t += 100 * MS; assertEquals(emptySet<Int>(), e.keys()) } // too long for a double tap
        // A third press starts over.
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A), e.keys())
    }

    @Test
    fun quickDoubleTapStillShows() {
        val e = engine(Mapping(InputSource.Touch("b"), OutputTarget.Key(C), trigger = Trigger.DOUBLE_TAP))
        e.tap()
        t += 100 * MS
        e.buttonDown(1, "b", 10f, 10f); t += 2 * MS; e.buttonUp(1)
        e.keys() // the press shows for its minimum pulse first
        t += 20 * MS
        assertEquals(setOf(C), e.keys()) // then the double tap, once
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun timingsFollowTheWorkspaceUnlessTheButtonSetsItsOwn() {
        val e = engine(long, ws = { it.copy(longPressMs = 200) })
        e.buttonDown(1, "b", 10f, 10f)
        t += 210 * MS
        assertEquals(setOf(D), e.keys())
        e.buttonUp(1); t += 20 * MS; e.keys()

        val own = engine(tap, double, control = button().copy(doubleTapMs = 600), ws = { it.copy(doubleTapMs = 150) })
        own.tap()
        t += 400 * MS
        assertEquals(emptySet<Int>(), own.keys()) // its own 600 ms window, not the workspace's 150
        own.buttonDown(1, "b", 10f, 10f); own.keys(); t += 50 * MS; own.buttonUp(1)
        assertEquals(setOf(C), own.keys())
    }

    @Test
    fun dontWaitTapNeverWaitsAndFiresOnEveryTap() {
        val any = Mapping(InputSource.Touch("b"), OutputTarget.Key(B), trigger = Trigger.TAP_NOW)
        val e = engine(any, Mapping(InputSource.Touch("b"), OutputTarget.Key(C), trigger = Trigger.DOUBLE_TAP))
        e.buttonDown(1, "b", 10f, 10f); e.keys(); t += 50 * MS; e.buttonUp(1)
        assertEquals(setOf(B), e.keys()) // at once, though a double is mapped
        t += 30 * MS; e.keys()
        e.buttonDown(1, "b", 10f, 10f); e.keys(); t += 50 * MS; e.buttonUp(1)
        assertEquals(setOf(B, C), e.keys()) // the second tap is a tap too
    }

    @Test
    fun aDoubleWindowOfZeroTurnsDoubleOff() {
        val e = engine(tap, double, ws = { it.copy(doubleTapMs = 0) })
        e.buttonDown(1, "b", 10f, 10f); e.keys(); t += 50 * MS; e.buttonUp(1)
        assertEquals(setOf(B), e.keys()) // nothing to wait for
        t += 30 * MS; e.keys()
        e.buttonDown(1, "b", 10f, 10f); e.keys(); t += 50 * MS; e.buttonUp(1)
        assertEquals(setOf(B), e.keys())
    }

    @Test
    fun movingPastTheSlopIsNeitherATapNorALongPress() {
        val e = engine(press, tap, long)
        e.buttonDown(1, "b", 10f, 10f)
        e.buttonMove(1, 40f, 10f)
        t += 50 * MS
        e.buttonUp(1)
        assertEquals(emptySet<Int>(), e.keys())
        e.buttonDown(1, "b", 10f, 10f)
        e.buttonMove(1, 40f, 10f)
        t += 600 * MS
        assertEquals(setOf(A), e.keys()) // the press still holds, no long press
    }

    @Test
    fun outputsToggleFireOnceAndStick() {
        val toggle = Mapping(InputSource.Touch("b"), OutputTarget.Key(A), trigger = Trigger(output = Output.TOGGLE))
        val e = engine(toggle)
        e.tap()
        assertEquals(setOf(A), e.keys())
        t += 500 * MS
        assertEquals(setOf(A), e.keys()) // on until the next press
        e.tap()
        assertEquals(emptySet<Int>(), e.keys())

        val both = Mapping(InputSource.Touch("b"), OutputTarget.Key(B), trigger = Trigger(output = Output.FIRE_ONCE, fireAt = FireAt.BOTH))
        val f = engine(both)
        f.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(B), f.keys())
        t += 20 * MS
        assertEquals(emptySet<Int>(), f.keys()) // a pulse
        t += 300 * MS
        f.buttonUp(1)
        assertEquals(setOf(B), f.keys()) // the gap after the first pulse has shown
        t += 20 * MS
        assertEquals(emptySet<Int>(), f.keys())
    }

    private val dev = PadDevice(1, 0x45e, 0x2ea, "pad")

    private fun keyEngine(vararg ms: Mapping): MappingEngine {
        val w = Workspace(id = "w", name = "w", pads = listOf(AttachedPad(id = "p", mappings = ms.toList())))
        return MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
    }

    private fun key(g: ButtonGesture, usage: Int) = Mapping(InputSource.KeyGesture(KEY_A, g), OutputTarget.Key(usage))

    @Test
    fun controllerButtonsHaveTheSameTriggers() {
        val e = keyEngine(
            Mapping(InputSource.Key(KEY_A), OutputTarget.Key(A)),
            key(ButtonGesture.TAP, B), key(ButtonGesture.DOUBLE_TAP, C), key(ButtonGesture.LONG_PRESS, D),
        )
        // Long press.
        e.setKey(dev, KEY_A, true)
        assertEquals(setOf(A), e.keys())
        t += 410 * MS
        assertEquals(setOf(A, D), e.keys())
        e.setKey(dev, KEY_A, false)
        e.keys(); t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
        // Tap: waits out the window (a double is mapped).
        e.setKey(dev, KEY_A, true); e.keys(); t += 50 * MS; e.setKey(dev, KEY_A, false)
        assertEquals(emptySet<Int>(), e.keys())
        t += 310 * MS
        assertEquals(setOf(B), e.keys())
        t += 20 * MS; e.keys()
        // Double tap: on the second lift.
        e.setKey(dev, KEY_A, true); e.keys(); t += 50 * MS; e.setKey(dev, KEY_A, false); e.keys()
        t += 100 * MS
        e.setKey(dev, KEY_A, true)
        assertEquals(setOf(A), e.keys())
        t += 50 * MS
        e.setKey(dev, KEY_A, false)
        assertEquals(setOf(C), e.keys())
        repeat(5) { t += 100 * MS; assertTrue(B !in e.keys()) }
    }

    @Test
    fun aKeyWithOnlyGesturesIsStillConsumed() {
        val e = keyEngine(key(ButtonGesture.TAP, B))
        assertTrue(e.setKey(dev, KEY_A, true))
        e.keys(); t += 50 * MS
        e.setKey(dev, KEY_A, false)
        assertEquals(setOf(B), e.keys()) // no double tap: at once
    }

    @Test
    fun oldTouchpadReleaseTapTimeBecomesATap() {
        val set = Profile(
            id = "s", name = "s",
            workspaces = listOf(
                Workspace(
                    id = "w",
                    name = "w",
                    touchpad = TouchpadSettings(releaseTapMs = 150),
                    mappings = listOf(Mapping(InputSource.Surface(2, SurfaceInput.RELEASE), OutputTarget.Key(E))),
                )
            ),
        )
        val s = ButtonActivators.upgrade(set)
        assertEquals(
            listOf(Mapping(InputSource.Surface(2, SurfaceInput.TAP), OutputTarget.Key(E))),
            s.workspaces.single().mappings
        )
        assertEquals(0, s.workspaces.single().touchpad.releaseTapMs)
    }

    @Test
    fun oldSwitchesBecomeTapAndLongPressMappings() {
        val waited = button().copy(doubleTapWaits = true)
        val timed = TouchControl("r", TouchKind.BUTTON, "", 0.2f, 0.2f, releaseTapMs = 250)
        val set = Profile(
            id = "s", name = "s",
            workspaces = listOf(
                Workspace(
                    id = "w",
                    name = "w",
                    touchControls = listOf(waited, timed),
                    mappings = listOf(press, double, Mapping(InputSource.TouchRelease("r"), OutputTarget.Key(E))),
                )
            ),
        )
        val s = ButtonActivators.upgrade(set)
        val touch = s.workspaces.single().mappings.toSet()
        assertEquals(
            setOf(
                Mapping(InputSource.TouchTap("b"), OutputTarget.Key(A)),
                Mapping(InputSource.TouchLongPress("b"), OutputTarget.Key(A)),
                double,
                Mapping(InputSource.TouchTap("r"), OutputTarget.Key(E)),
            ),
            touch,
        )
        val (b, r) = s.workspaces.single().touchControls
        assertEquals(TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, longPressMs = 200), b)
        assertEquals(250, r.longPressMs)
        assertEquals(0, r.releaseTapMs)
        // Once upgraded, nothing changes.
        assertTrue(ButtonActivators.upgrade(s) === s)
    }

    private fun gyroEngine(gyro: GyroSettings, ratchet: OutputTarget = OutputTarget.GyroPause, touchpadOn: Boolean = true) = MappingEngine(
        single(
            Workspace(
                id = "w", name = "w",
                gyro = gyro,
                touchpad = TouchpadSettings(enabled = touchpadOn),
                mappings = listOf(Mapping(InputSource.Surface(1, SurfaceInput.HOLD), ratchet)),
            )
        )
    )

    private val yaw = MappingEngine.gyroBit(MotionAxis.YAW)
    private val pitch = MappingEngine.gyroBit(MotionAxis.PITCH)
    private val roll = MappingEngine.gyroBit(MotionAxis.ROLL)

    @Test
    fun ratchetOnOneAxis() {
        val e = gyroEngine(GyroSettings(mode = GyroMode.ALWAYS), OutputTarget.GyroPauseAxis(MotionAxis.ROLL))
        assertEquals(MappingEngine.ALL_GYRO, e.gyroAxes)
        e.touchpadDown(1, 10f, 10f); e.snapshot()
        assertEquals(yaw or pitch, e.gyroAxes)
        e.touchpadUp(1)
        val en = gyroEngine(GyroSettings(mode = GyroMode.OFF), OutputTarget.GyroPauseAxis(MotionAxis.PITCH))
        assertEquals(0, en.gyroAxes)
        en.touchpadDown(1, 10f, 10f); en.snapshot()
        assertEquals(pitch, en.gyroAxes)
    }

    @Test
    fun eachAxisHasItsOwnRatchetModeOrTheWorkspaces() {
        // Pitch: ratchet enables; yaw and roll follow the workspace's "ratchet disables".
        val e = gyroEngine(GyroSettings(mode = GyroMode.ALWAYS, pitchMode = GyroMode.OFF))
        assertEquals(yaw or roll, e.gyroAxes)
        e.touchpadDown(1, 10f, 10f); e.snapshot()
        assertEquals(pitch, e.gyroAxes)
        e.touchpadUp(1)
        // Gyro off is the whole gyro's.
        assertEquals(0, gyroEngine(GyroSettings(mode = GyroMode.SENSOR_OFF, pitchMode = GyroMode.ALWAYS)).gyroAxes)
    }

    @Test
    fun touchpadRatchetFollowsTheTouchpad() {
        val e = gyroEngine(GyroSettings(mode = GyroMode.OFF), touchpadOn = false)
        e.touchpadDown(1, 10f, 10f); e.snapshot()
        assertEquals(0, e.gyroAxes)
        e.touchpadUp(1)
    }
}
