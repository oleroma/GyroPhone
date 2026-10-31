package com.oleroma.gyrophone.core.model

/**
 * Turns each button's own gyro settings ([TouchControl.gyro], from before layers held them) into
 * a layer named after the button, held while it is pressed: an override layer with those gyro
 * settings and no mappings, so the button still does what it did. One per button and set it had
 * them in, while there is room ([MappingSet.MAX]); past that they are dropped. Running it again
 * changes nothing.
 */
object ButtonGyroLayers {
    /** [w] upgraded; the same instance when nothing changed. */
    fun workspace(w: Workspace): Workspace {
        if (w.touchControls.none { it.gyro.isNotEmpty() }) return w
        val sets = w.sets.toMutableList()
        val added = ArrayList<Mapping>()
        for (c in w.touchControls) for (g in c.gyro) {
            if (!g.any) continue
            val id = (1..MappingSet.MAX).firstOrNull { i -> sets.none { it.id == i } } ?: break
            val under = if (g.set == MappingSet.BASE) emptyList() else listOf(g.set)
            val before = WorkspaceDefaults.layered(w, MappingSet.BASE, under)
            val after = before.copy(gyro = g.over(before.gyro))
            sets += WorkspaceDefaults.editLayer(MappingSet(id, c.label.ifBlank { "Button" }), before, after)
            added += Mapping(InputSource.Touch(c.id), OutputTarget.Layer(id, override = true, whileHeld = true), set = g.set)
        }
        return w.copy(
            sets = sets,
            mappings = w.mappings + added,
            touchControls = w.touchControls.map { if (it.gyro.isEmpty()) it else it.copy(gyro = emptyList()) },
        )
    }

    /** [p] with every workspace upgraded; the same instance when nothing changed. */
    fun profile(p: Profile): Profile {
        val workspaces = p.workspaces.map(::workspace)
        return if (workspaces.indices.all { workspaces[it] === p.workspaces[it] }) p else p.copy(workspaces = workspaces)
    }
}
