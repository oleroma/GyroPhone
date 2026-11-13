package com.oleroma.gyrophone.core.model

/**
 * Edit mode groups (and the Sync menu's size and toggle syncs): controls sharing a [TouchControl.group] id are selected, moved and sized as
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

    /** Whether [ids] (two or more) are all buttons with How Toggle: they can be toggle-synced. */
    fun canSyncToggles(controls: List<TouchControl>, ids: Collection<String>): Boolean {
        val found = controls.filter { it.id in ids }
        return found.size >= 2 && found.all { it.kind == TouchKind.BUTTON && it.how == How.TOGGLE }
    }

    /** Whether [ids] can be synced "Never both": toggle-syncable, all with the same initial state. */
    fun canNeverBoth(controls: List<TouchControl>, ids: Collection<String>): Boolean =
        canSyncToggles(controls, ids) && controls.filter { it.id in ids }.map { it.initialOn }.distinct().size == 1

    /** The rule of the toggle sync all of [ids] share, or null when they don't share one. */
    fun toggleRuleOf(controls: List<TouchControl>, ids: Collection<String>): ToggleRule? {
        val found = controls.filter { it.id in ids }
        val s = found.firstOrNull()?.toggleSync ?: return null
        if (found.any { it.toggleSync != s }) return null
        return found.first().toggleRule
    }

    /** Whether any of [ids] is toggle-synced. */
    fun anyToggleSynced(controls: List<TouchControl>, ids: Collection<String>): Boolean =
        controls.any { it.id in ids && it.toggleSync != null }

    /**
     * Toggle-syncs [ids] by [rule] ([TouchControl.toggleSync]): they leave the toggle syncs they
     * were in (a button is in one at a time). Nothing when they can't ([canSyncToggles], [canNeverBoth]).
     */
    fun syncToggles(workspace: Workspace, ids: Collection<String>, rule: ToggleRule): Workspace {
        val cs = workspace.touchControls
        if (!(if (rule == ToggleRule.NEVER_BOTH) canNeverBoth(cs, ids) else canSyncToggles(cs, ids))) return workspace
        val taken = cs.mapNotNullTo(HashSet()) { it.toggleSync }
        val id = generateSequence(1) { it + 1 }.map { "t$it" }.first { it !in taken }
        return tidy(workspace.copy(touchControls = cs.map {
            if (it.id in ids) it.copy(toggleSync = id, toggleRule = rule) else it
        }))
    }

    /** Takes every toggle sync any of [ids] is in apart. */
    fun unsyncToggles(workspace: Workspace, ids: Collection<String>): Workspace {
        val syncs = workspace.touchControls.filter { it.id in ids }.mapNotNullTo(HashSet()) { it.toggleSync }
        if (syncs.isEmpty()) return workspace
        return workspace.copy(touchControls = workspace.touchControls.map {
            if (it.toggleSync in syncs) it.copy(toggleSync = null, toggleRule = ToggleRule.FLIP) else it
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
        val toggles = workspace.touchControls.mapNotNull { it.toggleSync }.groupingBy { it }.eachCount()
        if (counts.values.all { it >= 2 } && syncs.values.all { it >= 2 } && toggles.values.all { it >= 2 }) return workspace
        return workspace.copy(touchControls = workspace.touchControls.map {
            var c = it
            if (c.group != null && (counts[c.group] ?: 0) < 2) c = c.copy(group = null)
            if (c.sizeSync != null && (syncs[c.sizeSync] ?: 0) < 2) c = c.copy(sizeSync = null)
            if (c.toggleSync != null && (toggles[c.toggleSync] ?: 0) < 2) c = c.copy(toggleSync = null, toggleRule = ToggleRule.FLIP)
            c
        })
    }
}
