package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AxisRange
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.DevicePresets
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.HeldButton
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KeyTiming
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PhysicalDevice
import com.oleroma.gyrophone.core.model.PhysicalLayouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swapping what two physical controls do, and what counts as a remap. */
class DeviceSwapTest {
    private val controls = (PhysicalLayouts.controllerFront.controls + PhysicalLayouts.controllerBack.controls).associateBy { it.id }
    private fun inputs(id: String) = controls.getValue(id).inputs
    private val space = OutputTarget.Key(0x2C)
    private val ctrl = OutputTarget.Key(HidUsage.LEFT_CTRL)

    private fun preset() = DevicePresets.standard("p", "P")

    private fun targets(list: List<Mapping>, source: InputSource, set: Int = 0) =
        list.filter { it.source == source && it.set == set }.map { it.target }

    @Test fun aStandardPresetHasNoRemaps() {
        val p = preset()
        for (c in controls.values) {
            assertTrue(c.id, DevicePresets.remaps(PhysicalDevice.CONTROLLER, p.controller, c.inputs).isEmpty())
        }
        for (c in PhysicalLayouts.phoneControls) {
            assertTrue(c.id, DevicePresets.remaps(PhysicalDevice.PHONE, p.phone, c.inputs).isEmpty())
        }
    }

    @Test fun remapsListEachSetAndInput() {
        val b = InputSource.Key(AndroidKey.BUTTON_B)
        val p = preset().let {
            it.copy(
                controller = it.controller.filterNot { m -> m.source == b } + Mapping(b, space) +
                    Mapping(InputSource.KeyGesture(AndroidKey.BUTTON_B, ButtonGesture.LONG_PRESS), ctrl) +
                    Mapping(b, ctrl, set = 2),
            )
        }
        val r = DevicePresets.remaps(PhysicalDevice.CONTROLLER, p.controller, inputs("b"))
        assertEquals(listOf(0, 0, 2), r.map { it.set })
        assertEquals(listOf(space), r[0].targets)
        assertEquals(ButtonGesture.LONG_PRESS.label, r[1].input)
    }

    @Test fun swappingLtAndBTradesTheirMappings() {
        val b = InputSource.Key(AndroidKey.BUTTON_B)
        val l2 = InputSource.Key(AndroidKey.BUTTON_L2)
        val base = preset().let { it.copy(controller = it.controller + Mapping(b, space, set = 2)) }
        val p = DevicePresets.swap(base, PhysicalDevice.CONTROLLER, "lt", inputs("lt"), "b", inputs("b"))
        assertEquals(listOf(OutputTarget.Pad(GamepadButton.B)), targets(p.controller, l2))
        assertEquals(listOf(space), targets(p.controller, l2, set = 2))
        // Every source of LT's Pull gets B's mapping; B gets what LT's Pull did.
        for (s in inputs("lt")[0].sources) assertTrue(p.controller.any { it.source == s && it.target == OutputTarget.Pad(GamepadButton.B) })
        assertEquals(targets(base.controller, l2), targets(p.controller, b))
        assertTrue(targets(p.controller, b, set = 2).isEmpty())
        // Swapping back gives the same mappings.
        val back = DevicePresets.swap(p, PhysicalDevice.CONTROLLER, "lt", inputs("lt"), "b", inputs("b"))
        assertEquals(base.controller.toSet(), back.controller.toSet())
    }

    @Test fun gyroWhileHeldAndTimingsGoWithTheButton() {
        val gyro = Mapping(InputSource.Gyro(MotionAxis.YAW, HeldButton.GYRO_ID + "CONTROLLER:a"), OutputTarget.MouseAxis(MouseMotion.MOVE_X))
        val base = preset().copy(
            controller = preset().controller + gyro,
            held = listOf(HeldButton("CONTROLLER:a", listOf(InputSource.Key(AndroidKey.BUTTON_A)))),
            timings = listOf(KeyTiming(AndroidKey.BUTTON_A, longPressMs = 700)),
        )
        val p = DevicePresets.swap(base, PhysicalDevice.CONTROLLER, "a", inputs("a"), "x", inputs("x"))
        assertTrue(p.controller.any { (it.source as? InputSource.Gyro)?.id == HeldButton.GYRO_ID + "CONTROLLER:x" })
        assertEquals("CONTROLLER:x", p.held.single().id)
        assertEquals(listOf(InputSource.Key(AndroidKey.BUTTON_X)), p.held.single().sources)
        assertEquals(AndroidKey.BUTTON_X, p.timings.single().keyCode)
    }

    @Test fun theHandlesDrawingHasEveryControlInsideItsBox() {
        val ids = (PhysicalLayouts.controllerFront.controls + PhysicalLayouts.controllerBack.controls).map { it.id }.toSet()
        for (d in listOf(PhysicalLayouts.controllerHandles, PhysicalLayouts.controllerHandlesNarrow)) inside(ids, d)
    }

    private fun inside(ids: Set<String>, d: com.oleroma.gyrophone.core.model.ControllerDiagram) {
        assertEquals(ids, d.controls.map { it.id }.toSet())
        for (c in d.controls) {
            assertTrue(c.id, c.x - c.w / 2 >= -1e-4f && c.x + c.w / 2 <= 1.0001f && c.y - c.h / 2 >= -1e-4f && c.y + c.h / 2 <= 1.0001f)
        }
        // No two controls overlap (touching edges, as the D-pad's keys do, is fine).
        val e = 1e-4f
        for (a in d.controls) for (b in d.controls) if (a.id < b.id) {
            val apart = a.x + a.w / 2 <= b.x - b.w / 2 + e || b.x + b.w / 2 <= a.x - a.w / 2 + e ||
                a.y + a.h / 2 <= b.y - b.h / 2 + e || b.y + b.h / 2 <= a.y - a.h / 2 + e
            assertTrue(a.id + " and " + b.id, apart)
        }
    }

    @Test fun sticksSwapOnlyWithSticks() {
        assertTrue(DevicePresets.canSwap(inputs("ls"), inputs("rs")))
        assertFalse(DevicePresets.canSwap(inputs("ls"), inputs("a")))
        assertTrue(DevicePresets.canSwap(inputs("lt"), inputs("up")))
        val move = PhysicalLayouts.mouseControls.first { it.id == "move" }.inputs
        assertFalse(DevicePresets.canSwap(move, move))
        val base = preset()
        assertEquals(base, DevicePresets.swap(base, PhysicalDevice.CONTROLLER, "ls", inputs("ls"), "a", inputs("a")))
    }

    @Test fun swappingSticksTradesDirectionsAndClicks() {
        val base = preset()
        val p = DevicePresets.swap(base, PhysicalDevice.CONTROLLER, "ls", inputs("ls"), "rs", inputs("rs"))
        val lsClick = InputSource.Key(AndroidKey.BUTTON_THUMBL)
        assertEquals(targets(base.controller, InputSource.Key(AndroidKey.BUTTON_THUMBR)), targets(p.controller, lsClick))
        for ((i, input) in inputs("ls").withIndex()) {
            val rs = inputs("rs")[i]
            assertEquals(
                input.label,
                base.controller.filter { m -> rs.sources.any { it == m.source } }.map { it.target }.toSet(),
                p.controller.filter { m -> input.sources.any { it == m.source } }.map { it.target }.toSet(),
            )
        }
    }

    @Test fun aSticksGyroWhileHeldStaysOnItsClick() {
        val base = preset().copy(held = listOf(HeldButton("CONTROLLER:ls", listOf(InputSource.Key(AndroidKey.BUTTON_THUMBL)))))
        val p = DevicePresets.swap(base, PhysicalDevice.CONTROLLER, "ls", inputs("ls"), "rs", inputs("rs"))
        assertEquals("CONTROLLER:rs", p.held.single().id)
        assertEquals(listOf(InputSource.Key(AndroidKey.BUTTON_THUMBR)), p.held.single().sources)
    }

    @Test fun aChipsOutputRangeGoesWithIt() {
        val range = AxisRange(0.2f, 0.8f)
        val lx = InputSource.Axis(AndroidAxis.X)
        val base = preset().let { it.copy(controller = it.controller.map { m -> if (m.source == lx) m.copy(range = range) else m }) }
        val p = DevicePresets.swap(base, PhysicalDevice.CONTROLLER, "ls", inputs("ls"), "rs", inputs("rs"))
        assertEquals(range, p.controller.single { it.source == InputSource.Axis(AndroidAxis.Z) && it.target == OutputTarget.PadAxis(GamepadAxis.LX) }.range)
        val back = DevicePresets.swap(p, PhysicalDevice.CONTROLLER, "ls", inputs("ls"), "rs", inputs("rs"))
        assertEquals(base.controller.toSet(), back.controller.toSet())
    }
}
