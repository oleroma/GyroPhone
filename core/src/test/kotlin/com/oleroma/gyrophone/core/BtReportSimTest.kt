package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.PadDevice
import com.oleroma.gyrophone.core.hid.HidDescriptor
import com.oleroma.gyrophone.core.hid.ReportScheduler
import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Bluetooth pump's report choice driven by the real engine: gyro aiming (mouse motion every
 * 2 ms) and strafing (left stick every 4 ms) keep both mouse and pad dirty on every tick, while
 * A is tapped or briefly released at many phases against the pump's ticks.
 */
class BtReportSimTest {
    private val pad = PadDevice(id = 7, vendorId = 0x3537, productId = 0x1001, name = "GameSir X5")

    /** Runs one press pattern; returns the A bit of every game pad report, consecutive repeats merged. */
    private fun aBits(rateHz: Int, phaseNs: Long, events: List<Pair<Long, Boolean>>): List<Int> {
        val interval = 1_000_000_000L / rateHz
        var now = 0L
        val ws = Workspace(id = "w", name = "w", pads = listOf(AttachedPad(id = "p", mappings = KnownLayouts.standardControllerMappings())))
        val engine = MappingEngine(single(ws)) { now }
        val bits = ArrayList<Int>()
        val scheduler = ReportScheduler { id, r ->
            if (id == HidDescriptor.REPORT_ID_GAMEPAD) {
                val a = r[0].toInt() and 1
                if (bits.isEmpty() || bits.last() != a) bits.add(a)
            }
            true
        }
        var next = 0
        var nextTick = 0L
        var step = 0L
        while (now < 400_000_000L) {
            if (step % 8 == 0L) engine.addMouseMotion(3, 1)
            if (step % 16 == 0L) engine.setAxis(pad, AndroidAxis.X, 0.5f + 0.001f * ((step / 16) % 64))
            while (next < events.size && now >= events[next].first + phaseNs) {
                engine.setKey(pad, AndroidKey.BUTTON_A, events[next].second)
                next++
            }
            if (now >= nextTick) {
                scheduler.tick(engine.snapshot(), now, 0)
                nextTick += interval
            }
            now += 250_000L
            step++
        }
        return bits
    }

    @Test fun tapLongerThanOneTickAlwaysReachesTheHost() {
        for (hz in intArrayOf(30, 45, 60, 90)) {
            val interval = 1_000_000_000L / hz
            val tapNs = interval + 2_000_000L
            var seen = 0
            val trials = 100
            for (t in 0 until trials) {
                val phase = 2 * interval * t / trials // both pad / mouse parities
                val bits = aBits(hz, phase, listOf(200_000_000L to true, 200_000_000L + tapNs to false))
                if (bits.windowed(3).any { it == listOf(0, 1, 0) }) seen++
            }
            assertEquals("taps seen at $hz Hz", trials, seen)
        }
    }

    @Test fun releaseLongerThanOneTickAlwaysReachesTheHost() {
        for (hz in intArrayOf(30, 45, 60, 90)) {
            val interval = 1_000_000_000L / hz
            val gapNs = interval + 2_000_000L
            var seen = 0
            val trials = 100
            for (t in 0 until trials) {
                val phase = 2 * interval * t / trials
                val bits = aBits(hz, phase, listOf(100_000_000L to true, 200_000_000L to false, 200_000_000L + gapNs to true))
                if (bits.windowed(3).any { it == listOf(1, 0, 1) }) seen++
            }
            assertEquals("release gaps seen at $hz Hz", trials, seen)
        }
    }
}
