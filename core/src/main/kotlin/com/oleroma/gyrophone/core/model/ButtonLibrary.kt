package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A touch control saved to the button library, shared by every workspace: its look and size, and
 * its [mappings] (keyed by [control]'s id, as in a workspace). It is also a button preset of the
 * mapping editor: its mappings (and [control]'s settings) go onto a control of the same kind
 * ([ButtonLibrary.kindOf]). One saved from a physical control ([control] a stand-in, id "dev:...")
 * is a button preset only, its mappings as [chips]; so is one of the touchpad ([control] id "").
 */
@Serializable
data class LibraryButton(
    val id: String,
    val control: TouchControl,
    val mappings: List<Mapping> = emptyList(),
    /** The mapping sets [mappings] use or switch to, with their names. */
    val sets: List<MappingSet> = emptyList(),
    /** Its name as a preset; null = [ButtonLibrary.name]'s. */
    val name: String? = null,
    /** A physical control's mappings, by input place ([MappingPresets.slot]); empty for a touch control (its [mappings] say it). */
    val chips: List<PresetChip> = emptyList(),
)

object ButtonLibrary {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(LibraryButton.serializer())

    fun encode(buttons: List<LibraryButton>): String = json.encodeToString(listSerializer, buttons)

    /** Reads a saved list; unreadable text gives null (the caller keeps the file). */
    fun decode(text: String): List<LibraryButton>? =
        runCatching { json.decodeFromString(listSerializer, text) }.getOrNull()

    fun newId(taken: Collection<String>): String {
        var n = 1
        while ("lib$n" in taken) n++
        return "lib$n"
    }

    /** Control [c] of [w] as a library button (with its mappings in every set), given an id not in [taken]. */
    fun save(w: Workspace, c: TouchControl, taken: Collection<String>): LibraryButton {
        val mappings = w.mappings.filter { MappingProfiles.touchId(it.source) == c.id }
        val used = setsOf(mappings)
        return LibraryButton(newId(taken), c.copy(group = null), mappings, w.sets.filter { it.id in used }.map { it.copy(settings = null) })
    }

    private fun setsOf(mappings: List<Mapping>): Set<Int> =
        mappings.flatMapTo(HashSet()) { m -> listOfNotNull(m.set, (m.target as? OutputTarget.Layer)?.set) } - MappingSet.BASE

    /** One saved from a physical control or the touchpad: a button preset only, never added to the pad. */
    fun presetOnly(b: LibraryButton): Boolean = b.control.id.isEmpty() || b.control.id.startsWith("dev:")

    /**
     * What kind of control [c] is, for button presets: a touch button, a touch stick, the touchpad
     * (id ""), or a physical device's button or stick.
     */
    fun kindOf(c: TouchControl): String = when {
        c.id.isEmpty() -> "touchpad"
        c.id.startsWith("dev:") -> "dev." + c.id.removePrefix("dev:").substringBefore(':') + "." + c.kind.name
        else -> c.kind.name
    }

    /** The name shown for [b]: its own, else its label, else its first mapping's short name. */
    fun name(b: LibraryButton): String =
        b.name ?: b.control.label.ifBlank {
            Bindings.touchInputs(b.control).firstNotNullOfOrNull { Bindings.targets(b.mappings, it).firstOrNull() }
                ?.let { Describe.shortTarget(it) } ?: "Button"
        }

    /**
     * [w] with [b] added as control [id], at [placed] (the caller's placement of it). Mappings of
     * mapping sets [w] does not have are added with those sets, so layer buttons keep working.
     */
    fun addTo(w: Workspace, b: LibraryButton, id: String, placed: (TouchControl) -> TouchControl): Workspace {
        val control = placed(b.control.copy(id = id, group = null, display = b.control.display?.migrated()))
        val mappings = WorkspaceMappings.rekeyAll(b.mappings, b.control.id, id)
        val missing = setsOf(mappings).filter { set -> w.sets.none { it.id == set } }
        val added = missing.map { set -> b.sets.firstOrNull { it.id == set } ?: MappingSet(set) }
        return w.copy(
            touchControls = w.touchControls + control,
            mappings = w.mappings + mappings,
            sets = (w.sets + added).sortedBy { it.id },
        )
    }
}
