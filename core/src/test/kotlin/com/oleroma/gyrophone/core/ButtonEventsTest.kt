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
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Triggers
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.MappingProfile
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
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
    fun longPressFiresOneShotActionsWhileStillHeld() {
        val long = InputSource.Surface(1, SurfaceInput.LONG_PRESS, "b")
        for (options in listOf(null, MapOptions())) {
            t = 0L
            val e = engine(Mapping(long, OutputTarget.SetOrientation(), options = options))
            var fired = 0
            e.onSetOrientation = { fired++ }
            e.buttonDown(1, "b", 10f, 10f); e.keys()
            repeat(100) { t += 5 * MS; e.keys() } // still, past the long-press time
            assertEquals("options $options", 1, fired)
            e.buttonUp(1); repeat(10) { t += 5 * MS; e.keys() }
            assertEquals("options $options", 1, fired)
        }
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

    /** Key [k]'s presses while polled every 5 ms for [ms]: how many times it went down. */
    private fun MappingEngine.presses(k: Int, ms: Long): Int {
        var n = 0
        var was = false
        repeat((ms / 5).toInt()) {
            val on = k in keys()
            if (on && !was) n++
            was = on; t += 5 * MS
        }
        return n
    }

    @Test
    fun repeatPressesItsCount() {
        val e = engine(tap.copy(options = MapOptions(how = How.REPEAT, repeat = 3)))
        e.buttonDown(1, "b", 10f, 10f); e.keys(); t += 50 * MS; e.buttonUp(1)
        assertEquals(3, e.presses(B, 400))
    }

    @Test
    fun doubleTapWithHeldAndAnotherChipTellsAQuickTapFromAHold() {
        val held = double.copy(target = OutputTarget.Key(A))
        // Quick second tap: the other chip (C, Once), not the Held one.
        val e = engine(tap, double.copy(options = MapOptions(how = How.ONCE)), held)
        e.tap()
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(emptySet<Int>(), e.keys()) // waits for the lift or the Tap time
        t += 50 * MS; e.buttonUp(1)
        assertEquals(setOf(C), e.keys())
        t += 400 * MS; e.keys()
        // Held second touch: the Held chip from the Tap time until the lift, and no C.
        e.tap()
        e.buttonDown(1, "b", 10f, 10f); e.keys()
        t += 290 * MS
        assertEquals(emptySet<Int>(), e.keys())
        t += 20 * MS
        assertEquals(setOf(A), e.keys())
        t += 500 * MS
        assertEquals(setOf(A), e.keys())
        e.buttonUp(1); t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun doubleTapWithOneChipFiresAtTheSecondTouch() {
        val e = engine(double.copy(options = MapOptions()))
        e.tap()
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(C), e.keys())
    }

    @Test
    fun oneWhenHoldsTheSameOutputWithAnotherHowButNeverTwiceAlike() {
        val input = BindableInput("b", listOf(InputSource.TouchDoubleTap("b")))
        val rep = Bindings.Entry(OutputTarget.Key(C), MapOptions(how = How.REPEAT, repeat = 2))
        val held = Bindings.Entry(OutputTarget.Key(C), null)
        val m = Bindings.setEntries(emptyList(), input, listOf(rep, held, held.copy(options = MapOptions())))
        assertEquals(listOf(rep, held), Bindings.entries(m, input))
        assertEquals(listOf(held), Bindings.entries(Bindings.removeEntry(m, input, OutputTarget.Key(C), 0), input))
    }

    @Test
    fun oldTapDragBecomesTapAndDoubleTapRepeatAndHeld() {
        val left = OutputTarget.Mouse(MouseButton.LEFT)
        val out = Triggers.upgrade(listOf(Mapping(InputSource.Surface(1, SurfaceInput.TAP_DRAG), left)))
        assertEquals(
            listOf(
                Mapping(InputSource.Surface(1, SurfaceInput.TAP), left),
                Mapping(InputSource.Surface(1, SurfaceInput.DOUBLE_TAP), left, options = MapOptions(how = How.REPEAT, repeat = 2)),
                Mapping(InputSource.Surface(1, SurfaceInput.DOUBLE_TAP), left),
            ),
            out,
        )
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
        t += 290 * MS // the Tap time is 300 ms
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
    fun doubleTapIsNeitherATapNorALongPress() {
        val e = engine(press, tap, double, long)
        e.tap()
        t += 100 * MS
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A, C), e.keys()) // the press is raw: it presses every time
        t += 800 * MS
        assertEquals(setOf(A, C), e.keys()) // held: still the double tap, no long press
        e.buttonUp(1)
        repeat(5) { t += 100 * MS; assertEquals(emptySet<Int>(), e.keys()) }
        // A third tap starts over.
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A), e.keys())
    }

    @Test
    fun quickDoubleTapStillShows() {
        val e = engine(double)
        e.tap()
        t += 100 * MS
        e.buttonDown(1, "b", 10f, 10f); t += 2 * MS; e.buttonUp(1)
        assertEquals(setOf(C), e.keys()) // let go before any snapshot: shown once
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

        val own = engine(tap, double, control = button().copy(longPressMs = 600), ws = { it.copy(longPressMs = 150) })
        own.tap()
        t += 400 * MS
        assertEquals(emptySet<Int>(), own.keys()) // its own 600 ms window, not the workspace's 150
        own.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(C), own.keys())
    }

    private val dev = PadDevice(1, 0x45e, 0x2ea, "pad")

    private fun keyEngine(vararg ms: Mapping): MappingEngine {
        val w = Workspace(id = "w", name = "w", pads = listOf(AttachedPad(id = "p", mappings = ms.toList())))
        return MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
    }

    private fun key(g: ButtonGesture, usage: Int) = Mapping(InputSource.KeyGesture(KEY_A, g), OutputTarget.Key(usage))

    @Test
    fun controllerButtonsHaveTheSameGestures() {
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
        // Tap: waits out the window (the double tap does something else).
        e.setKey(dev, KEY_A, true); e.keys(); t += 50 * MS; e.setKey(dev, KEY_A, false)
        assertEquals(emptySet<Int>(), e.keys())
        t += 310 * MS
        assertEquals(setOf(B), e.keys())
        t += 20 * MS; e.keys()
        // Double tap.
        e.setKey(dev, KEY_A, true); e.keys(); t += 50 * MS; e.setKey(dev, KEY_A, false); e.keys()
        t += 100 * MS
        e.setKey(dev, KEY_A, true)
        assertEquals(setOf(A, C), e.keys())
        e.setKey(dev, KEY_A, false)
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
    fun tapAndDoubleTapMappedAlikeStayInstant() {
        val e = engine(
            Mapping(InputSource.TouchTap("b"), OutputTarget.Key(B)),
            Mapping(InputSource.TouchDoubleTap("b"), OutputTarget.Key(B))
        )
        e.buttonDown(1, "b", 10f, 10f); e.keys(); t += 50 * MS; e.buttonUp(1)
        assertEquals(setOf(B), e.keys())
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
                    mappingProfileId = "m"
                )
            ),
        )
        val release = Mapping(InputSource.Surface(2, SurfaceInput.RELEASE), OutputTarget.Key(E))
        val (s, ps) = ButtonActivators.upgrade(set, listOf(MappingProfile("m", "m", touchpad = listOf(release))))
        assertEquals(
            listOf(Mapping(InputSource.Surface(2, SurfaceInput.TAP), OutputTarget.Key(E))),
            ps.single().touchpad
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
                    mappingProfileId = "m"
                )
            ),
        )
        val profile = MappingProfile(
            "m", "m",
            touch = listOf(press, double, Mapping(InputSource.TouchRelease("r"), OutputTarget.Key(E))),
        )
        val (s, ps) = ButtonActivators.upgrade(set, listOf(profile))
        val touch = ps.single().touch.toSet()
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
        val again = ButtonActivators.upgrade(s, ps)
        assertTrue(again.first === s && again.second === ps)
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
