package com.oleroma.gyrophone.runtime

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.net.OverlayFeed
import com.oleroma.gyrophone.core.net.OverlayKeys
import com.oleroma.gyrophone.core.net.OverlayKeysTable
import kotlinx.coroutines.delay

/**
 * Feeds the PC's Keys overlay: while it is open (and the PC wants overlay data), reads which of the attached pad's
 * mappings are on about 60 times a second ([MappingEngine.padKeys], a short read under the engine's lock, nothing
 * made) and hands [OverlayFeed.setKeys] new rows only when something changed. Runs on a background thread, never on
 * an input thread; the rows go in OVERLAY_FRAMES, never in input packets.
 */
object PadKeysWatch {
    /** How often the pad is read, ms: once per overlay frame. A tap's pulse (40 ms at least) is never missed. */
    private const val EVERY_MS = 16L

    /** While the PC's overlay data is off, how often to look whether it came back, ms. */
    private const val IDLE_MS = 250L

    suspend fun run(engine: MappingEngine, overlay: OverlayFeed) {
        var table: OverlayKeysTable? = null
        var on = BooleanArray(0)
        var toggled = BooleanArray(0)
        var lastOn = BooleanArray(0)
        var lastToggled = BooleanArray(0)
        var sent = false
        try {
            while (true) {
                if (!overlay.wanted) {
                    table = null
                    sent = false
                    delay(IDLE_MS)
                    continue
                }
                val t = table
                val ms = engine.padKeys(t?.ms, on, toggled)
                when {
                    ms == null -> {
                        table = null
                        overlay.setKeys(OverlayKeys.NONE)
                        sent = true
                    }
                    t == null || ms !== t.ms -> {
                        // A new pad or a new set in use: sorted again, then read at once.
                        table = OverlayKeysTable(ms, engine.padKeysRightStickRx())
                        on = BooleanArray(ms.size); toggled = BooleanArray(ms.size)
                        lastOn = BooleanArray(ms.size); lastToggled = BooleanArray(ms.size)
                        sent = false
                        continue
                    }
                    !sent || !on.contentEquals(lastOn) || !toggled.contentEquals(lastToggled) -> {
                        on.copyInto(lastOn); toggled.copyInto(lastToggled)
                        overlay.setKeys(t.keys(on, toggled))
                        sent = true
                    }
                }
                delay(EVERY_MS)
            }
        } finally {
            overlay.setKeys(null)
        }
    }
}
