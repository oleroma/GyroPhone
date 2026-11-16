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
import com.oleroma.gyrophone.core.model.SensLink
import com.oleroma.gyrophone.core.model.SliderOverride
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.with
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sensitivities that follow another one ([SensLink]): a sibling, the shared one or a named one, times a scale. */
class SensLinkTest {
    private val yaw = MotionAxis.YAW
    private val pitch = MotionAxis.PITCH
    private fun g(p: String) = WorkspaceDefaults.gyroKey(p)

    @Test fun anAxisFollowsASiblingFromItsSourceEveryTime() {
        val s = GyroSettings(sensitivity = 16f, yawSensitivity = 10f, pitchLink = SensLink("YAW", 0.5f))
        assertEquals(5f, s.sensitivityOf(pitch), 0f)
        assertEquals(6f, s.withSensitivity(yaw, 12f).sensitivityOf(pitch), 0f)
        // Worked out from the source, never from its last result: no compounding.
        val r = s.resolved()
        assertNull(r.pitchLink)
        assertEquals(5f, r.sensitivityOf(pitch), 0f)
        assertEquals(5f, r.resolved().sensitivityOf(pitch), 0f)
        assertEquals(5f, s.resolved().resolved().sensitivityOf(pitch), 0f)
        // An exact value ends the link.
        assertNull(s.withSensitivity(pitch, 3f).pitchLink)
    }

    @Test fun anOldScaleParentFactorReadsAsAShareOfTheSharedSensitivity() {
        val s = GyroSettings(sensitivity = 16f, yawScale = 0.5f)
        assertEquals(SensLink(SensLink.SHARED, 0.5f), s.ownLink(yaw))
        assertEquals(8f, s.sensitivityOf(yaw), 0f)
        assertNull(s.withLink(yaw, SensLink("PITCH")).yawScale)
        assertNull(s.withSensitivity(yaw, 4f).ownLink(yaw))
    }

    @Test fun aNamedSensitivityIsTheSharedOneUntilSet() {
        val s = GyroSettings(sensitivity = 16f, yawLink = SensLink(SensLink.named(1)), pitchLink = SensLink(SensLink.named(1), 0.5f))
        assertEquals(16f, s.sensitivityOf(yaw), 0f)
        val named: (Int) -> Float? = { if (it == 1) 6f else null }
        assertEquals(6f, s.sensitivityOf(yaw, named), 0f)
        assertEquals(3f, s.resolved(named).sensitivityOf(pitch), 0f)
    }

    @Test fun aLoopOfLinksEndsAtTheExactValue() {
        val s = GyroSettings(sensitivity = 16f, yawLink = SensLink("PITCH"), pitchLink = SensLink("YAW"))
        assertEquals(16f, s.sensitivityOf(yaw), 0f)
        assertEquals(16f, s.resolved().sensitivityOf(pitch), 0f)
    }

    @Test fun aWorkspaceExactValueHidesTheDefaultsLinkAndALinkFollowsTheDefaults() {
        val defaults = Workspace("d", "d", gyro = GyroSettings(sensitivity = 20f, yawSensitivity = 8f, pitchLink = SensLink("YAW", 0.5f)))
        val exact = Workspace("w", "w", gyro = GyroSettings(pitchSensitivity = 12f), ownSettings = setOf(g("pitchSensitivity")))
        assertEquals(12f, WorkspaceDefaults.apply(exact, defaults).gyro.sensitivityOf(pitch), 0f)
        val follows = Workspace("w", "w", ownSettings = emptySet())
        assertEquals(4f, WorkspaceDefaults.apply(follows, defaults).gyro.sensitivityOf(pitch), 0f)
        val own = Workspace("w", "w", gyro = GyroSettings(yawLink = SensLink(SensLink.SHARED, 0.25f)), ownSettings = setOf(g("yawLink")))
        assertEquals(5f, WorkspaceDefaults.apply(own, defaults).gyro.sensitivityOf(yaw), 0f)
        // Pitch follows yaw, which now follows the shared value.
        assertEquals(2.5f, WorkspaceDefaults.apply(own, defaults).gyro.sensitivityOf(pitch), 0f)
    }

    @Test fun aButtonsLinkFollowsTheAxesInEffectWhileHeld() {
        val ws = GyroSettings(sensitivity = 16f, yawSensitivity = 10f)
        assertEquals(5f, ButtonGyro(pitchLink = SensLink("YAW", 0.5f)).sensitivityOf(pitch, ws), 0f)
        // Its own yaw is the sibling while held.
        assertEquals(2f, ButtonGyro(yaw = 4f, pitchLink = SensLink("YAW", 0.5f)).sensitivityOf(pitch, ws), 0f)
        assertEquals(7f, ButtonGyro(yawLink = SensLink(SensLink.named(2))).sensitivityOf(yaw, ws) { if (it == 2) 7f else null }, 0f)
        // Its own shared Sensitivity hides the workspace's link.
        val linked = ws.copy(pitchLink = SensLink("YAW", 2f))
        assertEquals(3f, ButtonGyro(sensitivity = 3f).sensitivityOf(pitch, linked), 0f)
        // A layer's exact value ends the link below; a layer's link replaces the value below.
        assertNull(ButtonGyro(yawLink = SensLink("PITCH")).under(ButtonGyro(yaw = 3f)).yawLink)
        assertNull(ButtonGyro(yaw = 3f).under(ButtonGyro(yawLink = SensLink("PITCH"))).yaw)
        assertNull(ButtonGyro(yaw = 3f).withLink(yaw, SensLink("PITCH")).yaw)
    }

    @Test fun aButtonsTouchSpeedFollowsANamedTouchSens() {
        val t = TouchpadSettings(sensitivity = 2f).withMove(MoveSlot.X2) { it.copy(sensitivity = 4f) }
        val b = ButtonGyro(touchLink = SensLink(SensLink.named(1), 0.5f))
        assertEquals(1f, b.over(t).feel(1).sensitivityX, 0f)
        assertEquals(3f, b.over(t) { 6f }.feel(2).sensitivityX, 0f)
        // An old factor reads as a share of the shared touchpad Sensitivity.
        assertEquals(1f, ButtonGyro(touchScale = 0.5f).over(t).feel(2).sensitivityY, 0f)
    }

    @Test fun aMoveFollowsTheOtherMoveOrANamedTouchSens() {
        val t = TouchpadSettings(sensitivity = 2f)
            .withMove(MoveSlot.X1) { it.copy(sensitivity = 3f) }
            .withMove(MoveSlot.Y1) { it.copy(link = SensLink(SensLink.X, 0.5f)) }
        assertEquals(1.5f, t.feel(1).sensitivityY, 0f)
        assertEquals(1.5f, t.resolved().feel(1).sensitivityY, 0f)
        assertNull(t.resolved().move(MoveSlot.Y1).link)
        val named = t.withMove(MoveSlot.Y1) { it.copy(link = SensLink(SensLink.named(1))) }
        assertEquals(2f, named.feel(1).sensitivityY, 0f)
        assertEquals(5f, named.resolved { 5f }.feel(1).sensitivityY, 0f)
        // An old factor: a share of the shared one. An exact value below ends a link above.
        assertEquals(1f, TouchpadSettings(sensitivity = 2f).withMove(MoveSlot.Y1) { it.copy(scale = 0.5f) }.feel(1).sensitivityY, 0f)
        assertNull(MoveSettings(link = SensLink(SensLink.X)).under(MoveSettings(sensitivity = 3f)).link)
    }

    @Test fun aWorkspaceMoveLinkFollowsTheDefaults() {
        val k = WorkspaceDefaults.moveKey(MoveSlot.Y1, MoveField.LINK)
        val defaults = Workspace("d", "d", touchpad = TouchpadSettings(sensitivity = 2f))
        val ws = Workspace("w", "w", touchpad = TouchpadSettings().withMove(MoveSlot.Y1) { it.copy(link = SensLink(SensLink.SHARED, 0.5f)) }, ownSettings = setOf(k))
        assertEquals(1f, WorkspaceDefaults.apply(ws, defaults).touchpad.feel(1).sensitivityY, 0f)
    }

    @Test fun aButtonSurfaceLinkOverTheWorkspacesValue() {
        val pad = TouchpadSettings(sensitivity = 2f).withMove(MoveSlot.X1) { it.copy(sensitivity = 6f) }
        val own = com.oleroma.gyrophone.core.model.SurfaceSettings(moves = TouchpadSettings().withMove(MoveSlot.X1) { it.copy(link = SensLink(SensLink.SHARED, 0.5f)) }.moves)
        assertEquals(1f, pad.with(own).feel(1).sensitivityX, 0f)
    }

    // The engine: a held button following a named Gyro sens a slider keeps; sliders under held buttons.

    private val pad = PadDevice(id = 1, vendorId = 0x045E, productId = 0x0B13, name = "Xbox Wireless Controller")
    private val lbId = "CONTROLLER:lb"
    private val lbGyro = HeldButton.GYRO_ID + lbId

    private fun aimWorkspace(button: ButtonGyro, sliders: List<SliderOverride>) = Workspace(
        id = "w", name = "w", gyro = GyroSettings(sensitivity = 16f),
        mappings = listOf(Mapping(InputSource.Gyro(MotionAxis.ROLL), OutputTarget.MouseAxis(MouseMotion.MOVE_X))),
        pads = listOf(AttachedPad(id = "p", match = DeviceMatch(vendorId = 0x045E, productId = 0x0B13))),
        heldButtons = listOf(HeldButton(lbId, listOf(InputSource.Key(AndroidKey.BUTTON_L1)), gyro = listOf(button))),
        sliders = sliders,
    )

    @Test fun aHeldButtonFollowsANamedGyroSensASliderKeeps() {
        val e = MappingEngine(
            single(aimWorkspace(ButtonGyro(yawLink = SensLink(SensLink.named(1)), pitchLink = SensLink("YAW", 0.5f)), listOf(SliderOverride(OutputTarget.GyroSens(named = 1), 6f)))),
        )
        assertEquals(16f, e.gyroSettings.sensitivityOf(yaw), 0f)
        e.setKey(pad, AndroidKey.BUTTON_L1, true)
        assertEquals(6f, e.gyroSettings.sensitivityOf(yaw), 0f)
        assertEquals(3f, e.gyroSettings.sensitivityOf(pitch), 0f)
        assertNull(e.gyroSettings.yawLink)
        e.setKey(pad, AndroidKey.BUTTON_L1, false)
        assertEquals(16f, e.gyroSettings.sensitivityOf(yaw), 0f)
    }

    @Test fun aKeptSliderSetsTheWorkspacesValueUnderAHeldButtonsOwn() {
        val e = MappingEngine(single(aimWorkspace(ButtonGyro(yaw = 5f), listOf(SliderOverride(OutputTarget.GyroSens(), 30f)))))
        assertEquals(30f, e.gyroSettings.sensitivityOf(yaw), 0f)
        e.setKey(pad, AndroidKey.BUTTON_L1, true)
        assertEquals(5f, e.gyroSettings.sensitivityOf(yaw), 0f)
        assertEquals(30f, e.gyroSettings.sensitivityOf(pitch), 0f)
    }

    // A physical button's Motion tab: gyro mapped while it is held.

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
