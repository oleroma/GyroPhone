package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Placement
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.contains
import com.oleroma.gyrophone.core.model.placeSize
import com.oleroma.gyrophone.core.model.placeX
import com.oleroma.gyrophone.core.model.placeY
import com.oleroma.gyrophone.core.model.placement
import com.oleroma.gyrophone.core.model.withPlacement
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingsTest {
    private val a = BindableInput("A", listOf(InputSource.Touch("a")))
    private fun t(id: String, target: OutputTarget) = Mapping(InputSource.Touch(id), target)
    private val padA = OutputTarget.Pad(GamepadButton.A)
    private val padB = OutputTarget.Pad(GamepadButton.B)

    @Test fun setTargetsReplacesInPlaceAndKeepsOrder() {
        val p = Profile("p", "p", listOf(t("x", padB), t("a", padA), t("y", padB), t("a", OutputTarget.Key(4))))
        val out = Bindings.setTargets(p, a, listOf(OutputTarget.Mouse(MouseButton.LEFT), OutputTarget.Key(5)))
        assertEquals(
            listOf(t("x", padB), t("a", OutputTarget.Mouse(MouseButton.LEFT)), t("a", OutputTarget.Key(5)), t("y", padB)),
            out.mappings,
        )
        val appended = Bindings.setTargets(Profile("q", "q", listOf(t("x", padB))), a, listOf(padA))
        assertEquals(listOf(t("x", padB), t("a", padA)), appended.mappings)
        assertEquals(listOf(OutputTarget.Mouse(MouseButton.LEFT), OutputTarget.Key(5)), Bindings.targets(out, a))
    }

    @Test fun addAndRemoveTarget() {
        var p = Profile("p", "p", emptyList())
        p = Bindings.addTarget(p, a, padA)
        p = Bindings.addTarget(p, a, padA)
        assertEquals(1, p.mappings.size)
        p = Bindings.addTarget(p, a, padB)
        p = Bindings.removeTarget(p, a, padA)
        assertEquals(listOf(padB), Bindings.targets(p, a))
    }

    @Test fun triggerFullAndPositiveAreTheSameSource() {
        val lt = BindableInput(
            "LT",
            listOf(InputSource.Axis(AndroidAxis.LTRIGGER), InputSource.Axis(AndroidAxis.BRAKE), InputSource.Key(104)),
        )
        val kbm = BuiltinProfiles.keyboardMouse
        assertEquals(listOf(OutputTarget.Mouse(MouseButton.RIGHT)), Bindings.targets(kbm, lt))
        val out = Bindings.setTargets(kbm, lt, listOf(OutputTarget.Key(0x2C)))
        assertTrue(out.mappings.none { it.source == InputSource.Axis(AndroidAxis.LTRIGGER, AxisHalf.POSITIVE) })
        assertEquals(listOf(OutputTarget.Key(0x2C)), Bindings.targets(out, lt))
        // A stick half is not merged with the full axis.
        val x = BindableInput("X", listOf(InputSource.Axis(AndroidAxis.X)), analog = true)
        assertEquals(emptyList<OutputTarget>(), Bindings.targets(kbm, x))
    }

    @Test fun removeTouchControlDropsItsMappings() {
        val p = BuiltinProfiles.virtualGamepad
        val out = Bindings.removeTouchControl(p, "ls")
        assertTrue(out.touchControls.none { it.id == "ls" })
        assertTrue(out.mappings.none { (it.source as? InputSource.TouchStick)?.id == "ls" })
        assertEquals(p.mappings.size - 2, out.mappings.size)
        val out2 = Bindings.removeTouchControl(p, "a")
        assertTrue(out2.mappings.none { it.source == InputSource.Touch("a") })
    }

    @Test fun newTouchIdIsUnique() {
        val controls = listOf(
            TouchControl("btn1", TouchKind.BUTTON, "", 0f, 0f),
            TouchControl("btn2", TouchKind.BUTTON, "", 0f, 0f),
        )
        assertEquals("btn3", Bindings.newTouchId(controls, "btn"))
        assertEquals("stick1", Bindings.newTouchId(controls, "stick"))
    }

    @Test fun stickPresetsDriveTheEngine() {
        val stick = TouchControl("s1", TouchKind.STICK, "S", 0.5f, 0.5f)
        val inputs = Bindings.touchInputs(stick)
        var p = Profile("p", "p", emptyList(), touchControls = listOf(stick))
        p = Bindings.applyStickPreset(p, inputs, Bindings.StickPreset.WASD)
        val e = MappingEngine(p)
        e.setTouchStick("s1", 0f, -1f)
        assertEquals(listOf(0x1A), e.snapshot().keyboard.keys)
        e.setTouchStick("s1", 1f, 0f)
        assertEquals(listOf(0x07), e.snapshot().keyboard.keys)

        p = Bindings.applyStickPreset(p, inputs, Bindings.StickPreset.RIGHT_STICK)
        assertEquals(listOf(OutputTarget.PadAxis(GamepadAxis.RX)), Bindings.targets(p, inputs[0]))
        assertEquals(2, p.mappings.size)
        e.profile = p
        e.setTouchStick("s1", -1f, 0f)
        assertEquals(-32767, e.snapshot().gamepad.rx)

        p = Bindings.applyStickPreset(p, inputs, Bindings.StickPreset.NONE)
        assertTrue(p.mappings.isEmpty())
    }

    @Test fun containsCircleVersusSquare() {
        val circle = TouchControl("c", TouchKind.BUTTON, "", 0.5f, 0.5f, 0.2f)
        val square = circle.copy(shape = TouchShape.SQUARE)
        // Screen 2000x1000: centre (1000, 500), half side 100. Corner point (1090, 590).
        assertFalse(circle.contains(1090f, 590f, 2000f, 1000f, slop = 1f))
        assertTrue(square.contains(1090f, 590f, 2000f, 1000f, slop = 1f))
        assertTrue(circle.contains(1095f, 500f, 2000f, 1000f, slop = 1f))
        assertFalse(square.contains(1110f, 500f, 2000f, 1000f, slop = 1f))
        assertTrue(square.contains(1110f, 500f, 2000f, 1000f))
    }

    @Test fun touchControlJson() {
        val json = Json { ignoreUnknownKeys = true }
        val c = TouchControl("a", TouchKind.BUTTON, "A", 0.1f, 0.2f, 0.3f, TouchShape.SQUARE)
        assertEquals(c, json.decodeFromString<TouchControl>(json.encodeToString(c)))
        val old = json.decodeFromString<TouchControl>("""{"id":"a","kind":"BUTTON","label":"A","x":0.1,"y":0.2,"size":0.3}""")
        assertEquals(TouchShape.CIRCLE, old.shape)
        assertEquals(null, old.portrait)
        assertFalse(json.encodeToString(TouchControl("a", TouchKind.BUTTON, "A", 0.1f, 0.2f, 0.3f)).contains("portrait"))
        val withPortrait = c.copy(portrait = Placement(0.3f, 0.4f, 0.2f))
        assertEquals(withPortrait, json.decodeFromString<TouchControl>(json.encodeToString(withPortrait)))
        val p = BuiltinProfiles.virtualGamepad
        assertEquals(p, json.decodeFromString<Profile>(json.encodeToString(p)))
    }

    @Test fun profileJsonWithoutTouchpadKeepsDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        val p = json.decodeFromString<Profile>("""{"id":"p","name":"p","mappings":[]}""")
        assertEquals(TouchpadSettings(), p.touchpad)
        assertFalse(json.encodeToString(p).contains("touchpad"))
        assertFalse(json.encodeToString(BuiltinProfiles.controllerPassthrough).contains("touchpad"))
        val custom = p.copy(touchpad = TouchpadSettings(tapDrag = false, sensitivity = 2f))
        assertEquals(custom, json.decodeFromString<Profile>(json.encodeToString(custom)))
    }

    @Test fun portraitPlacementDerivedAndExplicit() {
        val c = TouchControl("c", TouchKind.BUTTON, "", 0.2f, 0.4f, 0.2f)
        assertEquals(0.2f, c.placeX(true))
        assertEquals(0.45f + 0.4f * 0.55f, c.placeY(true), 1e-6f)
        assertEquals(0.2f * TouchControl.PORTRAIT_SIZE_SCALE, c.placeSize(true), 1e-6f)
        assertEquals(Placement(0.2f, 0.4f, 0.2f), c.placement(false))
        val e = c.copy(portrait = Placement(0.7f, 0.3f, 0.25f))
        assertEquals(Placement(0.7f, 0.3f, 0.25f), e.placement(true))
        assertEquals(Placement(0.2f, 0.4f, 0.2f), e.placement(false))
    }

    @Test fun withPlacementEditsOneOrientation() {
        val c = TouchControl("c", TouchKind.BUTTON, "", 0.2f, 0.4f, 0.2f)
        val p = c.withPlacement(true, 0.5f, 0.5f, 0.3f)
        assertEquals(Triple(0.2f, 0.4f, 0.2f), Triple(p.x, p.y, p.size))
        assertEquals(Placement(0.5f, 0.5f, 0.3f), p.portrait)
        val l = c.withPlacement(false, 0.6f, 0.6f, 0.25f)
        assertEquals(Triple(0.6f, 0.6f, 0.25f), Triple(l.x, l.y, l.size))
        assertEquals(c.placement(true), l.portrait)
        // An explicit portrait placement survives landscape edits.
        assertEquals(p.portrait, p.withPlacement(false, 0.1f, 0.1f, 0.1f).portrait)
    }

    @Test fun containsUsesActiveOrientationAndShortSide() {
        val c = TouchControl("c", TouchKind.BUTTON, "", 0.5f, 0.8f, 0.2f, portrait = Placement(0.5f, 0.25f, 0.2f))
        // 1000x2000 portrait box: centre (500, 500), radius 0.2 * 1000 / 2 = 100.
        assertTrue(c.contains(500f, 500f, 1000f, 2000f, inPortrait = true))
        assertTrue(c.contains(500f, 595f, 1000f, 2000f, slop = 1f, inPortrait = true))
        assertFalse(c.contains(500f, 610f, 1000f, 2000f, slop = 1f, inPortrait = true))
        assertFalse(c.contains(500f, 500f, 1000f, 2000f, inPortrait = false))
    }

    @Test fun builtinPortraitLayoutFitsAndDoesNotOverlap() {
        val controls = BuiltinProfiles.virtualGamepad.touchControls
        assertTrue(controls.all { it.portrait != null })
        for ((w, h) in listOf(412f to 860f, 360f to 700f, 412f to 780f)) {
            val unit = min(w, h)
            fun cx(c: TouchControl) = c.placeX(true) * w
            fun cy(c: TouchControl) = c.placeY(true) * h
            fun half(c: TouchControl) = c.placeSize(true) * unit / 2f
            for (c in controls) {
                assertTrue("${c.id} outside ${w}x$h",
                    cx(c) - half(c) >= 0f && cx(c) + half(c) <= w && cy(c) - half(c) >= 0f && cy(c) + half(c) <= h)
            }
            for (i in controls.indices) for (j in i + 1 until controls.size) {
                val a = controls[i]; val b = controls[j]
                val squaresMeet = kotlin.math.abs(cx(a) - cx(b)) < half(a) + half(b) &&
                    kotlin.math.abs(cy(a) - cy(b)) < half(a) + half(b)
                if (!squaresMeet) continue
                val circlesApart = a.shape == TouchShape.CIRCLE && b.shape == TouchShape.CIRCLE &&
                    hypot(cx(a) - cx(b), cy(a) - cy(b)) >= half(a) + half(b)
                assertTrue("${a.id} overlaps ${b.id} in ${w}x$h", circlesApart)
            }
        }
    }

    @Test fun builtinLandscapeUnchanged() {
        val ls = BuiltinProfiles.virtualGamepadControls.first { it.id == "ls" }
        assertEquals(Triple(0.14f, 0.62f, 0.38f), Triple(ls.x, ls.y, ls.size))
        assertEquals(BuiltinProfiles.virtualGamepadControls,
            BuiltinProfiles.virtualGamepad.touchControls.map { it.copy(portrait = null) })
    }

    @Test fun savedBuiltinCopyWithoutPortraitGetsBuiltinPortrait() {
        // A copy saved before portrait existed: same ids and landscape values, no portrait.
        val old = BuiltinProfiles.virtualGamepadControls
        for ((c, builtin) in old.zip(BuiltinProfiles.virtualGamepad.touchControls)) {
            assertEquals(builtin.portrait, c.placement(true))
        }
        // Moved in landscape by an older version: no longer matches, so derived.
        val moved = old.first { it.id == "a" }.copy(x = 0.5f)
        assertEquals(0.45f + moved.y * 0.55f, moved.placeY(true), 1e-6f)
    }

    @Test fun derivedPortraitStaysInsideTheBox() {
        // Every control moved a little in landscape, so none matches the builtin portrait.
        val derived = BuiltinProfiles.virtualGamepadControls.map { it.copy(id = it.id + "_copy") }
        for ((w, h) in listOf(412f to 860f, 360f to 700f)) {
            val unit = min(w, h)
            for (c in derived) {
                val half = c.placeSize(true) * unit / 2f
                val cx = c.placeX(true) * w
                val cy = c.placeY(true) * h
                assertTrue("${c.id} outside ${w}x$h", cx - half >= -0.01f && cx + half <= w + 0.01f &&
                    cy - half >= 0f && cy + half <= h)
            }
        }
    }
}
