package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.AxisStage
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.DevicePreset
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PhysicalSticks
import com.oleroma.gyrophone.core.model.StickCurve
import com.oleroma.gyrophone.core.model.StickTuning
import com.oleroma.gyrophone.core.model.TriggerTuning
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A physical stick's and trigger's preset tuning: round deadzones, curve, pies, Outer, Press and Full pull. */
class StickTuningTest {
    private val pad = PadDevice(id = 1, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")
    private val x = AndroidAxis.X
    private val y = AndroidAxis.Y

    private fun engine(
        stick: StickTuning? = StickTuning(),
        trigger: TriggerTuning? = null,
        mappings: List<Mapping> = listOf(Mapping(InputSource.Axis(x), OutputTarget.PadAxis(GamepadAxis.LX))),
    ) = MappingEngine(
        single(
            Workspace(
                id = "w", name = "w",
                pads = listOf(AttachedPad(id = "p", match = DeviceMatch(vendorId = 0x045E, productId = 0x0B13), mappings = mappings)),
                stickTunings = if (stick == null) emptyMap() else mapOf("ls" to stick),
                triggerTunings = if (trigger == null) emptyMap() else mapOf("lt" to trigger),
            ),
        ),
    )

    private fun MappingEngine.at(vx: Float, vy: Float) = apply { setAxis(pad, x, vx); setAxis(pad, y, vy) }
    private fun MappingEngine.v(s: InputSource) = sourceValue(s)

    @Test fun untunedStickIsRaw() {
        val e = engine(stick = null).at(0.05f, 0f)
        assertEquals(0.05f, e.v(InputSource.Axis(x)), 1e-6f)
    }

    @Test fun innerDeadzoneIsRoundAndRescales() {
        val e = engine().at(0.06f, 0.06f)
        // |v| = 0.085 < 0.10
        assertEquals(0f, e.v(InputSource.Axis(x)), 0f)
        assertEquals(0f, e.v(InputSource.Axis(y)), 0f)
        e.at(0.525f, 0f)
        // Halfway between 0.10 and 0.95.
        assertEquals(0.5f, e.v(InputSource.Axis(x)), 1e-4f)
        e.at(0.97f, 0f)
        assertEquals(1f, e.v(InputSource.Axis(x)), 1e-6f)
    }

    @Test fun curveAndSensitivityShapeTravel() {
        val soft = engine(StickTuning(inner = 0f, outer = 1f, curve = StickCurve.SOFT)).at(0.5f, 0f)
        assertEquals(0.25f, soft.v(InputSource.Axis(x)), 1e-5f)
        val quick = engine(StickTuning(inner = 0f, outer = 1f, curve = StickCurve.AGGRESSIVE)).at(0.25f, 0f)
        assertEquals(0.5f, quick.v(InputSource.Axis(x)), 1e-5f)
        val sens = engine(StickTuning(inner = 0f, outer = 1f, sensitivity = 1.5f)).at(0.5f, 0f)
        assertEquals(0.75f, sens.v(InputSource.Axis(x)), 1e-5f)
        sens.at(0.9f, 0f)
        assertEquals(1f, sens.v(InputSource.Axis(x)), 1e-5f)
    }

    @Test fun directionsUsePies() {
        val left = InputSource.Axis(x, AxisHalf.NEGATIVE)
        val up = InputSource.Axis(y, AxisHalf.NEGATIVE)
        val right = InputSource.Axis(x, AxisHalf.POSITIVE)
        // Default 135° pies: a diagonal presses both.
        val e = engine().at(-0.6f, -0.6f)
        assertTrue(e.v(left) > 0.5f)
        assertTrue(e.v(up) > 0.5f)
        assertEquals(0f, e.v(right), 0f)
        // 90° pies: 30° off left is only left.
        val narrow = engine(StickTuning(pieX = 90f, pieY = 90f)).at(-0.8f, -0.46f)
        assertTrue(narrow.v(left) > 0.5f)
        assertEquals(0f, narrow.v(up), 0f)
        // Just past the deadzone a direction is already on, with its travel.
        val small = engine().at(-0.2f, 0f)
        assertEquals(0.1f / 0.85f, small.v(left), 1e-4f)
    }

    @Test fun outerRingPerDirectionAndAny() {
        val any = InputSource.Axis(x, stage = AxisStage.OUTER)
        val outerUp = InputSource.Axis(y, AxisHalf.NEGATIVE, AxisStage.OUTER)
        val outerLeft = InputSource.Axis(x, AxisHalf.NEGATIVE, AxisStage.OUTER)
        val e = engine().at(0f, -0.5f)
        assertEquals(0f, e.v(any), 0f)
        assertEquals(0f, e.v(outerUp), 0f)
        e.at(0f, -0.9f)
        assertEquals(1f, e.v(any), 0f)
        assertEquals(1f, e.v(outerUp), 0f)
        assertEquals(0f, e.v(outerLeft), 0f)
        // Untuned sticks still have an Outer, with the default ring.
        val raw = engine(stick = null).at(0f, -0.9f)
        assertEquals(1f, raw.v(outerUp), 0f)
        assertEquals(-0.9f, raw.v(InputSource.Axis(y)), 1e-6f)
    }

    @Test fun triggerPressAndFullPull() {
        val press = InputSource.Axis(AndroidAxis.LTRIGGER, stage = AxisStage.PRESS)
        val full = InputSource.Axis(AndroidAxis.LTRIGGER, stage = AxisStage.FULL_PULL)
        val e = engine(trigger = TriggerTuning(press = 0.3f, full = 0.9f))
        e.setAxis(pad, AndroidAxis.LTRIGGER, 0.2f)
        assertEquals(0f, e.v(press), 0f)
        e.setAxis(pad, AndroidAxis.LTRIGGER, 0.35f)
        assertEquals(1f, e.v(press), 0f)
        assertEquals(0f, e.v(full), 0f)
        e.setAxis(pad, AndroidAxis.LTRIGGER, 0.92f)
        assertEquals(1f, e.v(full), 0f)
        assertEquals(0.92f, e.v(InputSource.Axis(AndroidAxis.LTRIGGER)), 1e-6f)
    }

    @Test fun outerDrivesAKey() {
        val shift = OutputTarget.Key(0xE1)
        val e = engine(mappings = listOf(Mapping(InputSource.Axis(x, stage = AxisStage.OUTER), shift))).at(0.95f, 0f)
        assertTrue(0xE1 in e.snapshot().keyboard.keys || e.snapshot().keyboard.modifiers != 0)
    }

    private fun rightStickEngine() = MappingEngine(
        single(
            Workspace(
                id = "w", name = "w",
                pads = listOf(AttachedPad(id = "p", match = DeviceMatch(vendorId = 0x045E, productId = 0x0B13))),
                stickTunings = mapOf("rs" to StickTuning()),
            ),
        ),
    )

    @Test fun rightStickOnZRzIsTunedAsOneStick() {
        // GameSir / Xbox style: Z / RZ stick, triggers on LTRIGGER / RTRIGGER.
        val p = pad.copy(analogLeftTrigger = true, analogRightTrigger = true)
        val e = rightStickEngine()
        e.setAxis(p, AndroidAxis.Z, 0.06f); e.setAxis(p, AndroidAxis.RZ, 0.06f)
        assertEquals(0f, e.v(InputSource.Axis(AndroidAxis.Z)), 0f)
        e.setAxis(p, AndroidAxis.Z, 0.525f); e.setAxis(p, AndroidAxis.RZ, 0f)
        assertEquals(0.5f, e.v(InputSource.Axis(AndroidAxis.Z)), 1e-4f)
        e.setAxis(p, AndroidAxis.RX, 0.05f)
        assertEquals(0.05f, e.v(InputSource.Axis(AndroidAxis.RX)), 1e-6f)
    }

    @Test fun rightStickOnRxRyLeavesZRzRaw() {
        // Generic.kl pad: RX / RY stick, triggers on Z / RZ.
        val p = pad.copy(rightStickRx = true)
        val e = rightStickEngine()
        e.setAxis(p, AndroidAxis.Z, 0.05f)
        assertEquals(0.05f, e.v(InputSource.Axis(AndroidAxis.Z)), 1e-6f)
        // One trigger does not scale the other.
        e.setAxis(p, AndroidAxis.Z, 0.5f); e.setAxis(p, AndroidAxis.RZ, 0.8f)
        assertEquals(0.5f, e.v(InputSource.Axis(AndroidAxis.Z)), 1e-6f)
        assertEquals(0.8f, e.v(InputSource.Axis(AndroidAxis.RZ)), 1e-6f)
        e.setAxis(p, AndroidAxis.RX, 0.06f); e.setAxis(p, AndroidAxis.RY, 0.06f)
        assertEquals(0f, e.v(InputSource.Axis(AndroidAxis.RX)), 0f)
        e.setAxis(p, AndroidAxis.RX, 0.525f); e.setAxis(p, AndroidAxis.RY, 0f)
        assertEquals(0.5f, e.v(InputSource.Axis(AndroidAxis.RX)), 1e-4f)
        val up = InputSource.Axis(AndroidAxis.RY, AxisHalf.NEGATIVE, AxisStage.OUTER)
        e.setAxis(p, AndroidAxis.RX, 0f); e.setAxis(p, AndroidAxis.RY, -0.9f)
        assertEquals(1f, e.v(up), 0f)
    }

    @Test fun rightStickAxesFollowTheReportedAxes() {
        fun rx(vararg axes: Int) = PhysicalSticks.rightStickOnRx { it in axes }
        val a = AndroidAxis
        // Xbox / GameSir: Z / RZ stick even with RX / RY, since the triggers have their own axes.
        assertFalse(rx(a.X, a.Y, a.Z, a.RZ, a.LTRIGGER, a.RTRIGGER))
        assertFalse(rx(a.X, a.Y, a.Z, a.RZ, a.RX, a.RY, a.GAS, a.BRAKE))
        // Generic.kl: RX / RY stick, Z / RZ triggers or nothing.
        assertTrue(rx(a.X, a.Y, a.Z, a.RZ, a.RX, a.RY))
        assertTrue(rx(a.X, a.Y, a.RX, a.RY))
        assertFalse(rx(a.X, a.Y))
    }

    @Test fun presetTuningReachesTheWorkspace() {
        val preset = DevicePreset("p1", "P").withStick("ls", StickTuning(inner = 0.2f))
        val w = WorkspaceMappings.resolve(Workspace(id = "w", name = "w", devicePreset = "p1"), listOf(preset))
        assertEquals(0.2f, w.stickTunings.getValue("ls").inner, 0f)
        assertEquals(StickTuning(), w.stickTunings.getValue("rs"))
        assertEquals(TriggerTuning(), w.triggerTunings.getValue("lt"))
        assertEquals(DevicePreset("p1", "P"), preset.withStick("ls", StickTuning()))
    }

    @Test fun standInMappingsMoveToTheButtonsPress() {
        val p = DevicePreset(
            "p1", "P",
            controller = listOf(
                Mapping(InputSource.Touch("dev:CONTROLLER:a"), OutputTarget.Mouse(com.oleroma.gyrophone.core.model.MouseButton.RIGHT)),
                Mapping(InputSource.Surface(1, com.oleroma.gyrophone.core.model.SurfaceInput.MOTION_X, "dev:CONTROLLER:a"), OutputTarget.Key(4)),
                Mapping(InputSource.Key(com.oleroma.gyrophone.core.model.AndroidKey.BUTTON_B), OutputTarget.Key(5)),
            ),
        )
        val fixed = com.oleroma.gyrophone.core.model.DevicePresets.fixStandIns(p).controller
        assertEquals(
            listOf(
                Mapping(InputSource.Key(com.oleroma.gyrophone.core.model.AndroidKey.BUTTON_A), OutputTarget.Mouse(com.oleroma.gyrophone.core.model.MouseButton.RIGHT)),
                Mapping(InputSource.Key(com.oleroma.gyrophone.core.model.AndroidKey.BUTTON_B), OutputTarget.Key(5)),
            ),
            fixed,
        )
    }

    @Test fun aSticksStandInMovesToItsClick() {
        val right = OutputTarget.Mouse(com.oleroma.gyrophone.core.model.MouseButton.RIGHT)
        val p = DevicePreset("p1", "P", controller = listOf(Mapping(InputSource.Touch("dev:CONTROLLER:ls"), right)))
        assertEquals(
            listOf(Mapping(InputSource.Key(com.oleroma.gyrophone.core.model.AndroidKey.BUTTON_THUMBL), right)),
            com.oleroma.gyrophone.core.model.DevicePresets.fixStandIns(p).controller,
        )
    }
}
