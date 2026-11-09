package com.oleroma.gyrophone.core.engine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The phone's orientation for the pad's gyro phone view, integrated from gyro rates. Its frame is
 * the player's view: x right, y up, z toward the player. [rotate]: since the last [reset], neutral
 * being the phone facing the player. [fuse]: against the world, its tilt held to gravity
 * ([setUp], from the accelerometer) and its heading eased back to facing the player or held by
 * the magnetometer ([setNorth]) to where it was aimed ([aimHere]), so it never drifts. [easeAll] / [easeHeading] take it
 * back toward straight. Written by the sensor threads (a few multiplies per sample), read by the UI ([matrix])
 * once per frame.
 */
class GyroPose {
    private var w = 1f
    private var x = 0f
    private var y = 0f
    private var z = 0f

    /** Bumped on every change, so a reader can skip frames where nothing moved. */
    @Volatile var version = 0L
        private set

    /** The last measured "up" in the phone's frame (unit length), or none yet. */
    private var upX = 0f
    private var upY = 0f
    private var upZ = 0f
    private var hasUp = false

    /** The last measured magnetic field in the phone's frame (unit length), or none yet. */
    private var northX = 0f
    private var northY = 0f
    private var northZ = 0f
    private var hasNorth = false

    /**
     * Where north lies from straight ahead (radians, as [fuse] measures it) while the heading is 0:
     * the compass holds the pose there, so heading 0 is the way the phone faced when it took it.
     */
    private var northAt = 0f

    /** [northAt] is to be taken at the next settled [fuse] with a magnetometer reading ([aimHere]). */
    private var aimPending = true

    /** Seconds of [fuse] left that pull hard toward gravity: a fresh pose settles at once. */
    private var settling = SETTLE_S

    /**
     * The resting grip (w, x, y, z; null = upright, facing the player) the heading is measured from
     * ([headingFrom]): the heading eases back, and the compass aims, to the way the screen faces in it.
     */
    @Volatile var rest: FloatArray? = null

    @Synchronized
    fun reset() {
        w = 1f; x = 0f; y = 0f; z = 0f
        hasUp = false
        settling = SETTLE_S
        aimPending = true
        version++
    }

    /**
     * The compass ([fuse]) takes the way the phone faces now as heading 0, instead of north: at the
     * next settled sample with a magnetometer reading, the heading goes to 0 and stays held there.
     */
    @Synchronized
    fun aimHere() {
        aimPending = true
    }

    /**
     * The accelerometer's reading ([ax] right, [ay] up, [az] toward the player, in the phone's
     * frame, any unit): at rest it points up. Readings far from 1 g (the phone being shaken) are
     * skipped, so a jolt does not tip the view.
     */
    @Synchronized
    fun setUp(ax: Float, ay: Float, az: Float, g: Float) {
        val l = sqrt(ax * ax + ay * ay + az * az)
        if (l == 0f || l < g * (1 - G_SLACK) || l > g * (1 + G_SLACK)) return
        upX = ax / l; upY = ay / l; upZ = az / l
        hasUp = true
    }

    /** The magnetometer's reading in the phone's frame (as [setUp]), any unit: where north is, dipping down. */
    @Synchronized
    fun setNorth(mx: Float, my: Float, mz: Float) {
        val l = sqrt(mx * mx + my * my + mz * mz)
        if (l == 0f) return
        northX = mx / l; northY = my / l; northZ = mz / l
        hasNorth = true
    }

    /**
     * [rotate] against the world: the gyro turn, pulled toward the measured up ([setUp]) so the tilt
     * matches gravity, then the heading (which way the right edge points, round the vertical)
     * eased back to facing the player over about [easeSeconds] (none at 0), or with [compass] held
     * by the magnetometer ([setNorth]) to the way the phone faced when it was aimed ([aimHere], and
     * at the start): that way is heading 0, wherever north is.
     */
    @Synchronized
    fun fuse(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float, easeSeconds: Float, compass: Boolean = false) {
        var ax = pitch * DEG_TO_RAD
        var ay = -yaw * DEG_TO_RAD
        var az = -roll * DEG_TO_RAD
        if (hasUp) {
            // Up as the pose has it, in the phone's frame: the matrix's middle row.
            val px = 2 * (x * y + w * z)
            val py = 1 - 2 * (x * x + z * z)
            val pz = 2 * (y * z - w * x)
            // Turning about measured × predicted brings the predicted up onto the measured one.
            val k = if (settling > 0f) KP_SETTLE else KP
            ax += k * (upY * pz - upZ * py)
            ay += k * (upZ * px - upX * pz)
            az += k * (upX * py - upY * px)
            settling -= dtSeconds
        }
        val settled = settling <= 0f
        turn(ax * dtSeconds, ay * dtSeconds, az * dtSeconds)
        if (compass && hasNorth) {
            // North in the view frame (the matrix times it), flattened: its angle from straight ahead (-z).
            val nx = (1 - 2 * (y * y + z * z)) * northX + 2 * (x * y - w * z) * northY + 2 * (x * z + w * y) * northZ
            val nz = 2 * (x * z - w * y) * northX + 2 * (y * z + w * x) * northY + (1 - 2 * (x * x + y * y)) * northZ
            if (nx * nx + nz * nz > 0.01f) {
                val a = atan2(nx, -nz)
                if (aimPending) {
                    // Turning the heading by t takes both it and a down by t: a - heading is where
                    // north lies at heading 0. Then the heading goes straight to 0.
                    if (settled) {
                        val h = headingFrom(rest)
                        northAt = a - h
                        turnVertical(h)
                        aimPending = false
                    }
                } else {
                    turnVertical(wrap(a - northAt) * (dtSeconds * KC).coerceAtMost(1f))
                }
            }
        } else if (easeSeconds > 0f) {
            turnVertical(headingFrom(rest) * (dtSeconds / easeSeconds).coerceAtMost(1f))
        }
        version++
    }

    /** The heading in radians (right +) from upright facing the player: see [headingFrom]. */
    @Synchronized
    fun heading(): Float = headingFrom(null)

    /**
     * The turn round the vertical (radians, right +) from the way the screen faces in the resting
     * grip [rest] (w, x, y, z; null = upright, facing the player): [turnHeading] by minus it brings
     * the screen back to face that way. Measured by where the screen faces, so a turn about the
     * screen's own axis is left alone; when the screen (now or at rest) faces nearly up or down,
     * the phone's edges take over, smoothly, so there are no jumps or flips. As the PC overlays'.
     */
    @Synchronized
    fun headingFrom(rest: FloatArray?): Float {
        var rw = 1f
        var rx = 0f
        var ry = 0f
        var rz = 0f
        if (rest != null && rest.size == 4) {
            rw = rest[0]; rx = rest[1]; ry = rest[2]; rz = rest[3]
        }
        // The phone's axes in the world, now (a) and at rest (b): screen normal, top edge, right edge.
        val anx = 2 * (x * z + w * y)
        val anz = 1 - 2 * (x * x + y * y)
        val bnx = 2 * (rx * rz + rw * ry)
        val bnz = 1 - 2 * (rx * rx + ry * ry)
        var sn = bnx * anz - bnz * anx
        var cn = bnx * anx + bnz * anz
        val m = sqrt(sn * sn + cn * cn)
        val k = (1f - m / EDGES_BELOW).coerceAtLeast(0f)
        if (k > 0f) {
            val aux = 2 * (x * y - w * z)
            val auz = 2 * (y * z + w * x)
            val bux = 2 * (rx * ry - rw * rz)
            val buz = 2 * (ry * rz + rw * rx)
            val arx = 1 - 2 * (y * y + z * z)
            val arz = 2 * (x * z - w * y)
            val brx = 1 - 2 * (ry * ry + rz * rz)
            val brz = 2 * (rx * rz - rw * ry)
            sn += k * (bux * auz - buz * aux + brx * arz - brz * arx)
            cn += k * (bux * aux + buz * auz + brx * arx + brz * arz)
        }
        if (sn * sn + cn * cn < 1e-8f) return 0f
        return atan2(sn, cn)
    }

    /** How far (radians) the whole pose is turned from straight. */
    @Synchronized
    fun angle(): Float = 2 * acos(abs(w).coerceAtMost(1f))

    /**
     * Turns as a game camera does: [yawDeg] right about the vertical, [pitchDeg] up about its own
     * right, pitch held within ±89°, never any roll. Where it points now is read from the pose (so
     * easing back keeps working), then the pose is made again from that heading and pitch alone.
     */
    @Synchronized
    fun cameraTurn(yawDeg: Float, pitchDeg: Float) {
        // Where the screen's back points (as a laser pointer's beam): out of the phone's back.
        val bx = -(2 * (x * z + w * y))
        val by = -(2 * (y * z - w * x))
        val bz = -(1 - 2 * (x * x + y * y))
        val heading = atan2(bx, -bz) + yawDeg * DEG_TO_RAD
        val pitch = (asin(by.coerceIn(-1f, 1f)) + pitchDeg * DEG_TO_RAD).coerceIn(-MAX_CAMERA_PITCH, MAX_CAMERA_PITCH)
        // Ry(-heading) · Rx(pitch)
        val cy = cos(-heading / 2)
        val sy = sin(-heading / 2)
        val cx = cos(pitch / 2)
        val sx = sin(pitch / 2)
        w = cy * cx; x = cy * sx; y = sy * cx; z = -sy * sx
        version++
    }

    /** The gimbal angles ([gimbalTurn]) and the pose they made: read again from the pose when anything else turned it. */
    private val gimbal = FloatArray(3)
    private val gimbalOf = FloatArray(4) { Float.NaN }

    /**
     * Turns as a gimbal (a game camera's order): by [a] radians about the vertical, then [b] about
     * its own right, then [c] about where it faces, as Ry(a)·Rx(b)·Rz(c). The angles add up and
     * [locks] (bit 0 the vertical, 1 the right, 2 the facing axis) holds those at 0, so nothing,
     * not even the other two, ever turns a locked one; [stops] (the same bits) holds those within ±90°
     * (the right one within ±89°, short of the poles). Over the top (beyond ±90° about the right),
     * [a] turns the other way, so left and right stay as seen.
     */
    @Synchronized
    fun gimbalTurn(a: Float, b: Float, c: Float, locks: Int, stops: Int) {
        syncGimbal()
        gimbal[0] += if (abs(gimbal[1]) > PI_F / 2) -a else a
        gimbal[1] += b; gimbal[2] += c
        for (i in 0..2) {
            // Within half a turn either way, so easing back never unwinds whole turns.
            if (gimbal[i] > PI_F) gimbal[i] -= 2 * PI_F else if (gimbal[i] < -PI_F) gimbal[i] += 2 * PI_F
            if (locks and (1 shl i) != 0) gimbal[i] = 0f
        }
        for (i in 0..2) if (stops and (1 shl i) != 0) {
            val max = if (i == 1) MAX_CAMERA_PITCH else PI_F / 2
            gimbal[i] = gimbal[i].coerceIn(-max, max)
        }
        // Ry(a) · Rx(b), then · Rz(c)
        val cy = cos(gimbal[0] / 2); val sy = sin(gimbal[0] / 2)
        val cx = cos(gimbal[1] / 2); val sx = sin(gimbal[1] / 2)
        val cz = cos(gimbal[2] / 2); val sz = sin(gimbal[2] / 2)
        val pw = cy * cx; val px = cy * sx; val py = sy * cx; val pz = -sy * sx
        w = pw * cz - pz * sz
        x = px * cz + py * sz
        y = py * cz - px * sz
        z = pw * sz + pz * cz
        gimbalOf[0] = w; gimbalOf[1] = x; gimbalOf[2] = y; gimbalOf[3] = z
        version++
    }

    /** The gimbal angles ([gimbalTurn]: about the vertical, its right, where it faces; radians) into [out]. */
    @Synchronized
    fun gimbalAngles(out: FloatArray) {
        syncGimbal()
        gimbal.copyInto(out)
    }

    /** Reads the gimbal angles from the pose again when anything but [gimbalTurn] turned it. */
    private fun syncGimbal() {
        if (w == gimbalOf[0] && x == gimbalOf[1] && y == gimbalOf[2] && z == gimbalOf[3]) return
        gimbal[0] = atan2(2 * (x * z + w * y), 1 - 2 * (x * x + y * y))
        gimbal[1] = asin((-2 * (y * z - w * x)).coerceIn(-1f, 1f))
        gimbal[2] = atan2(2 * (x * y + w * z), 1 - 2 * (x * x + z * z))
    }

    /** Exactly straight, keeping what the sensors last said (unlike [reset]). */
    @Synchronized
    fun straighten() {
        w = 1f; x = 0f; y = 0f; z = 0f
        version++
    }

    /**
     * The pose with its turn round the vertical taken out (w, x, y, z): only how it is tilted against
     * gravity. That turn is the twist of the whole pose about the vertical, so tipping forward or back
     * never counts, however far.
     */
    @Synchronized
    fun tiltOnly(): FloatArray {
        val a = if (y * y + w * w > 1e-8f) -2f * atan2(y, w) else 0f
        val bw = cos(a / 2)
        val by = sin(a / 2)
        val q = floatArrayOf(bw * w - by * y, bw * x + by * z, bw * y + by * w, bw * z - by * x)
        val n = 1f / sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        for (i in 0..3) q[i] *= n
        return q
    }

    /** The rotation as a quaternion (w, x, y, z) into [out]. */
    @Synchronized
    fun quaternion(out: FloatArray) {
        out[0] = w; out[1] = x; out[2] = y; out[3] = z
    }

    /** Turns the heading by [a] radians (right +), about the world's vertical. */
    @Synchronized
    fun turnHeading(a: Float) {
        if (a == 0f) return
        turnVertical(-a)
        version++
    }

    /**
     * Takes the heading back toward 0: by the share [amount] of it, or with [bySpeed] by [amount]
     * radians, never past 0.
     */
    @Synchronized
    fun easeHeading(bySpeed: Boolean, amount: Float) {
        val h = headingFrom(rest)
        if (h == 0f) return
        turnHeading(eased(abs(h), bySpeed, amount) * (if (h < 0f) -1f else 1f) - h)
    }

    /** Takes the whole pose back toward straight, along the shortest turn, as [easeHeading] does the heading. */
    @Synchronized
    fun easeAll(bySpeed: Boolean, amount: Float) {
        if (w < 0f) {
            w = -w; x = -x; y = -y; z = -z
        }
        val a = 2 * acos(w.coerceAtMost(1f))
        if (a < 1e-5f) return
        val n = eased(a, bySpeed, amount)
        val k = sin(n / 2) / sin(a / 2)
        x *= k; y *= k; z *= k
        w = cos(n / 2)
        normalize()
        version++
    }

    /** [a] wrapped into -π..π. */
    private fun wrap(a: Float): Float {
        var r = a % TWO_PI
        if (r > PI) r -= TWO_PI else if (r < -PI) r += TWO_PI
        return r
    }

    private fun eased(a: Float, bySpeed: Boolean, amount: Float): Float =
        if (bySpeed) (a - amount).coerceAtLeast(0f) else a * (1f - amount.coerceIn(0f, 1f))

    /** Turns by [a] radians about the world's vertical (anticlockwise seen from above: the heading goes down by [a]): q = b * q. */
    private fun turnVertical(a: Float) {
        val bw = cos(a / 2)
        val by = sin(a / 2)
        val nw = bw * w - by * y
        val nx = bw * x + by * z
        val ny = bw * y + by * w
        val nz = bw * z - by * x
        w = nw; x = nx; y = ny; z = nz
        normalize()
    }

    /**
     * Turns by [yaw] (right +), [pitch] (up +) and [roll] (clockwise as seen by the player +), in
     * deg/s as [GyroMouse.motion] gives them, over [dtSeconds]. They are rates about the phone's
     * own axes, so they apply on the phone's side of the orientation.
     */
    @Synchronized
    fun rotate(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float) {
        // Pitching up turns about +x (the top comes toward the player), yaw right about -y, clockwise roll about -z.
        val k = DEG_TO_RAD * dtSeconds
        if (turn(pitch * k, -yaw * k, -roll * k)) version++
    }

    /**
     * The world as the phone sees it while it turns by [yaw], [pitch] and [roll] (as [rotate]): the
     * opposite turn, about the view's axes, so the pose holds still against the world.
     */
    @Synchronized
    fun counterRotate(yaw: Float, pitch: Float, roll: Float, dtSeconds: Float) {
        val k = DEG_TO_RAD * dtSeconds
        val ax = -pitch * k
        val ay = yaw * k
        val az = roll * k
        val angle = sqrt(ax * ax + ay * ay + az * az)
        if (angle == 0f) return
        val s = sin(angle / 2) / angle
        val bw = cos(angle / 2)
        val bx = ax * s
        val by = ay * s
        val bz = az * s
        // q = b * q
        val nw = bw * w - bx * x - by * y - bz * z
        val nx = bw * x + bx * w + by * z - bz * y
        val ny = bw * y - bx * z + by * w + bz * x
        val nz = bw * z + bx * y - by * x + bz * w
        w = nw; x = nx; y = ny; z = nz
        normalize()
        version++
    }

    /** Turns by the rotation vector ([ax], [ay], [az], radians) about the phone's own axes; false when it is zero. */
    private fun turn(ax: Float, ay: Float, az: Float): Boolean {
        val angle = sqrt(ax * ax + ay * ay + az * az)
        if (angle == 0f) return false
        val s = sin(angle / 2) / angle
        val bw = cos(angle / 2)
        val bx = ax * s
        val by = ay * s
        val bz = az * s
        // q = q * b
        val nw = w * bw - x * bx - y * by - z * bz
        val nx = w * bx + x * bw + y * bz - z * by
        val ny = w * by - x * bz + y * bw + z * bx
        val nz = w * bz + x * by - y * bx + z * bw
        w = nw; x = nx; y = ny; z = nz
        normalize()
        return true
    }

    /** Renormalized every step: float error never builds up into a stretched model. */
    private fun normalize() {
        val n = 1f / sqrt(w * w + x * x + y * y + z * z)
        w *= n; x *= n; y *= n; z *= n
    }

    /** Writes the rotation as a row-major 3×3 matrix (phone frame → view frame) into [out]. */
    @Synchronized
    fun matrix(out: FloatArray) {
        out[0] = 1 - 2 * (y * y + z * z); out[1] = 2 * (x * y - w * z); out[2] = 2 * (x * z + w * y)
        out[3] = 2 * (x * y + w * z); out[4] = 1 - 2 * (x * x + z * z); out[5] = 2 * (y * z - w * x)
        out[6] = 2 * (x * z - w * y); out[7] = 2 * (y * z + w * x); out[8] = 1 - 2 * (x * x + y * y)
    }

    companion object {
        /**
         * The resting grip held to gravity from an accelerometer reading ([setUp]'s frame, any unit):
         * the turn (w, x, y, z) that tips that up onto the view's up with no turn round the vertical,
         * as [tiltOnly] of a pose held that way. Null with no reading.
         */
        fun tiltFromUp(ax: Float, ay: Float, az: Float): FloatArray? {
            val l = sqrt(ax * ax + ay * ay + az * az)
            if (!(l > 0f)) return null
            val x = ax / l
            val y = ay / l
            val z = az / l
            // Shortest turn from up to (0, 1, 0): its axis up × (0, 1, 0) lies flat, so it has no turn round the vertical.
            val w = 1f + y
            if (w < 1e-4f) return floatArrayOf(0f, 1f, 0f, 0f) // upside down: half a turn about the right edge
            val n = 1f / sqrt(w * w + z * z + x * x)
            return floatArrayOf(w * n, -z * n, 0f, x * n)
        }

        private const val DEG_TO_RAD = (Math.PI / 180).toFloat()

        /** A camera pitches no further than this up or down (radians, 89°). */
        const val MAX_CAMERA_PITCH = (89 * Math.PI / 180).toFloat()
        private const val PI_F = Math.PI.toFloat()
        private const val PI = Math.PI.toFloat()
        private const val TWO_PI = (2 * Math.PI).toFloat()

        /** Pull toward gravity (rad/s per unit of error): slow enough to ride out hand shake. */
        private const val KP = 1.5f
        private const val KP_SETTLE = 20f

        /** Pull toward north (rad/s per radian off): slow, as the magnetometer is noisy. */
        private const val KC = 1f
        private const val SETTLE_S = 0.3f

        /** How far from 1 g a reading may be and still count as gravity. */
        private const val G_SLACK = 0.15f

        /** Below this much (screen sideways, now times at rest) the edges help find the heading. */
        private const val EDGES_BELOW = 0.5f
    }
}
