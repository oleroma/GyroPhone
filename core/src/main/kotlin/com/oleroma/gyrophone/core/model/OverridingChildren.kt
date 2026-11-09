package com.oleroma.gyrophone.core.model

import kotlin.math.abs
import kotlin.math.roundToLong

/** A child that sets a setting itself: its [name] ("Workspace › Layer › Button") and its [value] shown short (null = none to show). */
data class ChildOverride(val name: String, val value: String?)

/**
 * Who sets a setting ([WorkspaceDefaults] key) below a level, so the level's value doesn't reach
 * them: under the Defaults every workspace, layer and button; under a workspace its layers and
 * buttons; under a layer the buttons (a button's own value wins over every layer's). And setting
 * them back to follow the level ("Reset children").
 */
object OverridingChildren {
    private const val SEP = " › "

    /** The children of the Defaults of [p] that set any of [keys] themselves. */
    fun ofDefaults(p: Profile, keys: List<String>): List<ChildOverride> = p.workspaces.flatMap { w ->
        val own = WorkspaceDefaults.own(w)
        keys.filter { it in own }.take(1).map { k -> ChildOverride(w.name, text(k, WorkspaceDefaults.valueOf(k, w))) } +
            ofWorkspace(w, keys, w.name + SEP)
    }

    /** The children of workspace [w] (its layers and buttons) that set any of [keys]; names start with [prefix]. */
    fun ofWorkspace(w: Workspace, keys: List<String>, prefix: String = ""): List<ChildOverride> =
        w.sets.filter { it.id != MappingSet.BASE }.mapNotNull { set ->
            val own = WorkspaceDefaults.layerOwn(set)
            val k = keys.firstOrNull { it in own } ?: return@mapNotNull null
            ChildOverride(prefix + set.label, text(k, WorkspaceDefaults.valueOf(k, set.settings!!)))
        } + buttons(w, keys, prefix, null)

    /** The buttons of [w] that set any of [keys] over layer [setId]: their own Base values and that layer's. */
    fun ofLayer(w: Workspace, setId: Int, keys: List<String>): List<ChildOverride> = buttons(w, keys, "", setId)

    /** [p] with every child of its Defaults following them for [keys] again. */
    fun resetDefaults(p: Profile, keys: List<String>): Profile = p.copy(
        workspaces = p.workspaces.map { w ->
            val own = WorkspaceDefaults.own(w)
            resetWorkspace(keys.filter { it in own }.fold(w) { r, k -> WorkspaceDefaults.reset(r, k, p.defaults) }, keys)
        },
    )

    /** [w] with its layers and buttons following it for [keys] again. */
    fun resetWorkspace(w: Workspace, keys: List<String>): Workspace = resetButtons(
        w.copy(sets = w.sets.map { s -> keys.fold(s) { r, k -> WorkspaceDefaults.resetLayer(r, k) } }),
        keys, null,
    )

    /** [w] with its buttons following layer [setId] for [keys] again. */
    fun resetLayer(w: Workspace, setId: Int, keys: List<String>): Workspace = resetButtons(w, keys, setId)

    /** A value shown short: numbers trimmed, switches On / Off, enums by label; a factor ([WorkspaceDefaults.isFactor]) as ×. */
    fun text(key: String, v: Any?, factor: Boolean = WorkspaceDefaults.isFactor(key)): String? {
        val s = when (v) {
            null -> return null
            is Boolean -> if (v) "On" else "Off"
            is Float -> num(v.toDouble())
            is Double -> num(v)
            is Number -> v.toString()
            is GyroMode -> v.label
            is GyroSpace -> v.label
            is YawAxis -> v.label
            is InertiaCurve -> v.label
            is MotionAxis -> v.label
            is HapticEdge -> v.label
            is Enum<*> -> v.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            is String -> v.ifBlank { return null }
            else -> return null
        }
        return if (factor) "×$s" else s
    }

    private fun num(v: Double): String {
        val r = (v * 100).roundToLong() / 100.0
        return if (abs(r - r.roundToLong()) < 1e-9) r.roundToLong().toString() else r.toString()
    }

    /** One setting a button sets itself: its key, its value, whether that is a factor, and the button without it. */
    private class Own(val key: String, val value: Any?, val factor: Boolean, val drop: (TouchControl) -> TouchControl)

    private fun controlName(c: TouchControl) = c.label.ifBlank { "Button" }

    /** What [c] sets itself in [w], beside its gyro ([gyroOwn]). */
    private fun surfaceOwn(c: TouchControl, w: Workspace): List<Own> {
        val out = ArrayList<Own>()
        if (c.longPressMs != null) out += Own(WorkspaceDefaults.LONG_PRESS, c.longPressMs, false) { it.copy(longPressMs = null) }
        val own = c.ownSurface
        val eff by lazy { c.surfaceSettings(w.touchpad) }
        SurfaceField.entries.filter { it.button && it.isOwn(own) }.forEach { f ->
            out += Own(WorkspaceDefaults.touchpadKey(f), f.get(eff), false) { it.copy(surface = f.drop(it.ownSurface)) }
        }
        Direction.entries.forEach { d ->
            DirField.entries.filter { it.isOwn(own, d) }.forEach { f ->
                out += Own(WorkspaceDefaults.dirKey(d, f), f.get(own.dirs!![d.ordinal]), false) { it.copy(surface = f.drop(it.ownSurface, d)) }
            }
        }
        MoveSlot.entries.forEach { m ->
            MoveField.of(m).filter { it.isOwn(own, m) }.forEach { f ->
                out += Own(WorkspaceDefaults.moveKey(m, f), f.get(own.moves!![m.ordinal]), f == MoveField.SCALE) { it.copy(surface = f.drop(it.ownSurface, m)) }
            }
        }
        return out
    }

    /** What [g] (one of a button's gyro sets) sets itself. */
    private fun gyroOwn(g: ButtonGyro): List<Own> {
        fun k(p: String) = WorkspaceDefaults.gyroKey(p)
        val out = ArrayList<Own>()
        fun add(key: String, v: Any?, factor: Boolean = false, drop: (ButtonGyro) -> ButtonGyro) {
            if (v != null) out += Own(key, v, factor) { c -> c.copy(gyro = c.gyro.map { if (it == g) drop(it) else it }.filter { it.any }) }
        }
        add(k("sensitivity"), g.sensitivity) { it.copy(sensitivity = null) }
        add(k("accel"), g.accel) { it.copy(accel = null) }
        for (a in listOf(MotionAxis.YAW, MotionAxis.PITCH, MotionAxis.ROLL)) {
            val glide = g.ownGlide(a)
            when (a) {
                MotionAxis.YAW -> {
                    add(k("yawSensitivity"), g.yaw) { it.copy(yaw = null) }
                    add(k("yawScale"), g.yawScale, true) { it.copy(yawScale = null) }
                    add(k("yawMode"), g.yawMode) { it.copy(yawMode = null) }
                    add(k("yawAccel"), g.yawAccel) { it.copy(yawAccel = null) }
                }
                MotionAxis.PITCH -> {
                    add(k("pitchSensitivity"), g.pitch) { it.copy(pitch = null) }
                    add(k("pitchScale"), g.pitchScale, true) { it.copy(pitchScale = null) }
                    add(k("pitchMode"), g.pitchMode) { it.copy(pitchMode = null) }
                    add(k("pitchAccel"), g.pitchAccel) { it.copy(pitchAccel = null) }
                }
                MotionAxis.ROLL -> {
                    add(k("rollSensitivity"), g.roll) { it.copy(roll = null) }
                    add(k("rollScale"), g.rollScale, true) { it.copy(rollScale = null) }
                    add(k("rollMode"), g.rollMode) { it.copy(rollMode = null) }
                    add(k("rollAccel"), g.rollAccel) { it.copy(rollAccel = null) }
                }
                MotionAxis.NONE -> {}
            }
            val p = a.name.lowercase()
            add(k(p + "Inertia"), glide.inertia) { it.withGlide(a, it.ownGlide(a).copy(inertia = null)) }
            add(k(p + "GlideMs"), glide.glideMs) { it.withGlide(a, it.ownGlide(a).copy(glideMs = null)) }
            add(k(p + "Curve"), glide.curve) { it.withGlide(a, it.ownGlide(a).copy(curve = null)) }
        }
        val sens = WorkspaceDefaults.touchpadKey(SurfaceField.SENSITIVITY)
        add(sens, g.touchSensitivity) { it.copy(touchSensitivity = null) }
        add(sens, g.touchScale, true) { it.copy(touchScale = null) }
        add(WorkspaceDefaults.touchpadKey(SurfaceField.ACCELERATION), g.touchAcceleration) { it.copy(touchAcceleration = null) }
        return out
    }

    /** The gyro sets of [c] a level sees: under a workspace ([setId] null) all, under layer [setId] Base's and its own. */
    private fun gyroSets(c: TouchControl, setId: Int?) =
        c.gyro.filter { setId == null || it.set == MappingSet.BASE || it.set == setId }

    private fun buttons(w: Workspace, keys: List<String>, prefix: String, setId: Int?): List<ChildOverride> {
        val out = ArrayList<ChildOverride>()
        for (c in w.touchControls) {
            val name = prefix + controlName(c)
            surfaceOwn(c, w).firstOrNull { it.key in keys }?.let { out += ChildOverride(name, text(it.key, it.value, it.factor)) }
            for (g in gyroSets(c, setId)) {
                val o = gyroOwn(g).firstOrNull { it.key in keys } ?: continue
                val layer = if (g.set == MappingSet.BASE) "" else (w.sets.firstOrNull { it.id == g.set }?.label ?: "Layer ${g.set}") + SEP
                out += ChildOverride(prefix + layer + controlName(c), text(o.key, o.value, o.factor))
            }
        }
        return out
    }

    private fun resetButtons(w: Workspace, keys: List<String>, setId: Int?): Workspace = w.copy(
        touchControls = w.touchControls.map { c0 ->
            var c = c0
            surfaceOwn(c0, w).filter { it.key in keys }.forEach { c = it.drop(c) }
            // Each drop is of one gyro set as it was, so drop them one at a time against the current button.
            while (true) {
                val o = gyroSets(c, setId).firstNotNullOfOrNull { g -> gyroOwn(g).firstOrNull { it.key in keys } } ?: break
                c = o.drop(c)
            }
            c
        },
    )
}
