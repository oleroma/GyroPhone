package com.oleroma.gyrophone.runtime

import android.os.Process
import com.oleroma.gyrophone.core.net.LogFileWire
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/**
 * Hands the newest Diagnostics save to the PC over TCP ([LogFileWire]), so getting it needs no adb. Runs only
 * while Diagnostics is on, on a background-priority thread, one PC at a time, and paces what it writes
 * ([BYTES_PER_SECOND]) so the transfer never crowds out the input packets on a shared network. Only the PC of
 * the live session gets anything: its request carries a mac under that session's log key ([keyFor]), and the
 * file goes out sealed under it.
 */
class LogFileServer(
    private val dir: File,
    /** The live session's log key when its id is the one given, else null. */
    private val keyFor: (Int) -> ByteArray?,
    /** A save is being written (answer "not yet" rather than send an older one). */
    private val saving: () -> Boolean,
    private val note: (String) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val s = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LogFileWire.PORT), 2)
            }
        }.getOrElse {
            note("diagnostics file server: not started (${it.javaClass.simpleName})")
            return
        }
        server = s
        thread(name = "gp-logfile", isDaemon = true) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            while (server === s) {
                val c = try {
                    s.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    continue
                }
                runCatching { serve(c) }
                runCatching { c.close() }
            }
        }
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        runCatching { s.close() }
    }

    private fun serve(c: Socket) {
        c.soTimeout = IO_TIMEOUT_MS
        c.tcpNoDelay = true
        val input = DataInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val req = ByteArray(LogFileWire.REQUEST)
        input.readFully(req)
        val sid = LogFileWire.requestSessionId(req)
        val key = keyFor(sid)
        if (key == null) {
            out.write(LogFileWire.head(LogFileWire.OFF))
            return
        }
        val r = LogFileWire.decodeRequest(key, req)
        if (r == null || r.kind != LogFileWire.KIND_NEWEST) {
            out.write(LogFileWire.head(LogFileWire.REFUSED))
            return
        }
        val newest = dir.listFiles { f -> f.name.startsWith("phone-") && f.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() }
        if (saving() || newest == null || System.currentTimeMillis() - newest.lastModified() > r.maxAgeS * 1000L) {
            out.write(LogFileWire.head(LogFileWire.NOT_YET))
            return
        }
        val raw = newest.readBytes()
        val zipped = ByteArrayOutputStream(raw.size / 6 + 1024).also { b -> GZIPOutputStream(b).use { it.write(raw) } }.toByteArray()
        out.write(LogFileWire.head(LogFileWire.OK))
        var index = 0
        out.write(LogFileWire.sealFrame(key, r.reqId, index++, LogFileWire.meta(raw.size, zipped.size, newest.name)))
        var off = 0
        val started = System.nanoTime()
        while (off < zipped.size) {
            val n = minOf(LogFileWire.FRAME_MAX, zipped.size - off)
            out.write(LogFileWire.sealFrame(key, r.reqId, index++, zipped, off, n))
            off += n
            // Paced: [off] bytes should take off / BYTES_PER_SECOND seconds.
            val ahead = off * 1_000_000_000L / BYTES_PER_SECOND - (System.nanoTime() - started)
            if (ahead > 0) Thread.sleep(ahead / 1_000_000, (ahead % 1_000_000).toInt())
        }
        out.write(LogFileWire.sealFrame(key, r.reqId, index, ByteArray(0)))
        out.flush()
        note("diagnostics file sent to the PC: ${newest.name}, ${zipped.size / 1024} KB zipped")
    }

    private companion object {
        const val IO_TIMEOUT_MS = 5_000
        /** At most 1 MB a second: a typical save (100-300 KB zipped) goes in well under a second. */
        const val BYTES_PER_SECOND = 1_000_000L
    }
}
