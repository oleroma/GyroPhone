package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A named mapping for a physical game pad, applied to an [AttachedPad] by copying its mappings. */
@Serializable
data class PadPreset(
    val id: String,
    val name: String,
    /** Android key / axis sources ([InputSource.Key], [InputSource.Axis]) to PC outputs. */
    val mappings: List<Mapping> = emptyList(),
)

/**
 * The pad presets (shown as a device's "profile"): two builtins (the standard controller mapping
 * for the pad's family, and keyboard + mouse with WASD) followed by the user's own. A pad keeps a
 * copy of its preset's mappings and the preset's id ([AttachedPad.presetId]); it shows that preset
 * while the mappings still match it, else any preset with exactly its mappings, else "Custom".
 */
object PadPresets {
    const val STANDARD = "builtin.standard"
    const val KBM = "builtin.kbm"
    const val CUSTOM_NAME = "Custom"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(PadPreset.serializer())

    /** The builtin presets; the standard one follows [family] (Nintendo swaps A/B and X/Y). */
    fun builtins(family: FaceLayout): List<PadPreset> = listOf(
        PadPreset(STANDARD, "Standard controller", KnownLayouts.familyMappings(family)),
        PadPreset(KBM, "Keyboard + mouse (WASD)", BuiltinProfiles.kbmPadMappings()),
    )

    fun isBuiltin(id: String): Boolean = id.startsWith("builtin.")

    /** Builtins for [family], then [user] presets. */
    fun all(family: FaceLayout, user: List<PadPreset>): List<PadPreset> = builtins(family) + user

    /**
     * The pad's preset: the one it names while the mappings still equal it, else the first preset
     * whose mappings equal the pad's (order ignored), or null when the pad is custom.
     */
    fun applied(pad: AttachedPad, user: List<PadPreset>): PadPreset? {
        val mine = pad.mappings.toSet()
        val all = all(pad.family, user)
        pad.presetId?.let { id -> all.firstOrNull { it.id == id && it.mappings.toSet() == mine }?.let { return it } }
        return all.firstOrNull { it.mappings.toSet() == mine }
    }

    /** The name shown for the pad's mapping: its preset's, else [CUSTOM_NAME]. */
    fun label(pad: AttachedPad, user: List<PadPreset>): String = applied(pad, user)?.name ?: CUSTOM_NAME

    fun apply(pad: AttachedPad, preset: PadPreset): AttachedPad = pad.copy(mappings = preset.mappings, presetId = preset.id)

    /**
     * True when [pad] uses the saved preset [old]: its mappings still equal the preset's (order
     * ignored) and it names that preset, or names none (pads saved before presets were named).
     * A pad that names a deleted preset whose id was reused, or that was edited since, is not.
     */
    fun uses(pad: AttachedPad, old: PadPreset): Boolean =
        (pad.presetId == old.id || pad.presetId == null) && pad.mappings.toSet() == old.mappings.toSet()

    /**
     * [profile] with every pad that [uses] [old] (in any workspace) given [updated]'s mappings and
     * id; the same instance when no pad uses it.
     */
    fun retarget(profile: Profile, old: PadPreset, updated: PadPreset): Profile {
        if (profile.workspaces.none { w -> w.pads.any { uses(it, old) } }) return profile
        return profile.copy(workspaces = profile.workspaces.map { w ->
            if (w.pads.none { uses(it, old) }) w
            else w.copy(pads = w.pads.map { if (uses(it, old)) apply(it, updated) else it })
        })
    }

    /** A user preset id not in [taken]. */
    fun newId(taken: Collection<String>): String {
        var n = 1
        while ("user.pad$n" in taken) n++
        return "user.pad$n"
    }

    fun encode(presets: List<PadPreset>): String = json.encodeToString(listSerializer, presets)

    /** Reads a saved preset list; unreadable text gives null (the caller keeps the file). */
    fun decode(text: String): List<PadPreset>? = runCatching { json.decodeFromString(listSerializer, text) }.getOrNull()
}
