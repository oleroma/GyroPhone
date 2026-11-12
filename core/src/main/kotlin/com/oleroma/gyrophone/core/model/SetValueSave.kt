package com.oleroma.gyrophone.core.model

/**
 * Where a Set value sensitivity chip with no end ([SetValue], [OutputTarget.GyroSens],
 * [OutputTarget.TouchSens]) writes its value as the finger lifts: into the setting nearest to it
 * that sets that sensitivity, along the same chain the engine reads it by. Its button's own
 * (while held), else the topmost layer in use that sets it, else the workspace's own, else the
 * Defaults. A factor (× parent) there becomes the exact value.
 */
object SetValueSave {
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
        else -> null
    }

    /** The factor key beside [keyOf] (an axis's × parent), if it has one. */
    private fun scaleKeyOf(t: OutputTarget): String? = when (t) {
        is OutputTarget.GyroSens -> when (t.axis) {
            MotionAxis.NONE -> null
            MotionAxis.YAW -> WorkspaceDefaults.gyroKey("yawScale")
            MotionAxis.PITCH -> WorkspaceDefaults.gyroKey("pitchScale")
            MotionAxis.ROLL -> WorkspaceDefaults.gyroKey("rollScale")
        }
        is OutputTarget.TouchSens -> when (t.axis) {
            null -> null
            StickAxis.X -> WorkspaceDefaults.moveKey(MoveSlot.X1, MoveField.SCALE)
            StickAxis.Y -> WorkspaceDefaults.moveKey(MoveSlot.Y1, MoveField.SCALE)
        }
        else -> null
    }

    /** [w] with target [t]'s sensitivity set to [v] (an exact value, ending a factor). */
    fun write(w: Workspace, t: OutputTarget, v: Float): Workspace = when (t) {
        is OutputTarget.GyroSens -> w.copy(
            gyro = when (t.axis) {
                MotionAxis.NONE -> w.gyro.copy(sensitivity = v)
                MotionAxis.YAW -> w.gyro.copy(yawSensitivity = v, yawScale = null)
                MotionAxis.PITCH -> w.gyro.copy(pitchSensitivity = v, pitchScale = null)
                MotionAxis.ROLL -> w.gyro.copy(rollSensitivity = v, rollScale = null)
            }
        )
        is OutputTarget.TouchSens -> w.copy(
            touchpad = when (t.axis) {
                null -> w.touchpad.copy(sensitivity = v)
                StickAxis.X -> w.touchpad.withMove(MoveSlot.X1) { it.copy(sensitivity = v, scale = null) }
                StickAxis.Y -> w.touchpad.withMove(MoveSlot.Y1) { it.copy(sensitivity = v, scale = null) }
            }
        )
        else -> w
    }

    /** Button sensitivities [g] set what target [t] drives themselves. */
    private fun buttonSets(g: ButtonGyro, t: OutputTarget): Boolean = when (t) {
        is OutputTarget.GyroSens -> if (t.axis == MotionAxis.NONE) g.sensitivity != null else g.own(t.axis) != null || g.ownScale(t.axis) != null
        is OutputTarget.TouchSens -> t.axis == null && (g.touchSensitivity != null || g.touchScale != null)
        else -> false
    }

    private fun buttonWrite(g: ButtonGyro, t: OutputTarget, v: Float): ButtonGyro = when (t) {
        is OutputTarget.GyroSens -> if (t.axis == MotionAxis.NONE) g.copy(sensitivity = v) else g.with(t.axis, v)
        is OutputTarget.TouchSens -> g.withTouch(v)
        else -> g
    }

    /**
     * Profile [p] with [value] written for chip [m] on workspace [wi], [sets] in use (Base first):
     * see [SetValueSave]. A workspace setting written here is marked as the workspace's own by
     * the edit as ever ([WorkspaceDefaults.markOwn]); the Defaults are written only when nothing
     * below sets it.
     */
    fun apply(p: Profile, wi: Int, sets: List<Int>, m: Mapping, value: Float): Profile {
        val t = m.target
        val key = keyOf(t) ?: return p
        val scaleKey = scaleKeyOf(t)
        val w = p.workspace(wi)
        val top = sets.reversed()
        fun withWs(n: Workspace) = p.copy(workspaces = p.workspaces.map { if (it.id == w.id) n else it })
        // Its button's own, in the topmost set in use that has one.
        MappingProfiles.touchId(m.source)?.takeIf { it.isNotEmpty() }?.let { id ->
            val c = w.touchControls.firstOrNull { it.id == id }
            if (c != null) for (s in top) {
                val g = c.gyro.firstOrNull { it.set == s } ?: continue
                if (!buttonSets(g, t)) continue
                val nc = c.withGyro(s) { buttonWrite(it, t, value) }
                return withWs(w.copy(touchControls = w.touchControls.map { if (it.id == id) nc else it }))
            }
        }
        // The topmost layer in use that sets it.
        for (s in top) {
            if (s == MappingSet.BASE) continue
            val set = w.sets.firstOrNull { it.id == s } ?: continue
            val own = WorkspaceDefaults.layerOwn(set)
            if (key !in own && (scaleKey == null || scaleKey !in own)) continue
            val before = WorkspaceDefaults.overlay(w, set)
            val ns = WorkspaceDefaults.editLayer(set, before, write(before, t, value))
            return withWs(w.copy(sets = w.sets.map { if (it.id == s) ns else it }))
        }
        // The workspace's own.
        val own = WorkspaceDefaults.own(w)
        if (key in own || (scaleKey != null && scaleKey in own)) return withWs(write(w, t, value))
        // Nothing below sets it: the Defaults.
        return p.copy(defaults = write(p.defaults, t, value))
    }
}
