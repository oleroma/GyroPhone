package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

/**
 * A slider's kept setting ([Workspace.sliders]): a Toggle value chip ([Mapping.setValue]) to
 * Gyro or Touch sensitivity or Tap time [target] with no end let go at [value] (Gyro counts per
 * degree, Touch its sensitivity, Tap time ms), on button [by] ("" = the touchpad). It lies over the workspace's settings in
 * effect, a held button's included, until a newer one of the same sensitivity replaces it or it
 * is reset; the settings themselves never change.
 */
@Serializable
data class SliderOverride(val target: OutputTarget, val value: Float, val by: String = "")

/** Slider overrides ([SliderOverride]): saving one, laying them over the settings, naming them. */
object SliderOverrides {
    /** The workspace setting key chip target [t] drives. */
    fun keyOf(t: OutputTarget): String? = when (t) {
        is OutputTarget.GyroSens -> WorkspaceDefaults.gyroKey(
            when (t.axis) {
                MotionAxis.NONE -> "sensitivity"
                MotionAxis.YAW -> "yawSensitivity"
                MotionAxis.PITCH -> "pitchSensitivity"
                MotionAxis.ROLL -> "rollSensitivity"
            }
        )
        is OutputTarget.TouchSens -> when (t.axis) {
            null -> WorkspaceDefaults.touchpadKey(SurfaceField.SENSITIVITY)
            StickAxis.X -> WorkspaceDefaults.moveKey(MoveSlot.X1, MoveField.SENSITIVITY)
            StickAxis.Y -> WorkspaceDefaults.moveKey(MoveSlot.Y1, MoveField.SENSITIVITY)
        }
        OutputTarget.TapTime -> WorkspaceDefaults.LONG_PRESS
        else -> null
    }

    /** A new override of [new] replaces one of [old]: the same sensitivity, or every axis over one axis. */
    fun replaces(new: OutputTarget, old: OutputTarget): Boolean = when (new) {
        is OutputTarget.GyroSens -> old is OutputTarget.GyroSens && (new.axis == MotionAxis.NONE || new.axis == old.axis)
        is OutputTarget.TouchSens -> old is OutputTarget.TouchSens && (new.axis == null || new.axis == old.axis)
        OutputTarget.TapTime -> old == OutputTarget.TapTime
        else -> false
    }

    /** [w] with chip [m]'s [value] as its slider override, replacing older ones of that sensitivity (newest wins). */
    fun put(w: Workspace, m: Mapping, value: Float): Workspace {
        if (keyOf(m.target) == null) return w
        val by = MappingProfiles.touchId(m.source) ?: ""
        return w.copy(sliders = w.sliders.filterNot { replaces(m.target, it.target) } + SliderOverride(m.target, value, by))
    }

    /** Profile [p] with chip [m]'s [value] as workspace [wi]'s slider override. */
    fun put(p: Profile, wi: Int, m: Mapping, value: Float): Profile {
        val w = p.workspace(wi)
        val n = put(w, m, value)
        return if (n == w) p else p.copy(workspaces = p.workspaces.map { if (it.id == w.id) n else it })
    }

    /** [g] with the gyro overrides of [list] over it. */
    fun overGyro(g: GyroSettings, list: List<SliderOverride>): GyroSettings {
        var r = g
        for (o in list) {
            val t = o.target as? OutputTarget.GyroSens ?: continue
            val v = o.value
            r = when (t.axis) {
                MotionAxis.NONE -> r.copy(
                    sensitivity = v, yawSensitivity = null, pitchSensitivity = null, rollSensitivity = null,
                    yawScale = null, pitchScale = null, rollScale = null,
                )
                MotionAxis.YAW -> r.copy(yawSensitivity = v, yawScale = null)
                MotionAxis.PITCH -> r.copy(pitchSensitivity = v, pitchScale = null)
                MotionAxis.ROLL -> r.copy(rollSensitivity = v, rollScale = null)
            }
        }
        return r
    }

    /** [t] with the touch overrides of [list] over it. */
    fun overTouch(t: TouchpadSettings, list: List<SliderOverride>): TouchpadSettings {
        var r = t
        for (o in list) {
            val s = o.target as? OutputTarget.TouchSens ?: continue
            val v = o.value
            r = when (s.axis) {
                null -> r.copy(sensitivity = v, moves = r.moves?.map { it.copy(sensitivity = null, scale = null) })
                StickAxis.X -> r.withMove(MoveSlot.X1) { it.copy(sensitivity = v, scale = null) }
                StickAxis.Y -> r.withMove(MoveSlot.Y1) { it.copy(sensitivity = v, scale = null) }
            }
        }
        return r
    }

    /** The Tap time (ms) of the Tap time override of [list], or null if it has none. */
    fun tapTime(list: List<SliderOverride>): Int? =
        list.lastOrNull { it.target == OutputTarget.TapTime }?.value?.let { tapMs(it) }

    /** Tap time [v] (ms) as the workspace takes it: whole, within its limits. */
    fun tapMs(v: Float): Int = kotlin.math.round(v).toInt().coerceIn(Workspace.MIN_LONG_PRESS_MS, Workspace.MAX_LONG_PRESS_MS)

    /** Who set override [o] of [w], for the children lists: "Aim slider", "Touchpad slider". */
    fun name(w: Workspace, o: SliderOverride): String {
        val who = if (o.by.isEmpty()) "Touchpad" else w.touchControls.firstOrNull { it.id == o.by }?.label?.ifBlank { null } ?: "Button"
        return "$who slider"
    }
}
