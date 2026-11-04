package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.ButtonGyro
import com.oleroma.gyrophone.core.model.CurvePoint
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.GyroCurve
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.HeldButton
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical keys and buttons with settings of their own while held: gyro sensitivity and acceleration, touchpad speed. */
class HeldButtonsTest {
    private val pad = PadDevice(id = 1, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")
    private val aim = HeldButton(
        "CONTROLLER:lb", listOf(InputSource.Key(AndroidKey.BUTTON_L1)),
        listOf(ButtonGyro(sensitivity = 4f, accel = GyroCurve.OFF, touchSensitivity = 0.5f)),
    )
    private val fast = HeldButton(
        "CONTROLLER:rb", listOf(InputSource.Key(AndroidKey.BUTTON_R1)),
        listOf(ButtonGyro(sensitivity = 30f)),
    )
    private val curve = GyroCurve(listOf(CurvePoint(10f, 1f), CurvePoint(100f, 2f)))

    private fun engine() = MappingEngine(
        single(
            Workspace(
                id = "w", name = "w",
                gyro = GyroSettings(sensitivity = 16f, accel = curve),
                pads = listOf(
                    AttachedPad(
                        id = "p", match = DeviceMatch(vendorId = 0x045E, productId = 0x0B13),
                        mappings = listOf(Mapping(InputSource.Key(AndroidKey.BUTTON_A), OutputTarget.GyroPause)),
                    ),
                ),
                heldButtons = listOf(aim, fast),
            ),
        ),
    )

    @Test fun aHeldButtonSetsGyroAndTouchpadUntilLetGo() {
        val e = engine()
        assertEquals(16f, e.gyroSettings.sensitivityOf(MotionAxis.YAW), 0f)
        assertEquals(curve, e.gyroSettings.accelOf(MotionAxis.YAW))
        val touch = e.touchpadSettingsNow.sensitivity
        e.setKey(pad, AndroidKey.BUTTON_L1, true)
        assertEquals(4f, e.gyroSettings.sensitivityOf(MotionAxis.ROLL), 0f)
        assertTrue(e.gyroSettings.accelOf(MotionAxis.ROLL).isOff)
        assertEquals(0.5f, e.touchpadSettingsNow.sensitivity, 0f)
        e.setKey(pad, AndroidKey.BUTTON_L1, false)
        assertEquals(16f, e.gyroSettings.sensitivityOf(MotionAxis.ROLL), 0f)
        assertEquals(curve, e.gyroSettings.accelOf(MotionAxis.ROLL))
        assertEquals(touch, e.touchpadSettingsNow.sensitivity, 0f)
    }

    @Test fun theLastPressedWinsAndTheOtherComesBack() {
        val e = engine()
        e.setKey(pad, AndroidKey.BUTTON_L1, true)
        e.setKey(pad, AndroidKey.BUTTON_R1, true)
        assertEquals(30f, e.gyroSettings.sensitivityOf(MotionAxis.PITCH), 0f)
        // Only gyro: the touchpad keeps the first one's.
        assertEquals(0.5f, e.touchpadSettingsNow.sensitivity, 0f)
        e.setKey(pad, AndroidKey.BUTTON_R1, false)
        assertEquals(4f, e.gyroSettings.sensitivityOf(MotionAxis.PITCH), 0f)
    }

    @Test fun pcControllerButtonsGetTheirSettingsAndAKindBit() {
        val e = engine()
        val places = e.pcHeldGyro.map { it.first }
        assertEquals(2, places.size)
        assertEquals(4f, e.pcHeldGyro[0].second.sensitivityOf(MotionAxis.YAW), 0f)
        assertTrue(e.pcGyroButtons.count { it and MappingEngine.PC_GYRO_SETTINGS != 0 } == 2)
    }

    @Test fun curveGainIsStraightBetweenPointsAndFlatOutside() {
        assertEquals(1f, curve.gainAt(0f), 0f)
        assertEquals(1.5f, curve.gainAt(55f), 1e-6f)
        assertEquals(2f, curve.gainAt(500f), 0f)
        assertEquals(1f, GyroCurve.OFF.gainAt(300f), 0f)
    }
}
