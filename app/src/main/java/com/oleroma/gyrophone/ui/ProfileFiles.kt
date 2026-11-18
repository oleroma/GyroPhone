package com.oleroma.gyrophone.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.oleroma.gyrophone.core.model.ProfileExport
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saving an export file: the system's "Save to" picker (phone storage, Drive...) under the name
 * given; [onDone] gets a line to show. Call the result with the file name and its text.
 */
@Composable
fun rememberExportFile(onDone: (String) -> Unit): (name: String, text: String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDone)
    var pending by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val held = pending
        pending = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val stash = pendingExportFile(context)
                // The app may have been restarted while the picker was open (its memory gone): the
                // text waits in the cache too.
                val text = held ?: runCatching { stash.readText(Charsets.UTF_8) }.getOrNull()
                stash.delete()
                when {
                    uri == null -> null
                    text == null -> {
                        // The picker already made the file: don't leave it empty.
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                        "Could not save the file"
                    }
                    writeExport(context, uri, text) -> "Exported"
                    else -> "Could not save the file"
                }
            }
            if (result != null) done(result)
        }
    }
    return { name, text ->
        pending = text
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { pendingExportFile(context).writeText(text, Charsets.UTF_8) } }
            saver.launch(name)
        }
    }
}

/** Where the text being exported waits while the "Save to" picker is open. */
private fun pendingExportFile(context: Context) = java.io.File(context.cacheDir, "pending-export.json")

/** Writes [text] to the new document [uri]: truncating where the provider allows it, else plain "w" (it is new and empty). */
private fun writeExport(context: Context, uri: Uri, text: String): Boolean {
    val bytes = text.toByteArray(Charsets.UTF_8)
    for (mode in arrayOf("wt", "w")) {
        val ok = runCatching { context.contentResolver.openOutputStream(uri, mode)?.use { it.write(bytes) } != null }.getOrDefault(false)
        if (ok) return true
    }
    return false
}

/**
 * Opening an export file with the system's file picker: [onRead] gets it read, [onError] a line
 * saying why it can't be imported. Call the result to open the picker.
 */
@Composable
fun rememberImportFile(onRead: (ProfileExport.Read) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val read by rememberUpdatedState(onRead)
    val error by rememberUpdatedState(onError)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Off the main thread, and never more than MAX_EXPORT_FILE: any file can be picked, even a large or cloud one.
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_EXPORT_FILE + 1) }
                        ?: throw ProfileExport.NotAnExport("Could not read that file.")
                    if (bytes.size > MAX_EXPORT_FILE) throw ProfileExport.NotAnExport("This is not a GyroPhone file.")
                    ProfileExport.read(bytes.toString(Charsets.UTF_8))
                }
            }
            result.fold(
                onSuccess = { read(it) },
                onFailure = { e -> error((e as? ProfileExport.NotAnExport)?.message ?: "Could not read that file.") },
            )
        }
    }
    return { picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }
}

/** Larger than any real export (a full set is well under 1 MB). */
private const val MAX_EXPORT_FILE = 16 * 1024 * 1024

/** Up to [limit] bytes from the start of [input]. */
private fun readAtMost(input: InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val r = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (r < 0) break
        out.write(buf, 0, r)
    }
    return out.toByteArray()
}

/** A short line shown over whatever is on screen. */
fun note(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
