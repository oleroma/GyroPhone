package com.oleroma.gyrophone.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
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

    /** The touchpad's finger position inputs' settings ([Workspace.padFingers]). */
    const val PAD_FINGERS = "padFingers"
    private const val GYRO = "gyro."
    private const val TOUCHPAD = "touchpad."

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** The built-in values, the parent of the defaults. */
    val builtin = Workspace(ID, "Workspace defaults")

    private val top = listOf(
        LONG_PRESS, DOUBLE_TAP, CONTROL_OPACITY, HAPTICS, HAPTIC_EDGE, PAD_FINGERS,
    )

    /**
     * Each thread's last few gyro settings turned to JSON, by identity: a pass over every key (a
     * comparison, [apply]) then turns each one once instead of once per gyro key. Per thread, so
     * the engine's thread and the UI's never wait for each other.
     */
    private class GyroJsonCache {
        val settings = arrayOfNulls<GyroSettings>(GYRO_CACHE)
        val json = arrayOfNulls<JsonObject>(GYRO_CACHE)
        var next = 0
    }

    private const val GYRO_CACHE = 4
    private val gyroCache = ThreadLocal.withInitial { GyroJsonCache() }

    private fun gyroJson(g: GyroSettings): JsonObject {
        val c = gyroCache.get()
        for (i in 0 until GYRO_CACHE) if (c.settings[i] === g) return c.json[i]!!
        val j = json.encodeToJsonElement(GyroSettings.serializer(), g).jsonObject
        c.settings[c.next] = g
        c.json[c.next] = j
        c.next = (c.next + 1) % GYRO_CACHE
        return j
    }

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

    /** Each gyro axis's own sensitivity and factor keys. */
    private val gyroAxisSens: Map<String, String> = MotionAxis.entries.mapNotNull { a ->
        GyroSettings.sensitivityProp(a)?.let { s -> gyroKey(GyroSettings.scaleProp(a)!!) to gyroKey(s) }
    }.toMap()

    /**
     * The keys that hide a parent's value of [key] when a level sets one of them itself: [key]
     * then follows nothing (the shared value) instead. A shared touchpad value or gyro
     * Sensitivity hides a direction's, Move X / Y's or gyro axis's own values above; an exact
     * sensitivity hides a factor above (a factor is only ever of the nearest exact value).
     */
    private fun hidersOf(key: String): List<String> {
        dirOf(key)?.let { (d, f) -> return listOf(touchpadKey(f.sharedOf(d))) }
        moveOf(key)?.let { (m, f) ->
            val shared = touchpadKey(f.sharedOf(m))
            return if (f == MoveField.SCALE) listOf(shared, moveKey(m, MoveField.SENSITIVITY)) else listOf(shared)
        }
        gyroAxisSens[key]?.let { exact -> return listOf(gyroKey("sensitivity"), exact) }
        if (key in gyroAxisSens.values) return listOf(gyroKey("sensitivity"))
        if (key in gyroAxisSpaces) return listOf(gyroKey("gyroSpace"))
        return emptyList()
    }

    /** Each gyro axis's own space key: hidden by a level's own Gyro space. */
    private val gyroAxisSpaces: Set<String> = MotionAxis.entries.mapNotNull { GyroSettings.spaceProp(it)?.let(::gyroKey) }.toSet()

    private fun hiddenBy(key: String, own: Collection<String>): Boolean = hidersOf(key).any { it in own }

    /** Every key a workspace can set over the defaults. */
    val keys: List<String> = top + gyroProps.map(::gyroKey) + SurfaceField.entries.map(::touchpadKey) +
        Direction.entries.flatMap { d -> DirField.entries.map { f -> dirKey(d, f) } } +
        MoveSlot.entries.flatMap { m -> MoveField.of(m).map { f -> moveKey(m, f) } }

    /** The PC's DualShock 4 gyro axes ([GyroSettings.padYaw]...): the workspace's and Defaults' only. */
    val PAD_GYRO_KEYS: List<String> = listOf("padYaw", "padPitch", "padRoll", "padYawInvert", "padPitchInvert", "padRollInvert").map(::gyroKey)

    /** The keys a layer ([MappingSet.settings]) may set: all but the controls' opacity, the game's turn, the gyro space, the base orientation and the PC pad's gyro axes. */
    val layerKeys: List<String> = keys - setOf(
        CONTROL_OPACITY, gyroKey("gameCountsPer360"), gyroKey("gameName"), gyroKey("gyroSpace"), gyroKey("yawAxis"),
        gyroKey("gripPitch"),
    ) - PAD_GYRO_KEYS.toSet()

    /** The touchpad's keys: shared and per direction. */
    val touchpadKeys: List<String> = keys.filter { it.startsWith(TOUCHPAD) }

    /** [a] and [b] have the same value of [key]. */
    fun same(key: String, a: Workspace, b: Workspace): Boolean = when {
        key.startsWith(GYRO) -> a.gyro === b.gyro || key.removePrefix(GYRO).let { p -> gyroJson(a.gyro)[p] == gyroJson(b.gyro)[p] }
        dirOf(key) != null -> dirOf(key)!!.let { (d, f) -> f.same(a.touchpad.dir(d), b.touchpad.dir(d)) }
        moveOf(key) != null -> moveOf(key)!!.let { (m, f) -> f.same(a.touchpad.move(m), b.touchpad.move(m)) }
        key.startsWith(TOUCHPAD) -> SurfaceField.valueOf(key.removePrefix(TOUCHPAD)).same(a.touchpad, b.touchpad)
        else -> when (key) {
            LONG_PRESS -> a.longPressMs == b.longPressMs
            DOUBLE_TAP -> a.doubleTapMs == b.doubleTapMs
            CONTROL_OPACITY -> a.controlOpacity == b.controlOpacity
            HAPTICS -> a.haptics == b.haptics
            HAPTIC_EDGE -> a.hapticEdge == b.hapticEdge
            PAD_FINGERS -> a.padFingers == b.padFingers
            else -> true
        }
    }

    /**
     * [w]'s value of [key], for showing: a number, switch, enum or text; a gyro enum stored by
     * name comes back as its enum. Null when it has none, or one that has no short form.
     */
    fun valueOf(key: String, w: Workspace): Any? = when {
        key.startsWith(GYRO) -> key.removePrefix(GYRO).let { p -> gyroValue(p, gyroJson(w.gyro)[p]) }
        dirOf(key) != null -> dirOf(key)!!.let { (d, f) -> f.get(w.touchpad.dir(d)) }
        moveOf(key) != null -> moveOf(key)!!.let { (m, f) -> f.get(w.touchpad.move(m)) }
        key.startsWith(TOUCHPAD) -> SurfaceField.valueOf(key.removePrefix(TOUCHPAD)).get(w.touchpad)
        else -> when (key) {
            LONG_PRESS -> w.longPressMs
            DOUBLE_TAP -> w.doubleTapMs
            CONTROL_OPACITY -> w.controlOpacity
            HAPTIC_EDGE -> w.hapticEdge
            else -> null
        }
    }

    private fun gyroValue(prop: String, e: JsonElement?): Any? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (!p.isString) return p.booleanOrNull ?: p.doubleOrNull
        val name = p.content
        return when {
            prop == "gyroSpace" || prop.endsWith("Space") -> GyroSpace.entries.firstOrNull { it.name == name }
            prop == "yawAxis" -> YawAxis.entries.firstOrNull { it.name == name }
            prop == "mode" || prop.endsWith("Mode") -> GyroMode.entries.firstOrNull { it.name == name }
            prop.endsWith("Curve") -> InertiaCurve.entries.firstOrNull { it.name == name }
            prop.startsWith("pad") -> MotionAxis.entries.firstOrNull { it.name == name }
            else -> name
        }
    }

    /** [key] holds a factor of the value above (× parent), not a value of its own. */
    fun isFactor(key: String): Boolean = key.endsWith("Scale") || key.endsWith("." + MoveField.SCALE.name)

    /** [to] with [from]'s value of [key]. */
    fun take(key: String, to: Workspace, from: Workspace): Workspace = when {
        key.startsWith(GYRO) -> {
            val p = key.removePrefix(GYRO)
            val src = if (to.gyro === from.gyro) null else gyroJson(from.gyro)[p]
            val dst = if (src == null) null else gyroJson(to.gyro)
            if (src == null || dst!![p] == src) to
            else to.copy(gyro = json.decodeFromJsonElement(GyroSettings.serializer(), JsonObject(dst + (p to src))))
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
            PAD_FINGERS -> to.copy(padFingers = from.padFingers)
            else -> to
        }
    }

    /**
     * [to] with the value of each of [keys] from [from] (by key), as [take] one after another but
     * with every gyro key's value laid over the gyro settings at once (one decode, none when they
     * already match).
     */
    private fun takeAll(to: Workspace, keys: Iterable<String>, from: (String) -> Workspace): Workspace {
        var r = to
        var gyro: LinkedHashMap<String, JsonElement>? = null
        for (k in keys) {
            if (!k.startsWith(GYRO)) {
                r = take(k, r, from(k))
                continue
            }
            val src = from(k)
            if (src.gyro === to.gyro) continue
            val p = k.removePrefix(GYRO)
            val v = gyroJson(src.gyro)[p] ?: continue
            // Only gyro keys change the gyro settings, so [to]'s are still the ones in [r].
            val now = gyro?.get(p) ?: gyroJson(to.gyro)[p]
            if (now == v) continue
            val g = gyro ?: LinkedHashMap(gyroJson(to.gyro)).also { gyro = it }
            g[p] = v
        }
        val g = gyro ?: return r
        return r.copy(gyro = json.decodeFromJsonElement(GyroSettings.serializer(), JsonObject(g)))
    }

    /** Where [follow] takes [key] from. */
    private fun sourceOf(key: String, own: Set<String>, defaults: Workspace): Workspace =
        if (hiddenBy(key, own)) blank else defaults

    /** A workspace with every built-in value: what a hidden key follows ([follow]). */
    private val blank = Workspace("", "")

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
        return takeAll(w.copy(ownSettings = own), keys.filter { it !in own }) { k -> sourceOf(k, own, defaults) }
    }

    /**
     * [w] (setting [own] itself) with [defaults]' value of [key]. A direction's or Move X / Y's
     * own value of the defaults gives way to a shared one the workspace sets: every one uses that.
     */
    private fun follow(key: String, w: Workspace, own: Set<String>, defaults: Workspace): Workspace =
        take(key, w, sourceOf(key, own, defaults))

    /** [w] following [defaults] for [key] again. */
    fun reset(w: Workspace, key: String, defaults: Workspace): Workspace {
        val own = own(w) - key
        // A shared touchpad value back to the defaults' brings their direction values back too.
        val then = keys.filter { k -> k !in own && key in hidersOf(k) }
        return takeAll(w.copy(ownSettings = own), listOf(key) + then) { k -> sourceOf(k, own, defaults) }
    }

    /** [w] following [defaults] for everything. */
    fun resetAll(w: Workspace, defaults: Workspace): Workspace =
        takeAll(w, keys) { defaults }.copy(ownSettings = emptySet())

    /**
     * [after] (an edit of [before]) setting the keys the edit changed itself. A key whose
     * ownership the edit changed (a reset) stays as the edit has it.
     */
    fun markOwn(before: Workspace, after: Workspace): Workspace {
        // Only the layout, mappings or the like changed (a control moved): no setting to mark.
        if (before === after || sameSettings(before, after)) return after
        val ownB = own(before)
        val ownA = own(after)
        val changed = keys.filter { k -> k !in ownA && k !in ownB && !same(k, before, after) }
        if (changed.isEmpty()) return after
        val own = ownA + changed
        // A shared touchpad value set here hides the defaults' direction values of it.
        return keys.filter { k -> k !in own && hiddenBy(k, changed) }
            .fold(after.copy(ownSettings = own)) { r, k -> follow(k, r, own, r) }
    }

    /**
     * [a] and [b] have the same value of every key: their gyro, touchpad and top-level settings
     * are equal (so [same] holds for each key, without comparing key by key).
     */
    private fun sameSettings(a: Workspace, b: Workspace): Boolean =
        a.gyro == b.gyro && a.touchpad == b.touchpad && a.longPressMs == b.longPressMs &&
            a.doubleTapMs == b.doubleTapMs && a.controlOpacity == b.controlOpacity && a.haptics == b.haptics &&
            a.hapticEdge == b.hapticEdge && a.padFingers == b.padFingers

    /** [after] (an edit of [before]) with each workspace setting the keys the edit changed itself ([markOwn]). */
    fun markOwn(before: Profile, after: Profile): Profile {
        val byId = HashMap<String, Workspace>()
        for (w in before.workspaces) byId.putIfAbsent(w.id, w)
        return after.copy(
            workspaces = after.workspaces.map { w ->
                // A workspace the edit left alone is the same object: nothing to compare.
                val b = byId[w.id]
                if (b == null || b === w) w else markOwn(b, w)
            },
        )
    }

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
        val r = takeAll(base, own) { layer }
        return keys.filter { k -> k !in own && hiddenBy(k, own) }
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
        takeAll(blank, keys) { w }.copy(ownSettings = own)

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
