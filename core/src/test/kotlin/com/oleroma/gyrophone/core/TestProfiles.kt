package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.isTouch

/** A one-workspace profile: touch mappings in the workspace, the rest on an "any gamepad" pad. */
fun profileOf(
    mappings: List<Mapping>,
    touchControls: List<TouchControl> = emptyList(),
    gyro: GyroSettings = GyroSettings(),
    touchpad: TouchpadSettings = TouchpadSettings(),
): Profile {
    val pad = mappings.filterNot { it.source.isTouch }
    return single(
        Workspace(
            id = "ws1", name = "Main", touchControls = touchControls,
            mappings = mappings.filter { it.source.isTouch }, gyro = gyro, touchpad = touchpad,
            pads = if (pad.isEmpty()) emptyList() else listOf(AttachedPad(id = "pad1", mappings = pad)),
        ),
    )
}

fun single(w: Workspace) = Profile(id = "t", name = "t", workspaces = listOf(w))

/** The builtin workspaces as one-workspace profiles. */
val controllerProfile get() = single(BuiltinProfiles.controllerWorkspace)
val wasdProfile get() = single(BuiltinProfiles.wasdWorkspace)

/** All mappings of the profile's workspaces and their pads. */
val Profile.allMappings: List<Mapping> get() = workspaces.flatMap { w -> w.mappings + w.pads.flatMap { it.mappings } }
val Profile.main: Workspace get() = workspaces.first()

/** This profile with every touchpad mapping of the Defaults cleared, so the levels below inherit none. */
fun Profile.noTouchpadDefaults(): Profile = copy(
    defaults = WorkspaceDefaults.builtin.copy(mappings = Inheritance.builtin.map { it.copy(target = OutputTarget.NoOutput) }),
)
