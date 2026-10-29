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

    /** Every key a workspace can set over the defaults. */
    val keys: List<String> = top + gyroProps.map(::gyroKey) + SurfaceField.entries.map(::touchpadKey)

    /** [a] and [b] have the same value of [key]. */
    fun same(key: String, a: Workspace, b: Workspace): Boolean = when {
        key.startsWith(GYRO) -> key.removePrefix(GYRO).let { p -> gyroJson(a.gyro)[p] == gyroJson(b.gyro)[p] }
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
        return keys.filter { it !in own }.fold(w.copy(ownSettings = own)) { r, k -> take(k, r, defaults) }
    }

    /** [w] following [defaults] for [key] again. */
    fun reset(w: Workspace, key: String, defaults: Workspace): Workspace =
        take(key, w, defaults).copy(ownSettings = own(w) - key)

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
        return if (changed.isEmpty()) after else after.copy(ownSettings = ownA + changed)
    }

    /** [after] (an edit of [before]) with each workspace setting the keys the edit changed itself ([markOwn]). */
    fun markOwn(before: Profile, after: Profile): Profile = after.copy(
        workspaces = after.workspaces.map { w ->
            before.workspaces.firstOrNull { it.id == w.id }?.let { markOwn(it, w) } ?: w
        },
    )
}
