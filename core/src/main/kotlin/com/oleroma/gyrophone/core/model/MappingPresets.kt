package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * What a preset of the mapping editor fills: one chip (added to any card that takes its target),
 * one card (its chips on every When, Where and zone, onto a card of the same kind), or a whole
 * control (every card of a button, stick or physical control of the same kind).
 */
@Serializable
enum class PresetLevel { CHIP, CARD, BUTTON }

/**
 * One chip of a preset: [target] (with its How [options] and analog [range]) on [slot], the input
 * it maps written without its control ([MappingPresets.slot]), so it lands on the same input of
 * another card or control.
 */
@Serializable
data class PresetChip(
    val slot: InputSource,
    val target: OutputTarget,
    val options: MapOptions? = null,
    val range: AxisRange? = null,
    val perDp: Float? = null,
)

/**
 * A chip or card preset the user made in the mapping editor, shared by every workspace (button
 * presets are [LibraryButton]s). [kind]: the card it was made from without its control
 * ([MappingPresets.slot]); a card preset fits only cards of that kind. [control]: the touch control
 * it was made from, whose settings for that card it can bring along; null = none (a physical
 * control's or the touchpad's card).
 */
@Serializable
data class MappingPreset(
    val id: String,
    val name: String,
    val level: PresetLevel,
    val kind: InputSource,
    val chips: List<PresetChip>,
    val control: TouchControl? = null,
)

object MappingPresets {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(MappingPreset.serializer())

    fun encode(presets: List<MappingPreset>): String = json.encodeToString(listSerializer, presets)

    /** Reads a saved list; unreadable text gives null (the caller keeps the file). */
    fun decode(text: String): List<MappingPreset>? = runCatching { json.decodeFromString(listSerializer, text) }.getOrNull()

    fun newId(taken: Collection<String>): String {
        var n = 1
        while ("preset$n" in taken) n++
        return "preset$n"
    }

    /**
     * [s] without the control it belongs to: no touch id, no finger count when [keepFingers] is
     * off (a card fits the same card of any finger count), and for a physical key or axis its
     * place [index] among its control's inputs instead of its code (0 on a card: one input).
     */
    fun slot(s: InputSource, index: Int = 0, keepFingers: Boolean = false): InputSource = when (s) {
        is InputSource.Key -> InputSource.Key(index)
        is InputSource.KeyGesture -> s.copy(keyCode = index)
        is InputSource.Axis -> s.copy(axis = index)
        is InputSource.Touch -> s.copy(id = "")
        is InputSource.TouchTap -> s.copy(id = "")
        is InputSource.TouchLongPress -> s.copy(id = "")
        is InputSource.TouchRelease -> s.copy(id = "")
        is InputSource.TouchDoubleTap -> s.copy(id = "")
        is InputSource.TouchStick -> s.copy(id = "")
        is InputSource.TouchStickDiagonal -> s.copy(id = "")
        is InputSource.TouchSwipe -> s.copy(id = "")
        is InputSource.TouchArea -> s.copy(id = "")
        is InputSource.Surface -> s.copy(id = "", fingers = if (keepFingers) s.fingers else 0)
        is InputSource.Gyro -> s.copy(id = "")
        is InputSource.Tilt -> s.copy(id = "")
        is InputSource.Finger -> s.copy(id = "")
        is InputSource.Shake -> s.copy(id = "")
        else -> s
    }

    /** A card's kind: its first source as a [slot]. A card preset fits cards of its own kind only. */
    fun kindOf(card: BindableInput): InputSource = slot(card.sources.first())

    /**
     * The chips of [inputs] in [mappings] (Base), each on its [slot] ([index] gives an input's
     * place among its control's; [keepFingers] see [slot]). An input with several sources (LT is
     * three) gives each chip once.
     */
    fun chipsOf(
        mappings: List<Mapping>,
        inputs: List<BindableInput>,
        index: (BindableInput) -> Int = { 0 },
        keepFingers: Boolean = false,
    ): List<PresetChip> = inputs.flatMap { input ->
        Bindings.entries(mappings, input).map { e ->
            PresetChip(slot(input.sources.first(), index(input), keepFingers), e.target, e.options, e.range, e.perDp)
        }
    }.distinct()

    /**
     * [chips] written onto [inputs] (the inputs of the card or control they go to, every When,
     * Where and zone that may take them), on top of [mappings] (Base): each to the input with its
     * slot, an exact duplicate (same target and How) left out; chips with no such input are left
     * out too. [replace]: [inputs]' own chips go first.
     */
    fun place(
        mappings: List<Mapping>,
        chips: List<PresetChip>,
        inputs: List<BindableInput>,
        replace: Boolean,
        index: (BindableInput) -> Int = { 0 },
        keepFingers: Boolean = false,
    ): List<Mapping> {
        var out = if (!replace) mappings else inputs.fold(mappings) { m, i -> Bindings.setTargets(m, i, emptyList()) }
        val bySlot = HashMap<InputSource, BindableInput>()
        for (i in inputs) bySlot.putIfAbsent(slot(i.sources.first(), index(i), keepFingers), i)
        for ((s, list) in chips.groupBy { it.slot }) {
            val input = bySlot[s] ?: continue
            val now = Bindings.entries(out, input)
            val add = list.map { Bindings.Entry(it.target, it.options, it.range, it.perDp) }
                .filter { e -> now.none { it.target == e.target && it.shape == e.shape } }
            if (add.isNotEmpty()) out = Bindings.setEntries(out, input, now + add)
        }
        return out
    }

    /**
     * Every input [tops] (a control's or card's inputs) may map: each on every When it offers, a
     * held direction in every length zone, and a surface's Swipe lines (Any, ↑ → ↓ ←).
     */
    fun allInputs(tops: List<BindableInput>): List<BindableInput> {
        val out = LinkedHashMap<List<InputSource>, BindableInput>()
        fun add(i: BindableInput) {
            for (t in Bindings.triggersOf(i)) {
                val ti = Bindings.triggerInput(i, t) ?: i
                out.putIfAbsent(ti.sources, ti)
            }
        }
        fun addZoned(i: BindableInput) {
            val s = i.sources.firstOrNull() as? InputSource.Surface
            if (s != null && s.input.heldSwipe) for (z in 0 until TouchpadSettings.MAX_HOLD_ZONES) add(Bindings.zoneInput(i, z))
            else add(i)
        }
        val surfaces = LinkedHashSet<Pair<Int, String>>()
        for (top in tops.filterNot(Bindings::isTriggerOf)) {
            addZoned(top)
            (top.sources.first() as? InputSource.Surface)?.let { surfaces += it.fingers to it.id }
        }
        for ((n, id) in surfaces) for (line in Bindings.swipeSlots(n, id)) addZoned(line)
        return out.values.toList()
    }

    // Settings

    /** The settings of a touch control a preset brings: everything but its id, look and place. */
    val BUTTON_SETTINGS: Set<String> = setOf(
        "deadzone", "zones", "longPressMs", "zoneInterrupt", "zoneResume", "followLayers", "surface",
        "sharesTouches", "gyro", "fingers",
    )

    /** The settings of a touch control that belong to its card of kind [kind] ([kindOf]). */
    fun cardSettings(kind: InputSource): Set<String> = when (kind) {
        is InputSource.Touch -> setOf("followLayers", "sharesTouches")
        is InputSource.TouchTap, is InputSource.TouchLongPress, is InputSource.TouchDoubleTap -> setOf("longPressMs")
        is InputSource.TouchArea -> setOf("zoneInterrupt", "zoneResume", "longPressMs")
        is InputSource.TouchStick, is InputSource.TouchStickDiagonal -> setOf("deadzone", "zones")
        // A move, swipe or held direction: the surface's settings (sensitivity, directions...); a tap: its timing.
        is InputSource.Surface -> when (kind.input) {
            SurfaceInput.TAP, SurfaceInput.DOUBLE_TAP, SurfaceInput.LONG_PRESS -> setOf("longPressMs")
            else -> setOf("surface", "longPressMs")
        }
        is InputSource.Gyro -> setOf("gyro")
        is InputSource.Finger -> setOf("fingers")
        else -> emptySet()
    }

    private val full = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }

    private fun tree(c: TouchControl): JsonObject = full.encodeToJsonElement(TouchControl.serializer(), c).jsonObject

    private fun control(o: JsonObject): TouchControl = full.decodeFromJsonElement(TouchControl.serializer(), o)

    /** What [c] does in [w], as far as its [keys] settings go: their values in effect, not who sets them. */
    private fun effect(c: TouchControl, w: Workspace): List<Any?> = listOf(
        c.longPress(w), c.deadzone, c.zones, c.zoneInterrupt, c.zoneResume, c.followLayers, c.sharesTouches,
        if (c.swipes) c.surfaceSettings(w.touchpad) else null,
        (listOf(MappingSet.BASE) + w.sets.map { it.id }).map { s -> c.gyroIn(listOf(MappingSet.BASE, s)).over(w.gyro) },
        FingerAxis.entries.map { c.fingerOf(it) },
    )

    /**
     * Every value under [e] with its path, a null one too (it follows the level above). A whole
     * null [TouchControl.surface] is left out: it means a button from before surface settings.
     */
    private fun leaves(e: JsonElement, path: List<String>, out: MutableList<Pair<List<String>, JsonElement>>) {
        when {
            e is JsonObject -> for ((k, v) in e) leaves(v, path + k, out)
            e is JsonNull && path == listOf("surface") -> {}
            else -> out += path to e
        }
    }

    private fun with(o: JsonObject, path: List<String>, v: JsonElement): JsonObject {
        val k = path.first()
        if (path.size == 1) return JsonObject(o + (k to v))
        val inner = o[k] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(o + (k to with(inner, path.drop(1), v)))
    }

    /**
     * [target] in [w] with [from]'s settings among [keys], so it does what [from] does, each value
     * written only where it changes what [target] does: one it already has in effect (its own or
     * the workspace's) is not written, so it doesn't turn up as an override of the level above. A
     * setting [from] leaves to the workspace is left to it on [target] too, where that changes it.
     */
    fun applySettings(target: TouchControl, from: TouchControl, keys: Set<String>, w: Workspace): TouchControl {
        val src = tree(from)
        val found = ArrayList<Pair<List<String>, JsonElement>>()
        for (k in keys) src[k]?.let { leaves(it, listOf(k), found) }
        var now = target
        var o = tree(target)
        for ((path, v) in found) {
            val next = runCatching { with(o, path, v).let { it to control(it) } }.getOrNull() ?: continue
            if (effect(next.second, w) == effect(now, w)) continue
            o = next.first
            now = next.second
        }
        return now
    }

    /** [from] sets something among [keys] (its own value, not the workspace's). */
    fun hasSettings(from: TouchControl, keys: Set<String>): Boolean {
        val src = tree(from)
        val found = ArrayList<Pair<List<String>, JsonElement>>()
        for (k in keys) src[k]?.let { leaves(it, listOf(k), found) }
        val plain = tree(TouchControl(from.id, from.kind, "", 0f, 0f))
        return found.any { (path, v) ->
            var e: JsonElement? = plain
            for (p in path) e = (e as? JsonObject)?.get(p)
            e != v
        }
    }
}
