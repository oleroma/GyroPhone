package com.oleroma.gyrophone.core.input

import com.oleroma.gyrophone.core.model.AndroidAxis

/**
 * A USB game pad read directly ("Read USB controllers directly"): its HID report descriptor turned
 * into the same Android key codes and axes Android would have given for it, so mappings, device
 * presets and the drawings work the same either way.
 *
 * It follows what Linux (hid-input) and Android (Generic.kl, the joystick mapper) do with a game pad
 * or joystick: buttons 1-15 become the gamepad buttons in Generic.kl's order (A B C X Y Z L1 R1 L2
 * R2 Select Start Mode ThumbL ThumbR; a joystick's become BUTTON_1..16), X Y Z Rx Ry Rz the sticks,
 * the accelerator and brake the right and left triggers, the hat switch HAT_X / HAT_Y, the D-pad
 * usages the D-pad keys. Sticks are scaled to -1..1 and triggers to 0..1 over the logical range,
 * with Linux's flat (1/16 of the range) as the deadzone ([AndroidAxis.deadzone]).
 *
 * [decode] takes one input report (with its report id first when the descriptor has ids) and tells
 * a [Sink] which keys changed and, when an axis moved, every axis's value now.
 */
class HidGamepad(descriptor: ByteArray) {
    /** One Input field of the descriptor. */
    class Field(
        val reportId: Int,
        val bitOffset: Int,
        val bitSize: Int,
        val count: Int,
        val logicalMin: Int,
        val logicalMax: Int,
        val variable: Boolean,
        /** Full usages (page shl 16 or id), one per slot for a variable field, the usable ones for an array. */
        val usages: IntArray,
        val application: Int,
    )

    interface Sink {
        /** [keyCode] (an Android key code) went down or up. */
        fun key(keyCode: Int, down: Boolean)

        /** The axes now, in [AndroidAxis.polled]'s order, deadzone applied; NaN where the pad has none. */
        fun axes(values: FloatArray)
    }

    val fields: List<Field> = parse(descriptor)

    private val hasIds = fields.any { it.reportId != 0 }

    private class KeyMap(val field: Int, val slot: Int, val keyCode: Int)
    private class AxisMap(val field: Int, val slot: Int, val polled: Int, val scale: Float, val offset: Float, val flat: Float)
    private class HatMap(val field: Int, val slot: Int, val min: Int, val max: Int)
    private class ArrayKeys(val field: Int, val byUsage: Map<Int, Int>)

    private val keyMaps = ArrayList<KeyMap>()
    private val axisMaps = ArrayList<AxisMap>()
    private val hatMaps = ArrayList<HatMap>()
    private val arrayKeys = ArrayList<ArrayKeys>()

    /** The Android key codes this pad has. */
    val keys: Set<Int>

    /** The Android axes this pad has. */
    val axes: Set<Int>

    /** Whether this is a game pad or joystick with something to read. */
    val usable: Boolean

    // Each key's state, by key code; and the axes as last sent.
    private val held = HashSet<Int>()
    private val nowHeld = HashSet<Int>()
    private val axisNow = FloatArray(AndroidAxis.polled.size) { Float.NaN }
    private val axisSent = FloatArray(AndroidAxis.polled.size) { Float.NaN }

    init {
        val k = LinkedHashSet<Int>()
        val a = LinkedHashSet<Int>()
        for ((fi, f) in fields.withIndex()) {
            if (f.application != GD_GAMEPAD && f.application != GD_JOYSTICK) continue
            if (!f.variable) {
                val by = HashMap<Int, Int>()
                for (u in f.usages) keyOf(u, f.application)?.let { by[u] = it; k += it }
                if (by.isNotEmpty()) arrayKeys += ArrayKeys(fi, by)
                continue
            }
            for (slot in 0 until f.count) {
                // Fewer usages than slots: the last one repeats (as in Linux).
                if (f.usages.isEmpty()) continue
                val u = if (slot < f.usages.size) f.usages[slot] else f.usages.last()
                if (u == HAT) {
                    hatMaps += HatMap(fi, slot, f.logicalMin, f.logicalMax)
                    a += AndroidAxis.HAT_X; a += AndroidAxis.HAT_Y
                    continue
                }
                val key = keyOf(u, f.application)
                if (key != null) {
                    keyMaps += KeyMap(fi, slot, key); k += key
                    continue
                }
                val axis = axisOf(u) ?: continue
                val polled = AndroidAxis.polled.indexOf(axis)
                if (polled < 0 || f.logicalMax <= f.logicalMin) continue
                val range = (f.logicalMax - f.logicalMin).toFloat()
                // Linux's flat for an absolute axis: 1/16 of its range.
                val rawFlat = ((f.logicalMax - f.logicalMin) shr 4).toFloat()
                val trigger = AndroidAxis.isTrigger(axis)
                val scale = if (trigger) 1f / range else 2f / range
                val offset = if (trigger) -f.logicalMin * scale else -(f.logicalMin + f.logicalMax) / 2f * scale
                axisMaps += AxisMap(fi, slot, polled, scale, offset, rawFlat * scale)
                a += axis
            }
        }
        keys = k
        axes = a
        usable = k.isNotEmpty() || a.isNotEmpty()
        if (hatMaps.isNotEmpty()) {
            axisNow[HAT_X_I] = 0f
            axisNow[HAT_Y_I] = 0f
        }
    }

    /** One input report: [len] bytes of [report]. */
    fun decode(report: ByteArray, len: Int, sink: Sink) {
        if (len <= 0) return
        val id = if (hasIds) report[0].toInt() and 0xFF else 0
        val base = if (hasIds) 8 else 0
        var any = false
        nowHeld.clear()
        nowHeld.addAll(held)
        for (m in keyMaps) {
            val f = fields[m.field]
            if (f.reportId != id) continue
            val v = read(report, len, base + f.bitOffset + m.slot * f.bitSize, f.bitSize, false) ?: continue
            if (v != 0) nowHeld.add(m.keyCode) else nowHeld.remove(m.keyCode)
            any = true
        }
        for (ak in arrayKeys) {
            val f = fields[ak.field]
            if (f.reportId != id) continue
            for (code in ak.byUsage.values) nowHeld.remove(code)
            for (slot in 0 until f.count) {
                val v = read(report, len, base + f.bitOffset + slot * f.bitSize, f.bitSize, f.logicalMin < 0) ?: continue
                if (v < f.logicalMin || v > f.logicalMax) continue
                val usage = arrayUsage(f, v) ?: continue
                ak.byUsage[usage]?.let { nowHeld.add(it) }
            }
            any = true
        }
        for (m in axisMaps) {
            val f = fields[m.field]
            if (f.reportId != id) continue
            val raw = read(report, len, base + f.bitOffset + m.slot * f.bitSize, f.bitSize, f.logicalMin < 0) ?: continue
            if (raw < f.logicalMin || raw > f.logicalMax) continue
            val axis = AndroidAxis.polled[m.polled]
            val v = (raw * m.scale + m.offset).coerceIn(if (AndroidAxis.isTrigger(axis)) 0f else -1f, 1f)
            axisNow[m.polled] = AndroidAxis.deadzone(axis, v, m.flat)
        }
        for (m in hatMaps) {
            val f = fields[m.field]
            if (f.reportId != id) continue
            val raw = read(report, len, base + f.bitOffset + m.slot * f.bitSize, f.bitSize, f.logicalMin < 0) ?: continue
            // Linux: the eight ways from the logical range, anything else (the null state) centred.
            var dir = (raw - m.min) * 8 / (m.max - m.min + 1) + 1
            if (raw < m.min || raw > m.max || dir < 0 || dir > 8) dir = 0
            axisNow[HAT_X_I] = HAT_TABLE[dir * 2].toFloat()
            axisNow[HAT_Y_I] = HAT_TABLE[dir * 2 + 1].toFloat()
        }
        if (any) {
            // Ups before downs, each in key code order: the same report always gives the same events.
            for (code in held.sorted()) if (code !in nowHeld) sink.key(code, false)
            for (code in nowHeld.sorted()) if (code !in held) sink.key(code, true)
            held.clear(); held.addAll(nowHeld)
        }
        var moved = false
        for (i in axisNow.indices) {
            val v = axisNow[i]
            if (v.isNaN()) continue
            if (v != axisSent[i]) moved = true
        }
        if (moved) {
            axisNow.copyInto(axisSent)
            sink.axes(axisSent)
        }
    }

    /** Lets go of every key and centres every axis (the pad went away), telling [sink]. */
    fun release(sink: Sink) {
        for (code in held.sorted()) sink.key(code, false)
        held.clear()
        var any = false
        for (i in axisNow.indices) if (!axisNow[i].isNaN()) { axisNow[i] = 0f; any = true }
        if (any) {
            axisNow.copyInto(axisSent)
            sink.axes(axisSent)
        }
    }

    private fun arrayUsage(f: Field, v: Int): Int? = f.usages.getOrNull(v - f.logicalMin)

    companion object {
        const val GD_JOYSTICK = 0x00010004
        const val GD_GAMEPAD = 0x00010005
        private const val HAT = 0x00010039
        private val HAT_X_I = AndroidAxis.polled.indexOf(AndroidAxis.HAT_X)
        private val HAT_Y_I = AndroidAxis.polled.indexOf(AndroidAxis.HAT_Y)

        /** Linux's hat directions (x, y) for 0 (centred) and the eight ways from up, clockwise. */
        private val HAT_TABLE = intArrayOf(0, 0, 0, -1, 1, -1, 1, 0, 1, 1, 0, 1, -1, 1, -1, 0, -1, -1)

        // Generic.kl's Android key codes for Linux's BTN_GAMEPAD + 0..14 (304-318).
        private val GAMEPAD_KEYS = intArrayOf(
            96, 97, 98, 99, 100, 101, // A B C X Y Z
            102, 103, 104, 105, // L1 R1 L2 R2
            109, 108, 110, 106, 107, // Select Start Mode ThumbL ThumbR
        )

        /** The Android key code Android gives HID usage [u] on a pad of [application], or null. */
        fun keyOf(u: Int, application: Int): Int? {
            val page = u ushr 16
            val id = u and 0xFFFF
            return when (page) {
                0x09 -> {
                    if (id == 0) return null
                    val n = id - 1
                    if (n > 0xF) return null
                    if (application == GD_GAMEPAD) GAMEPAD_KEYS.getOrNull(n)
                    // BUTTON_1..16 (Generic.kl 288-303).
                    else 188 + n
                }
                0x01 -> when (id) {
                    0x90 -> 19 // D-pad up
                    0x91 -> 20 // down
                    0x92 -> 22 // right
                    0x93 -> 21 // left
                    else -> null
                }
                0x0C -> when (id) {
                    0x224 -> 4 // AC Back: BACK
                    else -> null
                }
                else -> null
            }
        }

        /** The Android axis Android gives HID usage [u], or null. */
        fun axisOf(u: Int): Int? {
            val page = u ushr 16
            val id = u and 0xFFFF
            return when (page) {
                0x01 -> when (id) {
                    0x30 -> AndroidAxis.X
                    0x31 -> AndroidAxis.Y
                    0x32 -> AndroidAxis.Z
                    0x33 -> AndroidAxis.RX
                    0x34 -> AndroidAxis.RY
                    0x35 -> AndroidAxis.RZ
                    else -> null
                }
                // Simulation: accelerator = ABS_GAS = right trigger, brake = ABS_BRAKE = left trigger (Generic.kl).
                0x02 -> when (id) {
                    0xC4 -> AndroidAxis.RTRIGGER
                    0xC5 -> AndroidAxis.LTRIGGER
                    else -> null
                }
                else -> null
            }
        }

        /** [size] bits from bit [bit] of [r] (little endian), sign-extended when [signed]; null past [len]. */
        fun read(r: ByteArray, len: Int, bit: Int, size: Int, signed: Boolean): Int? {
            if (size <= 0 || size > 32 || bit + size > len * 8) return null
            var v = 0L
            for (i in 0 until size) {
                val b = bit + i
                if ((r[b ushr 3].toInt() ushr (b and 7)) and 1 != 0) v = v or (1L shl i)
            }
            if (signed && size < 32 && v and (1L shl (size - 1)) != 0L) v -= 1L shl size
            return v.toInt()
        }

        /** The Input fields of a HID report descriptor. */
        fun parse(d: ByteArray): List<Field> {
            val out = ArrayList<Field>()
            var page = 0
            var logMin = 0
            var logMax = 0
            var size = 0
            var count = 0
            var reportId = 0
            val stack = ArrayList<IntArray>()
            val usages = ArrayList<Int>()
            var usageMin = -1
            var usageMax = -1
            var depth = 0
            var application = 0
            val offsets = HashMap<Int, Int>()
            var i = 0
            while (i < d.size) {
                val p = d[i].toInt() and 0xFF
                if (p == 0xFE) {
                    // A long item: skip it.
                    val n = if (i + 1 < d.size) d[i + 1].toInt() and 0xFF else 0
                    i += 3 + n
                    continue
                }
                val n = when (p and 3) { 3 -> 4; else -> p and 3 }
                if (i + 1 + n > d.size) break
                var u = 0L
                for (j in 0 until n) u = u or ((d[i + 1 + j].toLong() and 0xFF) shl (8 * j))
                val s = when (n) {
                    1 -> u.toByte().toInt()
                    2 -> u.toShort().toInt()
                    4 -> u.toInt()
                    else -> 0
                }
                val type = (p shr 2) and 3
                val tag = p shr 4
                fun full(v: Long) = if (n == 4) v.toInt() else (page shl 16) or (v.toInt() and 0xFFFF)
                when (type) {
                    0 -> { // Main
                        when (tag) {
                            0x8 -> { // Input
                                val constant = u and 1L != 0L
                                val variable = u and 2L != 0L
                                val list = ArrayList<Int>(usages)
                                if (usageMin >= 0 && usageMax >= usageMin && usageMax - usageMin < 4096) {
                                    for (x in usageMin..usageMax) list += x
                                }
                                val off = offsets[reportId] ?: 0
                                if (!constant && size > 0 && count > 0) {
                                    // A max read as unsigned when the min is not below zero (Linux does the same).
                                    out += Field(reportId, off, size, count, logMin, logMax, variable, list.toIntArray(), application)
                                }
                                offsets[reportId] = off + size * count
                            }
                            0xA -> { // Collection
                                if (depth == 0 && (u and 0xFF) == 1L) application = usages.firstOrNull() ?: (if (usageMin >= 0) usageMin else 0)
                                depth++
                            }
                            0xC -> depth = maxOf(0, depth - 1)
                        }
                        usages.clear(); usageMin = -1; usageMax = -1
                    }
                    1 -> when (tag) { // Global
                        0x0 -> page = (u and 0xFFFF).toInt()
                        0x1 -> logMin = s
                        0x2 -> logMax = if (logMin >= 0) u.toInt() else s
                        0x7 -> size = u.toInt()
                        0x8 -> reportId = u.toInt()
                        0x9 -> count = u.toInt()
                        0xA -> stack += intArrayOf(page, logMin, logMax, size, count, reportId)
                        0xB -> stack.removeLastOrNull()?.let { st ->
                            page = st[0]; logMin = st[1]; logMax = st[2]; size = st[3]; count = st[4]; reportId = st[5]
                        }
                    }
                    2 -> when (tag) { // Local
                        0x0 -> usages += full(u)
                        0x1 -> usageMin = full(u)
                        0x2 -> usageMax = full(u)
                    }
                }
                i += 1 + n
            }
            return out
        }
    }
}
