package com.oleroma.gyrophone.data

import android.content.Context
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.Profile
import kotlinx.serialization.json.Json
import java.io.File

/** User profiles as JSON files in app storage; builtins are always listed after them. */
class ProfileStore(context: Context) {
    private val dir = File(context.filesDir, "profiles").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("gyrophone", Context.MODE_PRIVATE)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun userProfiles(): List<Profile> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { json.decodeFromString(Profile.serializer(), it.readText()) }.getOrNull() }
        .sortedBy { it.name }

    fun all(): List<Profile> = userProfiles() + BuiltinProfiles.all

    fun save(profile: Profile) {
        require(!isBuiltin(profile)) { "builtin profiles are read-only" }
        File(dir, fileName(profile.id)).writeText(json.encodeToString(Profile.serializer(), profile))
    }

    fun delete(id: String) { File(dir, fileName(id)).delete() }

    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) { prefs.edit().putString(KEY_ACTIVE, value).apply() }

    fun isBuiltin(profile: Profile): Boolean = profile.id.startsWith("builtin.")

    private fun fileName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"

    companion object {
        private const val KEY_ACTIVE = "active_profile"
    }
}
