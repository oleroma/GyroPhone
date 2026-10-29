package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.ZoneKeep
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
 * [TouchpadSettings.holdMode]. Each held direction is split into length zones
 * ([TouchpadSettings.holdZone]): [zones] says which of a direction's zones are held now.
 */
internal class DirectionTracker {
    /** The directions held now. */
    var held = 0
        private set

    /**
     * How many length zones each direction has ([Direction.ordinal]; at least 1): the caller
     * sets it from what is mapped. With [TouchpadSettings.dragStart] there is only the first.
     */
    val zoneCount = IntArray(4) { 1 }

    /** Per direction: the zone the finger is in now (FROM_START) or got to (DIRECTION_CHANGE), -1 = none. */
    private val at = IntArray(4) { -1 }

    /** Per direction: the zones held now, a mask of 1 shl zone. */
    private val zoneMask = IntArray(4)

    /** Per direction: DIRECTION_CHANGE travel since it was held (dp). */
    private val travel = FloatArray(4)

    /** Per direction: zones reached this motion that stay held until the lift ([ZoneKeep.UNTIL_LIFT]). */
    private val untilLift = IntArray(4)

    /** The swipe pies the recent travel points into (0 = none yet, or a gap between pies). */
    var current = 0
        private set

    /** The same for the held directions' pies ([TouchpadSettings.pieDeg] of holds), and the travel since it last changed. */
    private var holdCurrent = 0
    private var holdSince = 0f

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
    private val carryZones = IntArray(4)

    /**
     * A motion starts; [latched] directions are held from a latch, with their zones
     * [latchedZones] (by [Direction.ordinal]), and go on as if this motion held them.
     */
    fun start(latched: Int = 0, latchedZones: IntArray? = null) {
        winX = 0f; winY = 0f; sampled = 0f; since = 0f; fired = 0
        offX = 0f; offY = 0f; pressed = 0
        current = 0; holdCurrent = 0; holdSince = 0f
        held = latched
        carry = latched
        for (i in 0 until 4) {
            val z = if (latched and (1 shl i) != 0) latchedZones?.get(i) ?: 1 else 0
            carryZones[i] = z; zoneMask[i] = z
            at[i] = -1; travel[i] = 0f; untilLift[i] = 0
        }
    }

    /** The zones of [d] held now, a mask of 1 shl zone (bit 0 = the first zone). */
    fun zones(d: Direction): Int = zoneMask[d.ordinal]

    /** The zone of [d] the finger is in now (FROM_START) or got to (DIRECTION_CHANGE), or -1. */
    fun zoneAt(d: Direction): Int = at[d.ordinal]

    /** [d]'s zone the finger is in fires on release: it fires as the finger lifts. */
    fun firesOnRelease(d: Direction, s: TouchpadSettings): Boolean {
        val z = at[d.ordinal]
        return z >= 0 && zone(d, z, s).onRelease
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
        val holdMask = if (sampled < sample) 0 else s.directionsAt(winX, winY, hold = true)
        if (holdMask != holdCurrent) {
            holdSince = if (firstSample && holdCurrent == 0) sampled else 0f
            holdCurrent = holdMask
        } else holdSince += len

        if (s.holdMode == HoldMode.FROM_START) fromStart(dx, dy, s)
        else directionChange(len, s)

        // Each direction of the pies fires once its own swipe distance is travelled.
        val ready = passed(current, since) { s.swipeDpOf(it) }
        if (ready == 0) return 0
        val fire = ready and fired.inv()
        fired = (fired and current) or ready
        return fire
    }

    /** The directions of [mask] whose [dp] the [travel] has reached. */
    private inline fun passed(mask: Int, travel: Float, dp: (Direction) -> Float): Int {
        var out = 0
        for (d in Direction.entries) {
            val b = 1 shl d.ordinal
            if (mask and b != 0 && travel >= activate(dp(d))) out = out or b
        }
        return out
    }

    /** Held while the offset from the start point, along a direction and inside its pie, passes the hold distance. */
    private fun fromStart(dx: Float, dy: Float, s: TouchpadSettings) {
        offX += dx; offY += dy
        if (s.dragStart) {
            val hold = Direction.entries.maxOf { activate(s.holdDpOf(it)) }
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
            val i = d.ordinal
            at[i] = if (s.pies == null || s.inPie(offX, offY, d, hold = true)) zoneOf(along, d, s) else -1
            if (at[i] >= 0) mask = mask or (1 shl i)
        }
        // Dragged: letting go re-centres the start point on the finger, so the opposite
        // direction is the hold distance further on, not back past where the finger landed.
        if (s.dragStart && mask == 0 && pressed != 0) {
            offX = 0f; offY = 0f
        }
        pressed = mask
        if (mask != 0) carry = 0
        held = if (carry != 0) carry else mask
        for (d in Direction.entries) {
            val i = d.ordinal
            reach(d, at[i], s)
            zoneMask[i] = (if (carry != 0) carryZones[i] else heldZones(d, at[i], s)) or untilLift[i]
        }
    }

    /**
     * Held once the finger travelled the hold distance since turning that way, until another
     * direction is; its zone is the furthest the travel got to since then (never back).
     */
    private fun directionChange(len: Float, s: TouchpadSettings) {
        // The directions of the hold pies that went their own hold distance.
        val ready = passed(holdCurrent, holdSince) { s.holdDpOf(it) }
        if (ready != 0 && ready != held) {
            held = ready
            for (i in 0 until 4) {
                travel[i] = if (held and (1 shl i) != 0) holdSince else 0f
                at[i] = -1
            }
        } else {
            for (i in 0 until 4) if (held and holdCurrent and (1 shl i) != 0) travel[i] += len
        }
        for (d in Direction.entries) {
            val i = d.ordinal
            if (held and (1 shl i) == 0) {
                at[i] = -1; zoneMask[i] = untilLift[i]; continue
            }
            val z = zoneOf(travel[i], d, s)
            // A latched direction keeps its zones until this motion reaches one.
            if (z > at[i]) {
                at[i] = z
                reach(d, z, s)
                zoneMask[i] = heldZones(d, z, s) or untilLift[i]
            }
        }
    }

    /** The zone of [d] travel [along] (dp, from the start) is in, or -1 before the hold distance. */
    private fun zoneOf(along: Float, d: Direction, s: TouchpadSettings): Int {
        var edge = activate(s.holdDpOf(d))
        if (along < edge) return -1
        val last = count(d, s) - 1
        for (i in 0 until last) {
            edge += s.holdZoneDp(d, i)
            if (along < edge) return i
        }
        return last
    }

    /** The zones of [d] held with the finger in zone [z]: it unless it fires on release, and the kept ones before it. */
    private fun heldZones(d: Direction, z: Int, s: TouchpadSettings): Int {
        if (z < 0) return 0
        var m = 0
        for (i in 0..z) {
            val zone = zone(d, i, s)
            if (zone.onRelease) continue
            if (i == z || zone.keep != ZoneKeep.OFF) m = m or (1 shl i)
        }
        return m
    }

    /** The finger got to zone [z] of [d]: it and the zones before it that stay until the lift are held from now on. */
    private fun reach(d: Direction, z: Int, s: TouchpadSettings) {
        for (i in 0..z) {
            val zone = zone(d, i, s)
            if (!zone.onRelease && zone.keep == ZoneKeep.UNTIL_LIFT) untilLift[d.ordinal] = untilLift[d.ordinal] or (1 shl i)
        }
    }

    /** Zone [i] of [d]; with one zone only it is a plain held direction, whatever its settings say. */
    private fun zone(d: Direction, i: Int, s: TouchpadSettings): HoldZone =
        if (count(d, s) < 2) PLAIN else s.holdZone(d, i)

    private fun count(d: Direction, s: TouchpadSettings): Int =
        if (s.dragStart && s.holdMode == HoldMode.FROM_START) 1
        else zoneCount[d.ordinal].coerceIn(1, TouchpadSettings.MAX_HOLD_ZONES)

    private fun activate(dp: Float) = dp.coerceIn(TouchpadSettings.MIN_ACTIVATE_DP, TouchpadSettings.MAX_ACTIVATE_DP)

    private companion object {
        val PLAIN = HoldZone()
    }
}
