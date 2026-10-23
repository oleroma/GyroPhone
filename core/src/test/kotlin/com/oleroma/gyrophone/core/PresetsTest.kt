package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.placeSize
import com.oleroma.gyrophone.core.model.placeX
import com.oleroma.gyrophone.core.model.placeY
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {
    private val presets = BuiltinProfiles.presets

    /** Pad areas (px): landscape phones of common aspect ratios, portrait ones. */
    private val landscape = listOf(2400f to 1080f, 2340f to 1080f, 2200f to 1080f, 2000f to 1000f, 1920f to 1080f)
    private val portrait = listOf(1080f to 2100f, 1080f to 2340f, 1080f to 1900f, 412f to 860f)

    private class Box(val c: TouchControl, val cx: Float, val cy: Float, val half: Float)

    private fun box(c: TouchControl, w: Float, h: Float, inPortrait: Boolean) =
        Box(c, c.placeX(inPortrait) * w, c.placeY(inPortrait) * h, c.placeSize(inPortrait) * min(w, h) / 2f)

    private fun overlaps(a: Box, b: Box): Boolean {
        val reach = a.half + b.half
        if (abs(a.cx - b.cx) >= reach || abs(a.cy - b.cy) >= reach) return false
        val bothCircles = a.c.shape == TouchShape.CIRCLE && b.c.shape == TouchShape.CIRCLE
        return !(bothCircles && hypot(a.cx - b.cx, a.cy - b.cy) >= reach)
    }

    private fun sizes(inPortrait: Boolean) = if (inPortrait) portrait else landscape

    @Test fun threePresetsWithExplicitPlacements() {
        assertEquals(listOf("Controller", "WASD", "Keyboard"), presets.map { it.name })
        assertEquals(3, presets.map { it.id }.toSet().size)
        for (w in presets) {
            assertTrue("${w.name} has no controls", w.touchControls.isNotEmpty())
            for (c in w.touchControls) {
                assertTrue("${w.name}/${c.id} has no portrait placement", c.portrait != null)
                assertTrue(c.kind != TouchKind.FULLSCREEN)
            }
        }
    }

    @Test fun controlsStayInsideThePad() {
        for (w in presets) for (inPortrait in listOf(false, true)) for ((pw, ph) in sizes(inPortrait)) {
            for (c in w.touchControls) {
                val b = box(c, pw, ph, inPortrait)
                assertTrue("${w.name}/${c.id} outside ${pw}x$ph (portrait=$inPortrait)",
                    b.cx - b.half >= 0f && b.cx + b.half <= pw && b.cy - b.half >= 0f && b.cy + b.half <= ph)
            }
        }
    }

    @Test fun controlsDoNotOverlapEachOtherOrTheCorners() {
        val corners = NavCorners.defaults()
        for (w in presets) for (inPortrait in listOf(false, true)) for ((pw, ph) in sizes(inPortrait)) {
            val boxes = w.touchControls.map { box(it, pw, ph, inPortrait) }
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                assertFalse("${w.name}: ${boxes[i].c.id} overlaps ${boxes[j].c.id} in ${pw}x$ph",
                    overlaps(boxes[i], boxes[j]))
            }
            for (corner in corners.map { box(it, pw, ph, inPortrait) }) for (b in boxes) {
                assertFalse("${w.name}: ${b.c.id} overlaps ${corner.c.id} in ${pw}x$ph", overlaps(b, corner))
            }
        }
    }

    @Test fun everyControlIsMappedAndIdsAreUnique() {
        for (w in presets) {
            val ids = w.touchControls.map { it.id }
            assertEquals("${w.name} ids", ids.size, ids.toSet().size)
            assertTrue(ids.none { it == NavCorners.PREV || it == NavCorners.NEXT || it == NavCorners.GYRO })
            for (c in w.touchControls) {
                assertTrue("${w.name}/${c.id} unmapped", w.mappings.any { m ->
                    val s = m.source
                    (s is InputSource.Touch && s.id == c.id) || (s is InputSource.TouchStick && s.id == c.id)
                })
                assertTrue("${w.name}/${c.id} has no label", c.label.isNotBlank())
            }
            // No mapping points at a control that does not exist.
            for (m in w.mappings) {
                val id = when (val s = m.source) {
                    is InputSource.Touch -> s.id
                    is InputSource.TouchStick -> s.id
                    else -> error("non-touch source in workspace mappings: $s")
                }
                assertTrue("${w.name}: mapping for missing $id", id in ids)
            }
        }
    }

    @Test fun controllerKeepsItsIdsAndPassThroughPad() {
        val w = BuiltinProfiles.controllerWorkspace
        assertEquals(
            setOf("ls", "rs", "a", "b", "x", "y", "up", "down", "left", "right", "lb", "lt", "rb", "rt", "view", "menu", "gyro"),
            w.touchControls.map { it.id }.toSet(),
        )
        assertEquals(GyroMode.OFF, w.gyro.mode)
        assertEquals(1, w.pads.size)
        assertEquals(null, w.pads[0].match)
        assertFalse(w.touchpadOverButtons)
    }

    @Test fun wasdStickPressesKeysAndRatchetPausesGyro() {
        val w = BuiltinProfiles.wasdWorkspace
        assertEquals(GyroMode.ALWAYS, w.gyro.mode)
        assertEquals(BuiltinProfiles.kbmPadMappings(), w.pads.single().mappings)
        assertEquals(null, w.pads.single().match)
        assertTrue(w.mappings.any { it.source == InputSource.Touch("ratchet") && it.target == OutputTarget.GyroPause })
        val e = MappingEngine(single(w))
        e.setTouchStick("move", 0f, -1f)
        assertEquals(listOf(0x1A), e.snapshot().keyboard.keys)
        e.setTouchStick("move", -1f, 1f) // diagonal: A + S
        assertEquals(setOf(0x04, 0x16), e.snapshot().keyboard.keys.toSet())
        val wanted = mapOf(
            "shift" to HidUsage.LEFT_SHIFT, "space" to 0x2C, "ctrl" to HidUsage.LEFT_CTRL, "r" to 0x15,
            "e" to 0x08, "f" to 0x09, "q" to 0x14, "k1" to 0x1E, "k4" to 0x21, "tab" to 0x2B, "esc" to 0x29,
        )
        for ((id, usage) in wanted) {
            assertTrue("$id -> $usage", w.mappings.any { it.source == InputSource.Touch(id) && it.target == OutputTarget.Key(usage) })
        }
    }

    @Test fun keyboardIsAFullK380() {
        val w = BuiltinProfiles.keyboardWorkspace
        assertTrue(w.touchpadOverButtons)
        assertTrue(w.stickyModifiers)
        assertTrue(w.pads.isEmpty())
        assertTrue(w.touchControls.all { it.shape == TouchShape.SQUARE && it.kind == TouchKind.BUTTON })
        val usages = w.mappings.map { (it.target as OutputTarget.Key).usage }.toSet()
        val expected = (0x04..0x1D) + (0x1E..0x27) + (0x3A..0x45) + listOf(0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x39) +
            listOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38) + listOf(0x4F, 0x50, 0x51, 0x52) +
            listOf(HidUsage.LEFT_CTRL, HidUsage.LEFT_SHIFT, HidUsage.LEFT_ALT, HidUsage.LEFT_GUI,
                HidUsage.RIGHT_CTRL, HidUsage.RIGHT_SHIFT, HidUsage.RIGHT_ALT)
        assertEquals(expected.toSet(), usages)
        // Landscape: the keys fill the lower part and leave the top strip to the touchpad.
        for (c in w.touchControls) {
            assertTrue(c.id, c.y - c.size / 2f >= 0.33f)
            assertTrue(c.id, c.placeY(true) >= 0.55f)
        }
    }

    @Test fun presetsRoundTripAsJson() {
        for (w in presets) {
            val p = single(w)
            assertEquals(p, ProfileJson.decode(ProfileJson.encode(p)))
        }
        val p = BuiltinProfiles.newProfile("p1", "Game")
        assertEquals(presets, p.workspaces)
        assertEquals(p, ProfileJson.decode(ProfileJson.encode(p)))
    }
}
