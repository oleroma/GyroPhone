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
    const val TOUCHPAD_OVER_BUTTONS = "touchpadOverButtons"
    const val BUTTON_TAP = "buttonTapMs"
    const val STICKY_MODIFIERS = "stickyModifiers"
    const val CONTROL_OPACITY = "controlOpacity"
    const val HAPTICS = "haptics"
    const val HAPTIC_EDGE = "hapticEdge"
    private const val GYRO = "gyro."
    private const val TOUCHPAD = "touchpad."

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** The built-in values, the parent of the defaults. */
    val builtin = Workspace(ID, "Workspace defaults")

    private val top = listOf(
        LONG_PRESS, DOUBLE_TAP, TOUCHPAD_OVER_BUTTONS, BUTTON_TAP, STICKY_MODIFIERS, CONTROL_OPACITY, HAPTICS, HAPTIC_EDGE,
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
        return Direction.valueOf(d) to DirField.valueOf(f)
    }

    /** Every key a workspace can set over the defaults. */
    val keys: List<String> = top + gyroProps.map(::gyroKey) + SurfaceField.entries.map(::touchpadKey) +
        Direction.entries.flatMap { d -> DirField.entries.map { f -> dirKey(d, f) } }

    /** The touchpad's keys: shared and per direction. */
    val touchpadKeys: List<String> = keys.filter { it.startsWith(TOUCHPAD) }

    /** [a] and [b] have the same value of [key]. */
    fun same(key: String, a: Workspace, b: Workspace): Boolean = when {
        key.startsWith(GYRO) -> key.removePrefix(GYRO).let { p -> gyroJson(a.gyro)[p] == gyroJson(b.gyro)[p] }
        dirOf(key) != null -> dirOf(key)!!.let { (d, f) -> f.same(a.touchpad.dir(d), b.touchpad.dir(d)) }
        key.startsWith(TOUCHPAD) -> SurfaceField.valueOf(key.removePrefix(TOUCHPAD)).same(a.touchpad, b.touchpad)
        else -> when (key) {
            LONG_PRESS -> a.longPressMs == b.longPressMs
            DOUBLE_TAP -> a.doubleTapMs == b.doubleTapMs
            TOUCHPAD_OVER_BUTTONS -> a.touchpadOverButtons == b.touchpadOverButtons
            BUTTON_TAP -> a.buttonTapMs == b.buttonTapMs
            STICKY_MODIFIERS -> a.stickyModifiers == b.stickyModifiers
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
        key.startsWith(TOUCHPAD) -> to.copy(touchpad = SurfaceField.valueOf(key.removePrefix(TOUCHPAD)).take(to.touchpad, from.touchpad))
        else -> when (key) {
            LONG_PRESS -> to.copy(longPressMs = from.longPressMs)
            DOUBLE_TAP -> to.copy(doubleTapMs = from.doubleTapMs)
            TOUCHPAD_OVER_BUTTONS -> to.copy(touchpadOverButtons = from.touchpadOverButtons)
            BUTTON_TAP -> to.copy(buttonTapMs = from.buttonTapMs)
            STICKY_MODIFIERS -> to.copy(stickyModifiers = from.stickyModifiers)
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
    fun own(w: Workspace): Set<String> = w.ownSettings ?: differing(w, builtin)

    /** [w] with [defaults]' value of every key it does not set itself. */
    fun apply(w: Workspace, defaults: Workspace): Workspace {
        val own = own(w)
        return keys.filter { it !in own }.fold(w.copy(ownSettings = own)) { r, k -> follow(k, r, own, defaults) }
    }

    /**
     * [w] (setting [own] itself) with [defaults]' value of [key]. A direction's own value of the
     * defaults gives way to a shared one the workspace sets: every direction of it uses that.
     */
    private fun follow(key: String, w: Workspace, own: Set<String>, defaults: Workspace): Workspace {
        val dir = dirOf(key)
        return if (dir != null && touchpadKey(dir.second.shared) in own) w.copy(touchpad = dir.second.take(w.touchpad, dir.first, TouchpadSettings()))
        else take(key, w, defaults)
    }

    /** [w] following [defaults] for [key] again. */
    fun reset(w: Workspace, key: String, defaults: Workspace): Workspace {
        val own = own(w) - key
        // A shared touchpad value back to the defaults' brings their direction values back too.
        val then = keys.filter { k -> k !in own && dirOf(k)?.second?.shared?.let(::touchpadKey) == key }
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
        return keys.filter { k -> k !in own && dirOf(k)?.second?.shared?.let(::touchpadKey) in changed }
            .fold(after.copy(ownSettings = own)) { r, k -> follow(k, r, own, r) }
    }

    /** [after] (an edit of [before]) with each workspace setting the keys the edit changed itself ([markOwn]). */
    fun markOwn(before: Profile, after: Profile): Profile = after.copy(
        workspaces = after.workspaces.map { w ->
            before.workspaces.firstOrNull { it.id == w.id }?.let { markOwn(it, w) } ?: w
        },
    )
}
