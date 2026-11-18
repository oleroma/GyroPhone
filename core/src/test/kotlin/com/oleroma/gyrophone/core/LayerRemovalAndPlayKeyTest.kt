package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.ButtonGyro
import com.oleroma.gyrophone.core.model.DevicePreset
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.HeldButton
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SliderOverride
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.withSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Deleting a layer cleans its pinned device preset too; the play touch handler's key ignores what it doesn't read. */
class LayerRemovalAndPlayKeyTest {
    private val l3 = InputSource.Key(106) // KEYCODE_BUTTON_THUMBL

    private fun preset() = DevicePreset(
        id = "p", name = "p",
        controller = listOf(
            Mapping(l3, OutputTarget.Layer(2)),
            Mapping(l3, OutputTarget.Pad(GamepadButton.A), 2),
            Mapping(l3, OutputTarget.Pad(GamepadButton.B)),
        ),
        keyboard = listOf(Mapping(l3, OutputTarget.Layer(2, override = true))),
        held = listOf(
            HeldButton("CONTROLLER:l3", listOf(l3), listOf(ButtonGyro(2, sensitivity = 2f))),
            HeldButton("CONTROLLER:r3", listOf(l3), listOf(ButtonGyro(0, sensitivity = 3f), ButtonGyro(2, sensitivity = 4f))),
        ),
    )

    private fun ws(id: String, sets: List<MappingSet>) = Workspace(
        id = id, name = id, devicePreset = "p", sets = sets,
        touchControls = listOf(
            TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f, gyro = listOf(ButtonGyro(0, sensitivity = 1f), ButtonGyro(2, sensitivity = 5f))),
        ),
    )

    @Test
    fun deletingALayerDropsItFromThePinnedPreset() {
        val p = single(ws("w", listOf(MappingSet(2)))).copy(devicePresets = listOf(preset()))
        val after = p.withSets(0, emptyList(), 2)
        val d = after.devicePresets.single()
        assertEquals(listOf(Mapping(l3, OutputTarget.Pad(GamepadButton.B))), d.controller)
        assertEquals(emptyList<Mapping>(), d.keyboard)
        // A held button left with nothing goes; another keeps its other sets.
        assertEquals(listOf(HeldButton("CONTROLLER:r3", listOf(l3), listOf(ButtonGyro(0, sensitivity = 3f)))), d.held)
        assertEquals(listOf(ButtonGyro(0, sensitivity = 1f)), after.workspaces[0].touchControls[0].gyro)
        assertEquals(emptyList<MappingSet>(), after.workspaces[0].sets)
    }

    @Test
    fun aPresetSharedWithAWorkspaceStillHavingTheLayerKeepsIt() {
        val base = single(ws("w", listOf(MappingSet(2))))
        val p = base.copy(workspaces = base.workspaces + ws("v", listOf(MappingSet(2))), devicePresets = listOf(preset()))
        val after = p.withSets(0, emptyList(), 2)
        assertEquals(preset(), after.devicePresets.single())
        assertEquals(emptyList<MappingSet>(), after.workspaces[0].sets)
    }

    @Test
    fun addingALayerLeavesThePresetAlone() {
        val p = single(ws("w", emptyList())).copy(devicePresets = listOf(preset()))
        val after = p.withSets(0, listOf(MappingSet(2)), null)
        assertEquals(preset(), after.devicePresets.single())
        assertEquals(listOf(MappingSet(2)), after.workspaces[0].sets)
    }

    @Test
    fun playKeyIgnoresSlidersGyroAndDisplayLevel() {
        val display = TouchControl("d", TouchKind.DISPLAY, "", 0.2f, 0.2f, display = GyroDisplay(GyroDisplayKind.PHONE))
        val p = single(ws("w", emptyList()).let { it.copy(touchControls = it.touchControls + display) })
        val key = p.playTouchKey(0)
        val slid = p.updateWorkspace(0) { it.copy(sliders = listOf(SliderOverride(OutputTarget.GyroSens(), 3f))) }
        assertEquals(key, slid.playTouchKey(0))
        val gyro = p.updateWorkspace(0) { it.copy(gyro = it.gyro.copy(sensitivity = it.gyro.sensitivity + 1f)) }
        assertEquals(key, gyro.playTouchKey(0))
        val levelled = p.updateWorkspace(0) { w ->
            w.copy(touchControls = w.touchControls.map { c -> c.display?.let { c.copy(display = it.copy(levelUp = listOf(0f, 1f, 0f))) } ?: c })
        }
        assertEquals(key, levelled.playTouchKey(0))
        // What the handler reads restarts it.
        val moved = p.updateWorkspace(0) { w -> w.copy(touchControls = w.touchControls.map { if (it.id == "b") it.copy(x = 0.6f) else it }) }
        assertNotEquals(key, moved.playTouchKey(0))
        val mapped = p.updateWorkspace(0) { it.copy(mappings = listOf(Mapping(InputSource.Touch("b"), OutputTarget.Pad(GamepadButton.A)))) }
        assertNotEquals(key, mapped.playTouchKey(0))
        assertNotEquals(key, p.copy(wrapWorkspaces = true).playTouchKey(0))
    }
}
