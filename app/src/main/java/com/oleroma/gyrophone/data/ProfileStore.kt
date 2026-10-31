package com.oleroma.gyrophone.data

import android.content.Context
import android.util.Log
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.ButtonLibrary
import com.oleroma.gyrophone.core.model.LibraryButton
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

    private val libraryFile = File(context.filesDir, "button_library.json")

    /** The button library (empty if none saved, or if the file cannot be read: it is then left alone). */
    @Synchronized fun buttonLibrary(): List<LibraryButton> =
        if (!libraryFile.exists()) emptyList()
        else ButtonLibrary.decode(libraryFile.readText()).orEmpty()

    /** Saves the button library; refuses to replace a file that exists but cannot be read. */
    @Synchronized fun saveButtonLibrary(buttons: List<LibraryButton>) {
        if (libraryFile.exists() && ButtonLibrary.decode(libraryFile.readText()) == null) return
        replaceFile(libraryFile, ButtonLibrary.encode(buttons))
    }

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
    }
}
