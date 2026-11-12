package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.ButtonGyro
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.DevicePreset
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.HeldButton
import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.MoveField
import com.oleroma.gyrophone.core.model.MoveSettings
import com.oleroma.gyrophone.core.model.MoveSlot
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PhysicalDevice
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.with
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sensitivities set as a factor of the parent's (Scale parent) instead of an exact value. */
class ScaledSensTest {
    private val yaw = MotionAxis.YAW
    private fun g(p: String) = WorkspaceDefaults.gyroKey(p)

    @Test fun anAxisFactorMultipliesItsExactValueOrTheShared() {
        val s = GyroSettings(sensitivity = 16f, yawScale = 0.5f)
        assertEquals(8f, s.sensitivityOf(yaw), 0f)
        assertEquals(16f, s.exactOf(yaw), 0f)
        assertEquals(10f, s.copy(yawSensitivity = 20f).sensitivityOf(yaw), 0f)
        // An exact value set ends the factor.
        assertNull(s.withSensitivity(yaw, 12f).yawScale)
        assertEquals(12f, s.withSensitivity(yaw, 12f).sensitivityOf(yaw), 0f)
        val r = s.resolvedScales()
        assertNull(r.yawScale)
        assertEquals(8f, r.sensitivityOf(yaw), 0f)
    }

    @Test fun aWorkspaceFactorFollowsTheDefaultsAndTheSharedValue() {
        val defaults = Workspace("d", "d", gyro = GyroSettings(sensitivity = 20f))
        val ws = Workspace("w", "w", gyro = GyroSettings(sensitivity = 20f, yawScale = 0.5f), ownSettings = setOf(g("yawScale")))
        assertEquals(10f, WorkspaceDefaults.apply(ws, defaults).gyro.sensitivityOf(yaw), 0f)
        // The Defaults change: the factor follows.
        assertEquals(15f, WorkspaceDefaults.apply(ws, defaults.copy(gyro = GyroSettings(sensitivity = 30f))).gyro.sensitivityOf(yaw), 0f)
        // The workspace's own shared value is its parent too.
        val own = ws.copy(gyro = ws.gyro.copy(sensitivity = 40f), ownSettings = setOf(g("yawScale"), g("sensitivity")))
        assertEquals(20f, WorkspaceDefaults.apply(own, defaults).gyro.sensitivityOf(yaw), 0f)
    }

    @Test fun aSharedValueOrAnExactOneHidesTheFactorsAbove() {
        val defaults = Workspace("d", "d", gyro = GyroSettings(sensitivity = 20f, yawScale = 0.5f, pitchSensitivity = 30f))
        // An exact yaw of the workspace's: no factor of the Defaults'.
        val exact = Workspace("w", "w", gyro = GyroSettings(yawSensitivity = 12f), ownSettings = setOf(g("yawSensitivity")))
        assertEquals(12f, WorkspaceDefaults.apply(exact, defaults).gyro.sensitivityOf(yaw), 0f)
        // The workspace's own shared Sensitivity: the Defaults' axis values give way to it.
        val shared = Workspace("w", "w", gyro = GyroSettings(sensitivity = 10f), ownSettings = setOf(g("sensitivity")))
        val r = WorkspaceDefaults.apply(shared, defaults).gyro
        assertEquals(10f, r.sensitivityOf(yaw), 0f)
        assertEquals(10f, r.sensitivityOf(MotionAxis.PITCH), 0f)
    }

    @Test fun anEditToAnExactValueOwnsIt() {
        val defaults = Workspace("d", "d", gyro = GyroSettings(sensitivity = 20f, yawScale = 0.5f))
        val before = WorkspaceDefaults.apply(Workspace("w", "w", ownSettings = emptySet()), defaults)
        assertEquals(10f, before.gyro.sensitivityOf(yaw), 0f)
        val after = WorkspaceDefaults.markOwn(before, before.copy(gyro = before.gyro.withSensitivity(yaw, 25f)))
        assertEquals(25f, WorkspaceDefaults.apply(after, defaults).gyro.sensitivityOf(yaw), 0f)
    }

    @Test fun aLayerFactorScalesBasesExactAxisValue() {
        val base = Workspace(
            "w", "w", gyro = GyroSettings(sensitivity = 16f, yawSensitivity = 20f),
            sets = listOf(MappingSet(1, "Aim", settings = Workspace("", "", gyro = GyroSettings(yawScale = 0.25f), ownSettings = setOf(g("yawScale"))))),
        )
        assertEquals(5f, WorkspaceDefaults.layered(base, 1, emptyList()).gyro.sensitivityOf(yaw), 0f)
        // A layer's exact value over Base's factor: exact.
        val scaledBase = base.copy(
            gyro = GyroSettings(sensitivity = 16f, yawScale = 0.5f),
            sets = listOf(MappingSet(1, "Aim", settings = Workspace("", "", gyro = GyroSettings(yawSensitivity = 30f), ownSettings = setOf(g("yawSensitivity"))))),
        )
        assertEquals(30f, WorkspaceDefaults.layered(scaledBase, 1, emptyList()).gyro.sensitivityOf(yaw), 0f)
    }

    @Test fun aButtonFactorReplacesTheWorkspacesAndScalesTheNearestExactValue() {
        val ws = GyroSettings(sensitivity = 16f, yawScale = 0.5f, pitchSensitivity = 30f)
        assertEquals(8f, ButtonGyro().sensitivityOf(yaw, ws), 0f)
        // The button's factor multiplies the workspace's exact value, not its scaled one.
        assertEquals(4f, ButtonGyro(yawScale = 0.25f).sensitivityOf(yaw, ws), 0f)
        assertEquals(15f, ButtonGyro(pitchScale = 0.5f).sensitivityOf(MotionAxis.PITCH, ws), 0f)
        // The button's own Gyro sensitivity is nearer.
        assertEquals(2f, ButtonGyro(sensitivity = 8f, yawScale = 0.25f).sensitivityOf(yaw, ws), 0f)
        val over = ButtonGyro(yawScale = 0.25f).over(ws)
        assertNull(over.yawScale)
        assertEquals(4f, over.sensitivityOf(yaw), 0f)
        // One level holds one of the two.
        assertNull(ButtonGyro(yaw = 3f).withScale(yaw, 0.5f).yaw)
        assertNull(ButtonGyro(yawScale = 0.5f).with(yaw, 3f).yawScale)
    }

    @Test fun aButtonLayerFactorScalesItsBaseExactValueAndAnExactOneEndsAFactor() {
        assertEquals(5f, ButtonGyro(yaw = 10f).under(ButtonGyro(yawScale = 0.5f)).sensitivityOf(yaw, GyroSettings()), 0f)
        val g = ButtonGyro(yawScale = 0.5f).under(ButtonGyro(yaw = 7f))
        assertNull(g.yawScale)
        assertEquals(7f, g.sensitivityOf(yaw, GyroSettings()), 0f)
    }

    @Test fun aButtonTouchFactorScalesEachFingerCountsExactValue() {
        val t = TouchpadSettings(sensitivity = 2f).withMove(MoveSlot.X2) { it.copy(sensitivity = 4f) }
        val r = ButtonGyro(touchScale = 0.5f).over(t)
        assertEquals(1f, r.feel(1).sensitivityX, 0f)
        assertEquals(2f, r.feel(2).sensitivityX, 0f)
        assertEquals(1f, r.feel(2).sensitivityY, 0f)
        assertEquals(1.5f, ButtonGyro(touchSensitivity = 3f).under(ButtonGyro(touchScale = 0.5f)).over(t).feel(1).sensitivityX, 0f)
    }

    @Test fun aMoveFactorMultipliesTheSharedOrItsExactValue() {
        val t = TouchpadSettings(sensitivity = 2f).withMove(MoveSlot.Y1) { it.copy(scale = 0.5f) }
        assertEquals(1f, t.feel(1).sensitivityY, 0f)
        assertEquals(2f, t.feel(1).sensitivityX, 0f)
        assertEquals(1.5f, t.withMove(MoveSlot.Y1) { it.copy(sensitivity = 3f, scale = 0.5f) }.feel(1).sensitivityY, 0f)
        // A level's exact value over a factor below ends it; a factor over an exact value keeps it to scale.
        assertNull(MoveSettings(scale = 0.5f).under(MoveSettings(sensitivity = 3f)).scale)
        assertEquals(1.5f, MoveSettings(sensitivity = 3f).under(MoveSettings(scale = 0.5f)).sensitivityOver(9f), 0f)
    }

    @Test fun aWorkspaceMoveFactorFollowsTheDefaultsAndGivesWayToAnExactValue() {
        val k = WorkspaceDefaults.moveKey(MoveSlot.Y1, MoveField.SCALE)
        val kSens = WorkspaceDefaults.moveKey(MoveSlot.Y1, MoveField.SENSITIVITY)
        val defaults = Workspace("d", "d", touchpad = TouchpadSettings(sensitivity = 2f))
        val ws = Workspace("w", "w", touchpad = TouchpadSettings().withMove(MoveSlot.Y1) { it.copy(scale = 0.5f) }, ownSettings = setOf(k))
        assertEquals(1f, WorkspaceDefaults.apply(ws, defaults).touchpad.feel(1).sensitivityY, 0f)
        val d2 = Workspace("d", "d", touchpad = TouchpadSettings(sensitivity = 2f).withMove(MoveSlot.Y1) { it.copy(scale = 0.5f) })
        val exact = Workspace("w", "w", touchpad = TouchpadSettings().withMove(MoveSlot.Y1) { it.copy(sensitivity = 5f) }, ownSettings = setOf(kSens))
        assertEquals(5f, WorkspaceDefaults.apply(exact, d2).touchpad.feel(1).sensitivityY, 0f)
    }

    @Test fun aButtonSurfaceFactorScalesTheWorkspacesExactValue() {
        val pad = TouchpadSettings(sensitivity = 2f).withMove(MoveSlot.X1) { it.copy(scale = 3f) }
        val own = com.oleroma.gyrophone.core.model.SurfaceSettings(moves = TouchpadSettings().withMove(MoveSlot.X1) { it.copy(scale = 0.5f) }.moves)
        assertEquals(1f, pad.with(own).feel(1).sensitivityX, 0f)
        val exact = com.oleroma.gyrophone.core.model.SurfaceSettings(moves = TouchpadSettings().withMove(MoveSlot.X1) { it.copy(sensitivity = 4f) }.moves)
        assertEquals(4f, pad.with(exact).feel(1).sensitivityX, 0f)
    }

    // A physical button's Motion tab: gyro mapped while it is held.

    private val pad = PadDevice(id = 1, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")
    private val lbId = "CONTROLLER:lb"
    private val lbGyro = HeldButton.GYRO_ID + lbId

    @Test fun aHeldPhysicalButtonTakesTheGyroAxesItMaps() {
        val e = MappingEngine(
            single(
                Workspace(
                    id = "w", name = "w",
                    mappings = listOf(Mapping(InputSource.Gyro(MotionAxis.ROLL), OutputTarget.MouseAxis(MouseMotion.MOVE_X))),
                    pads = listOf(
                        AttachedPad(
                            id = "p", match = DeviceMatch(vendorId = 0x045E, productId = 0x0B13),
                            mappings = listOf(Mapping(InputSource.Gyro(MotionAxis.ROLL, lbGyro), OutputTarget.MouseAxis(MouseMotion.WHEEL))),
                        ),
                    ),
                    heldButtons = listOf(HeldButton(lbId, listOf(InputSource.Key(AndroidKey.BUTTON_L1)))),
                ),
            ),
        )
        assertEquals(OutputTarget.MouseAxis(MouseMotion.MOVE_X), e.gyroRoutes().single { (it.source as InputSource.Gyro).axis == MotionAxis.ROLL }.target)
        e.setKey(pad, AndroidKey.BUTTON_L1, true)
        assertEquals(OutputTarget.MouseAxis(MouseMotion.WHEEL), e.gyroRoutes().single { (it.source as InputSource.Gyro).axis == MotionAxis.ROLL }.target)
        e.setKey(pad, AndroidKey.BUTTON_L1, false)
        assertEquals(OutputTarget.MouseAxis(MouseMotion.MOVE_X), e.gyroRoutes().single { (it.source as InputSource.Gyro).axis == MotionAxis.ROLL }.target)
    }

    @Test fun aPresetKeepsWhatPressesAButtonWhileItMapsGyro() {
        val src = listOf(InputSource.Key(AndroidKey.BUTTON_L1))
        val p = DevicePreset("p", "p", controller = listOf(Mapping(InputSource.Gyro(MotionAxis.ROLL, lbGyro), OutputTarget.NoOutput)))
        assertTrue(p.motionMapped(lbId))
        val synced = p.syncHeld(lbId, src)
        assertEquals(src, synced.held(lbId)!!.sources)
        val cleared = synced.withMappings(PhysicalDevice.CONTROLLER, emptyList()).syncHeld(lbId, src)
        assertNull(cleared.held(lbId))
        assertFalse(cleared.motionMapped(lbId))
    }

    @Test fun theWorkspacesGyroMappingsShowAsAButtonsInherited() {
        val ws = listOf(
            Mapping(InputSource.Gyro(MotionAxis.ROLL), OutputTarget.MouseAxis(MouseMotion.MOVE_X)),
            Mapping(InputSource.Gyro(MotionAxis.PITCH), OutputTarget.MouseAxis(MouseMotion.MOVE_Y)),
            Mapping(InputSource.Gyro(MotionAxis.PITCH), OutputTarget.NoOutput, set = 1),
        )
        val base = Inheritance.gyroInherited(ws, "b", MappingSet.BASE)
        assertEquals(setOf(InputSource.Gyro(MotionAxis.ROLL, "b"), InputSource.Gyro(MotionAxis.PITCH, "b")), base.mapTo(HashSet()) { it.source })
        // In layer 1 pitch is cleared: only roll passes down.
        assertEquals(listOf(InputSource.Gyro(MotionAxis.ROLL, "b")), Inheritance.gyroInherited(ws, "b", 1).map { it.source })
    }
}
