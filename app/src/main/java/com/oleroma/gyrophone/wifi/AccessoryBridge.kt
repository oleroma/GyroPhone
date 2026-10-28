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

/**
 * The direct USB cable (Android Open Accessory): the companion switches the phone to accessory
 * mode naming GyroPhone, and the cable becomes a byte stream to it. This bridges that stream to
 * a loopback UDP socket, so [WifiLink] uses the cable exactly like USB tethering: it sends to
 * [UsbTetherInfo.target] (this bridge) and whatever comes down the cable arrives from there.
 * Thread gp-acc-rx reads the cable, gp-acc-tx the loopback socket; writes to the cable block while
 * the PC does not read, which only holds up gp-acc-tx.
 */
class AccessoryBridge(context: Context) {
    private val appContext = context.applicationContext
    private val usb = appContext.getSystemService(UsbManager::class.java)

    private var pfd: ParcelFileDescriptor? = null
    private var socket: DatagramSocket? = null
    private var info: UsbTetherInfo? = null
    @Volatile private var dead = false
    /** The WifiLink socket that last sent here: datagrams from the PC go to it. */
    @Volatile private var peer: SocketAddress? = null
    private var asked: UsbAccessory? = null

    /**
     * Called about once a second (rx thread): the cable as a [UsbTetherInfo] while GyroPhone's
     * accessory is attached and open, else null. Asks for permission once per plug-in when the
     * system did not grant it already (it does when the user picked GyroPhone for the accessory).
     */
    fun poll(): UsbTetherInfo? {
        if (info != null && !dead) return info
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
        pfd = fd
        socket = s
        dead = false
        peer = null
        Thread({ cableToLoop(fd, s) }, "gp-acc-rx").apply { isDaemon = true }.start()
        Thread({ loopToCable(fd, s) }, "gp-acc-tx").apply { isDaemon = true }.start()
        Log.i(TAG, "accessory open: ${acc.manufacturer} ${acc.model} ${acc.serial}")
        return UsbTetherInfo("accessory", LOOPBACK, InetSocketAddress(LOOPBACK, s.localPort), direct = true).also { info = it }
    }

    private fun cableToLoop(fd: ParcelFileDescriptor, s: DatagramSocket) {
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
                        s.send(packet)
                    }
                }
            }
        } catch (e: IOException) {
            if (!dead) Log.i(TAG, "cable closed: ${e.message}")
        }
        dead = true
    }

    private fun loopToCable(fd: ParcelFileDescriptor, s: DatagramSocket) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val buf = ByteArray(Wire.MAX_DATAGRAM)
        val frame = ByteArray(UsbFrames.maxSize(Wire.MAX_DATAGRAM))
        val packet = DatagramPacket(buf, buf.size)
        try {
            FileOutputStream(fd.fileDescriptor).use { out ->
                while (!dead) {
                    packet.setLength(buf.size)
                    s.receive(packet)
                    val from = packet.address
                    if (from == null || !from.isLoopbackAddress || packet.length < 1) continue
                    peer = packet.socketAddress
                    val n = UsbFrames.encode(buf, 0, packet.length, frame)
                    out.write(frame, 0, n)
                }
            }
        } catch (e: IOException) {
            if (!dead) Log.i(TAG, "loopback or cable closed: ${e.message}")
        }
        dead = true
    }

    /** Lets go of the cable (the link stopped, or the accessory went away). */
    fun close() {
        dead = true
        socket?.close()
        socket = null
        try {
            pfd?.close()
        } catch (e: IOException) {
            // already gone
        }
        pfd = null
        info = null
        peer = null
    }

    companion object {
        private const val TAG = "AccessoryBridge"
        /** Must match the companion's AoaUsbLink.Manufacturer and Model, and res/xml/accessory_filter.xml. */
        const val MANUFACTURER = "GyroPhone"
        const val MODEL = "GyroPhone Companion"
        private const val ACTION_PERMISSION = "com.oleroma.gyrophone.USB_ACCESSORY_PERMISSION"
        private val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()

        fun isOurs(a: UsbAccessory) = a.manufacturer == MANUFACTURER && a.model == MODEL
    }
}
