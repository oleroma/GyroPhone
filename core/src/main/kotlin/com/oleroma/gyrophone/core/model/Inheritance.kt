package com.oleroma.gyrophone.core.model

/**
 * How touch mappings pass down the levels: Defaults ([Profile.defaults]) → workspace
 * → button. A level maps each action (an editor card: [cardOf]) itself or takes its parent's
 * mappings of it, one action at a time: mapping anything on an action (any trigger, zone or
 * Where) overrides all of the parent's mappings of it, and a level that maps an action to
 * [OutputTarget.NoOutput] has cleared it. An action is a finger count and a held [SurfaceInput]
 * (its taps, swipes, zones and triggers are its own), whoever's surface it is; a button's
 * one-finger Hold is its press ([InputSource.Touch]).
 *
 * Defaults have no layers: their mappings fill in the Base of a workspace. A button takes the
 * workspace's mappings of the layers in use only for inputs it maps in none of them, so a
 * button's own mapping beats the workspace's in every layer ([toButtons]).
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
     * Hold, so every trigger, zone and Where of it counts as that one action; null for a source
     * that is not a touch input. A button's Area and Outside are its press's.
     */
    fun cardOf(s: InputSource): TouchInput? =
        if (s is InputSource.TouchArea) {
            if (s.kind == TouchAreaKind.HELD) null else TouchInput(1, SurfaceInput.HOLD, 0, Trigger.HOLD)
        } else inputOf(holdOf(s))

    /** The held source of the action [s] maps (its Hold in zone 0; a button's one-finger Hold is its press). */
    fun holdOf(s: InputSource): InputSource = when (val b = Triggers.base(s)) {
        is InputSource.Surface -> {
            val z = b.copy(zone = 0, on = Trigger.HOLD)
            if (z.fingers == 1 && z.input == SurfaceInput.HOLD && z.id.isNotEmpty()) InputSource.Touch(z.id) else z
        }
        else -> b
    }

    /** Which action of its level [s] is, for a workspace's own input ([isWorkspaceInput]). */
    private fun levelKey(s: InputSource): Any? =
        if (s is InputSource.Gyro || s is InputSource.Tilt || s is InputSource.Shake) s else cardOf(s)

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
     * [own] with [parent]'s Base mappings of the workspace's own inputs (touchpad and gyro,
     * [isWorkspaceInput]) of each such input [own] does not map in Base.
     */
    fun fill(own: List<Mapping>, parent: List<Mapping>): List<Mapping> {
        val mapped = HashSet<Any>()
        for (m in own) if (m.set == MappingSet.BASE && m.source.isWorkspaceInput) levelKey(m.source)?.let(mapped::add)
        val taken = parent.filter { m -> m.set == MappingSet.BASE && m.source.isWorkspaceInput && levelKey(m.source) !in mapped }
        return if (taken.isEmpty()) own else own + taken
    }

    /** The Defaults' touchpad and gyro mappings: nothing is mapped until they map it. */
    fun defaults(d: Workspace): List<Mapping> = d.mappings.filter { it.source.isWorkspaceInput }

    /** Workspace [w]'s mappings with the touchpad and gyro mappings it takes from [defaults] ([Profile.defaults]). */
    fun workspace(w: Workspace, defaults: Workspace): List<Mapping> = fill(w.mappings, defaults(defaults))

    /** Which actions ([cardOf]) the mappings of [id] in [ms] cover. */
    private fun inputsOf(ms: List<Mapping>, id: String): Set<TouchInput> {
        val out = HashSet<TouchInput>()
        for (m in ms) if (ownerOf(m.source) == id) cardOf(m.source)?.let(out::add)
        return out
    }

    /** [m] (a touchpad mapping) as button [id]'s: its one-finger Hold is the button's press. */
    fun forButton(m: Mapping, id: String): Mapping {
        val s = m.source as InputSource.Surface
        val src = if (s.fingers == 1 && s.input == SurfaceInput.HOLD && s.zone == 0 && s.on == Trigger.HOLD) InputSource.Touch(id)
        else s.copy(id = id)
        return m.copy(source = src)
    }

    /**
     * The touch mappings in use [inUse] (the layers in use applied, [OutputTarget.NoOutput] kept)
     * with each of [buttons] taking the touchpad's mappings of every action it maps nothing on in
     * any of the layers in use. A button that maps an action in a layer not in use takes the
     * touchpad's for it meanwhile.
     */
    fun toButtons(inUse: List<Mapping>, buttons: Collection<String>): List<Mapping> {
        val pad = inUse.filter { it.source.isTouchpad }
        if (pad.isEmpty() || buttons.isEmpty()) return inUse
        val out = ArrayList(inUse)
        for (id in buttons) {
            val own = inputsOf(inUse, id)
            for (m in pad) if (cardOf(m.source) !in own) out.add(forButton(m, id))
        }
        return out
    }

    /**
     * The touchpad's mappings button [id] takes in mapping set [set] (as the editor shows them):
     * those of each action it maps nothing on in neither [set] nor Base. [touchpad] is the workspace's
     * touch mappings with its inherited ones ([workspace]).
     */
    fun inherited(touchpad: List<Mapping>, mappings: List<Mapping>, id: String, set: Int): List<Mapping> {
        val sets = if (set == MappingSet.BASE) listOf(MappingSet.BASE) else listOf(MappingSet.BASE, set)
        val own = inputsOf(mappings.filter { it.set in sets }, id)
        // As an override layer laid over Base (a set switched to as a whole has nothing of Base).
        val over = if (set == MappingSet.BASE) emptyList() else listOf(set)
        val pad = layered(touchpad.filter { it.source.isTouchpad }, MappingSet.BASE, over, keepNothing = true)
        return pad.filter { cardOf(it.source) !in own }.map { forButton(it, id) }
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

    /** The action [s] maps, with its owner: one key per editor card ([cardOf]; a source of its own otherwise). */
    private fun actionKey(s: InputSource): Any {
        val card = cardOf(s) ?: return Triggers.base(s)
        return ownerOf(s) to card
    }

    /**
     * [ms] without the [OutputTarget.NoOutput] of each action ([cardOf], per set) that maps
     * something else too: mapping anything on an action that was cleared overrides it with that instead.
     */
    fun tidy(ms: List<Mapping>): List<Mapping> {
        val mapped = ms.filter { it.target != OutputTarget.NoOutput }.mapTo(HashSet()) { actionKey(it.source) to it.set }
        fun gone(m: Mapping) = m.target == OutputTarget.NoOutput && (actionKey(m.source) to m.set) in mapped
        if (ms.none(::gone)) return ms
        return ms.filterNot(::gone)
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
