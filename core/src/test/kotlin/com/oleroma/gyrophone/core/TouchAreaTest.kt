package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchAreaKind
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.isTouch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** On touch, Outside and Zone mappings ([InputSource.TouchArea]) of touch controls. */
class TouchAreaTest {
    private val MS = 1_000_000L
    private val H = 0x0B; private val Z = 0x1D; private val O = 0x12

    private var t = 0L

    private fun engine(): MappingEngine {
        val stick = TouchControl("s", TouchKind.STICK, "", 0.3f, 0.5f)
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.7f, 0.5f)
        val p = single(Workspace(
            id = "w", name = "w", touchControls = listOf(stick, b),
            mappings = listOf(
                Mapping(InputSource.TouchArea("s", TouchAreaKind.HELD), OutputTarget.Key(H)),
                Mapping(InputSource.TouchArea("b", TouchAreaKind.ZONE), OutputTarget.Key(Z)),
                Mapping(InputSource.TouchArea("b", TouchAreaKind.OUTSIDE), OutputTarget.Key(O)),
            ),
        ))
        return MappingEngine(p) { t }.apply { touchpadMinPulseNanos = 10 * MS }
    }

    private fun MappingEngine.keys(): Set<Int> = snapshot().keyboard.keys.toSet()

    @Test
    fun heldWhileSet() {
        val e = engine()
        e.setTouchArea("s", TouchAreaKind.HELD, true)
        assertEquals(setOf(H), e.keys())
        e.setTouchArea("b", TouchAreaKind.ZONE, true)
        assertEquals(setOf(H, Z), e.keys())
        assertTrue("b" in e.pressedTouches())
        t += 50 * MS
        e.setTouchArea("s", TouchAreaKind.HELD, false)
        e.setTouchArea("b", TouchAreaKind.ZONE, false)
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun quickPassIsStretchedToAPulse() {
        val e = engine()
        e.keys()
        e.setTouchArea("b", TouchAreaKind.ZONE, true)
        t += 2 * MS
        e.setTouchArea("b", TouchAreaKind.ZONE, false)
        assertEquals(setOf(Z), e.keys()) // let go before any snapshot: still shown once
        t += 5 * MS
        assertEquals(setOf(Z), e.keys()) // and held for the minimum pulse
        t += 10 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun cancelLetsGoAtOnce() {
        val e = engine()
        e.setTouchArea("b", TouchAreaKind.OUTSIDE, true)
        e.touchAreasCancel()
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun inputsAndSources() {
        val b = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f)
        val labels = Bindings.touchInputs(b).map { it.label }
        assertTrue(labels.containsAll(listOf("Outside", "Zone")))
        assertTrue(InputSource.TouchArea("b", TouchAreaKind.ZONE).isTouch)
    }
}
