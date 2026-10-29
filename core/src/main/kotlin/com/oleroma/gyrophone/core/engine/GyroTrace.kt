package com.oleroma.gyrophone.core.engine

/**
 * Recent yaw / pitch / roll speeds for the pad's gyro graph: gyro samples averaged into buckets of
 * 1 / [rateHz] s, the last [maxSeconds] kept, each bucket marked when a ratchet was held during it.
 * Written by the gyro thread ([add]), read by the UI ([read]).
 */
class GyroTrace(val rateHz: Int = 120, maxSeconds: Int = 8) {
    val capacity = rateHz * maxSeconds
    private val yaw = FloatArray(capacity)
    private val pitch = FloatArray(capacity)
    private val roll = FloatArray(capacity)
    private val held = BooleanArray(capacity)
    private var head = 0
    private var count = 0

    private val bucketS = 1f / rateHz
    private var accT = 0f
    private var accYaw = 0f
    private var accPitch = 0f
    private var accRoll = 0f
    private var accHeld = false

    /** Bumped on every new bucket. */
    @Volatile var version = 0L
        private set

    /** One sample: rates in deg/s ([GyroMouse.motion]) over [dtSeconds], [ratchet] held during it. */
    @Synchronized
    fun add(yawRate: Float, pitchRate: Float, rollRate: Float, dtSeconds: Float, ratchet: Boolean) {
        accT += dtSeconds
        accYaw += yawRate * dtSeconds
        accPitch += pitchRate * dtSeconds
        accRoll += rollRate * dtSeconds
        accHeld = accHeld || ratchet
        if (accT < bucketS) return
        yaw[head] = accYaw / accT
        pitch[head] = accPitch / accT
        roll[head] = accRoll / accT
        held[head] = accHeld
        head = (head + 1) % capacity
        if (count < capacity) count++
        accT = 0f; accYaw = 0f; accPitch = 0f; accRoll = 0f; accHeld = false
        version++
    }

    @Synchronized
    fun clear() {
        head = 0; count = 0
        accT = 0f; accYaw = 0f; accPitch = 0f; accRoll = 0f; accHeld = false
        version++
    }

    /** Copies the newest buckets, up to [n] (and the arrays' size), oldest first; returns how many. */
    @Synchronized
    fun read(n: Int, outYaw: FloatArray, outPitch: FloatArray, outRoll: FloatArray, outHeld: BooleanArray): Int {
        val k = minOf(n, count, outYaw.size)
        var i = (head - k + capacity) % capacity
        for (j in 0 until k) {
            outYaw[j] = yaw[i]; outPitch[j] = pitch[i]; outRoll[j] = roll[i]; outHeld[j] = held[i]
            i = (i + 1) % capacity
        }
        return k
    }
}

/**
 * How far the pad's parallax edge has swung, in degrees: each axis gathers the turn and leaks
 * back to 0 with time constant springSeconds, so the edge slides as the phone turns and settles
 * back once it stops. Written by the gyro thread, read by the UI.
 */
class GyroSway {
    @Volatile var x = 0f
        private set
    @Volatile var y = 0f
        private set
    @Volatile var rot = 0f
        private set

    /** Rates in deg/s ([GyroMouse.motion]) over [dtSeconds]: yaw moves [x], pitch [y], roll [rot]. */
    fun update(yawRate: Float, pitchRate: Float, rollRate: Float, dtSeconds: Float, springSeconds: Float) {
        val k = if (springSeconds <= 0f) 0f else kotlin.math.exp(-dtSeconds / springSeconds)
        x = (x * k + yawRate * dtSeconds).coerceIn(-LIMIT, LIMIT)
        y = (y * k + pitchRate * dtSeconds).coerceIn(-LIMIT, LIMIT)
        rot = (rot * k + rollRate * dtSeconds).coerceIn(-LIMIT, LIMIT)
    }

    fun reset() {
        x = 0f; y = 0f; rot = 0f
    }

    private companion object {
        const val LIMIT = 90f
    }
}
