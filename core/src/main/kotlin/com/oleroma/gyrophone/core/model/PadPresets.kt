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
 * The pad presets: two builtins (the standard controller mapping for the pad's family, and
 * keyboard + mouse with WASD) followed by the user's own. A pad's preset is not stored: it is
 * whichever preset has exactly the pad's mappings, so editing a mapping makes the pad "Custom".
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

    /** The first preset whose mappings equal the pad's (order ignored), or null when the pad is custom. */
    fun applied(pad: AttachedPad, user: List<PadPreset>): PadPreset? {
        val mine = pad.mappings.toSet()
        return all(pad.family, user).firstOrNull { it.mappings.toSet() == mine }
    }

    /** The name shown for the pad's mapping: its preset's, else [CUSTOM_NAME]. */
    fun label(pad: AttachedPad, user: List<PadPreset>): String = applied(pad, user)?.name ?: CUSTOM_NAME

    fun apply(pad: AttachedPad, preset: PadPreset): AttachedPad = pad.copy(mappings = preset.mappings)

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
