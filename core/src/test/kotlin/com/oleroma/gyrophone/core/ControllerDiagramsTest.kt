package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.hid.AndroidKeyToHid
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.ControllerDiagrams
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.DiagramStyle
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PhysicalKind
import com.oleroma.gyrophone.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerDiagramsTest {
    @Test fun everyStyleHasEveryStandardControl() {
        for (style in DiagramStyle.entries) {
            val d = ControllerDiagrams.of(style)
            assertEquals(style.name, ControllerDiagrams.standardIds.toSet(), d.controls.map { it.id }.toSet())
            for (c in d.controls) {
                assertTrue("$style ${c.id} inside box", c.x - c.w / 2 >= 0f && c.x + c.w / 2 <= 1f)
                assertTrue("$style ${c.id} inside box", c.y - c.h / 2 >= 0f && c.y + c.h / 2 <= 1f)
            }
            val ls = d.controls.first { it.id == "ls" }
            assertEquals(7, ls.inputs.size)
            assertEquals(4, d.controls.first { it.kind == PhysicalKind.DPAD }.inputs.size)
        }
        assertEquals("Cross", ControllerDiagrams.of(DiagramStyle.PLAYSTATION).controls.first { it.id == "a" }.label)
        assertEquals("B", ControllerDiagrams.of(DiagramStyle.NINTENDO).controls.first { it.id == "a" }.label)
    }

    @Test fun defaultTargetsAreStandardActions() {
        val d = ControllerDiagrams.of(DiagramStyle.XBOX)
        val a = d.controls.first { it.id == "a" }.inputs.single()
        assertEquals(listOf(OutputTarget.Pad(GamepadButton.A)), ControllerDiagrams.defaultTargets(a))
        val lt = d.controls.first { it.id == "lt" }.inputs.single()
        assertEquals(listOf(OutputTarget.PadAxis(GamepadAxis.LT)), ControllerDiagrams.defaultTargets(lt))
        val rsX = d.controls.first { it.id == "rs" }.inputs[0]
        assertEquals(listOf(OutputTarget.PadAxis(GamepadAxis.RX)), ControllerDiagrams.defaultTargets(rsX))
        val up = d.controls.first { it.id == "dpad" }.inputs[0]
        assertEquals(listOf(OutputTarget.Pad(GamepadButton.DPAD_UP)), ControllerDiagrams.defaultTargets(up))
    }

    @Test fun resetControlRestoresStandard() {
        val d = ControllerDiagrams.of(DiagramStyle.XBOX)
        val a = d.controls.first { it.id == "a" }
        val base = Profile("p", "p", KnownLayouts.standardControllerMappings())
        val edited = Bindings.setTargets(base, a.inputs.single(), listOf(OutputTarget.Key(0x2C)))
        val reset = ControllerDiagrams.resetControl(edited, a)
        assertEquals(listOf(OutputTarget.Pad(GamepadButton.A)), Bindings.targets(reset, a.inputs.single()))

        val all = ControllerDiagrams.resetAll(BuiltinProfiles.keyboardMouse, d)
        val e = MappingEngine(all)
        e.setKey(AndroidKey.BUTTON_A, true)
        e.setAxis(AndroidAxis.Y, -1f)
        e.setAxis(AndroidAxis.HAT_X, 1f)
        val s = e.snapshot()
        assertEquals(1 shl GamepadButton.A.bit, s.gamepad.buttons)
        assertEquals(-32767, s.gamepad.ly)
        assertTrue(s.keyboard.keys.isEmpty())
    }

    @Test fun forDeviceFiltersGenericAndAddsExtras() {
        val known = KnownLayouts.identify(0x3537, 0x1000, "GameSir X5 Lite")
        assertEquals(DiagramStyle.GAMESIR_X5, ControllerDiagrams.forDevice(known).style)
        assertEquals(DiagramStyle.XBOX, KnownLayouts.identify(0x1532, 1, "Razer pad")?.diagram)
        val g = ControllerDiagrams.forDevice(
            null,
            supportedKeys = setOf(AndroidKey.BUTTON_A, AndroidKey.BUTTON_B, AndroidKey.BUTTON_1, AndroidKey.BUTTON_1 + 1),
            supportedAxes = setOf(AndroidAxis.X, AndroidAxis.Y, AndroidAxis.HAT_X, AndroidAxis.HAT_Y),
        )
        assertEquals(DiagramStyle.GENERIC, g.style)
        assertEquals(setOf("a", "b", "ls", "dpad", "m1", "m2"), g.controls.map { it.id }.toSet())
        assertEquals(ControllerDiagrams.standardIds.toSet(), ControllerDiagrams.forDevice(null).controls.map { it.id }.toSet())
        val many = ControllerDiagrams.forDevice(null, supportedKeys = (AndroidKey.BUTTON_1 until AndroidKey.BUTTON_1 + 16).toSet())
        val extras = many.controls.filter { it.id.startsWith("m") }
        assertEquals(16, extras.size)
        for (c in many.controls) assertTrue(c.id, c.x - c.w / 2 >= 0f && c.x + c.w / 2 <= 1f)
        for ((a, b) in extras.zipWithNext()) assertTrue(b.x - b.w / 2 >= a.x + a.w / 2)
    }

    @Test fun describeAndKeyTables() {
        assertEquals("Key W", Describe.shortTarget(OutputTarget.Key(0x1A)))
        assertEquals("LT", Describe.shortTarget(OutputTarget.PadAxis(GamepadAxis.LT)))
        assertEquals(Describe.allTargets.size, Describe.allTargets.distinct().size)
        assertTrue(Describe.analogTargets.all { it is OutputTarget.PadAxis })
        assertEquals(OutputTarget.Key(0x04), Describe.targetGroups.first { it.first == "Keyboard" }.second.first())
        assertEquals(0x1A, AndroidKeyToHid.usage(51))
        assertEquals(0x27, AndroidKeyToHid.usage(7))
        assertEquals(0x1E, AndroidKeyToHid.usage(8))
        assertEquals(0x62, AndroidKeyToHid.usage(144))
        assertEquals(0x45, AndroidKeyToHid.usage(142))
        assertNotNull(AndroidKeyToHid.usage(160))
        assertNull(AndroidKeyToHid.usage(AndroidKey.BUTTON_A))
    }
}
