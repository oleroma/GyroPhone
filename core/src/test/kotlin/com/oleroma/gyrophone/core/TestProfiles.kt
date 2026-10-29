package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.Bindings
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

fun single(w: Workspace) = Profile(id = "t", name = "t", workspaces = listOf(w), defaults = WorkspaceDefaults.builtin.copy(mappings = mouseDefaults))

/** The Defaults most tests assume: the "Windows touchpad" and "Gyro mouse" presets (built in, the Defaults map nothing). */
val mouseDefaults: List<Mapping> = Bindings.defaultTouchpadMappings(TouchpadSettings()) + Bindings.gyroMouseMappings()

/** The builtin workspaces as one-workspace profiles. */
val controllerProfile get() = single(BuiltinProfiles.controllerWorkspace)
val wasdProfile get() = single(BuiltinProfiles.wasdWorkspace)

/** All mappings of the profile's workspaces and their pads. */
val Profile.allMappings: List<Mapping> get() = workspaces.flatMap { w -> w.mappings + w.pads.flatMap { it.mappings } }
val Profile.main: Workspace get() = workspaces.first()

/** This profile with Defaults that map nothing, so the levels below inherit none. */
fun Profile.noTouchpadDefaults(): Profile = copy(defaults = WorkspaceDefaults.builtin)

/** One gyro sample routed with [x] on mouse left/right and [y] on up/down (yaw and pitch, as tests had them). */
fun com.oleroma.gyrophone.core.engine.GyroMouse.update(
    gx: Float, gy: Float, gz: Float, dt: Float,
    x: com.oleroma.gyrophone.core.model.MotionAxis = com.oleroma.gyrophone.core.model.MotionAxis.YAW,
    y: com.oleroma.gyrophone.core.model.MotionAxis = com.oleroma.gyrophone.core.model.MotionAxis.PITCH,
    scaleX: Float = 1f,
): com.oleroma.gyrophone.core.engine.GyroMouse.Counts {
    val routes = listOf(
        Mapping(com.oleroma.gyrophone.core.model.InputSource.Gyro(x), OutputTarget.MouseAxis(com.oleroma.gyrophone.core.model.MouseMotion.MOVE_X, scaleX)),
        Mapping(com.oleroma.gyrophone.core.model.InputSource.Gyro(y), OutputTarget.MouseAxis(com.oleroma.gyrophone.core.model.MouseMotion.MOVE_Y)),
    )
    val r = route(gx, gy, gz, dt, routes, moveX = true, moveY = true)
    return com.oleroma.gyrophone.core.engine.GyroMouse.Counts(r.dx, r.dy)
}
