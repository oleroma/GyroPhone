package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.ButtonGyro
import com.oleroma.gyrophone.core.model.ChildOverride
import com.oleroma.gyrophone.core.model.OverridingChildren
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.SurfaceField
import com.oleroma.gyrophone.core.model.SurfaceSettings
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import org.junit.Assert.assertEquals
import org.junit.Test

class OverridingChildrenTest {
    private val sens = WorkspaceDefaults.gyroKey("sensitivity")
    private val mode = WorkspaceDefaults.gyroKey("mode")

    private fun button(label: String, vararg gyro: ButtonGyro, surface: SurfaceSettings? = null) =
        TouchControl(label.lowercase(), TouchKind.BUTTON, label, 0.5f, 0.5f, gyro = gyro.toList(), surface = surface)

    private val apex = Workspace(
        "a", "Apex",
        gyro = GyroSettings(sensitivity = 2.6f),
        ownSettings = setOf(sens),
        sets = listOf(
            MappingSet(1, "Sniper", Workspace("", "", gyro = GyroSettings(sensitivity = 0.8f), ownSettings = setOf(sens))),
        ),
        touchControls = listOf(
            button("Aim", ButtonGyro(yawScale = 0.5f), ButtonGyro(set = 1, sensitivity = 1.25f)),
            button("Fire"),
        ),
    )
    private val desk = Workspace("d", "Desk", gyro = GyroSettings(mode = GyroMode.SENSOR_OFF), ownSettings = setOf(mode))
    private val profile = Profile("p", "P", listOf(apex, desk))

    @Test
    fun eachLevelListsOnlyItsNearestOverrides() {
        // Apex sets it, so the Defaults list only Apex; Apex lists Sniper; Sniper lists Aim.
        assertEquals(listOf(ChildOverride("Apex", "2.6")), OverridingChildren.ofDefaults(profile, listOf(sens)))
        assertEquals(listOf(ChildOverride("Sniper", "0.8")), OverridingChildren.ofWorkspace(apex, listOf(sens)))
        assertEquals(listOf(ChildOverride("Sniper › Aim", "1.25")), OverridingChildren.ofLayer(apex, 1, listOf(sens)))
        // A workspace that follows: its own children show under the Defaults.
        val follows = profile.copy(workspaces = listOf(apex.copy(ownSettings = emptySet()), desk))
        assertEquals(listOf(ChildOverride("Apex › Sniper", "0.8")), OverridingChildren.ofDefaults(follows, listOf(sens)))
        assertEquals(listOf(ChildOverride("Desk", "Gyro off")), OverridingChildren.ofDefaults(profile, listOf(mode)))
        // A factor shows as one.
        assertEquals(
            listOf(ChildOverride("Aim", "×0.5")),
            OverridingChildren.ofWorkspace(apex, listOf(WorkspaceDefaults.gyroKey("yawScale"))),
        )
    }

    @Test
    fun layerSeesBaseAndItsOwnButtonValues() {
        val w = apex.copy(touchControls = apex.touchControls + button("Walk", ButtonGyro(set = 2, sensitivity = 9f)))
        assertEquals(listOf(ChildOverride("Sniper › Aim", "1.25")), OverridingChildren.ofLayer(w, 1, listOf(sens)))
    }

    @Test
    fun buttonSurfaceCounts() {
        val w = apex.copy(touchControls = listOf(button("Pad", surface = SurfaceSettings(sensitivity = 4f))))
        assertEquals(
            listOf(ChildOverride("Pad", "4")),
            OverridingChildren.ofWorkspace(w, listOf(WorkspaceDefaults.touchpadKey(SurfaceField.SENSITIVITY))),
        )
    }

    @Test
    fun resetChildrenMakesEveryoneFollow() {
        val r = OverridingChildren.resetDefaults(profile, listOf(sens))
        assertEquals(emptyList<ChildOverride>(), OverridingChildren.ofDefaults(r, listOf(sens)))
        // Other settings stay.
        assertEquals(1, OverridingChildren.ofDefaults(r, listOf(mode)).size)
        assertEquals(listOf(ChildOverride("Apex › Aim", "×0.5")), OverridingChildren.ofDefaults(r, listOf(WorkspaceDefaults.gyroKey("yawScale"))))
        assertEquals(GyroSettings().sensitivity, r.workspaces[0].gyro.sensitivity)
    }
}
