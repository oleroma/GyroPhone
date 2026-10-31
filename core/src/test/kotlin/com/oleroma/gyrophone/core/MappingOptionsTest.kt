package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.Triggers
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Press trigger, mapping options (toggle, turbo, cycle, delays) and Layer back. */
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
        val e = keyEngine(a(MapOptions(toggle = true)))
        e.down(); assertEquals(setOf(A), e.keys())
        e.up(); t += 100 * MS
        assertEquals(setOf(A), e.keys()) // stays on
        e.down(); assertEquals(emptySet<Int>(), e.keys())
        e.up(); t += 100 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun turboRepeatsWhileHeld() {
        val e = keyEngine(a(MapOptions(turbo = true, turboRate = 10))) // 50 ms on, 50 ms off
        e.down()
        assertEquals(setOf(A), e.keys())
        t += 60 * MS
        assertEquals(emptySet<Int>(), e.keys())
        t += 50 * MS
        assertEquals(setOf(A), e.keys())
        e.up()
        t += 60 * MS
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
                options = MapOptions(toggle = true)),
            sets = true,
        )
        e.down(); e.up()
        assertEquals(listOf(1), e.layers.overrides)
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
    }
}
