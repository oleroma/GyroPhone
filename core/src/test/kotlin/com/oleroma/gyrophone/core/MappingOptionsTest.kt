package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.Triggers
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.ZoneKeep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Press trigger, mapping options (toggle, cycle, delays) and Layer back. */
class MappingOptionsTest {
    private val MS = 1_000_000L
    private val KEY_A = 96 // KEYCODE_BUTTON_A
    private val KEY_B = 97 // KEYCODE_BUTTON_B
    private val A = 0x04
    private val B = 0x05
    private val C = 0x06
    private var t = 0L
    private val dev = PadDevice(1, 0x45e, 0x2ea, "pad")

    private fun keyEngine(vararg ms: Mapping, sets: Boolean = false): MappingEngine {
        val w = Workspace(
            id = "w", name = "w", pads = listOf(AttachedPad(id = "p", mappings = ms.toList())),
            sets = if (sets) listOf(MappingSet(1), MappingSet(2)) else emptyList(),
        )
        return MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
    }

    private fun MappingEngine.keys(): Set<Int> = snapshot().keyboard.keys.toSet()
    private fun a(o: MapOptions?, usage: Int = A) = Mapping(InputSource.Key(KEY_A), OutputTarget.Key(usage), options = o)
    private fun MappingEngine.down() = setKey(dev, KEY_A, true)
    private fun MappingEngine.up() = setKey(dev, KEY_A, false)

    @Test
    fun pressIsAPulseAtEveryPress() {
        val e = keyEngine(Mapping(InputSource.KeyGesture(KEY_A, ButtonGesture.PRESS), OutputTarget.Key(A)))
        e.down()
        assertEquals(setOf(A), e.keys())
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys()) // still held: the pulse is over
        e.up(); e.keys(); t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun pressOnATouchButton() {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f)
        val w = Workspace(
            id = "w", name = "w", touchControls = listOf(b),
            mappings = listOf(Mapping(Triggers.with(InputSource.Touch("b"), Trigger.PRESS)!!, OutputTarget.Key(A))),
        )
        val e = MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A), e.keys())
        t += 30 * MS
        assertEquals(emptySet<Int>(), e.keys())
        e.buttonUp(1); e.keys()
    }

    @Test
    fun toggleTurnsOnAndOffByActivations() {
        val e = keyEngine(a(MapOptions(how = How.TOGGLE)))
        e.down(); assertEquals(setOf(A), e.keys())
        e.up(); t += 100 * MS
        assertEquals(setOf(A), e.keys()) // stays on
        e.down(); assertEquals(emptySet<Int>(), e.keys())
        e.up(); t += 100 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun delaysAndMinimumHold() {
        val e = keyEngine(a(MapOptions(startDelayMs = 100, minHoldMs = 200, endDelayMs = 50)))
        e.down()
        assertEquals(emptySet<Int>(), e.keys())
        t += 100 * MS
        assertEquals(setOf(A), e.keys())
        e.up() // at 100 ms: held at least to 300, then 50 more
        t += 240 * MS
        assertEquals(setOf(A), e.keys())
        t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun cycleTakesTurns() {
        val o = MapOptions(cycle = true)
        val e = keyEngine(a(o, A), a(o, B), a(o, C))
        val seen = (1..4).map {
            e.down(); val k = e.keys(); e.up(); e.keys(); t += 20 * MS; k
        }
        assertEquals(listOf(setOf(A), setOf(B), setOf(C), setOf(A)), seen)
    }

    @Test
    fun toggleWorksOnLayerActions() {
        val e = keyEngine(
            Mapping(InputSource.Key(KEY_A), OutputTarget.Layer(1, override = true, whileHeld = true),
                options = MapOptions(how = How.TOGGLE)),
            sets = true,
        )
        e.down(); e.up()
        assertEquals(listOf(1), e.layers.overrides)
        t += 20 * MS
        e.down(); e.up()
        assertEquals(emptyList<Int>(), e.layers.overrides)
    }

    @Test
    fun layerBackGoesToWhereItCameFrom() {
        val back = Mapping(InputSource.Key(KEY_B), OutputTarget.LayerBack)
        val e = keyEngine(
            Mapping(InputSource.Key(KEY_A), OutputTarget.Layer(2)),
            Mapping(InputSource.Key(KEY_A), OutputTarget.Layer(2), set = 1),
            back, back.copy(set = 1), back.copy(set = 2),
            Mapping(InputSource.Key(KEY_B), OutputTarget.Layer(1), set = 0).copy(source = InputSource.Key(98)),
            sets = true,
        )
        e.setKey(dev, 98, true); e.setKey(dev, 98, false)
        assertEquals(1, e.layers.set)
        e.down(); e.up()
        assertEquals(2, e.layers.set)
        e.setKey(dev, KEY_B, true); e.setKey(dev, KEY_B, false)
        assertEquals(1, e.layers.set) // back to where Layer 2 was entered from
        e.setKey(dev, KEY_B, true); e.setKey(dev, KEY_B, false)
        assertEquals(2, e.layers.set) // pressed again: swaps back
    }

    @Test
    fun triggersRoundTrip() {
        val bases = listOf(InputSource.Key(KEY_A), InputSource.Touch("b"), InputSource.Surface(2, SurfaceInput.HOLD))
        for (b in bases) for (tr in Trigger.entries) {
            val s = Triggers.with(b, tr)!!
            assertEquals(tr, Triggers.of(s))
            assertEquals(b, Triggers.base(s))
        }
        // A swipe direction: its quick swipe, long hold, release and double swipe.
        for (zone in 0..1) {
            val d = InputSource.Surface(1, SurfaceInput.HOLD_RIGHT, "b", zone)
            assertEquals(if (zone == 0) Triggers.OFFERED else listOf(Trigger.HOLD, Trigger.LONG_PRESS, Trigger.RELEASE), Triggers.offered(d))
            for (tr in Triggers.offered(d)) {
                val s = Triggers.with(d, tr)!!
                assertEquals(tr, Triggers.of(s))
                assertEquals(d, Triggers.base(s))
            }
        }
        assertEquals(SurfaceInput.SWIPE_RIGHT, (Triggers.with(InputSource.Surface(1, SurfaceInput.HOLD_RIGHT), Trigger.TAP) as InputSource.Surface).input)
    }

    @Test
    fun onceIsOneShortPress() {
        val e = keyEngine(a(MapOptions(how = How.ONCE)))
        e.down(); assertEquals(setOf(A), e.keys())
        t += 30 * MS
        assertEquals(emptySet<Int>(), e.keys()) // still held: the press is over
        e.up(); t += 30 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun pressBecomesOnTouchOnce() {
        val old = listOf(
            Mapping(InputSource.KeyGesture(KEY_A, ButtonGesture.PRESS), OutputTarget.Key(A)),
            Mapping(InputSource.Surface(1, SurfaceInput.PRESS, "b"), OutputTarget.Key(B)),
            Mapping(InputSource.Key(KEY_B), OutputTarget.Key(C)),
        )
        val up = Triggers.upgrade(old)
        assertEquals(InputSource.Key(KEY_A), up[0].source)
        assertEquals(InputSource.Touch("b"), up[1].source)
        assertEquals(How.ONCE, up[0].options!!.how)
        assertEquals(old[2], up[2])
        assertTrue(Triggers.upgrade(up) === up)
    }

    /** A swipe right on the touchpad: down, then 1 dp steps every 2 ms over [dx] dp. */
    private fun MappingEngine.swipeRight(dx: Int): MutableList<Set<Int>> {
        touchpadDown(1, 100f, 100f, t)
        val out = ArrayList<Set<Int>>()
        for (i in 1..dx) {
            t += 2 * MS; touchpadMove(1, 100f + i, 100f, t); out.add(keys())
        }
        return out
    }

    private fun swipeEngine(vararg ms: Mapping): MappingEngine {
        val w = Workspace(id = "w", name = "w", mappings = ms.toList())
        return MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
    }

    private fun MappingEngine.hold(ms: Long, out: MutableList<Set<Int>> = ArrayList()): MutableList<Set<Int>> {
        val end = t + ms * MS
        while (t < end) {
            out.add(keys()); t += 2 * MS
        }
        return out
    }

    @Test
    fun swipeLongHoldAndRelease() {
        val right = InputSource.Surface(1, SurfaceInput.HOLD_RIGHT)
        val e = swipeEngine(
            Mapping(Triggers.with(right, Trigger.LONG_PRESS)!!, OutputTarget.Key(A)),
            Mapping(Triggers.with(right, Trigger.RELEASE)!!, OutputTarget.Key(B)),
        )
        e.swipeRight(60)
        val early = e.hold(100)
        assertTrue(early.none { A in it || B in it })
        val late = e.hold(800)
        assertTrue(A in late.last()) // held past the long-press time
        e.touchpadUp(1)
        val after = e.hold(40)
        assertTrue(after.any { B in it }) // the release
        assertTrue(A !in after.last())
    }

    /** Swipe → zones (1 finger): zone 1 from 20 dp, zone 2 from 40 dp. */
    private fun zone(z: Int, tr: Trigger, key: Int) =
        Mapping(Triggers.with(InputSource.Surface(1, SurfaceInput.HOLD_RIGHT, zone = z), tr)!!, OutputTarget.Key(key))

    private fun zoneEngine(vararg ms: Mapping, keep: ZoneKeep = ZoneKeep.OFF): MappingEngine {
        val w = Workspace(
            id = "w", name = "w", mappings = ms.toList(),
            touchpad = TouchpadSettings(holdZones = listOf(HoldZone(keep = keep), HoldZone())),
        )
        return MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
    }

    /** Moves the touchpad finger from [from] to [to] dp right of where it landed. */
    private fun MappingEngine.slide(from: Int, to: Int, out: MutableList<Set<Int>>) {
        val step = if (to > from) 1 else -1
        var i = from
        while (i != to) {
            i += step; t += 2 * MS; touchpadMove(1, 100f + i, 100f, t); out.add(keys())
        }
    }

    private fun MappingEngine.lift(out: MutableList<Set<Int>>) {
        touchpadUp(1); hold(40, out)
    }

    @Test
    fun swipeReleaseFiresOnlyInTheZoneLiftedIn() {
        fun run(vararg path: Int, keep: ZoneKeep = ZoneKeep.OFF, extra: List<Mapping> = emptyList()): List<Set<Int>> {
            val e = zoneEngine(zone(0, Trigger.RELEASE, A), zone(1, Trigger.RELEASE, B), *extra.toTypedArray(), keep = keep)
            val seen = ArrayList<Set<Int>>()
            e.touchpadDown(1, 100f, 100f, t)
            for (k in 1 until path.size) e.slide(path[k - 1], path[k], seen)
            e.hold(20, seen)
            val before = seen.size
            e.lift(seen)
            assertTrue(seen.take(before).none { A in it || B in it }) // nothing before the lift
            return seen.drop(before)
        }
        // Lifted in zone 1: only zone 1's.
        run(0, 30).let { assertTrue(it.any { A in it }); assertTrue(it.none { B in it }) }
        // Slid on into zone 2, lifted there: only zone 2's (zone 1's does not fire on the way).
        run(0, 50).let { assertTrue(it.any { B in it }); assertTrue(it.none { A in it }) }
        // Slid back into zone 1, lifted there: zone 1's.
        run(0, 50, 30).let { assertTrue(it.any { A in it }); assertTrue(it.none { B in it }) }
        // Back before the hold distance: nothing.
        run(0, 50, 5).let { assertTrue(it.none { A in it || B in it }) }
        // Zone 1 kept until the lift: still only the zone lifted in.
        run(0, 50, keep = ZoneKeep.UNTIL_LIFT).let { assertTrue(it.any { B in it }); assertTrue(it.none { A in it }) }
    }

    @Test
    fun swipeReleaseBesideAHeldMapping() {
        // Zone 1 holds C and fires A at a lift in it; zone 2 only fires B at a lift.
        val e = zoneEngine(zone(0, Trigger.HOLD, C), zone(0, Trigger.RELEASE, A), zone(1, Trigger.RELEASE, B))
        val seen = ArrayList<Set<Int>>()
        e.touchpadDown(1, 100f, 100f, t)
        e.slide(0, 30, seen); e.hold(20, seen)
        assertTrue(C in seen.last())
        e.slide(30, 50, seen); e.hold(20, seen)
        assertTrue(seen.last().isEmpty()) // zone 2 holds nothing, as an on-release zone did
        val at = seen.size
        e.lift(seen)
        assertTrue(seen.drop(at).any { B in it })
        assertTrue(seen.drop(at).none { A in it })
    }

    @Test
    fun cancelledTouchFiresNoSwipeRelease() {
        val e = zoneEngine(zone(0, Trigger.RELEASE, A), zone(1, Trigger.RELEASE, B))
        val seen = ArrayList<Set<Int>>()
        e.touchpadDown(1, 100f, 100f, t)
        e.slide(0, 30, seen)
        e.touchpadCancel(); e.hold(40, seen)
        assertTrue(seen.none { A in it || B in it })
    }

    @Test
    fun eachActiveRatchetFlipsItsAxes() {
        val lt = listOf(InputSource.Axis(AndroidAxis.LTRIGGER), InputSource.Axis(AndroidAxis.BRAKE), InputSource.Key(AndroidKey.BUTTON_L2))
        val w = Workspace(
            id = "w", name = "w", gyro = GyroSettings(mode = GyroMode.ALWAYS),
            pads = listOf(AttachedPad(id = "p", mappings = listOf(
                Mapping(InputSource.Key(KEY_A), OutputTarget.GyroPause),
                Mapping(InputSource.KeyGesture(KEY_A, ButtonGesture.DOUBLE_TAP), OutputTarget.GyroPause),
                Mapping(InputSource.Key(KEY_B), OutputTarget.GyroPauseAxis(MotionAxis.YAW)),
            ) + lt.map { Mapping(it, OutputTarget.GyroPause) })),
        )
        val e = MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
        val all = MappingEngine.ALL_GYRO
        val yaw = MappingEngine.gyroBit(MotionAxis.YAW)
        assertEquals(all, e.gyroAxes)
        e.down(); e.keys()
        assertEquals(0, e.gyroAxes) // one ratchet: paused
        e.setKey(dev, KEY_B, true); e.keys()
        assertEquals(yaw, e.gyroAxes) // a yaw ratchet on top: yaw back on
        assertEquals(all, e.ratchetsHeld) // still shown as held
        e.setKey(dev, KEY_B, false); e.up(); e.keys(); t += 600 * MS; e.keys()
        // A double tap's ratchet on top of the same button's press: back on.
        e.down(); e.keys(); t += 20 * MS; e.up(); e.keys(); t += 20 * MS
        e.down(); e.keys()
        assertEquals(all, e.gyroAxes)
        e.up(); e.keys(); t += 600 * MS; e.keys()
        // A trigger pull (its axes and key mapped alike) is one ratchet.
        e.setKey(dev, AndroidKey.BUTTON_L2, true); e.setAxis(dev, AndroidAxis.LTRIGGER, 1f); e.setAxis(dev, AndroidAxis.BRAKE, 1f); e.keys()
        assertEquals(0, e.gyroAxes)
    }

    @Test
    fun swipeOnceFiresAtEngage() {
        val right = InputSource.Surface(1, SurfaceInput.HOLD_RIGHT)
        val e = swipeEngine(Mapping(right, OutputTarget.Key(A), options = MapOptions(how = How.ONCE)))
        val held = e.hold(300, e.swipeRight(60))
        assertTrue(held.any { A in it })
        assertTrue(A !in held.last()) // still held: the press is over
        e.touchpadUp(1); e.hold(20)
    }
}
