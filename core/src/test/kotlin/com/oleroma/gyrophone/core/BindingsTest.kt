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
import com.oleroma.gyrophone.core.model.contains
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
        val p = BuiltinProfiles.virtualGamepad
        assertEquals(p, json.decodeFromString<Profile>(json.encodeToString(p)))
    }
}
