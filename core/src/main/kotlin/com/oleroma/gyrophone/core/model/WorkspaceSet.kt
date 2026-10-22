package com.oleroma.gyrophone.core.model

/**
 * The app keeps every workspace in one [Profile] (there are no game profiles to pick). Phones
 * that had several profiles get them merged once by [merge]; the old files stay on disk untouched.
 */
object WorkspaceSet {
    const val ID = "user.workspaces"
    const val NAME = "Workspaces"

    /**
     * One profile with [active]'s workspaces first (so the current workspace index still holds),
     * then each of [others]' workspaces, named "<workspace> · <profile>" and given unused ids.
     * Corners and wrap-around come from [active].
     */
    fun merge(active: Profile, others: List<Profile>): Profile {
        val taken = active.workspaces.mapTo(HashSet()) { it.id }
        val extra = others.flatMap { p ->
            p.workspaces.map { w ->
                val id = if (w.id in taken) ProfileJson.newWorkspaceId(taken) else w.id
                taken += id
                w.copy(id = id, name = "${w.name} · ${p.name}")
            }
        }
        return active.copy(id = ID, name = NAME, workspaces = active.workspaces + extra, games = emptyList(), controllers = emptyList())
    }
}
