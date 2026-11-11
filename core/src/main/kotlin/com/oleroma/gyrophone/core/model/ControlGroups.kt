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
        // A control is grouped or size-synced, never both: grouping takes it out of its sync.
        return tidy(workspace.copy(touchControls = workspace.touchControls.map {
            if (it.id in members) it.copy(group = id, sizeSync = null) else it
        }))
    }

    /** The ids that change size with [id]: its size sync's members, else only itself. */
    fun syncedOf(controls: List<TouchControl>, id: String): Set<String> {
        val s = controls.firstOrNull { it.id == id }?.sizeSync ?: return setOf(id)
        return controls.filter { it.sizeSync == s }.mapTo(LinkedHashSet()) { it.id }
    }

    /** Whether any of [ids] is size-synced. */
    fun anySynced(controls: List<TouchControl>, ids: Collection<String>): Boolean =
        controls.any { it.id in ids && it.sizeSync != null }

    /**
     * Puts [ids] (and the syncs they are in) into one new size sync. They leave their groups:
     * a control is grouped or synced, never both.
     */
    fun syncSize(workspace: Workspace, ids: Collection<String>): Workspace {
        val members = ids.flatMapTo(LinkedHashSet()) { syncedOf(workspace.touchControls, it) }
            .filterTo(LinkedHashSet()) { id -> workspace.touchControls.any { it.id == id } }
        if (members.size < 2) return workspace
        val taken = workspace.touchControls.mapNotNullTo(HashSet()) { it.sizeSync }
        val id = generateSequence(1) { it + 1 }.map { "s$it" }.first { it !in taken }
        return tidy(workspace.copy(touchControls = workspace.touchControls.map {
            if (it.id in members) it.copy(sizeSync = id, group = null) else it
        }))
    }

    /** Takes every size sync any of [ids] is in apart. */
    fun unsync(workspace: Workspace, ids: Collection<String>): Workspace {
        val syncs = workspace.touchControls.filter { it.id in ids }.mapNotNullTo(HashSet()) { it.sizeSync }
        if (syncs.isEmpty()) return workspace
        return workspace.copy(touchControls = workspace.touchControls.map {
            if (it.sizeSync in syncs) it.copy(sizeSync = null) else it
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

    /** Clears the group or size sync of a control that is the only one left in it (the others deleted or moved). */
    fun tidy(workspace: Workspace): Workspace {
        val counts = workspace.touchControls.mapNotNull { it.group }.groupingBy { it }.eachCount()
        val syncs = workspace.touchControls.mapNotNull { it.sizeSync }.groupingBy { it }.eachCount()
        if (counts.values.all { it >= 2 } && syncs.values.all { it >= 2 }) return workspace
        return workspace.copy(touchControls = workspace.touchControls.map {
            var c = it
            if (c.group != null && (counts[c.group] ?: 0) < 2) c = c.copy(group = null)
            if (c.sizeSync != null && (syncs[c.sizeSync] ?: 0) < 2) c = c.copy(sizeSync = null)
            c
        })
    }
}
