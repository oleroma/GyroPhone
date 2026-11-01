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
        e.up(); e.keys() // at 100 ms: held at least to 300, then 50 more
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

    @Test
    fun toggleAlsoEndsOnATapOfTheSameButton() {
        val tap = Mapping(InputSource.KeyGesture(KEY_A, ButtonGesture.TAP), OutputTarget.Key(A),
            options = MapOptions(how = How.TOGGLE, endsOnTap = true))
        val e = keyEngine(tap)
        e.down(); e.keys(); t += 50 * MS; e.up()
        val on = e.hold(100)
        assertTrue(A in on.last()) // the tap turned it on
        e.down()
        assertEquals(emptySet<Int>(), e.keys()) // touching it again ends it
        t += 50 * MS; e.up()
        assertTrue(e.hold(200).none { A in it }) // and that tap does not turn it on again
        e.down(); e.keys(); t += 50 * MS; e.up()
        assertTrue(A in e.hold(100).last()) // the next one does
    }

    @Test
    fun toggleAlsoEndsOnLetGo() {
        val e = keyEngine(
            a(MapOptions(how = How.TOGGLE, endsOnLetGo = true)),
            a(MapOptions(how = How.TOGGLE), usage = C),
            Mapping(InputSource.Key(KEY_B), OutputTarget.LetGo),
        )
        e.down(); e.keys(); e.up(); t += 50 * MS
        assertEquals(setOf(A, C), e.keys())
        e.setKey(dev, KEY_B, true)
        assertEquals(setOf(C), e.keys()) // only the one set to end on it
        e.setKey(dev, KEY_B, false); t += 50 * MS
        assertEquals(setOf(C), e.keys())
    }

    @Test
    fun toggleAlsoEndsAfterItsTimer() {
        val e = keyEngine(a(MapOptions(how = How.TOGGLE, endsAfterMs = 300)))
        e.down(); e.keys(); e.up()
        t += 250 * MS
        assertEquals(setOf(A), e.keys())
        t += 60 * MS
        assertEquals(emptySet<Int>(), e.keys())
        e.down() // turns it on again
        assertEquals(setOf(A), e.keys())
        e.up()
    }

    @Test
    fun stickyShiftEndsAfterTheNextKey() {
        val shift = 0xE1
        val e = keyEngine(
            a(MapOptions(how = How.TOGGLE, endsOnNextInput = true), usage = shift),
            Mapping(InputSource.Key(KEY_B), OutputTarget.Key(B)),
        )
        e.down(); e.keys(); e.up(); t += 50 * MS
        assertEquals(0x02, e.snapshot().keyboard.modifiers) // left shift held
        e.setKey(dev, KEY_B, true)
        val with = e.snapshot().keyboard
        assertEquals(0x02, with.modifiers); assertEquals(listOf(B), with.keys)
        t += 50 * MS
        e.setKey(dev, KEY_B, false)
        val after = e.snapshot().keyboard
        assertEquals(0, after.modifiers) // let go with the key
        assertEquals(emptyList<Int>(), after.keys)
        t += 50 * MS
        assertEquals(0, e.snapshot().keyboard.modifiers)
    }

    @Test
    fun swipeToggleLatchesUntilATap() {
        val e = swipeEngine(Mapping(InputSource.Surface(1, SurfaceInput.HOLD_RIGHT), OutputTarget.Key(A),
            options = MapOptions(how = How.TOGGLE, endsOnTap = true)))
        assertTrue(A in e.swipeRight(40).last())
        e.touchpadUp(1)
        assertTrue(e.hold(200).all { A in it }) // latched
        e.touchpadDown(1, 100f, 100f, t); e.hold(30); e.touchpadUp(1)
        assertTrue(A !in e.hold(100).last()) // a tap lets it go
    }

    @Test
    fun swipeToggleLatchEndsOnLetGo() {
        val w = Workspace(
            id = "w", name = "w",
            mappings = listOf(Mapping(InputSource.Surface(1, SurfaceInput.HOLD_RIGHT), OutputTarget.Key(A),
                options = MapOptions(how = How.TOGGLE, endsOnTap = true, endsOnLetGo = true))),
            pads = listOf(AttachedPad(id = "p", mappings = listOf(Mapping(InputSource.Key(KEY_B), OutputTarget.LetGo)))),
        )
        val e = MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
        e.swipeRight(40); e.touchpadUp(1)
        assertTrue(A in e.hold(100).last())
        e.setKey(dev, KEY_B, true); e.keys(); e.setKey(dev, KEY_B, false)
        assertTrue(A !in e.hold(50).last()) // Let go ended the latch
    }

    @Test
    fun oldStickySettingsBecomeToggles() {
        val next = Mapping(InputSource.Key(KEY_A), OutputTarget.Key(A, com.oleroma.gyrophone.core.model.KeyMode.NEXT_KEY))
        val up = com.oleroma.gyrophone.core.model.ToggleUpgrade.keyMode(next)
        assertEquals(OutputTarget.Key(A), up.target)
        assertEquals(MapOptions(how = How.TOGGLE, endsOnNextInput = true), up.options)
        val dir = Mapping(InputSource.Surface(1, SurfaceInput.HOLD_RIGHT), OutputTarget.Key(A))
        val w = Workspace(id = "w", name = "w", mappings = listOf(dir), touchpad = TouchpadSettings(latch = true, latchReleaseMs = 500))
        val u = com.oleroma.gyrophone.core.model.ToggleUpgrade.latches(w)
        assertEquals(MapOptions(how = How.TOGGLE, endsOnTap = true, endsAfterMs = 500), u.mappings.single().options)
        assertTrue(!u.touchpad.latch)
        assertTrue(com.oleroma.gyrophone.core.model.ToggleUpgrade.latches(u) === u)
    }

    @Test
    fun toggleAlsoEndsOnTheSameKeystroke() {
        val e = keyEngine(
            a(MapOptions(how = How.TOGGLE, endsOnSame = true), usage = C),
            Mapping(InputSource.Key(KEY_B), OutputTarget.Key(C)),
        )
        e.down(); e.keys(); e.up(); t += 50 * MS
        assertEquals(setOf(C), e.keys())
        e.setKey(dev, KEY_B, true)
        assertEquals(setOf(C), e.keys()) // pressed by the other one
        t += 50 * MS; e.setKey(dev, KEY_B, false)
        assertEquals(emptySet<Int>(), e.keys()) // and the toggle ended with that press
        t += 50 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun touchHeldWithLongPressToggleOfTheSameKey() {
        // Crouch at once on every touch; held long it stays on; the next touch ends it.
        val e = keyEngine(
            Mapping(InputSource.Key(KEY_A), OutputTarget.Key(C)),
            Mapping(InputSource.KeyGesture(KEY_A, ButtonGesture.LONG_PRESS), OutputTarget.Key(C),
                options = MapOptions(how = How.TOGGLE, endsOnSame = true)),
        )
        e.down(); assertEquals(setOf(C), e.keys()) // at once
        t += 50 * MS; e.up(); assertEquals(emptySet<Int>(), e.keys()) // a quick touch: just crouch
        t += 500 * MS; e.down(); e.keys()
        t += 1000 * MS; assertEquals(setOf(C), e.keys()) // held long: the toggle came on
        e.up(); t += 50 * MS
        assertEquals(setOf(C), e.keys()) // and stays on after the lift
        t += 500 * MS; e.down(); assertEquals(setOf(C), e.keys()) // the next touch: held
        t += 50 * MS; e.up(); t += 50 * MS
        assertEquals(emptySet<Int>(), e.keys()) // and the toggle ended
    }

    @Test
    fun touchHeldWithDoubleTapToggleOfTheSameKey() {
        val e = keyEngine(
            Mapping(InputSource.Key(KEY_A), OutputTarget.Key(C)),
            Mapping(InputSource.KeyGesture(KEY_A, ButtonGesture.DOUBLE_TAP), OutputTarget.Key(C),
                options = MapOptions(how = How.TOGGLE, endsOnSame = true)),
        )
        e.down(); assertEquals(setOf(C), e.keys())
        t += 50 * MS; e.up(); assertEquals(emptySet<Int>(), e.keys())
        t += 50 * MS; e.down(); assertEquals(setOf(C), e.keys()) // the second touch
        t += 50 * MS; e.up(); t += 50 * MS
        assertEquals(setOf(C), e.keys()) // toggled on
        t += 1000 * MS; e.down(); e.keys(); t += 50 * MS; e.up(); t += 50 * MS
        assertEquals(emptySet<Int>(), e.keys()) // the next touch ends it
    }

    @Test
    fun anotherToggleOfTheSameKeystrokeOnlyEndsIt() {
        val e = keyEngine(
            a(MapOptions(how = How.TOGGLE, endsOnSame = true), usage = C),
            Mapping(InputSource.Key(KEY_B), OutputTarget.Key(C), options = MapOptions(how = How.TOGGLE)),
        )
        e.down(); e.keys(); e.up(); t += 50 * MS
        assertEquals(setOf(C), e.keys())
        e.setKey(dev, KEY_B, true); e.keys(); e.setKey(dev, KEY_B, false)
        assertTrue(e.hold(100).last().isEmpty()) // off: neither stays on
    }

    @Test
    fun swipeZoneToggleEndsOnTheSameKeystroke() {
        // Zone 1: Crouch (C) while held; zone 2: Crouch Toggle (latched) that ends on Crouch elsewhere.
        val w = Workspace(
            id = "w", name = "w",
            mappings = listOf(
                zone(0, Trigger.HOLD, C),
                Mapping(InputSource.Surface(1, SurfaceInput.HOLD_RIGHT, zone = 1), OutputTarget.Key(C),
                    options = MapOptions(how = How.TOGGLE, endsOnTap = true, endsOnSame = true)),
            ),
            pads = listOf(AttachedPad(id = "p", mappings = listOf(Mapping(InputSource.Key(KEY_B), OutputTarget.Key(C))))),
            touchpad = TouchpadSettings(holdZones = listOf(HoldZone(), HoldZone())),
        )
        val e = MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
        val out = ArrayList<Set<Int>>()
        e.touchpadDown(1, 100f, 100f, t)
        e.slide(0, 50, out)
        e.lift(out)
        assertTrue(C in out.last()) // zone 2 latched
        assertTrue(C in e.hold(200).last())
        // Crouch from another button ends it.
        e.setKey(dev, KEY_B, true); e.keys(); t += 30 * MS
        e.setKey(dev, KEY_B, false)
        assertTrue(C !in e.hold(50).last())
        // Again: latch it, then a swipe into zone 1 and a lift there lets it go.
        e.touchpadDown(1, 100f, 100f, t); e.slide(0, 50, out); e.lift(out)
        assertTrue(C in e.hold(100).last())
        e.touchpadDown(1, 100f, 100f, t); e.slide(0, 25, out)
        assertTrue(C in out.last()) // zone 1 crouches
        e.lift(out)
        assertTrue(C !in e.hold(100).last())
    }

    @Test
    fun aProfileWithAnUnknownActionStillReads() {
        val w = Workspace(id = "w", name = "w", mappings = listOf(
            Mapping(InputSource.Key(KEY_A), OutputTarget.Key(A)), Mapping(InputSource.Key(KEY_B), OutputTarget.Key(B)),
        ))
        val text = com.oleroma.gyrophone.core.model.ProfileJson.encode(single(w))
        val newer = text.replaceFirst("\"type\": \"key\"", "\"type\": \"set_level\"")
        assertTrue(newer != text)
        assertTrue(runCatching { com.oleroma.gyrophone.core.model.ProfileJson.decode(newer) }.isFailure)
        val read = com.oleroma.gyrophone.core.model.ProfileJson.decodeLenient(newer)!!
        assertEquals(1, read.dropped)
        assertEquals(1, read.profile.workspaces.single().mappings.size)
    }

    @Test
    fun swipeToggleWithoutTapIsAPlainToggle() {
        val e = swipeEngine(Mapping(InputSource.Surface(1, SurfaceInput.HOLD_RIGHT), OutputTarget.Key(A),
            options = MapOptions(how = How.TOGGLE)))
        assertTrue(A in e.swipeRight(40).last())
        e.touchpadUp(1)
        assertTrue(e.hold(200).all { A in it }) // stays on
        e.touchpadDown(1, 100f, 100f, t); e.hold(30); e.touchpadUp(1)
        assertTrue(A in e.hold(100).last()) // a tap does not end it
        e.swipeRight(40); e.touchpadUp(1)
        assertTrue(A !in e.hold(100).last()) // the next swipe that way does
    }

    @Test
    fun areaTriggers() {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f)
        fun area(t: Trigger, key: Int) =
            Mapping(Triggers.with(InputSource.TouchArea("b", com.oleroma.gyrophone.core.model.TouchAreaKind.ZONE), t)!!, OutputTarget.Key(key))
        val w = Workspace(id = "w", name = "w", touchControls = listOf(b),
            mappings = listOf(area(Trigger.LONG_PRESS, A), area(Trigger.TAP, B), area(Trigger.RELEASE, C)))
        val e = MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
        fun inside(on: Boolean) = e.setTouchArea("b", com.oleroma.gyrophone.core.model.TouchAreaKind.ZONE, on)
        // A quick pass: Tap and Release, no long press.
        inside(true); val quick = e.hold(60); inside(false); quick.addAll(e.hold(60))
        assertTrue(quick.none { A in it })
        assertTrue(quick.any { B in it }); assertTrue(quick.any { C in it })
        t += 500 * MS
        // Staying in: the long press, then only Release on the way out.
        inside(true); val stay = e.hold(1200)
        assertTrue(A in stay.last()); assertTrue(stay.none { B in it || C in it })
        inside(false); val out = e.hold(60)
        assertTrue(out.any { C in it }); assertTrue(out.none { B in it }); assertTrue(A !in out.last())
    }

    @Test
    fun areaLongPressTakesTheButtonsOwnTime() {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, longPressMs = 1500)
        val w = Workspace(id = "w", name = "w", touchControls = listOf(b), mappings = listOf(
            Mapping(Triggers.with(InputSource.TouchArea("b", com.oleroma.gyrophone.core.model.TouchAreaKind.ZONE), Trigger.LONG_PRESS)!!, OutputTarget.Key(A))))
        val e = MappingEngine(single(w)) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
        e.setTouchArea("b", com.oleroma.gyrophone.core.model.TouchAreaKind.ZONE, true)
        assertTrue(e.hold(1200).none { A in it }) // not at the workspace's time
        assertTrue(A in e.hold(500).last()) // at the button's
    }
}
