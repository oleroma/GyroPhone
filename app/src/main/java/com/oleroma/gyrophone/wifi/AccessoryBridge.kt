package com.oleroma.gyrophone.wifi

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.oleroma.gyrophone.core.net.UsbDeframer
import com.oleroma.gyrophone.core.net.UsbFrames
import com.oleroma.gyrophone.core.net.Wire
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.locks.LockSupport

/**
 * The direct USB cable (Android Open Accessory): the companion switches the phone to accessory
 * mode naming GyroPhone, and the cable becomes a byte stream to it. This bridges that stream to
 * a loopback UDP socket, so [WifiLink] uses the cable exactly like USB tethering: it sends to
 * [UsbTetherInfo.target] (this bridge) and whatever comes down the cable arrives from there.
 * Each open of the cable is its own [Cable], with its own threads: gp-acc-rx reads the cable,
 * gp-acc-tx the loopback socket, gp-acc-wr writes the game input. Writes to the cable block while
 * the PC does not read, and nothing but unplugging wakes them, so the game input ([WifiPump]) skips
 * the loopback but never writes itself: [sendDirect] hands the datagram to gp-acc-wr and returns.
 */
class AccessoryBridge(context: Context) {
    private val appContext = context.applicationContext
    private val usb = appContext.getSystemService(UsbManager::class.java)

    /** The open cable, or null. A thread of an earlier one only ever marks its own dead. */
    @Volatile private var cable: Cable? = null
    private var info: UsbTetherInfo? = null
    private var asked: UsbAccessory? = null
    /** The local ports of [WifiLink]'s own sockets: datagrams from any other (another app on the phone) are dropped. */
    @Volatile private var ownPorts = IntArray(0)

    /** Only [ports] (WifiLink's sockets; 0 or less skipped) may send down the cable and get its datagrams. */
    fun allowFrom(vararg ports: Int) {
        ownPorts = ports.filter { it > 0 }.toIntArray()
    }

    /**
     * Called about once a second (rx thread): the cable as a [UsbTetherInfo] while GyroPhone's
     * accessory is attached and open, else null. Asks for permission once per plug-in when the
     * system did not grant it already (it does when the user picked GyroPhone for the accessory).
     */
    fun poll(): UsbTetherInfo? {
        val c = cable
        if (info != null && c != null && !c.dead) return info
        if (info != null) close()
        val acc = usb?.accessoryList?.firstOrNull { isOurs(it) }
        if (acc == null) {
            asked = null
            return null
        }
        if (!usb.hasPermission(acc)) {
            if (asked != acc) {
                asked = acc
                val intent = Intent(ACTION_PERMISSION).setPackage(appContext.packageName)
                usb.requestPermission(acc, PendingIntent.getBroadcast(appContext, 0, intent, PendingIntent.FLAG_MUTABLE))
            }
            return null
        }
        return open(acc)
    }

    private fun open(acc: UsbAccessory): UsbTetherInfo? {
        val fd = try {
            usb?.openAccessory(acc)
        } catch (e: RuntimeException) {
            Log.w(TAG, "openAccessory failed", e)
            null
        } ?: return null
        val s = try {
            DatagramSocket(InetSocketAddress(LOOPBACK, 0)).apply { trafficClass = Wire.DSCP_TRAFFIC_CLASS }
        } catch (e: IOException) {
            Log.w(TAG, "no loopback socket", e)
            fd.close()
            return null
        }
        val c = Cable(fd, s)
        cable = c
        c.start()
        Log.i(TAG, "accessory open: ${acc.manufacturer} ${acc.model} ${acc.serial}")
        return UsbTetherInfo("accessory", LOOPBACK, c.addr, direct = true).also { info = it }
    }

    /**
     * Hands datagram [b] ([off], [len]) to the cable's writer when [to] is this bridge's loopback
     * address and the cable is open; never waits. False when it isn't for the cable, the cable is
     * gone, or the PC stopped reading it (the caller sends it as usual then): a write pending longer
     * than [STUCK_NS] marks the cable dead, and the next [poll] lets go of it, so the link moves off it.
     */
    fun sendDirect(to: java.net.SocketAddress?, b: ByteArray, off: Int, len: Int): Boolean {
        val c = cable ?: return false
        if (to == null || to != c.addr) return false
        return c.offer(b, off, len)
    }

    /** Lets go of the cable (the link stopped, or the accessory went away). */
    fun close() {
        val c = cable
        cable = null
        info = null
        c?.close()
    }

    /** One open of the accessory: its fd, loopback socket and threads, and its own dead flag. */
    private inner class Cable(private val fd: ParcelFileDescriptor, private val socket: DatagramSocket) {
        /** The loopback address [WifiLink] sends the cable's datagrams to. */
        val addr = InetSocketAddress(LOOPBACK, socket.localPort)
        private val out = FileOutputStream(fd.fileDescriptor)
        @Volatile var dead = false
            private set
        /** The WifiLink socket that last sent here: datagrams from the PC go to it. */
        @Volatile private var peer: SocketAddress? = null
        /** One frame at a time on the cable, from gp-acc-wr or gp-acc-tx. */
        private val writeLock = Any()
        /** When a gp-acc-wr write began, waiting for the lock included (0: none under way). */
        @Volatile private var writingSince = 0L
        /** A write waited longer than [STUCK_NS] (logged on [close], off the pump's thread). */
        @Volatile private var stuck = false

        // [sendDirect] (the pump, the one producer) to gp-acc-wr (the one consumer): a ring of ready frames.
        private val ring = Array(RING) { ByteArray(UsbFrames.maxSize(Wire.MAX_DATAGRAM)) }
        private val ringLen = IntArray(RING)
        @Volatile private var head = 0L
        @Volatile private var tail = 0L

        private val rx = Thread({ cableToLoop() }, "gp-acc-rx").apply { isDaemon = true }
        private val tx = Thread({ loopToCable() }, "gp-acc-tx").apply { isDaemon = true }
        private val wr = Thread({ writeLoop() }, "gp-acc-wr").apply { isDaemon = true }

        fun start() {
            rx.start()
            tx.start()
            wr.start()
        }

        /** The pump's thread: queues one datagram for gp-acc-wr (no lock, no allocation, no wait). */
        fun offer(b: ByteArray, off: Int, len: Int): Boolean {
            if (dead) return false
            val since = writingSince
            if (since != 0L && System.nanoTime() - since > STUCK_NS) {
                stuck = true
                dead = true
                return false
            }
            val h = head
            if (h - tail >= RING) return false
            val i = (h and RING_MASK).toInt()
            ringLen[i] = UsbFrames.encode(b, off, len, ring[i])
            head = h + 1
            LockSupport.unpark(wr)
            return true
        }

        private fun write(frame: ByteArray, n: Int) {
            writingSince = System.nanoTime() or 1L
            try {
                synchronized(writeLock) { out.write(frame, 0, n) }
            } finally {
                writingSince = 0L
            }
        }

        private fun writeLoop() {
            // It carries the game input: as urgent as the pump.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                while (!dead) {
                    val t = tail
                    if (t == head) {
                        LockSupport.park()
                        continue
                    }
                    val i = (t and RING_MASK).toInt()
                    write(ring[i], ringLen[i])
                    tail = t + 1
                }
            } catch (e: IOException) {
                if (!dead) Log.i(TAG, "cable closed: ${e.message}")
            }
            dead = true
        }

        private fun cableToLoop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            // Reads take whole USB transfers: the buffer must hold the largest the PC may send.
            val buf = ByteArray(16384)
            val deframer = UsbDeframer()
            val packet = DatagramPacket(ByteArray(0), 0)
            try {
                FileInputStream(fd.fileDescriptor).use { input ->
                    while (!dead) {
                        val n = input.read(buf)
                        if (n < 0) break
                        deframer.feed(buf, n) { b, off, len ->
                            val to = peer ?: return@feed
                            packet.setData(b, off, len)
                            packet.socketAddress = to
                            socket.send(packet)
                        }
                    }
                }
            } catch (e: IOException) {
                if (!dead) Log.i(TAG, "cable closed: ${e.message}")
            }
            dead = true
        }

        private fun loopToCable() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val buf = ByteArray(Wire.MAX_DATAGRAM)
            val frame = ByteArray(UsbFrames.maxSize(Wire.MAX_DATAGRAM))
            val packet = DatagramPacket(buf, buf.size)
            try {
                while (!dead) {
                    packet.setLength(buf.size)
                    socket.receive(packet)
                    val from = packet.address
                    if (from == null || !from.isLoopbackAddress || packet.length < 1 || packet.port !in ownPorts) continue
                    peer = packet.socketAddress
                    val n = UsbFrames.encode(buf, 0, packet.length, frame)
                    synchronized(writeLock) { out.write(frame, 0, n) }
                }
            } catch (e: IOException) {
                if (!dead) Log.i(TAG, "loopback or cable closed: ${e.message}")
            }
            dead = true
        }

        fun close() {
            if (stuck) Log.i(TAG, "the PC stopped reading the cable for ${STUCK_NS / 1_000_000} ms: let go")
            dead = true
            LockSupport.unpark(wr)
            socket.close()
            try {
                fd.close()
            } catch (e: IOException) {
                // already gone
            }
        }
    }

    companion object {
        private const val TAG = "AccessoryBridge"
        /** Must match the companion's AoaUsbLink.Manufacturer and Model, and res/xml/accessory_filter.xml. */
        const val MANUFACTURER = "GyroPhone"
        const val MODEL = "GyroPhone Companion"
        /** The USB permission answer; [WifiLink] listens for it to open the cable at once. */
        const val ACTION_PERMISSION = "com.oleroma.gyrophone.USB_ACCESSORY_PERMISSION"
        private val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()
        /** Frames the pump may have waiting for gp-acc-wr (a power of two); more means the PC is not keeping up. */
        private const val RING = 32
        private const val RING_MASK = RING - 1L
        /** A cable write pending this long: the PC stopped reading it (companion hung, restarted, or its cable switch off). */
        private const val STUCK_NS = 50_000_000L

        fun isOurs(a: UsbAccessory) = a.manufacturer == MANUFACTURER && a.model == MODEL
    }
}
