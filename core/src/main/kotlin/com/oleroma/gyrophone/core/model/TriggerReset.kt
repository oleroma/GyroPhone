package com.oleroma.gyrophone.core.model

/**
 * The one-time reset that came with mapping triggers and options (build of 2026-10-31): the
 * config goes back to a fresh install's, keeping only each workspace's name and layout (its
 * controls' kind, label, placement, shape, hit order, display and group) and the nav corners.
 * Mappings, layers, every setting, controller presets and game presets start over.
 */
object TriggerReset {
    /** [old] reset; [fresh] is what a new install starts with. */
    fun apply(old: Profile, fresh: Profile): Profile = fresh.copy(
        id = old.id,
        name = old.name,
        workspaces = old.workspaces.map { w ->
            Workspace(id = w.id, name = w.name, touchControls = w.touchControls.map(::layoutOnly), ownSettings = emptySet())
        },
        corners = old.corners,
        cornerOpacity = old.cornerOpacity,
        cornerSameSize = old.cornerSameSize,
        gyroCorner = false,
    )

    /** [c] with its layout only: every behaviour setting back to a new control's. */
    fun layoutOnly(c: TouchControl): TouchControl = TouchControl(
        id = c.id, kind = c.kind, label = c.label, x = c.x, y = c.y, size = c.size, shape = c.shape,
        portrait = c.portrait, aspect = c.aspect, layer = c.layer, display = c.display, group = c.group,
    )
}
