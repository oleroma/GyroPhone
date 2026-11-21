package com.oleroma.gyrophone.data

import android.util.Log
import com.oleroma.gyrophone.core.model.ButtonLibrary
import com.oleroma.gyrophone.core.model.MappingPresets
import com.oleroma.gyrophone.core.model.ProfileExport
import com.oleroma.gyrophone.core.model.ProfileJson
import java.io.File

/**
 * Automatic backups of everything the person set up: copies of the saved files as they are on
 * disk (the workspace set, the button library, the mapping presets), each in a folder of its own
 * under files/backups named "<time ms>-<reason>". Raw copies, not decoded ones, so a file this
 * build can't fully read is kept whole too. Taken before an update's first start, before an
 * import or restore, and after a save when the last one is [AUTO_EVERY_MS] old; the newest
 * [KEEP] are kept. Never on an input thread: saves run on the profile writer.
 */
class SettingsBackups(filesDir: File) {
    private val root = File(filesDir, "backups")
    private val profiles = File(filesDir, "profiles")
    private val loose = listOf(File(filesDir, "button_library.json"), File(filesDir, "mapping_presets.json"))

    /** One backup: when it was taken and why. */
    data class Entry(val dir: File, val timeMs: Long, val reason: String) {
        val label: String
            get() = when (reason) {
                UPDATE -> "Before an update"
                IMPORT -> "Before an import"
                RESTORE -> "Before a restore"
                else -> "Automatic"
            }
    }

    /** Every backup, newest first. */
    @Synchronized fun list(): List<Entry> =
        root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d ->
            val dash = d.name.indexOf('-')
            val t = (if (dash < 0) d.name else d.name.substring(0, dash)).toLongOrNull() ?: return@mapNotNull null
            Entry(d, t, if (dash < 0) AUTO else d.name.substring(dash + 1))
        }.sortedByDescending { it.timeMs }

    /** Takes a backup now ([reason] one of the constants), unless there is nothing saved yet. */
    @Synchronized fun take(reason: String, nowMs: Long = System.currentTimeMillis()) {
        val sources = profiles.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().toList() + loose.filter { it.isFile }
        if (sources.none { it.parentFile == profiles }) return
        try {
            var dir = File(root, "$nowMs-$reason")
            var n = nowMs
            while (dir.exists()) dir = File(root, "${++n}-$reason")
            val prof = File(dir, "profiles").apply { mkdirs() }
            for (f in sources) f.copyTo(File(if (f.parentFile == profiles) prof else dir, f.name), overwrite = true)
            prune()
        } catch (e: Exception) {
            Log.w(TAG, "backup ($reason) failed", e)
        }
    }

    /** After a save: an automatic backup when the newest one is at least [AUTO_EVERY_MS] old. */
    @Synchronized fun takeIfDue(nowMs: Long = System.currentTimeMillis()) {
        val last = list().firstOrNull()?.timeMs ?: 0L
        if (nowMs - last >= AUTO_EVERY_MS || nowMs < last) take(AUTO, nowMs)
    }

    /**
     * Backup [e] read as an everything export: the workspace set with id [profileId] (the newest
     * file holding it), the button library and the mapping presets. Null when it holds none.
     */
    @Synchronized fun read(e: Entry, profileId: String): ProfileExport.Read? {
        val files = File(e.dir, "profiles").listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.lastModified() }
        var dropped = 0
        val profile = files.firstNotNullOfOrNull { f ->
            runCatching { ProfileJson.decodeLenient(f.readText()) }.getOrNull()?.takeIf { it.profile.id == profileId }
                ?.also { dropped = it.dropped }?.profile
        } ?: return null
        fun text(name: String) = File(e.dir, name).takeIf { it.isFile }?.readText()
        val library = text("button_library.json")?.let { ButtonLibrary.decodeLenient(it) }.orEmpty()
        val presets = text("mapping_presets.json")?.let { MappingPresets.decodeLenient(it) }.orEmpty()
        return ProfileExport.Read(ProfileExport.EVERYTHING, profile, library, presets, dropped)
    }

    private fun prune() {
        for (old in list().drop(KEEP)) old.dir.deleteRecursively()
    }

    companion object {
        private const val TAG = "SettingsBackups"
        const val AUTO = "auto"
        const val UPDATE = "update"
        const val IMPORT = "import"
        const val RESTORE = "restore"
        const val KEEP = 20
        const val AUTO_EVERY_MS = 10 * 60_000L
    }
}
