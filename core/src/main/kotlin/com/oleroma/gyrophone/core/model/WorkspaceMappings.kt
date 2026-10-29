package com.oleroma.gyrophone.core.model

/**
 * Each workspace owns its touch mappings: its touch buttons' and touchpad's ([Workspace.mappings])
 * and its mapping sets ([Workspace.sets]). Nothing is shared between workspaces, so a duplicate
 * can change freely. Physical devices are mapped by the set's [DevicePreset]s instead, each
 * workspace pinning one ([Workspace.devicePreset]).
 *
 * A set is kept in two forms: stored ([store]), where workspaces hold no device mappings, and
 * resolved ([resolve]), where each holds its pinned preset's as one pad for any game pad
 * ([Workspace.pads]), [Workspace.keyboard] and [Workspace.mouse], as the engine sees it.
 */
object WorkspaceMappings {
    /** The one pad of a resolved workspace that pins a preset: any game pad. */
    const val PRESET_PAD = "preset"

    /**
     * [set] as the engine and editors see it: each workspace with the workspace defaults for the
     * settings it doesn't set itself ([WorkspaceDefaults.apply]), its pinned preset's device
     * mappings, and touch buttons' gestures where their mode reads them ([Bindings.matchSwipeModes]).
     */
    fun resolve(set: Profile): Profile {
        val defaults = set.defaults.copy(touchpad = set.defaults.touchpad.evenPies())
        return set.copy(
            defaults = defaults,
            workspaces = set.workspaces.map { resolve(WorkspaceDefaults.apply(evenPies(it), defaults), set.devicePresets) },
        )
    }

    /** [w] with pies of different widths turned into a shared width and directions' own ([TouchpadSettings.evenPies]). */
    private fun evenPies(w: Workspace): Workspace = w.copy(
        touchpad = w.touchpad.evenPies(),
        touchControls = w.touchControls.map { c -> c.surface?.let { c.copy(surface = it.evenPies()) } ?: c },
    )

    fun resolve(w: Workspace, presets: List<DevicePreset> = emptyList()): Workspace =
        Bindings.matchSwipeModes(w).let { r ->
            val p = presets.firstOrNull { it.id == w.devicePreset }
            r.copy(
                mappings = Bindings.upgradeSwipes(r.mappings),
                pads = if (p == null) emptyList() else listOf(AttachedPad(PRESET_PAD, mappings = p.controller)),
                keyboard = p?.keyboard.orEmpty(),
                mouse = p?.mouse.orEmpty(),
                keyTimings = p?.timings.orEmpty(),
            )
        }

    /** [set] as it is stored: the device mappings stay in the presets only. */
    fun store(set: Profile): Profile = set.copy(
        workspaces = set.workspaces.map { w ->
            w.copy(mappingProfileId = null, pads = emptyList(), keyboard = emptyList(), mouse = emptyList(), keyTimings = emptyList())
        },
    )

    /**
     * [after] (an edit of the resolved [before]) with each workspace new in it and holding no
     * mappings of its own (a blank one) starting with a copy of [current]'s touchpad mappings and
     * sets; one that comes with mappings (a builtin preset) keeps them, and its pad and keyboard
     * mappings, if any, become a device preset of its own that it uses.
     */
    fun settle(before: Profile, after: Profile, current: Workspace?): Profile {
        var set = after
        for (w in after.workspaces) {
            if (before.workspaces.any { it.id == w.id }) continue
            val padMappings = w.pads.firstOrNull()?.mappings.orEmpty()
            val fresh = startFrom(w, current)
            if (w.devicePreset != null || (padMappings.isEmpty() && w.keyboard.isEmpty())) {
                set = set.copy(workspaces = set.workspaces.map { if (it.id == w.id) fresh else it })
                continue
            }
            val id = DevicePresets.newId(set.devicePresets.map { it.id })
            val preset = DevicePresets.standard(id, w.name).let { p ->
                p.copy(controller = padMappings.ifEmpty { p.controller }, keyboard = w.keyboard.ifEmpty { p.keyboard })
            }
            set = DevicePresets.put(set, preset).copy(
                workspaces = set.workspaces.map { if (it.id == w.id) fresh.copy(devicePreset = id) else it },
            )
        }
        return set
    }

    private fun startFrom(w: Workspace, current: Workspace?): Workspace {
        if (current == null || w.mappings.any { !it.source.isTouchpad }) return w
        return w.copy(
            mappings = w.mappings.filterNot { it.source.isTouchpad } + current.mappings.filter { it.source.isTouchpad },
            touchpad = w.touchpad.copy(mapped = current.touchpad.mapped),
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
            MappingProfiles.resolve(w, p).copy(mappingProfileId = null)
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
            is InputSource.Gyro -> if (s.id.isEmpty()) return null else s.copy(id = to)
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
     * [target] with [source]'s touch mappings. Touchpad mappings are copied as they are, and
     * [source]'s sets join [target]'s (a set id both have takes [source]'s name).
     * [withButtons]: [target]'s buttons are replaced by [source]'s, with their places and mappings.
     * Else [target] keeps its buttons; each takes the mappings of the [source] button of the same
     * kind and name ([buttonName], ignoring case), and one with no match keeps its own.
     */
    fun copy(target: Workspace, source: Workspace, withButtons: Boolean): Copied {
        val src = resolve(source)
        val sets = (src.sets + target.sets.filter { t -> src.sets.none { it.id == t.id } }).sortedBy { it.id }
        val base = target.copy(
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
