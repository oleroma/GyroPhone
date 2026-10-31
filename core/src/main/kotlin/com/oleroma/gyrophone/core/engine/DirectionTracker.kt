package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.Direction
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.ZoneKeep
import com.oleroma.gyrophone.core.model.inPie
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The swipe directions of one motion, from its steps in dp: which direction pies the recent
 * travel points into, the swipes that fire and the directions held ([TouchpadSettings] direction
 * settings). Direction sets are bit masks, 1 shl [Direction.ordinal].
 *
 * A direction's heading is that of about the last of its sample of travel
 * ([TouchpadSettings.sampleDp]: swipes' and held directions' each, per direction), so jitter does
 * not flip it. A swipe fires once the travel along it since the direction last changed reaches
 * [TouchpadSettings.swipeDp], for the directions that did not fire last. With Lock heading
 * ([TouchpadSettings.swipeLock], [TouchpadSettings.holdLock]) a heading only changes once the
 * finger has headed elsewhere for the Turn after travel on end. Held directions follow
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

    /**
     * Per direction ([Direction.ordinal]): the zones mapped only to fire as the finger lifts in
     * them (a mask of 1 shl zone; the caller sets it). Nothing holds them: they are not held,
     * kept or latched, and fire through [zoneAt] at the lift.
     */
    val releaseOnly = IntArray(4)

    /** Per direction: the zone the finger is in now (FROM_START) or got to (DIRECTION_CHANGE), -1 = none. */
    private val at = IntArray(4) { -1 }

    /** Per direction: the zones held now, a mask of 1 shl zone. */
    private val zoneMask = IntArray(4)

    /** Per direction: DIRECTION_CHANGE travel since it was held (dp). */
    private val travel = FloatArray(4)

    /** Per direction: zones reached this motion that stay held until the lift ([ZoneKeep.UNTIL_LIFT]). */
    private val untilLift = IntArray(4)

    /** The swipe pies the recent travel points into (0 = none yet, or a gap between pies); with Lock heading, the locked ones. */
    var current = 0
        private set

    /** Per swipe direction: the travel along it since it joined [current] (dp). */
    private val since = FloatArray(4)

    // Lock heading: the travel (dp, and its length) heading elsewhere than the swipes' and the held
    // directions' locked heading, not yet a turn.
    private var turnX = 0f
    private var turnY = 0f
    private var turnLen = 0f
    private var holdTurnX = 0f
    private var holdTurnY = 0f
    private var holdTurnLen = 0f

    // The whole travel since the motion started (dp).
    private var totX = 0f
    private var totY = 0f

    // FROM_START with Lock heading: an axis the held heading just locked onto, and its offset from then.
    private var lockedX = false
    private var lockedY = false
    private var lockOffX = 0f
    private var lockOffY = 0f

    /** The same for the held directions' pies ([TouchpadSettings.pieDeg] of holds), and the travel since it last changed. */
    private var holdCurrent = 0
    private var holdSince = 0f

    // Per pie (swipes by Direction.ordinal, then held directions): about the last of its sample of
    // travel, and how much has gone into it yet.
    private val winX = FloatArray(8)
    private val winY = FloatArray(8)
    private val sampled = FloatArray(8)
    private var travelled = 0f
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
        winX.fill(0f); winY.fill(0f); sampled.fill(0f); travelled = 0f; since.fill(0f); fired = 0
        turnX = 0f; turnY = 0f; turnLen = 0f; holdTurnX = 0f; holdTurnY = 0f; holdTurnLen = 0f
        totX = 0f; totY = 0f
        lockedX = false; lockedY = false; lockOffX = 0f; lockOffY = 0f
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

    /** One step of travel ([dx], [dy] dp); returns the directions whose swipe fires now. */
    fun step(dx: Float, dy: Float, s: TouchpadSettings): Int {
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 0f) return 0
        travelled += len
        var mask = 0
        var holdMask = 0
        // Pies whose first sample filled just now.
        var fresh = 0
        var holdFresh = 0
        for (i in 0 until 8) {
            val d = Direction.entries[i % 4]
            val hold = i >= 4
            val sample = s.sampleDp(d, hold)
            var x = winX[i] + dx
            var y = winY[i] + dy
            val w = sqrt(x * x + y * y)
            if (w > sample) {
                x *= sample / w; y *= sample / w
            }
            winX[i] = x; winY[i] = y
            val first = sampled[i] < sample
            if (first) sampled[i] += len
            val inPie = if (hold && s.lockOf(d, true)) heads(x, y, d, s.pieDeg(d, true)) else s.inPie(x, y, d, hold)
            if (sampled[i] < sample || !inPie) continue
            val b = 1 shl d.ordinal
            if (hold) {
                holdMask = holdMask or b
                if (first) holdFresh = holdFresh or b
            } else {
                mask = mask or b
                if (first) fresh = fresh or b
            }
        }
        totX += dx; totY += dy
        swipeHeading(mask, fresh, dx, dy, len, s)
        holdHeading(holdMask, holdFresh, dx, dy, len, s)

        if (s.holdMode == HoldMode.FROM_START) fromStart(dx, dy, s)
        else directionChange(len, s)

        // Each direction of the pies fires once its own swipe distance is travelled along it.
        var ready = 0
        for (d in Direction.entries) {
            val b = 1 shl d.ordinal
            if (current and b != 0 && since[d.ordinal] >= activate(s.swipeDpOf(d))) ready = ready or b
        }
        if (ready == 0) return 0
        val fire = ready and fired.inv()
        fired = (fired and current) or ready
        return fire
    }

    /**
     * The swipes' heading after a step ([dx], [dy] dp, [len] long) whose pies are [mask] ([fresh]:
     * the ones whose first sample filled just now). Without Lock heading it is [mask] at once; with
     * it, it changes once the finger has headed elsewhere for [TouchpadSettings.swipeTurnDp] on
     * end, and the directions it turned to take that travel.
     */
    private fun swipeHeading(mask: Int, fresh: Int, dx: Float, dy: Float, len: Float, s: TouchpadSettings) {
        if (mask == current) {
            turnX = 0f; turnY = 0f; turnLen = 0f
            alongStep(current, dx, dy)
            return
        }
        val turn = turnOf(mask and current.inv(), false, s)
        if (current == 0 || turn <= 0f) {
            // From no direction yet, the sampled travel already went this way.
            val from = current == 0 && mask and fresh != 0
            alongStep(mask and current, dx, dy)
            joined(mask, if (from) totX else 0f, if (from) totY else 0f)
            current = mask
            return
        }
        alongStep(current, dx, dy)
        turnX += dx; turnY += dy; turnLen += len
        if (mask == 0 || turnLen < turn) return
        joined(mask, turnX, turnY)
        current = mask
        turnX = 0f; turnY = 0f; turnLen = 0f
    }

    /** The directions of [mask] not in [current] start counting, with the travel [x], [y] (dp) along them. */
    private fun joined(mask: Int, x: Float, y: Float) {
        for (d in Direction.entries) {
            val b = 1 shl d.ordinal
            if (mask and b != 0 && current and b == 0) since[d.ordinal] = along(d, x, y)
        }
    }

    /** The directions of [mask] count a step [dx], [dy] along them. */
    private fun alongStep(mask: Int, dx: Float, dy: Float) {
        for (d in Direction.entries) if (mask and (1 shl d.ordinal) != 0) since[d.ordinal] += along(d, dx, dy)
    }

    /**
     * The held directions' heading after a step, as [swipeHeading] with [TouchpadSettings.holdLock]
     * and [TouchpadSettings.holdTurnDp]; [holdSince] is the travel since it changed. From start
     * point with Lock heading, an axis it turns onto counts its offset from where it turned
     * ([lockedX], [lockedY]).
     */
    private fun holdHeading(mask: Int, fresh: Int, dx: Float, dy: Float, len: Float, s: TouchpadSettings) {
        if (mask == holdCurrent) {
            holdTurnX = 0f; holdTurnY = 0f; holdTurnLen = 0f
            holdSince += len
            return
        }
        val turn = turnOf(mask and holdCurrent.inv(), true, s)
        if (holdCurrent == 0 || turn <= 0f) {
            val from = holdCurrent == 0 && mask and fresh != 0
            holdSince = if (from) travelled else 0f
            lock(mask, if (from) totX else 0f, if (from) totY else 0f)
            return
        }
        holdTurnX += dx; holdTurnY += dy; holdTurnLen += len
        holdSince += len
        if (mask == 0 || holdTurnLen < turn) return
        holdSince = holdTurnLen
        lock(mask, holdTurnX, holdTurnY)
        holdTurnX = 0f; holdTurnY = 0f; holdTurnLen = 0f
    }

    /** The held heading becomes [mask]; an axis it newly takes counts its offset from the travel [x], [y] (dp) of the turn. */
    private fun lock(mask: Int, x: Float, y: Float) {
        if (mask and X_BITS != 0 && holdCurrent and X_BITS == 0) { lockedX = true; lockOffX = x }
        if (mask and Y_BITS != 0 && holdCurrent and Y_BITS == 0) { lockedY = true; lockOffY = y }
        holdCurrent = mask
    }

    /**
     * The Turn after (dp) a heading needs to turn to the directions of [mask] (swipes', or with
     * [hold] held ones): the longest of theirs that lock; 0 = turn at once. Turning to none (a gap)
     * needs a lock of any: the heading stays.
     */
    private fun turnOf(mask: Int, hold: Boolean, s: TouchpadSettings): Float {
        var t = 0f
        var any = false
        for (d in Direction.entries) {
            if (!s.lockOf(d, hold)) continue
            any = true
            if (mask and (1 shl d.ordinal) != 0) t = maxOf(t, s.turnDpOf(d, hold))
        }
        return if (mask == 0 && any) Float.MIN_VALUE else t
    }

    /** Vector [x], [y] heads into [d]'s pie of [pie] degrees, for Lock heading: a 180° pie heads as a 90° one. */
    private fun heads(x: Float, y: Float, d: Direction, pie: Float): Boolean {
        val deg = Math.toDegrees(atan2(x.toDouble(), (-y).toDouble())).toFloat()
        val off = abs(((deg - d.deg) % 360f + 540f) % 360f - 180f)
        val w = if (pie >= TouchpadSettings.MAX_PIE_DEG) TouchpadSettings.LARGER_AXIS_PIE_DEG else pie
        return off <= w / 2f
    }

    private fun along(d: Direction, x: Float, y: Float) = when (d) {
        Direction.UP -> -y
        Direction.RIGHT -> x
        Direction.DOWN -> y
        Direction.LEFT -> -x
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
        if (s.anyHoldLock) {
            // An axis off the locked heading: its start point follows the finger, so its drift never
            // adds up; one the heading just turned onto starts from the turn's travel on it.
            offX = when {
                !s.holdLocks(true) -> offX + dx
                holdCurrent and X_BITS == 0 -> 0f
                lockedX -> lockOffX
                else -> offX + dx
            }
            offY = when {
                !s.holdLocks(false) -> offY + dy
                holdCurrent and Y_BITS == 0 -> 0f
                lockedY -> lockOffY
                else -> offY + dy
            }
            lockedX = false; lockedY = false
        } else {
            offX += dx; offY += dy
        }
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
            at[i] = if (s.inPie(offX, offY, d, hold = true)) zoneOf(along, d, s) else -1
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
            if (lifts(d, i)) continue
            if (i == z || zone(d, i, s).keep != ZoneKeep.OFF) m = m or (1 shl i)
        }
        return m
    }

    /** The finger got to zone [z] of [d]: it and the zones before it that stay until the lift are held from now on. */
    private fun reach(d: Direction, z: Int, s: TouchpadSettings) {
        for (i in 0..z) {
            if (!lifts(d, i) && zone(d, i, s).keep == ZoneKeep.UNTIL_LIFT) untilLift[d.ordinal] = untilLift[d.ordinal] or (1 shl i)
        }
    }

    /** Zone [i] of [d] only fires as the finger lifts in it ([releaseOnly]). */
    private fun lifts(d: Direction, i: Int) = releaseOnly[d.ordinal] and (1 shl i) != 0

    /**
     * Zone [i] of [d]. A single zone keeps its Held setting (Always or Only in zone); with Drag
     * start point it is a plain held direction, whatever its settings say.
     */
    private fun zone(d: Direction, i: Int, s: TouchpadSettings): HoldZone =
        if (s.dragStart && s.holdMode == HoldMode.FROM_START) PLAIN else s.holdZone(d, i)

    private fun count(d: Direction, s: TouchpadSettings): Int =
        if (s.dragStart && s.holdMode == HoldMode.FROM_START) 1
        else zoneCount[d.ordinal].coerceIn(1, TouchpadSettings.MAX_HOLD_ZONES)

    private fun activate(dp: Float) = dp.coerceIn(TouchpadSettings.MIN_ACTIVATE_DP, TouchpadSettings.MAX_ACTIVATE_DP)

    /** FROM_START: the finger's offset from the start point (dp), for the swipe line. */
    val offsetX: Float get() = offX
    val offsetY: Float get() = offY

    private companion object {
        val PLAIN = HoldZone()
        val X_BITS = (1 shl Direction.RIGHT.ordinal) or (1 shl Direction.LEFT.ordinal)
        val Y_BITS = (1 shl Direction.UP.ordinal) or (1 shl Direction.DOWN.ordinal)
    }
}
