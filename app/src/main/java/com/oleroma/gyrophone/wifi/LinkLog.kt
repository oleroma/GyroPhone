package com.oleroma.gyrophone.wifi

import android.net.TrafficStats
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's side of the link, written to `Android/data/<app>/files/linklog/linklog.txt` so it can be read
 * over adb after a test on a network adb can't reach (the phone's own hotspot). The send thread only puts
 * numbers in a ring ([slowSend], [failedSend], [lateWake], [stateGap], [second]); a background thread turns
 * them into lines once a second, with the phone's network counters next to them. Nothing is allocated on
 * the send thread, and a full ring drops records (counted) rather than wait.
 */
object LinkLog {
    private const val TAG = "LinkLog"
    private const val FIELDS = 8
    private const val CAPACITY = 8192
    private const val MAX_BYTES = 8L * 1024 * 1024

    private const val SLOW_SEND = 1L
    private const val FAILED_SEND = 2L
    private const val LATE_WAKE = 3L
    private const val STATE_GAP = 4L
    private const val SECOND = 5L

    /** A send that took at least this long is written down. */
    const val SLOW_SEND_NS = 2_000_000L
    /** The send thread woke at least this much later than it asked to. */
    const val LATE_WAKE_NS = 5_000_000L
    /** Two STATEs at least this far apart (the keepalive is 20 ms). */
    const val STATE_GAP_NS = 30_000_000L

    private val ring = LongArray(CAPACITY * FIELDS)
    private val head = AtomicLong()
    private val tail = AtomicLong()
    @Volatile private var dropped = 0L
    @Volatile private var lastError: String? = null
    @Volatile private var thread: Thread? = null

    /** Starts the writer (once); [dir] is where linklog.txt goes. */
    @Synchronized
    fun start(dir: File) {
        if (thread != null) return
        thread = Thread({ run(dir) }, "gp-linklog").apply {
            isDaemon = true
            start()
        }
    }

    /** A socket send took [durNs] (send thread). */
    fun slowSend(t: Long, durNs: Long, type: Int, len: Int) = put(SLOW_SEND, t, durNs, type.toLong(), len.toLong())

    /** A socket send threw after [durNs] (send thread); [e] is kept as the last error. */
    fun failedSend(t: Long, durNs: Long, type: Int, e: Exception) {
        lastError = e.toString()
        put(FAILED_SEND, t, durNs, type.toLong())
    }

    /** The send thread woke [lateNs] after the time it parked for. */
    fun lateWake(t: Long, lateNs: Long) = put(LATE_WAKE, t, lateNs)

    /** [gapNs] since the previous STATE went out. */
    fun stateGap(t: Long, gapNs: Long) = put(STATE_GAP, t, gapNs)

    /** The send thread's last second: STATEs and all packets sent, failed, bytes, time in send (total and longest), latest wake. */
    fun second(t: Long, states: Int, packets: Int, failed: Int, bytes: Long, sendNs: Long, sendMaxNs: Long, wakeLateMaxNs: Long) =
        put(SECOND, t, states.toLong() or (packets.toLong() shl 20) or (failed.toLong() shl 40), bytes, sendNs, sendMaxNs, wakeLateMaxNs)

    private fun put(kind: Long, t: Long, a: Long = 0, b: Long = 0, c: Long = 0, d: Long = 0, e: Long = 0) {
        val h = head.get()
        if (h - tail.get() >= CAPACITY) {
            dropped++
            return
        }
        val i = ((h % CAPACITY) * FIELDS).toInt()
        ring[i] = kind; ring[i + 1] = t; ring[i + 2] = a; ring[i + 3] = b
        ring[i + 4] = c; ring[i + 5] = d; ring[i + 6] = e
        head.lazySet(h + 1)
    }

    private fun run(dir: File) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val day = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        var procNet = true
        var lastTx = TrafficStats.getTotalTxPackets()
        var lastDropped = 0L
        try {
            dir.mkdirs()
            val file = File(dir, "linklog.txt")
            if (file.length() > MAX_BYTES) file.renameTo(File(dir, "linklog.old.txt"))
            FileWriter(file, true).use { w ->
                w.write("# ${day.format(Date())} link log started (pid ${Process.myPid()})\n")
                w.write("# columns: time kind ... (times in ms; SEC = one second of the send thread)\n")
                w.flush()
                while (true) {
                    Thread.sleep(1000)
                    // nanoTime to the wall clock, for lining up with the PC's logs.
                    val offsetMs = System.currentTimeMillis() - System.nanoTime() / 1_000_000L
                    val sb = StringBuilder()
                    val h = head.get()
                    var tl = tail.get()
                    while (tl < h) {
                        val i = ((tl % CAPACITY) * FIELDS).toInt()
                        val at = clock.format(Date(ring[i + 1] / 1_000_000L + offsetMs))
                        val a = ring[i + 2]; val b = ring[i + 3]; val c = ring[i + 4]
                        val d = ring[i + 5]; val e = ring[i + 6]
                        when (ring[i]) {
                            SLOW_SEND -> sb.append(at).append(" slow-send ").append(ms(a)).append(" ms type 0x")
                                .append(b.toString(16)).append(" len ").append(c).append('\n')
                            FAILED_SEND -> sb.append(at).append(" FAILED-send ").append(ms(a)).append(" ms type 0x")
                                .append(b.toString(16)).append(' ').append(lastError).append('\n')
                            LATE_WAKE -> sb.append(at).append(" late-wake ").append(ms(a)).append(" ms\n")
                            STATE_GAP -> sb.append(at).append(" state-gap ").append(ms(a)).append(" ms\n")
                            SECOND -> sb.append(at).append(" SEC states ").append(a and 0xFFFFF)
                                .append(" packets ").append((a shr 20) and 0xFFFFF).append(" failed ").append((a shr 40) and 0xFFFFF)
                                .append(" bytes ").append(b).append(" send_ms ").append(ms(c)).append(" send_max_ms ").append(ms(d))
                                .append(" wake_late_max_ms ").append(ms(e)).append('\n')
                        }
                        tl++
                    }
                    tail.set(tl)
                    val now = clock.format(Date())
                    val tx = TrafficStats.getTotalTxPackets()
                    sb.append(now).append(" NET phone_tx_packets ").append(tx - lastTx)
                    lastTx = tx
                    val dr = dropped
                    if (dr != lastDropped) { sb.append(" log_dropped ").append(dr - lastDropped); lastDropped = dr }
                    sb.append('\n')
                    if (procNet) {
                        try {
                            File("/proc/net/dev").readLines().filter { it.contains("wlan") || it.contains("ap") }
                                .forEach { sb.append(now).append(" DEV ").append(it.trim().replace(Regex("\\s+"), " ")).append('\n') }
                            File("/proc/net/snmp").readLines().filter { it.startsWith("Udp:") }.lastOrNull()
                                ?.let { sb.append(now).append(" UDP ").append(it).append('\n') }
                        } catch (x: Exception) {
                            sb.append(now).append(" (no /proc/net: ").append(x.javaClass.simpleName).append(")\n")
                            procNet = false
                        }
                    }
                    w.write(sb.toString())
                    w.flush()
                }
            }
        } catch (x: InterruptedException) {
            // app going away
        } catch (x: Exception) {
            Log.w(TAG, "link log stopped", x)
        }
    }

    private fun ms(ns: Long): String = String.format(Locale.US, "%.2f", ns / 1_000_000.0)
}
