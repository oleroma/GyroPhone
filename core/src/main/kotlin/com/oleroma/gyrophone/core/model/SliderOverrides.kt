package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

/**
 * A slider's kept setting ([Workspace.sliders]): a Toggle value chip ([Mapping.setValue]) to
 * Gyro or Touch sensitivity or Tap time [target] with no end let go at [value] (Gyro counts per
 * degree, Touch its sensitivity, Tap time ms), on button [by] ("" = the touchpad). It lies over the workspace's settings in
 * effect (under a held button's own), until a newer one of the same sensitivity replaces it or it
 * is reset; the settings themselves never change. One of a named Gyro or Touch sens is that
 * sensitivity's value.
 */
@Serializable
data class SliderOverride(val target: OutputTarget, val value: Float, val by: String = "")

/** Slider overrides ([SliderOverride]): saving one, laying them over the settings, naming them. */
object SliderOverrides {
    /** The workspace setting key chip target [t] drives. */
    fun keyOf(t: OutputTarget): String? = when {
        t is OutputTarget.GyroSens && t.named > 0 -> "named.gyro.${t.named}"
        t is OutputTarget.TouchSens && t.named > 0 -> "named.touch.${t.named}"
        else -> keyOfSetting(t)
    }

    private fun keyOfSetting(t: OutputTarget): String? = when (t) {
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
        is OutputTarget.GyroGlide -> WorkspaceDefaults.gyroKey(
            if (t.axis == MotionAxis.NONE) "glideMs" else t.axis.name.lowercase() + "GlideMs"
        )
        is OutputTarget.TiltRange -> WorkspaceDefaults.gyroKey(
            when (t.axis) {
                null -> "tiltRangeDeg"
                StickAxis.X -> "tiltXRangeDeg"
                StickAxis.Y -> "tiltYRangeDeg"
            }
        )
        else -> null
    }

    /** A new override of [new] replaces one of [old]: the same sensitivity, or every axis over one axis. */
    fun replaces(new: OutputTarget, old: OutputTarget): Boolean = when (new) {
        is OutputTarget.GyroSens -> old is OutputTarget.GyroSens && (
            if (new.named > 0 || old.named > 0) new.named == old.named else new.axis == MotionAxis.NONE || new.axis == old.axis
            )
        is OutputTarget.TouchSens -> old is OutputTarget.TouchSens && (
            if (new.named > 0 || old.named > 0) new.named == old.named else new.axis == null || new.axis == old.axis
            )
        OutputTarget.TapTime -> old == OutputTarget.TapTime
        is OutputTarget.GyroGlide -> old is OutputTarget.GyroGlide && (new.axis == MotionAxis.NONE || new.axis == old.axis)
        is OutputTarget.TiltRange -> old is OutputTarget.TiltRange && (new.axis == null || new.axis == old.axis)
        else -> false
    }

    /**
     * [w]'s overrides that a chip still keeps: one kept by a touch control ([SliderOverride.by]) goes
     * once none of that control's chips sets that setting (the chip was removed or now sets
     * another). The touchpad's and controllers' ones stay.
     */
    fun live(w: Workspace): List<SliderOverride> = if (w.sliders.none { it.by.isNotEmpty() }) w.sliders else w.sliders.filter { o ->
        o.by.isEmpty() || w.mappings.any { m -> m.target == o.target && MappingProfiles.touchId(m.source) == o.by }
    }

    /** [w] without overrides no chip keeps any more ([live]). */
    fun pruned(w: Workspace): Workspace = live(w).let { if (it.size == w.sliders.size) w else w.copy(sliders = it) }

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
            if (o.target is OutputTarget.GyroGlide || o.target is OutputTarget.TiltRange) {
                r = gyroValue(r, o.target, o.value); continue
            }
            val t = o.target as? OutputTarget.GyroSens ?: continue
            if (t.named > 0) continue
            val v = o.value
            r = if (t.axis == MotionAxis.NONE) r.copy(
                sensitivity = v, yawSensitivity = null, pitchSensitivity = null, rollSensitivity = null,
                yawScale = null, pitchScale = null, rollScale = null, yawLink = null, pitchLink = null, rollLink = null,
            ) else r.withSensitivity(t.axis, v)
        }
        return r
    }

    /**
     * [g] with Gyro glide or Tilt range [t] set to [v] (ms, degrees), within their limits; an
     * every-axis one clears the axes' own.
     */
    fun gyroValue(g: GyroSettings, t: OutputTarget, v: Float): GyroSettings = when (t) {
        is OutputTarget.GyroGlide -> {
            val ms = v.coerceIn(GyroSettings.MIN_GLIDE_MS, GyroSettings.MAX_GLIDE_MS)
            if (t.axis == MotionAxis.NONE) g.copy(glideMs = ms, yawGlideMs = null, pitchGlideMs = null, rollGlideMs = null)
            else g.withGlide(t.axis, g.ownGlide(t.axis).copy(glideMs = ms))
        }
        is OutputTarget.TiltRange -> {
            val deg = v.coerceIn(GyroSettings.MIN_TILT_RANGE_DEG, GyroSettings.MAX_TILT_RANGE_DEG)
            when (t.axis) {
                null -> g.copy(tiltRangeDeg = deg, tiltXRangeDeg = null, tiltYRangeDeg = null)
                StickAxis.X -> g.copy(tiltXRangeDeg = deg)
                StickAxis.Y -> g.copy(tiltYRangeDeg = deg)
            }
        }
        else -> g
    }

    /** [t] with the touch overrides of [list] over it. */
    fun overTouch(t: TouchpadSettings, list: List<SliderOverride>): TouchpadSettings {
        var r = t
        for (o in list) {
            val s = o.target as? OutputTarget.TouchSens ?: continue
            if (s.named > 0) continue
            val v = o.value
            r = when (s.axis) {
                null -> r.copy(sensitivity = v, moves = r.moves?.map { it.copy(sensitivity = null, scale = null, link = null) })
                StickAxis.X -> r.withMove(MoveSlot.X1) { it.copy(sensitivity = v, scale = null, link = null) }
                StickAxis.Y -> r.withMove(MoveSlot.Y1) { it.copy(sensitivity = v, scale = null, link = null) }
            }
        }
        return r
    }

    /** Named Gyro sens ([touch] false) or Touch sens [n]'s kept value in [list], or null when none set it. */
    fun named(list: List<SliderOverride>, touch: Boolean, n: Int): Float? = list.lastOrNull { o ->
        val t = o.target
        if (touch) t is OutputTarget.TouchSens && t.named == n else t is OutputTarget.GyroSens && t.named == n
    }?.value

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
