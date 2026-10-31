package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KeyMode
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch-button fingers and background touchpad fingers together, through [MappingEngine]. */
class TouchFingersTest {
    private val MS = 1_000_000L
    /** One 120 Hz frame. */
    private val FRAME = 8_333_333L
    private val A = 0x04

    /**
     * Buttons "a" (A on its press) and "shift" over a touchpad that only moves the cursor (the
     * Defaults' mappings cleared). The buttons take its Move, so a finger on them aims, unless
     * [aimButton] is off (their Move cleared). [tapA]: "a" types on a still tap instead of its
     * press; [nextShift]: "shift" holds Shift for the next key on a tap.
     */
    private fun engine(
        aimButton: Boolean = true,
        tapA: Boolean = false,
        nextShift: Boolean = false,
        gyro: GyroSettings = GyroSettings(),
        now: () -> Long,
    ): MappingEngine {
        fun m(s: InputSource, t: OutputTarget) = Mapping(s, t)
        val moves = listOf(SurfaceInput.MOTION_X, SurfaceInput.MOTION_Y)
        val p = single(Workspace(
            id = "w", name = "w", gyro = gyro,
            touchControls = listOf("a", "shift").map { TouchControl(it, TouchKind.BUTTON, it, 0.5f, 0.5f) },
            mappings = listOf(
                m(InputSource.Surface(1, SurfaceInput.MOTION_X), OutputTarget.MouseAxis(MouseMotion.MOVE_X)),
                m(InputSource.Surface(1, SurfaceInput.MOTION_Y), OutputTarget.MouseAxis(MouseMotion.MOVE_Y)),
                if (tapA) m(InputSource.Surface(1, SurfaceInput.TAP, "a"), OutputTarget.Key(A))
                else m(InputSource.Touch("a"), OutputTarget.Key(A)),
                if (nextShift) m(InputSource.Surface(1, SurfaceInput.TAP, "shift"), OutputTarget.Key(HidUsage.LEFT_SHIFT, KeyMode.NEXT_KEY))
                else m(InputSource.Touch("shift"), OutputTarget.Key(HidUsage.LEFT_SHIFT)),
            ) + if (aimButton) emptyList() else listOf("a", "shift").flatMap { id ->
                moves.map { m(InputSource.Surface(1, it, id), OutputTarget.NoOutput) }
            },
        )).noTouchpadDefaults()
        return MappingEngine(p, now).apply { touchpadPxPerDp = 3f; touchpadMinPulseNanos = 40 * MS }
    }

    /** Everything the snapshots showed. */
    private class Out {
        var dx = 0; var dy = 0; var wheel = 0; var hwheel = 0; var mods = 0; var buttons = 0
        val keys = HashSet<Int>()
    }

    private fun MappingEngine.snap(o: Out) {
        val s = snapshot()
        o.dx += s.mouse.dx; o.dy += s.mouse.dy; o.wheel += s.mouse.wheel; o.hwheel += s.mouse.hwheel
        o.mods = o.mods or s.keyboard.modifiers; o.buttons = o.buttons or s.mouse.buttons
        o.keys.addAll(s.keyboard.keys)
    }

    // ---- Aiming on the touchpad while a button finger is down ----

    /** A thumb aims on the background at 0.6 dp/ms; another finger presses "a" mid-aim when [press]. */
    private fun aimWhilePressing(aimButton: Boolean, press: Boolean): Out {
        var now = 0L
        val e = engine(aimButton) { now }
        val o = Out()
        e.touchpadDown(7, 1500f, 600f)
        var x = 1500f; var y = 600f
        for (f in 1..12) { now = f * FRAME; x += 15f; y += 3f; e.touchpadMove(7, x, y); e.snap(o) }
        if (press) e.buttonDown(1, "a", 200f, 700f) // held still
        for (f in 13..40) { now = f * FRAME; x += 15f; y += 3f; e.touchpadMove(7, x, y); e.snap(o) }
        return o
    }

    @Test fun aimingKeepsItsMotionWhileAButtonIsHeld() {
        for (aim in listOf(true, false)) {
            val solo = aimWhilePressing(aim, press = false)
            val pressing = aimWhilePressing(aim, press = true)
            assertTrue(solo.dx > 200)
            assertEquals("aimButton=$aim", solo.dx, pressing.dx)
            assertEquals(solo.dy, pressing.dy)
            assertEquals(0, pressing.wheel); assertEquals(0, pressing.hwheel)
            assertEquals(0, pressing.mods) // no Ctrl of a pinch
            assertEquals(0, pressing.buttons) // no right click
        }
    }

    /** The right thumb aims with a button; with [brush] the left thumb brushes the background. */
    private fun aimAfterBrush(brush: Boolean): Pair<Int, Out> {
        var now = 0L
        val e = engine { now }
        val o = Out()
        var fx = 1600f
        e.buttonDown(1, "a", fx, 500f)
        for (i in 1..10) { now += FRAME; fx += 6f; e.buttonMove(1, fx, 500f); e.snap(o) }
        var bx = 400f
        if (brush) e.touchpadDown(7, bx, 500f)
        for (i in 1..12) {
            now += FRAME; fx += 6f; bx -= 3f
            e.buttonMove(1, fx, 500f)
            if (brush) e.touchpadMove(7, bx, 500f)
            e.snap(o)
        }
        if (brush) e.touchpadUp(7)
        val before = o.dx
        for (i in 1..30) { now += FRAME; fx += 6f; e.buttonMove(1, fx, 500f); e.snap(o) }
        return (o.dx - before) to o
    }

    @Test fun aimLivesOnAfterABackgroundBrush() {
        val (solo, _) = aimAfterBrush(brush = false)
        val (after, o) = aimAfterBrush(brush = true)
        assertTrue(solo > 50)
        assertEquals(solo, after)
        assertEquals(0, o.wheel); assertEquals(0, o.mods)
    }

    @Test fun thumbRestingOnAKeyDoesNotTurnTheCursorIntoAScroll() {
        fun run(rest: Boolean): Out {
            var now = 0L
            val e = engine(tapA = true) { now }
            val o = Out()
            if (rest) e.buttonDown(1, "a", 400f, 500f)
            now = 300 * MS; e.snap(o)
            e.touchpadDown(7, 1800f, 300f)
            now += 30 * MS; e.snap(o)
            var y = 300f
            for (i in 1..30) { now += FRAME; y += 6f; e.touchpadMove(7, 1800f, y); e.snap(o) }
            return o
        }
        val solo = run(rest = false)
        val resting = run(rest = true)
        assertTrue(solo.dy > 50)
        assertEquals(solo.dy, resting.dy)
        assertEquals(0, resting.wheel); assertEquals(0, resting.hwheel)
    }

    @Test fun overlappingKeyTapsOnTheKeyboardWorkspaceTypeBothAndNeverScroll() {
        var now = 0L
        val kb = BuiltinProfiles.keyboardWorkspace
        fun idOf(usage: Int) = MappingProfiles.touchId(kb.mappings.first { (it.target as? OutputTarget.Key)?.usage == usage }.source)!!
        val k1 = idOf(0x04); val k2 = idOf(0x16) // A, S
        val e = MappingEngine(single(kb)) { now }.apply { touchpadPxPerDp = 3f; touchpadMinPulseNanos = 40 * MS }
        val o = Out()
        // A fast roll: two fingers overlap in time, each wobbling a few px.
        e.buttonDown(1, k1, 100f, 500f); e.snap(o)
        now = 40 * MS; e.buttonDown(2, k2, 300f, 500f); e.snap(o)
        now = 48 * MS; e.buttonMove(1, 100f, 503f); e.buttonMove(2, 300f, 504f); e.snap(o)
        now = 56 * MS; e.buttonMove(1, 100f, 507f); e.buttonMove(2, 301f, 508f); e.snap(o)
        now = 70 * MS; e.buttonUp(1); e.snap(o)
        now = 100 * MS; e.buttonUp(2); e.snap(o)
        for (i in 1..20) { now = (100 + 10 * i) * MS; e.snap(o) }
        assertEquals(setOf(0x04, 0x16), o.keys)
        assertEquals(0, o.wheel); assertEquals(0, o.hwheel)
    }

    // ---- Event times ----

    /**
     * 240 Hz samples at 0.48 dp/ms. With [batched], pairs of samples are processed back to back
     * (as when a batch is flushed by another finger's down or up), each with its own event time.
     */
    private fun eventTimed(batched: Boolean): Int {
        var now = 0L
        val e = engine { now }
        val o = Out()
        val step = 4_166_667L
        e.touchpadDown(7, 0f, 500f, eventNanos = 0)
        for (f in 1..40) {
            val ev = f * step
            now = if (!batched) ev else if (f % 2 == 1) (f + 1) * step else ev + 50_000L
            e.touchpadMove(7, f * 6f, 500f, eventNanos = ev)
            e.snap(o)
        }
        return o.dx
    }

    @Test fun cursorSpeedIsTimedByTheEvents() {
        val even = eventTimed(batched = false)
        assertTrue(even > 100)
        assertEquals(even, eventTimed(batched = true))
    }

    // ---- System cancels ----

    @Test fun quickButtonTapBetweenTwoSnapshotsIsStillShown() {
        var now = 0L
        val e = engine { now }
        assertTrue(e.snapshot().keyboard.keys.isEmpty())
        // Down and up 10 ms apart, both between two ticks of a 30 Hz pump.
        now = 5 * MS
        e.buttonDown(1, "a", 100f, 100f)
        now = 15 * MS
        e.buttonUp(1)
        now = 33 * MS
        assertEquals(listOf(A), e.snapshot().keyboard.keys)
        // Held for the minimum pulse from its down, then released.
        assertEquals(45 * MS, e.touchpadDeadlineNanos())
        now = 45 * MS
        assertTrue(e.snapshot().keyboard.keys.isEmpty())
        assertTrue(e.pressedTouches().isEmpty())
        assertEquals(Long.MAX_VALUE, e.touchpadDeadlineNanos())
    }

    @Test fun longerButtonPressReleasesAtOnce() {
        var now = 0L
        val e = engine { now }
        e.buttonDown(1, "a", 100f, 100f)
        assertEquals(listOf(A), e.snapshot().keyboard.keys)
        now = 60 * MS
        e.buttonUp(1)
        assertTrue(e.snapshot().keyboard.keys.isEmpty())
    }

    @Test fun cancelledTapButtonFingerNeverTaps() {
        var now = 0L
        val e = engine(tapA = true) { now }
        e.buttonDown(1, "a", 100f, 100f)
        now = 50 * MS
        e.buttonCancel(1)
        e.buttonUp(1) // a late lift of the same finger does nothing
        val o = Out()
        for (i in 1..20) { now = (50 + 20 * i) * MS; e.snap(o) }
        assertTrue(o.keys.isEmpty())
        assertTrue(e.pressedTouches().isEmpty())
        assertEquals(Long.MAX_VALUE, e.touchpadDeadlineNanos())
    }

    @Test fun cancelReleasesAPressedButtonAndEndsItsCursor() {
        var now = 0L
        val plain = engine(aimButton = false) { now }
        plain.buttonDown(1, "a", 100f, 100f)
        assertEquals(listOf(A), plain.snapshot().keyboard.keys)
        plain.buttonCancel(1)
        assertTrue(plain.snapshot().keyboard.keys.isEmpty())

        val through = engine { now }
        through.buttonDown(1, "a", 100f, 100f)
        now = 10 * MS
        through.buttonMove(1, 130f, 100f)
        assertTrue(through.snapshot().mouse.dx > 0)
        through.buttonCancel(1)
        now = 20 * MS
        through.buttonMove(1, 200f, 100f)
        val s = through.snapshot()
        assertEquals(0, s.mouse.dx)
        assertTrue(s.keyboard.keys.isEmpty())
        through.buttonCancel(9) // unknown finger: nothing
    }

    @Test fun cancelledNextKeyModifierDoesNotLatch() {
        var now = 0L
        val e = engine(nextShift = true) { now }
        e.buttonDown(1, "shift", 0f, 0f)
        now = 100 * MS
        e.buttonCancel(1) // a quick lift would latch Shift for the next key
        now = 200 * MS
        e.buttonDown(2, "a", 0f, 0f)
        val s = e.snapshot()
        assertEquals(listOf(A), s.keyboard.keys)
        assertEquals(0, s.keyboard.modifiers)
    }

    @Test fun cancelledTouchpadFingerNeverClicks() {
        var now = 0L
        val e = engine { now }
        e.touchpadDown(7, 500f, 500f)
        now = 60 * MS
        e.touchpadCancel(7)
        e.touchpadUp(7)
        val o = Out()
        for (i in 1..20) { now = (60 + 20 * i) * MS; e.snap(o) }
        assertEquals(0, o.buttons)
    }

    @Test fun moveTrackFollowsAButtonsMoveTravel() {
        var now = 0L
        val e = engine { now }
        val out = FloatArray(2)
        assertEquals(0, engine(aimButton = false) { now }.moveTrack("a", out))
        val mapped = e.moveTrack("a", out)
        assertEquals(3, mapped and 3)
        val x0 = out[0]
        var fx = 1600f
        e.buttonDown(1, "a", fx, 500f)
        for (i in 1..20) { now += FRAME; fx += 6f; e.buttonMove(1, fx, 500f) }
        e.moveTrack("a", out)
        assertTrue(out[0] - x0 > 60f)
        assertEquals(0f, out[1], 0.01f)
    }
}
