package com.oleroma.gyrophone.core.model

/**
 * Each workspace owns its mappings: its touch buttons' and touchpad's ([Workspace.mappings]), its
 * keyboard's ([Workspace.keyboard]), its controller set ([Workspace.controller]) and its mapping
 * sets ([Workspace.sets]). Nothing is shared between workspaces, so a duplicate can change freely.
 *
 * A set is kept in two forms: stored ([store]), where pads hold only their [AttachedPad.own]
 * mappings, and resolved ([resolve]), where every pad holds the mappings it acts with, as the
 * engine and the editors see it.
 */
object WorkspaceMappings {
    /** [set] as the engine and editors see it: every pad without its own mappings has its workspace's controller set. */
    fun resolve(set: Profile): Profile = set.copy(workspaces = set.workspaces.map(::resolve))

    fun resolve(w: Workspace): Workspace = w.withTouchpadMappings().let { r ->
        r.copy(
            mappings = Bindings.upgradeSwipes(r.mappings),
            pads = r.pads.map { it.copy(mappings = it.own ?: r.controller) },
        )
    }

    /** [set] as it is stored: pads keep only their own mappings. */
    fun store(set: Profile): Profile = set.copy(
        workspaces = set.workspaces.map { w ->
            w.copy(mappingProfileId = null, pads = w.pads.map { it.copy(mappings = emptyList(), presetId = null) })
        },
    )

    /**
     * [after] (an edit of the resolved [before]) with each workspace's controller set brought up to
     * date: a set changed in the workspace stays; else the mappings of a pad that changed since
     * [before] (not one with its own) become it, so every pad of the workspace follows; a newly
     * attached pad only gives its mappings when the set is empty. A workspace new in [after] with no
     * mappings of its own (a blank one) starts with a copy of [current]'s keyboard, controller and
     * touchpad mappings and its sets; one that comes with mappings (a builtin preset) keeps them.
     */
    fun settle(before: Profile, after: Profile, current: Workspace?): Profile = after.copy(
        workspaces = after.workspaces.map { w ->
            val old = before.workspaces.firstOrNull { it.id == w.id }
            val fresh = if (old == null) startFrom(w, current) else w
            val controller = when {
                old != null && fresh.controller != old.controller -> fresh.controller
                else -> {
                    val changed = fresh.pads.firstOrNull { p ->
                        if (p.own != null) return@firstOrNull false
                        val oldPad = old?.pads?.firstOrNull { it.id == p.id }
                        if (oldPad != null) p.mappings.toSet() != oldPad.mappings.toSet()
                        else fresh.controller.isEmpty() && p.mappings.isNotEmpty()
                    }
                    changed?.mappings ?: fresh.controller
                }
            }
            fresh.copy(controller = controller, pads = fresh.pads.map { it.copy(mappings = it.own ?: controller) })
        },
    )

    private fun startFrom(w: Workspace, current: Workspace?): Workspace {
        val own = w.mappings.any { !it.source.isTouchpad } || w.keyboard.isNotEmpty() ||
                w.controller.isNotEmpty() || w.pads.any { it.mappings.isNotEmpty() }
        if (own) {
            return if (w.controller.isNotEmpty()) w
            else w.copy(
                controller = w.pads.firstOrNull { it.own == null && it.mappings.isNotEmpty() }?.mappings
                    ?: KnownLayouts.familyMappings(FaceLayout.XBOX),
            )
        }
        if (current == null) return w.copy(controller = KnownLayouts.familyMappings(FaceLayout.XBOX))
        return w.copy(
            mappings = w.mappings.filterNot { it.source.isTouchpad } + current.mappings.filter { it.source.isTouchpad },
            touchpad = w.touchpad.copy(mapped = current.touchpad.mapped),
            keyboard = current.keyboard,
            controller = current.controller,
            sets = current.sets,
        )
    }

    /**
     * Moves the mappings of a set saved with mapping profiles into its workspaces: each gets its own
     * copy of the profile it used (only its own controls' touch mappings), so every input does what
     * it did. A workspace naming no profile uses the first, as before; with no profiles at all the
     * set is left as it is.
     */
    fun fromProfiles(set: Profile, profiles: List<MappingProfile>): Profile = set.copy(
        workspaces = set.workspaces.map { w ->
            val p = MappingProfiles.of(w, profiles) ?: return@map w
            MappingProfiles.resolve(w, p).copy(mappingProfileId = null, controller = p.controller)
        },
    )

    /** Ids used by any control or touch mapping of [set]: a new control must avoid them. */
    fun takenTouchIds(set: Profile): Set<String> =
        set.workspaces.flatMapTo(HashSet()) { w ->
            w.touchControls.map { it.id } + w.mappings.mapNotNull { MappingProfiles.touchId(it.source) }
        }

    /** [m] moved from touch control [from] to [to]; null when its source is not [from]'s. */
    fun rekey(m: Mapping, from: String, to: String): Mapping? {
        val s = m.source
        if (MappingProfiles.touchId(s) != from) return null
        val moved = when (s) {
            is InputSource.Touch -> InputSource.Touch(to)
            is InputSource.TouchStick -> s.copy(id = to)
            is InputSource.TouchStickDiagonal -> s.copy(id = to)
            is InputSource.TouchSwipe -> s.copy(id = to)
            is InputSource.TouchRelease -> s.copy(id = to)
            is InputSource.TouchDoubleTap -> s.copy(id = to)
            is InputSource.TouchTap -> s.copy(id = to)
            is InputSource.TouchLongPress -> s.copy(id = to)
            is InputSource.TouchArea -> s.copy(id = to)
            is InputSource.Surface -> s.copy(id = to)
            else -> return null
        }
        return m.copy(source = moved)
    }

    /** The mappings of control [from] among [mappings], moved to control [to]. */
    fun rekeyAll(mappings: List<Mapping>, from: String, to: String): List<Mapping> =
        mappings.mapNotNull { rekey(it, from, to) }

    /** What [copy] made: the workspace, and its buttons that found no match (they kept their mappings). */
    data class Copied(val workspace: Workspace, val unmatched: List<TouchControl>)

    /** The name a button is matched by in [copy]: its label, else its first mapping's short name. */
    fun buttonName(w: Workspace, c: TouchControl): String {
        if (c.label.isNotBlank()) return c.label.trim()
        val first = Bindings.touchInputs(c).firstNotNullOfOrNull { Bindings.targets(w.mappings, it).firstOrNull() }
        return first?.let { Describe.shortTarget(it) }.orEmpty()
    }

    /**
     * [target] with [source]'s mappings. Keyboard, controller and touchpad mappings are copied as
     * they are, and [source]'s sets join [target]'s (a set id both have takes [source]'s name).
     * [withButtons]: [target]'s buttons are replaced by [source]'s, with their places and mappings.
     * Else [target] keeps its buttons; each takes the mappings of the [source] button of the same
     * kind and name ([buttonName], ignoring case), and one with no match keeps its own.
     */
    fun copy(target: Workspace, source: Workspace, withButtons: Boolean): Copied {
        val src = resolve(source)
        val sets = (src.sets + target.sets.filter { t -> src.sets.none { it.id == t.id } }).sortedBy { it.id }
        val pads = target.pads.map { it.copy(own = null, mappings = src.controller) }
        val base = target.copy(
            keyboard = src.keyboard,
            controller = src.controller,
            pads = pads,
            sets = sets,
            touchpad = target.touchpad.copy(mapped = true),
        )
        val touchpadMappings = src.mappings.filter { it.source.isTouchpad }
        if (withButtons) {
            return Copied(
                base.copy(touchControls = src.touchControls, mappings = src.mappings.filterNot { it.source.isTouchpad } + touchpadMappings),
                emptyList(),
            )
        }
        val byName = src.touchControls.groupBy { it.kind to buttonName(src, it).lowercase() }
        val unmatched = ArrayList<TouchControl>()
        val touch = ArrayList<Mapping>()
        for (c in target.touchControls) {
            val name = buttonName(target, c).lowercase()
            val match = if (name.isEmpty()) null else byName[c.kind to name]?.firstOrNull()
            val mine = target.mappings.filter { MappingProfiles.touchId(it.source) == c.id }
            if (match == null) {
                touch += mine
                if (c.kind != TouchKind.FULLSCREEN) unmatched += c
            } else {
                touch += rekeyAll(src.mappings, match.id, c.id)
            }
        }
        return Copied(base.copy(mappings = touch + touchpadMappings), unmatched)
    }
}
