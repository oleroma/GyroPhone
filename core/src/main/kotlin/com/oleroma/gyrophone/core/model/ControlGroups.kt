package com.oleroma.gyrophone.core.model

/**
 * Edit mode groups: controls sharing a [TouchControl.group] id are selected, moved and sized as
 * one. Corners are never grouped. A group left with one member is no group.
 */
object ControlGroups {
    /** The ids moved with [id]: its group's members, else only itself. */
    fun unitOf(controls: List<TouchControl>, id: String): Set<String> {
        val g = controls.firstOrNull { it.id == id }?.group ?: return setOf(id)
        return controls.filter { it.group == g }.mapTo(LinkedHashSet()) { it.id }
    }

    /** [ids] with every group any of them is in, whole. */
    fun expand(controls: List<TouchControl>, ids: Collection<String>): Set<String> =
        ids.flatMapTo(LinkedHashSet()) { unitOf(controls, it) }

    /** How many separate things [ids] are: each group counts once, each ungrouped control once. */
    fun units(controls: List<TouchControl>, ids: Collection<String>): Int {
        val byId = controls.associateBy { it.id }
        val found = ids.mapNotNull { byId[it] }
        return found.count { it.group == null } + found.mapNotNullTo(HashSet()) { it.group }.size
    }

    /** Whether any of [ids] is in a group. */
    fun anyGrouped(controls: List<TouchControl>, ids: Collection<String>): Boolean =
        controls.any { it.id in ids && it.group != null }

    /** Puts [ids] (and the groups they are in) into one new group. */
    fun group(workspace: Workspace, ids: Collection<String>): Workspace {
        val members = expand(workspace.touchControls, ids)
        if (members.size < 2) return workspace
        val taken = workspace.touchControls.mapNotNullTo(HashSet()) { it.group }
        val id = generateSequence(1) { it + 1 }.map { "g$it" }.first { it !in taken }
        return workspace.copy(touchControls = workspace.touchControls.map {
            if (it.id in members) it.copy(group = id) else it
        })
    }

    /** Takes every group any of [ids] is in apart. */
    fun ungroup(workspace: Workspace, ids: Collection<String>): Workspace {
        val groups = workspace.touchControls.filter { it.id in ids }.mapNotNullTo(HashSet()) { it.group }
        if (groups.isEmpty()) return workspace
        return workspace.copy(touchControls = workspace.touchControls.map {
            if (it.group in groups) it.copy(group = null) else it
        })
    }

    /** Clears the group of a control that is the only one left in it (the others deleted). */
    fun tidy(workspace: Workspace): Workspace {
        val counts = workspace.touchControls.mapNotNull { it.group }.groupingBy { it }.eachCount()
        if (counts.values.all { it >= 2 }) return workspace
        return workspace.copy(touchControls = workspace.touchControls.map {
            if (it.group != null && (counts[it.group] ?: 0) < 2) it.copy(group = null) else it
        })
    }
}
