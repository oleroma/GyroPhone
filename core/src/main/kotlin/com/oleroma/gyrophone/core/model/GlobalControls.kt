package com.oleroma.gyrophone.core.model

/**
 * Global controls ([TouchControl.global]): one control in every workspace, with the same id,
 * place, size, look and mappings (its touch mappings in every set). A change to it in one
 * workspace is made in all of them, a new workspace gets it, and deleting it in one deletes it
 * everywhere. Make local turns every copy into a workspace's own control again.
 */
object GlobalControls {
    /** [w]'s mappings of control [id]. */
    private fun mappingsOf(w: Workspace, id: String): List<Mapping> =
        w.mappings.filter { MappingProfiles.touchId(it.source) == id }

    /** Control [id] of [w] with its mappings, or null. */
    private fun copyIn(w: Workspace, id: String): Pair<TouchControl, List<Mapping>>? =
        w.touchControls.firstOrNull { it.id == id }?.let { it to mappingsOf(w, id) }

    /** The ids of the global controls of [p]. */
    fun ids(p: Profile): Set<String> =
        p.workspaces.flatMapTo(LinkedHashSet()) { w -> w.touchControls.filter { it.global }.map { it.id } }

    /**
     * [new] with its global controls made the same in every workspace, after a change from [old]
     * (null: no earlier version). A copy that changed is copied to the others; one deleted, or
     * made local, in a workspace is deleted, or made local, in all.
     */
    fun sync(old: Profile?, new: Profile): Profile {
        val oldIds = old?.let(::ids) ?: emptySet()
        val ids = oldIds + ids(new)
        if (ids.isEmpty()) return new
        var p = new
        for (id in ids) {
            val oldById = old?.workspaces?.associate { it.id to copyIn(it, id) } ?: emptyMap()
            val copies = p.workspaces.map { copyIn(it, id) }
            if (id in oldIds) {
                // Deleted in a workspace that had it: deleted everywhere.
                val deleted = p.workspaces.indices.any { i -> copies[i] == null && oldById[p.workspaces[i].id]?.first?.global == true }
                if (deleted) {
                    p = p.copy(workspaces = p.workspaces.map { w ->
                        if (copyIn(w, id)?.first?.global == true) Bindings.removeTouchControl(w, id) else w
                    })
                    continue
                }
                // Made local in one: local in all (each workspace keeps its own copy).
                val madeLocal = p.workspaces.indices.any { i ->
                    copies[i]?.first?.global == false && oldById[p.workspaces[i].id]?.first?.global == true
                }
                if (madeLocal) {
                    p = p.copy(workspaces = p.workspaces.map { w ->
                        w.copy(touchControls = w.touchControls.map { if (it.id == id && it.global) it.copy(global = false) else it })
                    })
                    continue
                }
            }
            // The copy that changed (else the first one) goes to every workspace.
            val source = p.workspaces.indices.firstOrNull { i ->
                copies[i]?.first?.global == true && copies[i] != oldById[p.workspaces[i].id]
            }?.let { copies[it] } ?: copies.firstOrNull { it?.first?.global == true } ?: continue
            // A workspace with a control of its own under that id keeps it and gets no copy.
            p = p.copy(workspaces = p.workspaces.map { w ->
                if (w.touchControls.any { it.id == id && !it.global }) w else put(w, source.first, source.second)
            })
        }
        return p
    }

    /** [w] with control [c] (in place of its copy, else added last) and its [mappings]. */
    private fun put(w: Workspace, c: TouchControl, mappings: List<Mapping>): Workspace {
        val have = copyIn(w, c.id)
        if (have != null && have.first == c && have.second == mappings) return w
        val controls = if (have == null) w.touchControls + c else w.touchControls.map { if (it.id == c.id) c else it }
        return w.copy(
            touchControls = controls,
            mappings = w.mappings.filterNot { MappingProfiles.touchId(it.source) == c.id } + mappings,
        )
    }

    /**
     * Make global: control [id] of workspace [index] goes to every workspace (out of its group:
     * groups belong to one workspace). A workspace that has another control with that id keeps it
     * and gets none.
     */
    fun makeGlobal(p: Profile, index: Int, id: String): Profile {
        val w = p.workspace(index)
        val (c, mappings) = copyIn(w, id) ?: return p
        val g = c.copy(global = true, group = null)
        return p.copy(workspaces = p.workspaces.mapIndexed { i, x ->
            when {
                i == index || x.id == w.id -> ControlGroups.tidy(put(x, g, mappings))
                x.touchControls.any { it.id == id } -> x
                else -> put(x, g, mappings)
            }
        })
    }

    /** Make local: every copy of control [id] becomes its workspace's own control. */
    fun makeLocal(p: Profile, id: String): Profile = p.copy(workspaces = p.workspaces.map { w ->
        if (w.touchControls.none { it.id == id && it.global }) w
        else w.copy(touchControls = w.touchControls.map { if (it.id == id) it.copy(global = false) else it })
    })

    /** [w] (a new workspace of [p]) with [p]'s global controls added. */
    fun into(p: Profile, w: Workspace): Workspace {
        var out = w
        for (id in ids(p)) {
            if (out.touchControls.any { it.id == id }) continue // its own control keeps the id
            val from = p.workspaces.firstNotNullOfOrNull { copyIn(it, id)?.takeIf { c -> c.first.global } } ?: continue
            out = put(out, from.first, from.second)
        }
        return out
    }
}
