package com.oleroma.gyrophone.data

import android.content.Context
import android.util.Log
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.MappingProfile
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.ProfileJson
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * User profiles as JSON files in app storage; builtins are always listed after them. Files are
 * read with [ProfileJson.decode] (which migrates files saved before workspaces) and written with
 * [ProfileJson.encode]. A file that fails to decode is skipped but kept on disk untouched: it is
 * never deleted or overwritten, so a later build can still read it.
 */
class ProfileStore(context: Context) {
    private val dir = File(context.filesDir, "profiles").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("gyrophone", Context.MODE_PRIVATE)

    /** The file each listed user profile was read from, by profile id. */
    private val files = HashMap<String, File>()

    @Synchronized fun userProfiles(): List<Profile> {
        files.clear()
        val out = ArrayList<Profile>()
        // Newest first, so if two files hold the same id the last saved one wins.
        for (f in dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }) {
            val p = runCatching { ProfileJson.decode(f.readText()) }.getOrNull()
            if (p == null || isBuiltin(p) || p.id in files) continue
            files[p.id] = f
            out += p
        }
        return out.sortedBy { it.name }
    }

    fun all(): List<Profile> = userProfiles() + BuiltinProfiles.all

    @Synchronized fun save(profile: Profile) {
        require(!isBuiltin(profile)) { "builtin profiles are read-only" }
        val file = files[profile.id] ?: freeFile(profile.id).also { files[profile.id] = it }
        replaceFile(file, ProfileJson.encode(profile))
    }

    /**
     * Writes [text] beside [file], flushes it to the disk and renames it over [file], so a crash
     * or power cut leaves the old file or the new one, never a torn one.
     */
    private fun replaceFile(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    /** Profiles waiting for [saveLater]'s writer, the newest per id. */
    private val pending = HashMap<String, Profile>()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "profile-writer") }

    /**
     * Saves [profile] on one background thread, so a caller on the UI thread (a gyro corner tap in
     * touch dispatch) never waits for the disk. Writes run one at a time and each takes the newest
     * profile queued for its id, so an older one never lands after a newer one. Once a profile is
     * saved through here, save it only through here: a direct [save] could be overwritten by an
     * older one still queued.
     */
    fun saveLater(profile: Profile) {
        require(!isBuiltin(profile)) { "builtin profiles are read-only" }
        // A write already queued for this id will pick this newer profile up.
        if (synchronized(pending) { pending.put(profile.id, profile) != null }) return
        writer.execute {
            val newest = synchronized(pending) { pending.remove(profile.id) } ?: return@execute
            try { save(newest) } catch (e: Exception) { Log.w(TAG, "saving profile ${newest.id} failed", e) }
        }
    }

    /** Deletes the file the listed user profile [id] was read from (or saved to); nothing else. */
    @Synchronized fun delete(id: String) {
        files.remove(id)?.delete()
        prefs.edit().remove(KEY_WORKSPACE_PREFIX + id).apply()
    }

    /** The saved active profile id; an id of a builtin that no longer exists means the Default builtin. */
    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE, null)?.let { if (it in BuiltinProfiles.retiredIds) BuiltinProfiles.default.id else it }
        set(value) { prefs.edit().putString(KEY_ACTIVE, value).apply() }

    /** The last active workspace index of profile [id] (0 if none saved; callers clamp it). */
    fun workspaceIndex(id: String): Int = prefs.getInt(KEY_WORKSPACE_PREFIX + id, 0)

    fun setWorkspaceIndex(id: String, index: Int) {
        prefs.edit().putInt(KEY_WORKSPACE_PREFIX + id, index).apply()
    }

    private val padPresetsFile = File(context.filesDir, "pad_presets.json")

    /** The user's pad presets (empty if none saved, or if the file cannot be read: it is then left alone). */
    @Synchronized fun padPresets(): List<PadPreset> =
        if (!padPresetsFile.exists()) emptyList()
        else PadPresets.decode(padPresetsFile.readText()).orEmpty()

    /** Saves the user's pad presets; refuses to replace a file that exists but cannot be read. */
    @Synchronized fun savePadPresets(presets: List<PadPreset>) {
        if (padPresetsFile.exists() && PadPresets.decode(padPresetsFile.readText()) == null) return
        replaceFile(padPresetsFile, PadPresets.encode(presets))
    }

    private val mappingFile = File(context.filesDir, "mapping_profiles.json")

    /** The saved mapping profiles; null when none were ever saved, or the file cannot be read (it is then left alone). */
    @Synchronized fun mappingProfiles(): List<MappingProfile>? =
        if (!mappingFile.exists()) null
        else MappingProfiles.decode(mappingFile.readText())

    /** True when a mapping profile file exists, readable or not. */
    fun hasMappingFile(): Boolean = mappingFile.exists()

    /** Saves the mapping profiles; refuses to replace a file that exists but cannot be read. */
    @Synchronized fun saveMappingProfiles(profiles: List<MappingProfile>) {
        if (mappingFile.exists() && MappingProfiles.decode(mappingFile.readText()) == null) return
        replaceFile(mappingFile, MappingProfiles.encode(profiles))
    }

    /**
     * Keeps a copy of [profile] as it is now in "<file>.<tag>.bak" beside the profiles (not listed
     * as a profile); an existing copy is never overwritten.
     */
    @Synchronized fun backup(profile: Profile, tag: String) {
        val bak = File(dir, fileName(profile.id) + ".$tag.bak")
        if (!bak.exists()) bak.writeText(ProfileJson.encode(profile))
    }

    /** The copy [backup] kept of profile [id] under [tag], or null when there is none (or it cannot be read). */
    @Synchronized fun readBackup(id: String, tag: String): Profile? {
        val bak = File(dir, fileName(id) + ".$tag.bak")
        return if (!bak.exists()) null else runCatching { ProfileJson.decode(bak.readText()) }.getOrNull()
    }

    /** Keeps a copy of the mapping profile file as "mapping_profiles.json.<tag>.bak"; an existing copy is kept. */
    @Synchronized fun backupMappings(tag: String) {
        val bak = File(mappingFile.path + ".$tag.bak")
        if (mappingFile.exists() && !bak.exists()) mappingFile.copyTo(bak)
    }

    /** Which version of the move to mapping profiles last ran (0 = none recorded). */
    var mappingMigration: Int
        get() = prefs.getInt(KEY_MAPPING_MIGRATION, 0)
        set(value) { prefs.edit().putInt(KEY_MAPPING_MIGRATION, value).apply() }

    fun isBuiltin(profile: Profile): Boolean = profile.id.startsWith("builtin.")

    private fun fileName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"

    /** A file for a profile not read from disk: its usual name, unless that is taken by another (or unreadable) file. */
    private fun freeFile(id: String): File {
        val base = fileName(id).removeSuffix(".json")
        var name = "$base.json"
        var n = 2
        while (File(dir, name).exists()) name = "$base-${n++}.json"
        return File(dir, name)
    }

    companion object {
        private const val TAG = "ProfileStore"
        private const val KEY_ACTIVE = "active_profile"
        private const val KEY_WORKSPACE_PREFIX = "workspace_"
        private const val KEY_MAPPING_MIGRATION = "mapping_migration"
    }
}
