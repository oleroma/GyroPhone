package com.oleroma.gyrophone.core.model

/** Edits of a profile's workspace list (edit-mode workspace bar). A profile keeps at least one. */
object WorkspaceEdits {
    /** Inserts [workspace] (given a fresh id) at [at] (default: the end); returns the profile and its index. */
    fun add(profile: Profile, workspace: Workspace, at: Int = profile.workspaces.size): Pair<Profile, Int> {
        val i = at.coerceIn(0, profile.workspaces.size)
        val w = workspace.copy(id = ProfileJson.newWorkspaceId(profile.workspaces.map { it.id }))
        val list = profile.workspaces.toMutableList().apply { add(i, w) }
        return profile.copy(workspaces = list) to i
    }

    /** A copy of the workspace at [index] right after it, named "<name> copy". */
    fun duplicate(profile: Profile, index: Int): Pair<Profile, Int> {
        val w = profile.workspace(index)
        return add(profile, w.copy(name = "${w.name} copy"), index.coerceIn(0, profile.workspaces.lastIndex) + 1)
    }

    /** Removes the workspace at [index] unless it is the only one. */
    fun delete(profile: Profile, index: Int): Profile =
        if (profile.workspaces.size <= 1 || index !in profile.workspaces.indices) profile
        else profile.copy(workspaces = profile.workspaces.filterIndexed { k, _ -> k != index })

    /** Moves the workspace at [from] to position [to]. */
    fun move(profile: Profile, from: Int, to: Int): Profile {
        if (from !in profile.workspaces.indices) return profile
        val list = profile.workspaces.toMutableList()
        val w = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), w)
        return profile.copy(workspaces = list)
    }

    fun rename(profile: Profile, index: Int, name: String): Profile =
        profile.updateWorkspace(index) { it.copy(name = name) }

    /** Where the active index lands after [move] (the moved one follows; others shift). */
    fun indexAfterMove(active: Int, from: Int, to: Int): Int = when {
        active == from -> to
        from < active && to >= active -> active - 1
        from > active && to <= active -> active + 1
        else -> active
    }
}

/**
 * This workspace with mapping [sets]; set [removed] (if any) goes with its mappings and the layer
 * actions that switch to it, on the controls, the touchpad, the keyboard, the mouse, the phone's keys
 * and every pad, and with the buttons' own gyro settings in it ([TouchControl.gyro]).
 * Its pinned device preset keeps them: [Profile.withSets] removes them there too.
 */
fun Workspace.withSets(sets: List<MappingSet>, removed: Int?): Workspace =
    if (removed == null) copy(sets = sets) else copy(
        sets = sets,
        mappings = Bindings.dropSet(mappings, removed),
        keyboard = Bindings.dropSet(keyboard, removed),
        mouse = Bindings.dropSet(mouse, removed),
        phone = Bindings.dropSet(phone, removed),
        pads = pads.map { it.copy(mappings = Bindings.dropSet(it.mappings, removed)) },
        touchControls = touchControls.map { c ->
            if (c.gyro.none { it.set == removed }) c else c.copy(gyro = c.gyro.filterNot { it.set == removed })
        },
    )

/**
 * This profile with workspace [index]'s mapping [sets] ([Workspace.withSets]). A [removed] set also
 * goes from the device preset that workspace pins ([DevicePresets.dropSet]), so no button there
 * switches to a layer that no longer exists (and a new layer reusing its id starts empty).
 */
fun Profile.withSets(index: Int, sets: List<MappingSet>, removed: Int?): Profile {
    val next = updateWorkspace(index) { it.withSets(sets, removed) }
    return if (removed == null) next else DevicePresets.dropSet(next, next.workspace(index).devicePreset, removed)
}
