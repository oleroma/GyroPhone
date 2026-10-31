package com.oleroma.gyrophone.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The settings a workspace takes from the workspace defaults ([Profile.defaults]) unless it sets
 * them itself ([Workspace.ownSettings]), each by a key: a top-level one ([LONG_PRESS]...), a gyro
 * setting ([gyroKey], by its property name) or a touchpad one ([touchpadKey]). Layout, mappings,
 * layers and the pinned preset are the workspace's own and have no key.
 */
object WorkspaceDefaults {
    const val ID = "defaults"
    const val LONG_PRESS = "longPressMs"
    const val DOUBLE_TAP = "doubleTapMs"
    const val CONTROL_OPACITY = "controlOpacity"
    const val HAPTICS = "haptics"
    const val HAPTIC_EDGE = "hapticEdge"
    private const val GYRO = "gyro."
    private const val TOUCHPAD = "touchpad."

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** The built-in values, the parent of the defaults. */
    val builtin = Workspace(ID, "Workspace defaults")

    private val top = listOf(
        LONG_PRESS, DOUBLE_TAP, CONTROL_OPACITY, HAPTICS, HAPTIC_EDGE,
    )

    private fun gyroJson(g: GyroSettings): JsonObject = json.encodeToJsonElement(GyroSettings.serializer(), g).jsonObject

    private val gyroProps: List<String> = gyroJson(GyroSettings()).keys.toList()

    fun gyroKey(property: String) = GYRO + property
    fun touchpadKey(f: SurfaceField) = TOUCHPAD + f.name
    fun dirKey(d: Direction, f: DirField) = TOUCHPAD + DIR + d.name + "." + f.name
    private const val DIR = "dir."

    private fun dirOf(key: String): Pair<Direction, DirField>? {
        if (!key.startsWith(TOUCHPAD + DIR)) return null
        val (d, f) = key.removePrefix(TOUCHPAD + DIR).split('.')
        return Direction.valueOf(d) to (DirField.entries.firstOrNull { it.name == f } ?: return null)
    }

    fun moveKey(m: MoveSlot, f: MoveField) = TOUCHPAD + MOVE + m.name + "." + f.name
    private const val MOVE = "move."

    private fun moveOf(key: String): Pair<MoveSlot, MoveField>? {
        if (!key.startsWith(TOUCHPAD + MOVE)) return null
        val (m, f) = key.removePrefix(TOUCHPAD + MOVE).split('.')
        return MoveSlot.valueOf(m) to (MoveField.entries.firstOrNull { it.name == f } ?: return null)
    }

    /** The shared touchpad key [key] (a direction's or a Move X / Y's own) sets over, else null. */
    private fun sharedOf(key: String): String? =
        dirOf(key)?.let { (d, f) -> touchpadKey(f.sharedOf(d)) } ?: moveOf(key)?.let { (m, f) -> touchpadKey(f.sharedOf(m)) }

    /** Every key a workspace can set over the defaults. */
    val keys: List<String> = top + gyroProps.map(::gyroKey) + SurfaceField.entries.map(::touchpadKey) +
        Direction.entries.flatMap { d -> DirField.entries.map { f -> dirKey(d, f) } } +
        MoveSlot.entries.flatMap { m -> MoveField.of(m).map { f -> moveKey(m, f) } }

    /** The keys a layer ([MappingSet.settings]) may set: all but the controls' opacity, the game's turn and absolute pitch. */
    val layerKeys: List<String> = keys - setOf(
        CONTROL_OPACITY, gyroKey("gameCountsPer360"), gyroKey("gameName"), gyroKey("absolutePitch"), gyroKey("pitchLimitDeg"),
        gyroKey("absoluteLevelDeg"), gyroKey("absoluteRws"), gyroKey("gyroSpace"),
    )

    /** The touchpad's keys: shared and per direction. */
    val touchpadKeys: List<String> = keys.filter { it.startsWith(TOUCHPAD) }

    /** [a] and [b] have the same value of [key]. */
    fun same(key: String, a: Workspace, b: Workspace): Boolean = when {
        key.startsWith(GYRO) -> key.removePrefix(GYRO).let { p -> gyroJson(a.gyro)[p] == gyroJson(b.gyro)[p] }
        dirOf(key) != null -> dirOf(key)!!.let { (d, f) -> f.same(a.touchpad.dir(d), b.touchpad.dir(d)) }
        moveOf(key) != null -> moveOf(key)!!.let { (m, f) -> f.same(a.touchpad.move(m), b.touchpad.move(m)) }
        key.startsWith(TOUCHPAD) -> SurfaceField.valueOf(key.removePrefix(TOUCHPAD)).same(a.touchpad, b.touchpad)
        else -> when (key) {
            LONG_PRESS -> a.longPressMs == b.longPressMs
            DOUBLE_TAP -> a.doubleTapMs == b.doubleTapMs
            CONTROL_OPACITY -> a.controlOpacity == b.controlOpacity
            HAPTICS -> a.haptics == b.haptics
            HAPTIC_EDGE -> a.hapticEdge == b.hapticEdge
            else -> true
        }
    }

    /** [to] with [from]'s value of [key]. */
    fun take(key: String, to: Workspace, from: Workspace): Workspace = when {
        key.startsWith(GYRO) -> {
            val p = key.removePrefix(GYRO)
            val src = gyroJson(from.gyro)[p]
            if (src == null) to
            else to.copy(gyro = json.decodeFromJsonElement(GyroSettings.serializer(), JsonObject(gyroJson(to.gyro) + (p to src))))
        }

        dirOf(key) != null -> dirOf(key)!!.let { (d, f) -> to.copy(touchpad = f.take(to.touchpad, d, from.touchpad)) }
        moveOf(key) != null -> moveOf(key)!!.let { (m, f) -> to.copy(touchpad = f.take(to.touchpad, m, from.touchpad)) }
        key.startsWith(TOUCHPAD) -> to.copy(touchpad = SurfaceField.valueOf(key.removePrefix(TOUCHPAD)).take(to.touchpad, from.touchpad))
        else -> when (key) {
            LONG_PRESS -> to.copy(longPressMs = from.longPressMs)
            DOUBLE_TAP -> to.copy(doubleTapMs = from.doubleTapMs)
            CONTROL_OPACITY -> to.copy(controlOpacity = from.controlOpacity)
            HAPTICS -> to.copy(haptics = from.haptics)
            HAPTIC_EDGE -> to.copy(hapticEdge = from.hapticEdge)
            else -> to
        }
    }

    /** The keys of [w] whose value differs from [parent]'s. */
    fun differing(w: Workspace, parent: Workspace): Set<String> = keys.filterTo(LinkedHashSet()) { !same(it, w, parent) }

    /**
     * The keys [w] sets itself: [Workspace.ownSettings], or for one from before the defaults
     * (null) each that differs from the built-in values, so nothing set by hand is lost.
     */
    fun own(w: Workspace): Set<String> = w.ownSettings?.filterTo(LinkedHashSet()) { it in keySet } ?: differing(w, builtin)

    /** [keys] to look up; a stored key no longer among them (a setting since removed) is dropped. */
    private val keySet: Set<String> = keys.toHashSet()

    /** [w] with [defaults]' value of every key it does not set itself. */
    fun apply(w: Workspace, defaults: Workspace): Workspace {
        val own = own(w)
        return keys.filter { it !in own }.fold(w.copy(ownSettings = own)) { r, k -> follow(k, r, own, defaults) }
    }

    /**
     * [w] (setting [own] itself) with [defaults]' value of [key]. A direction's or Move X / Y's
     * own value of the defaults gives way to a shared one the workspace sets: every one uses that.
     */
    private fun follow(key: String, w: Workspace, own: Set<String>, defaults: Workspace): Workspace =
        if (sharedOf(key) in own) take(key, w, Workspace("", "")) else take(key, w, defaults)

    /** [w] following [defaults] for [key] again. */
    fun reset(w: Workspace, key: String, defaults: Workspace): Workspace {
        val own = own(w) - key
        // A shared touchpad value back to the defaults' brings their direction values back too.
        val then = keys.filter { k -> k !in own && sharedOf(k) == key }
        return (listOf(key) + then).fold(w.copy(ownSettings = own)) { r, k -> follow(k, r, own, defaults) }
    }

    /** [w] following [defaults] for everything. */
    fun resetAll(w: Workspace, defaults: Workspace): Workspace =
        keys.fold(w) { r, k -> take(k, r, defaults) }.copy(ownSettings = emptySet())

    /**
     * [after] (an edit of [before]) setting the keys the edit changed itself. A key whose
     * ownership the edit changed (a reset) stays as the edit has it.
     */
    fun markOwn(before: Workspace, after: Workspace): Workspace {
        val ownB = own(before)
        val ownA = own(after)
        val changed = keys.filter { k -> k !in ownA && k !in ownB && !same(k, before, after) }
        if (changed.isEmpty()) return after
        val own = ownA + changed
        // A shared touchpad value set here hides the defaults' direction values of it.
        return keys.filter { k -> k !in own && sharedOf(k) in changed }
            .fold(after.copy(ownSettings = own)) { r, k -> follow(k, r, own, r) }
    }

    /** [after] (an edit of [before]) with each workspace setting the keys the edit changed itself ([markOwn]). */
    fun markOwn(before: Profile, after: Profile): Profile = after.copy(
        workspaces = after.workspaces.map { w ->
            before.workspaces.firstOrNull { it.id == w.id }?.let { markOwn(it, w) } ?: w
        },
    )

    /** The keys layer [set] sets itself. */
    fun layerOwn(set: MappingSet?): Set<String> = set?.settings?.ownSettings.orEmpty().filterTo(LinkedHashSet()) { it in keySet }

    /**
     * [base] with layer [set]'s own settings laid over. A shared touchpad value the layer sets
     * hides the direction values of it the layer does not set, as for a workspace.
     */
    fun overlay(base: Workspace, set: MappingSet?): Workspace {
        val layer = set?.settings ?: return base
        val own = layerOwn(set)
        if (own.isEmpty()) return base
        val r = own.fold(base) { acc, k -> take(k, acc, layer) }
        return keys.filter { k -> k !in own && sharedOf(k) in own }
            .fold(r) { acc, k -> follow(k, acc, own, acc) }
    }

    /**
     * The settings in effect in [w] while mapping set [full] is in use with [overrides] (bottom to
     * top) laid over it, as the mappings are ([layered]): each layer's own settings over the one below.
     */
    fun layered(w: Workspace, full: Int, overrides: List<Int>): Workspace {
        if (w.sets.none { it.settings != null }) return w
        fun set(id: Int) = if (id == MappingSet.BASE) null else w.sets.firstOrNull { it.id == id }
        var r = overlay(w, set(full))
        for (o in overrides) if (o != full) r = overlay(r, set(o))
        return r
    }

    /** Only [w]'s settings (every key), none of its layout or mappings: what a layer stores. */
    private fun settingsOf(w: Workspace, own: Set<String>): Workspace =
        keys.fold(Workspace("", "")) { r, k -> take(k, r, w) }.copy(ownSettings = own)

    /**
     * Layer [set] after an edit that turned the settings in effect in it from [before] to [after]:
     * the keys the edit changed become its own.
     */
    fun editLayer(set: MappingSet, before: Workspace, after: Workspace): MappingSet {
        val own = layerOwn(set) + layerKeys.filter { k -> !same(k, before, after) }
        if (own.isEmpty()) return set.copy(settings = null)
        return set.copy(settings = settingsOf(after, own))
    }

    /** Layer [set] following the settings below it again for [key]. */
    fun resetLayer(set: MappingSet, key: String): MappingSet {
        val layer = set.settings ?: return set
        val own = layer.ownSettings.orEmpty() - key
        return set.copy(settings = if (own.isEmpty()) null else layer.copy(ownSettings = own))
    }
}
