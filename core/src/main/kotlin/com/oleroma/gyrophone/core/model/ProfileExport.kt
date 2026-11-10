package com.oleroma.gyrophone.core.model

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Export and import files: the profile JSON the app keeps ([ProfileJson]) under a small header
 * ("format", "version", "kind"), so builds older and newer can read each other's files (a newer
 * build's unknown mapping is left out, [ProfileJson.decodeLenient]).
 *
 * A workspace file holds one workspace, its pinned device preset and the Defaults it was made
 * under; an "everything" file the whole workspace set, the button library (button presets) and
 * the mapping editor's chip and card presets.
 */
object ProfileExport {
    const val FORMAT = "gyrophone"
    const val VERSION = 1
    const val WORKSPACE = "workspace"
    const val EVERYTHING = "everything"

    /** The file name for an export named [name]: "<name>.gyrophone.json", without characters file systems refuse. */
    fun fileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "GyroPhone" } + ".gyrophone.json"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun header(kind: String, body: Map<String, JsonElement>): String = json.encodeToString(
        JsonElement.serializer(),
        JsonObject(
            mapOf("format" to JsonPrimitive(FORMAT), "version" to JsonPrimitive(VERSION), "kind" to JsonPrimitive(kind)) + body,
        ),
    )

    private fun profileJson(p: Profile): JsonElement = json.parseToJsonElement(ProfileJson.encode(p))

    /**
     * Workspace [index] of [stored] (the workspace set as saved, [WorkspaceMappings.store]) as a
     * file: it with its pinned device preset, and the Defaults (an import keeps what they gave it).
     */
    fun workspace(stored: Profile, index: Int): String {
        val w = stored.workspaces[index.coerceIn(0, stored.workspaces.lastIndex)]
        val one = Profile(
            id = "export", name = w.name, workspaces = listOf(w),
            devicePresets = stored.devicePresets.filter { it.id == w.devicePreset },
            defaults = stored.defaults,
        )
        return header(WORKSPACE, mapOf("profile" to profileJson(one)))
    }

    /** Everything: the workspace set as saved, the button library and the chip and card presets. */
    fun everything(stored: Profile, library: List<LibraryButton>, presets: List<MappingPreset>): String = header(
        EVERYTHING,
        mapOf(
            "profile" to profileJson(stored),
            "library" to json.encodeToJsonElement(ListSerializer(LibraryButton.serializer()), library),
            "presets" to json.encodeToJsonElement(ListSerializer(MappingPreset.serializer()), presets),
        ),
    )

    /** A file read back: its [kind], the profile in it, and (everything) its library and presets. */
    class Read(
        val kind: String,
        val profile: Profile,
        val library: List<LibraryButton>,
        val presets: List<MappingPreset>,
        /** Mappings this build could not read, left out. */
        val dropped: Int,
    )

    /** Why a file cannot be imported. */
    class NotAnExport(message: String) : Exception(message)

    /** Reads an export file; throws [NotAnExport] (with a reason to show) when it is not one this build can read. */
    fun read(text: String): Read {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw NotAnExport("This is not a GyroPhone file.")
        if (obj["format"]?.jsonPrimitive?.content != FORMAT) throw NotAnExport("This is not a GyroPhone file.")
        val version = obj["version"]?.jsonPrimitive?.intOrNull ?: 0
        if (version > VERSION) throw NotAnExport("This file is from a newer GyroPhone. Update the app first.")
        val kind = obj["kind"]?.jsonPrimitive?.content ?: throw NotAnExport("This GyroPhone file is damaged.")
        val profile = obj["profile"]?.let { ProfileJson.decodeLenient(json.encodeToString(JsonElement.serializer(), it)) }
            ?: throw NotAnExport("This GyroPhone file is damaged.")
        fun <T> list(key: String, decode: (String) -> List<T>?): List<T> =
            (obj[key] as? JsonArray)?.let { decode(json.encodeToString(JsonElement.serializer(), it)) }.orEmpty()
        return Read(kind, profile.profile, list("library", ButtonLibrary::decode), list("presets", MappingPresets::decode), profile.dropped)
    }

    /** [name], or "[name] 2", "[name] 3"... whichever no workspace of [p] has. */
    private fun freeName(p: Profile, name: String): String {
        val names = p.workspaces.mapTo(HashSet()) { it.name }
        if (name !in names) return name
        var n = 2
        while ("$name $n" in names) n++
        return "$name $n"
    }

    /**
     * [p] with the workspace of file [r] added last, never replacing one: a new id and a name of
     * its own, its device preset added beside [p]'s. Where it followed the Defaults of the phone it
     * came from and [p]'s Defaults differ, it takes the old value as its own, so it works the same;
     * where they agree it keeps following them, so nothing shows as an override that changes nothing.
     */
    fun addWorkspace(p: Profile, r: Read): Profile {
        val src = r.profile
        var w = src.workspaces.first()
        val from = src.defaults
        val to = p.defaults
        // Settings: the value it had over the old Defaults; own only where the new ones give another.
        val own = WorkspaceDefaults.own(w)
        val had = WorkspaceDefaults.apply(w, from)
        val now = WorkspaceDefaults.apply(w.copy(ownSettings = own), to)
        val differ = WorkspaceDefaults.keys.filter { k -> k !in own && !WorkspaceDefaults.same(k, had, now) }
        w = differ.fold(now) { acc, k -> WorkspaceDefaults.take(k, acc, had) }.copy(ownSettings = own + differ)
        // Touchpad and gyro mappings it took from the old Defaults: its own where the new ones map that action otherwise.
        val hadMappings = Inheritance.workspace(w, from)
        val nowMappings = Inheritance.workspace(w, to)
        val keys = (hadMappings + nowMappings).mapNotNull { m -> m.takeIf { it.set == MappingSet.BASE }?.let { Inheritance.levelKey(it.source) } }.toSet()
        val add = keys.flatMap { k ->
            val a = hadMappings.filter { it.set == MappingSet.BASE && Inheritance.levelKey(it.source) == k }
            val b = nowMappings.filter { it.set == MappingSet.BASE && Inheritance.levelKey(it.source) == k }
            if (a.toSet() == b.toSet()) emptyList()
            // Nothing there before: it did nothing; cleared here so the new Defaults don't add it.
            else a.ifEmpty { b.take(1).map { it.copy(target = OutputTarget.NoOutput, options = null, range = null) } }
        }
        w = w.copy(mappings = w.mappings + add.filter { m -> w.mappings.none { it.source == m.source && it.set == m.set } })
        // Its device preset, beside the ones here.
        var presets = p.devicePresets
        val dp = src.devicePresets.firstOrNull { it.id == w.devicePreset }
        w = if (dp == null) w.copy(devicePreset = null) else {
            val id = DevicePresets.newId(presets.map { it.id })
            presets = presets + dp.copy(id = id)
            w.copy(devicePreset = id)
        }
        w = w.copy(id = ProfileJson.newWorkspaceId(p.workspaces.map { it.id }), name = freeName(p, w.name))
        return p.copy(workspaces = p.workspaces + w, devicePresets = presets)
    }

    /** [now] with each item of [added] that [now] lacks (alike but for its id) added under a free id from [newId]. */
    fun <T> merge(now: List<T>, added: List<T>, idOf: (T) -> String, withId: (T, String) -> T, newId: (Collection<String>) -> String): List<T> {
        val out = now.toMutableList()
        for (a in added) {
            if (out.any { withId(it, "") == withId(a, "") }) continue
            out += withId(a, newId(out.map(idOf)))
        }
        return out
    }
}
