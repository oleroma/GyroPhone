package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.directionsAt
import com.oleroma.gyrophone.core.model.inPie
import kotlin.math.sqrt

/**
 * The swipe directions of one motion, from its steps in dp: which direction pies the recent
 * travel points into, the swipes that fire and the directions held ([TouchpadSettings] direction
 * settings). Direction sets are bit masks, 1 shl [Direction.ordinal].
 *
 * The direction is that of about the last [TouchpadSettings.dirSampleDp] of travel, so jitter does
 * not flip it. A swipe fires once the travel since the direction last changed reaches
 * [TouchpadSettings.swipeDp], for the directions that did not fire last. Held directions follow
 * [TouchpadSettings.holdMode].
 */
internal class DirectionTracker {
    /** The directions held now. */
    var held = 0
        private set

    /** The pies the recent travel points into (0 = none yet, or a gap between pies). */
    var current = 0
        private set

    private var winX = 0f
    private var winY = 0f
    private var sampled = 0f
    private var since = 0f
    private var fired = 0

    // FROM_START: the finger's offset from the start point, and directions carried over from a
    // latch, held until the offset presses something.
    private var offX = 0f
    private var offY = 0f
    private var pressed = 0
    private var carry = 0

    /** A motion starts; [latched] directions are held from a latch and go on as if this motion held them. */
    fun start(latched: Int = 0) {
        winX = 0f; winY = 0f; sampled = 0f; since = 0f; fired = 0
        offX = 0f; offY = 0f; pressed = 0
        current = 0
        held = latched
        carry = latched
    }

    /** One step of travel ([dx], [dy] dp); returns the directions whose swipe fires now. */
    fun step(dx: Float, dy: Float, s: TouchpadSettings): Int {
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 0f) return 0
        val sample = s.dirSampleDp.coerceIn(TouchpadSettings.MIN_DIR_SAMPLE_DP, TouchpadSettings.MAX_DIR_SAMPLE_DP)
        winX += dx; winY += dy
        val w = sqrt(winX * winX + winY * winY)
        if (w > sample) {
            winX *= sample / w; winY *= sample / w
        }
        val firstSample = sampled < sample
        if (firstSample) sampled += len
        val mask = if (sampled < sample) 0 else s.directionsAt(winX, winY)
        if (mask != current) {
            // From no direction yet, the sampled travel already went this way.
            since = if (firstSample && current == 0) sampled else 0f
            current = mask
        } else since += len

        if (s.holdMode == HoldMode.FROM_START) fromStart(dx, dy, s)
        else if (current != 0 && current != held && since >= activate(s.holdDp)) held = current

        if (current == 0 || since < activate(s.swipeDp)) return 0
        val fire = current and fired.inv()
        fired = current
        return fire
    }

    /** Held while the offset from the start point, along a direction and inside its pie, passes the hold distance. */
    private fun fromStart(dx: Float, dy: Float, s: TouchpadSettings) {
        offX += dx; offY += dy
        val hold = activate(s.holdDp)
        if (s.dragStart) {
            val max = hold + s.releaseDp.coerceIn(0f, TouchpadSettings.MAX_RELEASE_DP)
            val d = sqrt(offX * offX + offY * offY)
            if (d > max) {
                offX *= max / d; offY *= max / d
            }
        }
        var mask = 0
        for (d in Direction.entries) {
            val along = when (d) {
                Direction.UP -> -offY
                Direction.RIGHT -> offX
                Direction.DOWN -> offY
                Direction.LEFT -> -offX
            }
            if (along >= hold && (s.pies == null || s.inPie(offX, offY, d))) mask = mask or (1 shl d.ordinal)
        }
        // Dragged: letting go re-centres the start point on the finger, so the opposite
        // direction is the hold distance further on, not back past where the finger landed.
        if (s.dragStart && mask == 0 && pressed != 0) {
            offX = 0f; offY = 0f
        }
        pressed = mask
        if (mask != 0) carry = 0
        held = if (carry != 0) carry else mask
    }

    private fun activate(dp: Float) = dp.coerceIn(TouchpadSettings.MIN_ACTIVATE_DP, TouchpadSettings.MAX_ACTIVATE_DP)
}
