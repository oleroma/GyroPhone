package com.oleroma.gyrophone.core.model

/**
 * How touch mappings pass down the levels: Defaults ([Profile.defaults]) → workspace
 * → button, one chip at a time. A level takes each of its parent's chips unless it has
 * overridden that one: by a chip of its own with the same input and output (a copy it made its
 * own), by an [OutputTarget.Off] of that output on that input (Override), or by clearing the whole
 * action (an editor card, [cardOf]) with [OutputTarget.NoOutput]. Its own other chips are added
 * beside the parent's. An action is a finger count and a held [SurfaceInput] (its taps, swipes,
 * zones and triggers are its own), whoever's surface it is; a button's one-finger Hold is its
 * press ([InputSource.Touch]).
 *
 * Defaults have no layers: their mappings fill in the Base of a workspace. A button takes the
 * workspace's chips of the layers in use unless it overrides them in one of those layers
 * ([toButtons]); the engine runs the ones it takes on the touchpad itself ([passedOn]).
 */
object Inheritance {
    /** A touch input on any surface: finger count, input, hold zone and trigger ([InputSource.Surface.on]). */
    data class TouchInput(val fingers: Int, val input: SurfaceInput, val zone: Int, val on: Trigger)

    /**
     * The touch input [s] stands for on its surface, or null for a source that is not one: a level
     * maps or inherits each one, with each trigger on its own (the mapping editor shows it the same way).
     */
    fun inputOf(s: InputSource): TouchInput? = when (s) {
        is InputSource.Surface -> TouchInput(s.fingers, s.input, s.zone, s.on)
        is InputSource.Touch -> TouchInput(1, SurfaceInput.HOLD, 0, Trigger.HOLD)
        else -> null
    }

    /**
     * The action (editor card) [s] maps on its surface: its held input with zone 0 and trigger
     * Hold, so every trigger, zone and Where of it counts as that one action (every swipe direction
 * is the Swipe row's, [SurfaceInput.HOLD_ANY]); null for a source
     * that is not a touch input. A button's Area and Outside are its press's.
     */
    fun cardOf(s: InputSource): TouchInput? =
        if (s is InputSource.TouchArea) {
            if (s.kind == TouchAreaKind.HELD) null else TouchInput(1, SurfaceInput.HOLD, 0, Trigger.HOLD)
        } else inputOf(holdOf(s))?.let { t ->
            // Every swipe direction is a chip of the one Swipe row: one action.
            if (t.input.heldSwipe) t.copy(input = SurfaceInput.HOLD_ANY) else t
        }

    /** The held source of the action [s] maps (its Hold in zone 0; a button's one-finger Hold is its press). */
    fun holdOf(s: InputSource): InputSource = when (val b = Triggers.base(s)) {
        is InputSource.Surface -> {
            val z = b.copy(zone = 0, on = Trigger.HOLD)
            if (z.fingers == 1 && z.input == SurfaceInput.HOLD && z.id.isNotEmpty()) InputSource.Touch(z.id) else z
        }
        else -> b
    }

    /** Which action of its level [s] is, for a workspace's own input ([isWorkspaceInput]). */
    internal fun levelKey(s: InputSource): Any? =
        if (s is InputSource.Gyro || s is InputSource.Tilt || s is InputSource.Shake || s is InputSource.Finger) s else cardOf(s)

    /** The touch control [s] belongs to ("" = the touchpad), or null for a source that is no touch input. */
    private fun ownerOf(s: InputSource): String? = when (s) {
        is InputSource.Surface -> s.id
        is InputSource.Touch -> s.id
        is InputSource.TouchTap -> s.id
        is InputSource.TouchDoubleTap -> s.id
        is InputSource.TouchLongPress -> s.id
        is InputSource.TouchRelease -> s.id
        is InputSource.TouchArea -> s.id
        else -> null
    }

    /**
     * What [own] (one level's mappings) overrides of its parent: the chips it made its own or
     * stopped ([OutputTarget.Off]), by input and output, and the actions it cleared whole
     * ([OutputTarget.NoOutput]), by [key] of their input.
     */
    class Covers(own: List<Mapping>, private val key: (InputSource) -> Any?) {
        private val chips = HashSet<Pair<InputSource, OutputTarget>>()
        private val cleared = HashSet<Any>()

        init {
            for (m in own) when (val t = m.target) {
                OutputTarget.NoOutput -> key(m.source)?.let(cleared::add)
                is OutputTarget.Off -> chips.add(m.source to t.of)
                else -> chips.add(m.source to t)
            }
        }

        /** The parent's chip of [target] on [source] (as this level has that input) is overridden here. */
        fun covers(source: InputSource, target: OutputTarget): Boolean =
            (source to target) in chips || (cleared.isNotEmpty() && key(source)?.let { it in cleared } == true)
    }

    /**
     * [own] with [parent]'s Base chips of the workspace's own inputs (touchpad and gyro,
     * [isWorkspaceInput]) that [own] does not override in Base ([Covers]).
     */
    fun fill(own: List<Mapping>, parent: List<Mapping>): List<Mapping> {
        val covers = Covers(own.filter { it.set == MappingSet.BASE && it.source.isWorkspaceInput }, ::levelKey)
        val taken = parent.filter { m ->
            m.set == MappingSet.BASE && m.source.isWorkspaceInput && !m.target.nothing && !covers.covers(m.source, m.target)
        }
        return if (taken.isEmpty()) own else own + taken
    }

    /** The Defaults' touchpad and gyro mappings: nothing is mapped until they map it. */
    fun defaults(d: Workspace): List<Mapping> = d.mappings.filter { it.source.isWorkspaceInput }

    /** Workspace [w]'s mappings with the touchpad and gyro mappings it takes from [defaults] ([Profile.defaults]). */
    fun workspace(w: Workspace, defaults: Workspace): List<Mapping> = fill(w.mappings, defaults(defaults))

    /** What button [id]'s mappings in [ms] override of the touchpad's ([Covers], by action [cardOf]). */
    private fun coversOf(ms: List<Mapping>, id: String): Covers = Covers(ms.filter { ownerOf(it.source) == id }, ::cardOf)

    /** [m] (a touchpad mapping) as button [id]'s: its one-finger Hold is the button's press. */
    fun forButton(m: Mapping, id: String): Mapping {
        val s = m.source as InputSource.Surface
        val src = if (s.fingers == 1 && s.input == SurfaceInput.HOLD && s.zone == 0 && s.on == Trigger.HOLD) InputSource.Touch(id)
        else s.copy(id = id)
        return m.copy(source = src)
    }

    /**
     * The touch mappings in use [inUse] (the layers in use applied, [OutputTarget.NoOutput] and
     * [OutputTarget.Off] kept) with each of [buttons] taking the touchpad's chips it does not
     * override in the layers in use, as its own inputs. (The engine runs those on the touchpad
     * instead, [passedOn]; the editor shows them faded, [inherited].)
     */
    fun toButtons(inUse: List<Mapping>, buttons: Collection<String>): List<Mapping> {
        val pad = inUse.filter { it.source.isTouchpad && !it.target.nothing }
        if (pad.isEmpty() || buttons.isEmpty()) return inUse
        val out = ArrayList(inUse)
        for (id in buttons) {
            val covers = coversOf(inUse, id)
            for (m in pad) forButton(m, id).let { b -> if (!covers.covers(b.source, b.target)) out.add(b) }
        }
        return out
    }

    /**
     * For button [id], with the touch mappings in use [inUse] (as [toButtons] gets them): the
     * touchpad's chips it overrides (they must not fire for a finger on it), or null when it takes
     * none of the touchpad's (its fingers stay its own). A finger on a button that takes any is
     * also a touchpad finger: the touchpad runs what the button inherits, the button its own.
     */
    fun passedOn(inUse: List<Mapping>, id: String): Set<Mapping>? {
        val covers = coversOf(inUse, id)
        var any = false
        val blocked = HashSet<Mapping>()
        for (m in inUse) {
            if (!m.source.isTouchpad || m.target.nothing) continue
            val b = forButton(m, id)
            if (covers.covers(b.source, b.target)) blocked.add(m) else any = true
        }
        return if (any) blocked else null
    }

    /**
     * The touchpad's mappings button [id] takes in mapping set [set] (as the editor shows them):
     * those of each action it maps nothing on in neither [set] nor Base. [touchpad] is the workspace's
     * touch mappings with its inherited ones ([workspace]).
     */
    fun inherited(touchpad: List<Mapping>, mappings: List<Mapping>, id: String, set: Int): List<Mapping> {
        val sets = if (set == MappingSet.BASE) listOf(MappingSet.BASE) else listOf(MappingSet.BASE, set)
        val covers = coversOf(mappings.filter { it.set in sets }, id)
        // As an override layer laid over Base (a set switched to as a whole has nothing of Base).
        val over = if (set == MappingSet.BASE) emptyList() else listOf(set)
        val pad = layered(touchpad.filter { it.source.isTouchpad }, MappingSet.BASE, over, keepNothing = true)
        return pad.filter { it.target !is OutputTarget.Off }.map { forButton(it, id) }.filterNot { covers.covers(it.source, it.target) }
    }

    /**
     * What the workspace's gyro mappings ([workspace]: its touch mappings with the inherited ones,
     * [workspace]) do in mapping set [set], as owner [id]'s gyro inputs: while a button is held each
     * axis does that unless the button maps it itself, so its Motion tab shows them faded.
     */
    fun gyroInherited(workspace: List<Mapping>, id: String, set: Int): List<Mapping> {
        val over = if (set == MappingSet.BASE) emptyList() else listOf(set)
        val gyro = workspace.filter { (it.source as? InputSource.Gyro)?.id?.isEmpty() == true }
        return layered(gyro, MappingSet.BASE, over, keepNothing = true)
            .filter { it.target != OutputTarget.NoOutput }
            .map { it.copy(source = (it.source as InputSource.Gyro).copy(id = id)) }
    }

    /**
     * [w] (saved before [Workspace.chipInherit], with [defaults] above it) made to work the same
     * chip by chip: wherever it mapped an action and so took none of the level above's chips of
     * it, each of those chips gets an [OutputTarget.Off]. The workspace's touchpad and gyro over
     * the Defaults, and each button over the touchpad, in each of its mapping sets.
     */
    fun chipByChip(w: Workspace, defaults: Workspace): Workspace {
        if (w.chipInherit) return w
        val add = ArrayList<Mapping>()
        // The workspace over the Defaults: Base only.
        val parent = defaults(defaults).filter { it.set == MappingSet.BASE && !it.target.nothing }
        val base = w.mappings.filter { it.set == MappingSet.BASE && it.source.isWorkspaceInput }
        add += offs(base, parent, ::levelKey) { it }
        // Each button over the touchpad (the workspace's own and the ones it takes from the Defaults).
        val pad = workspace(w.copy(mappings = w.mappings + add), defaults).filter { it.source.isTouchpad }
        val ids = w.mappings.mapNotNullTo(LinkedHashSet()) { m -> ownerOf(m.source)?.takeIf { it.isNotEmpty() } }
        for (id in ids) {
            val mine = w.mappings.filter { ownerOf(it.source) == id }
            for (set in mine.mapTo(sortedSetOf()) { it.set }) {
                val sets = if (set == MappingSet.BASE) listOf(MappingSet.BASE) else listOf(MappingSet.BASE, set)
                val over = if (set == MappingSet.BASE) emptyList() else listOf(set)
                val above = layered(pad, MappingSet.BASE, over).map { forButton(it, id) }
                val own = mine.filter { it.set in sets }
                // In a layer, an Off goes into the layer only where the layer maps that input
                // (an input the layer maps hides Base's of it), else into Base.
                val inLayer = mine.filter { it.set == set }.mapTo(HashSet()) { it.source }
                add += offs(own, above, ::cardOf) { m ->
                    if (set == MappingSet.BASE || m.source in inLayer) m.copy(set = set) else m.copy(set = MappingSet.BASE)
                }
            }
        }
        return w.copy(mappings = (w.mappings + add).distinct(), chipInherit = true)
    }

    /**
     * The Offs that keep [own] working as when an action it maps took none of [above]'s chips of
     * it ([key] = the action): one for each such chip it neither maps nor clears.
     */
    private fun offs(own: List<Mapping>, above: List<Mapping>, key: (InputSource) -> Any?, place: (Mapping) -> Mapping): List<Mapping> {
        val mapped = own.filter { !it.target.nothing }.mapNotNullTo(HashSet()) { key(it.source) }
        if (mapped.isEmpty()) return emptyList()
        val covers = Covers(own, key)
        return above.filter { m -> !m.target.nothing && key(m.source) in mapped && !covers.covers(m.source, m.target) }
            .map { place(Mapping(it.source, OutputTarget.Off(it.target))) }
    }

    /**
     * [ms] without each [OutputTarget.Off] that stops a chip the same input also maps (per set):
     * that chip is its own then, so the Off says nothing more.
     */
    fun tidy(ms: List<Mapping>): List<Mapping> {
        if (ms.none { it.target is OutputTarget.Off }) return ms
        val own = ms.filter { !it.target.nothing }.mapTo(HashSet()) { Triple(it.source, it.target, it.set) }
        return ms.filterNot { m -> (m.target as? OutputTarget.Off)?.let { Triple(m.source, it.of, m.set) in own } == true }
    }

    /**
     * Button [id]'s mappings for an empty button: every action it would take from the workspace
     * ([touchpad], as in [inherited]) cleared in Base (on its Hold).
     */
    fun cleared(touchpad: List<Mapping>, id: String): List<Mapping> =
        inherited(touchpad, emptyList(), id, MappingSet.BASE).map {
            it.copy(source = holdOf(it.source), target = OutputTarget.NoOutput, set = MappingSet.BASE, options = null)
        }.distinct()
}
