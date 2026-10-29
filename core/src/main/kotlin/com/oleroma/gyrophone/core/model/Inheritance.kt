package com.oleroma.gyrophone.core.model

/**
 * How touch mappings pass down the levels: built-in → Defaults ([Profile.defaults]) → workspace
 * → button. A level maps each touch input itself or takes its parent's mapping of it, one input
 * at a time; a level that maps an input to [OutputTarget.NoOutput] has cleared it. An input is a
 * finger count, a [SurfaceInput] and a hold zone, whoever's surface it is; a button's one-finger
 * Hold is its press ([InputSource.Touch]).
 *
 * Defaults have no layers: their mappings fill in the Base of a workspace. A button takes the
 * workspace's mappings of the layers in use only for inputs it maps in none of them, so a
 * button's own mapping beats the workspace's in every layer ([toButtons]).
 */
object Inheritance {
    /** The touch input [s] stands for on its surface, or null for a source that is not one. */
    private fun inputOf(s: InputSource): Triple<Int, SurfaceInput, Int>? = when (s) {
        is InputSource.Surface -> Triple(s.fingers, s.input, s.zone)
        is InputSource.Touch -> Triple(1, SurfaceInput.HOLD, 0)
        else -> null
    }

    /** The touchpad's mappings every level starts from: what a laptop touchpad does. */
    val builtin: List<Mapping> = Bindings.defaultTouchpadMappings(TouchpadSettings())

    /**
     * [own] with [parent]'s Base touchpad mappings ([InputSource.isTouchpad]) of each input [own]
     * maps in none of its Base touchpad mappings.
     */
    fun fill(own: List<Mapping>, parent: List<Mapping>): List<Mapping> {
        val mapped = HashSet<Triple<Int, SurfaceInput, Int>>()
        for (m in own) if (m.set == MappingSet.BASE && m.source.isTouchpad) inputOf(m.source)?.let(mapped::add)
        val taken = parent.filter { m -> m.set == MappingSet.BASE && m.source.isTouchpad && inputOf(m.source) !in mapped }
        return if (taken.isEmpty()) own else own + taken
    }

    /** The Defaults' touchpad mappings in effect: their own, and the built-in ones of the rest. */
    fun defaults(d: Workspace): List<Mapping> = fill(d.mappings.filter { it.source.isTouchpad }, builtin)

    /** Workspace [w]'s mappings with the touchpad mappings it takes from [defaults] ([Profile.defaults]). */
    fun workspace(w: Workspace, defaults: Workspace): List<Mapping> = fill(w.mappings, defaults(defaults))

    /** Which of [inputOf] the mappings of [id] in [ms] cover. */
    private fun inputsOf(ms: List<Mapping>, id: String): Set<Triple<Int, SurfaceInput, Int>> {
        val out = HashSet<Triple<Int, SurfaceInput, Int>>()
        for (m in ms) {
            val s = m.source
            val owner = when (s) {
                is InputSource.Surface -> s.id
                is InputSource.Touch -> s.id
                else -> null
            }
            if (owner == id) inputOf(s)?.let(out::add)
        }
        return out
    }

    /** [m] (a touchpad mapping) as button [id]'s: its one-finger Hold is the button's press. */
    fun forButton(m: Mapping, id: String): Mapping {
        val s = m.source as InputSource.Surface
        val src = if (s.fingers == 1 && s.input == SurfaceInput.HOLD && s.zone == 0) InputSource.Touch(id)
        else s.copy(id = id)
        return m.copy(source = src)
    }

    /**
     * The touch mappings in use [inUse] (the layers in use applied, [OutputTarget.NoOutput] kept)
     * with each of [buttons] taking the touchpad's mapping of every input it maps itself in none
     * of the layers in use ([all]: every set's mappings). A button that maps an input in a layer not
     * in use takes the touchpad's for it meanwhile.
     */
    fun toButtons(inUse: List<Mapping>, buttons: Collection<String>): List<Mapping> {
        val pad = inUse.filter { it.source.isTouchpad }
        if (pad.isEmpty() || buttons.isEmpty()) return inUse
        val out = ArrayList(inUse)
        for (id in buttons) {
            val own = inputsOf(inUse, id)
            for (m in pad) if (inputOf(m.source) !in own) out.add(forButton(m, id))
        }
        return out
    }

    /**
     * The touchpad's mappings button [id] takes in mapping set [set] (as the editor shows them):
     * those of each input it maps itself in neither [set] nor Base. [touchpad] is the workspace's
     * touch mappings with its inherited ones ([workspace]).
     */
    fun inherited(touchpad: List<Mapping>, mappings: List<Mapping>, id: String, set: Int): List<Mapping> {
        val sets = if (set == MappingSet.BASE) listOf(MappingSet.BASE) else listOf(MappingSet.BASE, set)
        val own = inputsOf(mappings.filter { it.set in sets }, id)
        // As an override layer laid over Base (a set switched to as a whole has nothing of Base).
        val over = if (set == MappingSet.BASE) emptyList() else listOf(set)
        val pad = layered(touchpad.filter { it.source.isTouchpad }, MappingSet.BASE, over, keepNothing = true)
        return pad.filter { inputOf(it.source) !in own }.map { forButton(it, id) }
    }

    /**
     * [ms] without the [OutputTarget.NoOutput] of each input (source, set) that maps something
     * else too: mapping an action that was cleared overrides it with that instead.
     */
    fun tidy(ms: List<Mapping>): List<Mapping> {
        val mapped = ms.filter { it.target != OutputTarget.NoOutput }.mapTo(HashSet()) { it.source to it.set }
        if (ms.none { it.target == OutputTarget.NoOutput && (it.source to it.set) in mapped }) return ms
        return ms.filterNot { it.target == OutputTarget.NoOutput && (it.source to it.set) in mapped }
    }

    /**
     * Button [id]'s mappings for an empty button: every touchpad input it would take from the
     * workspace ([touchpad], as in [inherited]) cleared in Base.
     */
    fun cleared(touchpad: List<Mapping>, id: String): List<Mapping> =
        inherited(touchpad, emptyList(), id, MappingSet.BASE).map { it.copy(target = OutputTarget.NoOutput, set = MappingSet.BASE) }.distinct()
}
