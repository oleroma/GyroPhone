package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.GyroMouse
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.engine.Touchpad
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.GamepadAxis
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InertiaCurve
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.MoveSlot
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchpadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inertia: Move X / Y glide after the lift, gyro axes after a ratchet turns them off. */
class InertiaTest {
    private val ms = 1_000_000L

    /** One finger flicks right at 1.5 dp/ms and lifts at 105 ms; the cursor's dx per 5 ms poll up to [untilMs]. */
    private fun flick(s: TouchpadSettings, untilMs: Long = 3000): List<Int> {
        val p = Touchpad(s).apply { pxPerDp = 1f }
        val out = ArrayList<Int>()
        var t = 0L
        fun pollTo(to: Long) {
            while (t < to) {
                p.poll(t); out.add(p.dx); p.drainMotion(); t += 5 * ms
            }
        }
        p.down(1, 100f, 100f, 0)
        for (i in 1..10) {
            pollTo(i * 10 * ms); p.move(1, 100f + i * 15f, 100f, t)
        }
        pollTo(105 * ms); p.up(1, t)
        pollTo(untilMs * ms)
        return out
    }

    private fun gliding(c: InertiaCurve = InertiaCurve.SMOOTH) = TouchpadSettings(acceleration = 0f, inertiaCurve = c)
        .withMove(MoveSlot.X1) { it.copy(inertia = true) }.withMove(MoveSlot.Y1) { it.copy(inertia = true) }

    @Test
    fun cursorDoesNotGlideBuiltIn() {
        val d = flick(TouchpadSettings(acceleration = 0f))
        assertEquals(225, d.sum()) // 150 dp at 1.5
        assertEquals(0, d.drop(22).sum())
    }

    @Test
    fun cursorGlidesWithItsInertia() {
        val d = flick(gliding())
        val glide = d.drop(22).sum()
        // 1.5 dp/ms · 325 ms · 1.5 counts/dp ≈ 730.
        assertTrue("glide $glide", glide in 500..900)
    }

    @Test
    fun linearGlideStopsAfterTwiceTheGlideTimeAndGoesAsFar() {
        val smooth = flick(gliding()).drop(22).sum()
        val d = flick(gliding(InertiaCurve.LINEAR))
        val glide = d.drop(22).sum()
        assertTrue("linear $glide smooth $smooth", glide in smooth - 120..smooth + 120)
        // Lift at 105 ms, 2 · 325 ms on: nothing after about 760 ms.
        assertEquals(0, d.drop(160).sum())
    }

    @Test
    fun eachAxisGlidesByItsOwnInertia() {
        val s = gliding().withMove(MoveSlot.X1) { it.copy(inertia = false) }
        assertEquals(0, flick(s).drop(22).sum())
    }

    // ---- gyro ----

    private val stickX = Mapping(InputSource.Gyro(MotionAxis.ROLL), OutputTarget.PadAxis(GamepadAxis.LX))

    /** Rolls at about 57 °/s for 100 ms with the gyro on, then still with it off for 2 s, then still with it on. */
    private fun ratchet(g: GyroSettings, routes: List<Mapping> = Bindings.gyroMouseMappings()): Triple<Int, Int, Int> {
        val m = GyroMouse(g)
        var on = 0; var off = 0; var after = 0
        repeat(20) { on += m.route(0f, 0f, -1f, 0.005f, routes, MappingEngine.ALL_GYRO).dx }
        repeat(400) { off += m.route(0f, 0f, 0f, 0.005f, routes, 0).dx }
        assertTrue(!m.coasting)
        repeat(20) { m.route(0f, 0f, -1f, 0.005f, routes, MappingEngine.ALL_GYRO) }
        // Turned off and on again at once: on again, the glide stops.
        m.route(0f, 0f, 0f, 0.005f, routes, 0)
        repeat(50) { after += m.route(0f, 0f, 0f, 0.005f, routes, MappingEngine.ALL_GYRO).dx }
        return Triple(on, off, after)
    }

    @Test
    fun gyroDoesNotGlideBuiltIn() {
        val (on, off, _) = ratchet(GyroSettings())
        assertTrue(on > 0)
        assertEquals(0, off)
    }

    @Test
    fun gyroGlidesWhenARatchetTurnsItOff() {
        val (on, off, after) = ratchet(GyroSettings(inertia = true))
        // About 57 °/s · 0.325 s · 16 counts/° ≈ 300.
        assertTrue("on $on off $off", off in 200..400)
        assertEquals(0, after)
        val (_, linear, _) = ratchet(GyroSettings(inertia = true, inertiaCurve = InertiaCurve.LINEAR))
        assertTrue("linear $linear", linear in off - 60..off + 60)
    }

    @Test
    fun gyroAxisTakesItsOwnInertia() {
        val (_, off, _) = ratchet(GyroSettings(inertia = true, rollInertia = false))
        assertEquals(0, off)
    }

    @Test
    fun sticksNeverGlide() {
        val m = GyroMouse(GyroSettings(inertia = true))
        repeat(20) { m.route(0f, 0f, -1f, 0.005f, listOf(stickX), MappingEngine.ALL_GYRO) }
        val r = m.route(0f, 0f, 0f, 0.005f, listOf(stickX), 0)
        assertEquals(0f, r.pad[GamepadAxis.LX.ordinal])
    }
}
