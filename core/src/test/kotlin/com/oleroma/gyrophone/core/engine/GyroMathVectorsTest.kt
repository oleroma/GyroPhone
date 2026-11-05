package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.engine.GyroMath.Route
import com.oleroma.gyrophone.core.model.CurvePoint
import com.oleroma.gyrophone.core.model.GyroCurve
import com.oleroma.gyrophone.core.net.PcGyroFeed
import com.oleroma.gyrophone.core.net.getI16
import com.oleroma.gyrophone.core.net.getI32
import com.oleroma.gyrophone.core.net.getI64
import com.oleroma.gyrophone.core.net.getU16
import com.oleroma.gyrophone.core.net.getU32
import com.oleroma.gyrophone.core.net.getU8
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * The gyro math test vectors shared with the companion (protocol/v1/gyro-math-v1.json): fixed
 * cases run through [GyroMath], and one encoded STATE_GYRO tail ([PcGyroFeed.encodeTail]). The
 * companion's GyroMath.cs must give exactly the same counts and pad bits for every step.
 *
 * Normally the file must equal what this computes. To write it again after a deliberate change to
 * the math (then change GyroMath.cs too): GYRO_REGEN_VECTORS=1, or -DregenVectors=true.
 */
class GyroMathVectorsTest {

    private class Step(val yaw: Float, val pitch: Float, val roll: Float, val dt: Float, val axes: Int)

    private class Case(
        val name: String, val config: GyroMath.Config, val tightening: Float, val smoothing: Float, val steps: List<Step>,
    )

    @Test
    fun vectorsMatchTheFile() {
        val text = render()
        val file = File(PATH)
        val regen = System.getenv("GYRO_REGEN_VECTORS") == "1" || System.getProperty("regenVectors") == "true"
        if (regen) {
            file.parentFile.mkdirs()
            file.writeText(text)
            return
        }
        val stored = javaClass.getResourceAsStream("/protocol/v1/gyro-math-v1.json")?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("protocol/v1/gyro-math-v1.json is missing: run with GYRO_REGEN_VECTORS=1")
        assertEquals("gyro-math-v1.json differs from what GyroMath computes", text, stored.replace("\r\n", "\n"))
    }

    // ---- the cases ----

    private fun cfg(
        sens: FloatArray = floatArrayOf(16f, 16f, 16f),
        inertia: BooleanArray = BooleanArray(3),
        glideMs: FloatArray = floatArrayOf(325f, 325f, 325f),
        linear: BooleanArray = BooleanArray(3),
        routes: List<Route> = MOUSE,
        accel: List<GyroCurve> = List(3) { GyroCurve.OFF },
    ) = GyroMath.Config(
        sens, inertia, glideMs, linear, routes,
        Array(3) { accel[it].flat() }, BooleanArray(3) { !accel[it].isOff && accel[it].axisSpeed },
        BooleanArray(3) { !accel[it].isOff && accel[it].smooth },
    )

    /** [n] steps of motion from [rnd] around [base] deg/s (± [spread]) over [dt] with [axes]. */
    private fun walk(rnd: Random, n: Int, base: FloatArray, spread: Float, dt: Float, axes: (Int) -> Int = { ALL }): List<Step> =
        List(n) { i ->
            fun r(k: Int) = base[k] + (rnd.nextFloat() * 2f - 1f) * spread
            Step(r(0), r(1), r(2), dt, axes(i))
        }

    private fun cases(): List<Case> {
        val list = ArrayList<Case>()
        fun add(name: String, c: GyroMath.Config, tight: Float, smooth: Float, seed: Long, build: (Random) -> List<Step>) {
            list.add(Case(name, c, tight, smooth, build(Random(seed))))
        }
        add("mouse-slow-2ms", cfg(), 0f, 0f, 1) { walk(it, 24, floatArrayOf(2f, -4f, 6f), 3f, 0.002f) }
        add("mouse-fast-2ms", cfg(), 0f, 0f, 2) { walk(it, 24, floatArrayOf(-400f, 650f, 900f), 500f, 0.002f) }
        add("sensitivity-per-axis-and-scales", cfg(
            sens = floatArrayOf(5f, 12.5f, 30.25f),
            routes = listOf(Route(2, Route.MOUSE_X, 0.7f), Route(1, Route.MOUSE_Y, -1.3f), Route(0, Route.MOUSE_X, 2f)),
        ), 0f, 0f, 3) { walk(it, 24, floatArrayOf(4f, -9f, 13f), 20f, 0.0013f) }
        add("wheel-and-hwheel", cfg(
            sens = floatArrayOf(16f, 9f, 24f),
            routes = listOf(Route(1, Route.WHEEL, 1f), Route(2, Route.HWHEEL, -0.5f), Route(0, Route.WHEEL, 0.25f)),
        ), 0f, 0f, 4) { walk(it, 24, floatArrayOf(-30f, 45f, -60f), 80f, 0.002f) }
        add("pad-every-axis", cfg(
            sens = floatArrayOf(16f, 20f, 12f),
            routes = listOf(
                Route(2, Route.PAD + 0, 1f), Route(1, Route.PAD + 1, 1f), Route(2, Route.PAD + 2, 0.6f),
                Route(1, Route.PAD + 3, -1.5f), Route(0, Route.PAD + 4, 1f), Route(0, Route.PAD + 5, -2f),
            ),
        ), 0f, 0f, 5) { walk(it, 24, floatArrayOf(20f, -50f, 70f), 120f, 0.001f) { i -> if (i in 10..15) 0b101 else ALL } }
        add("every-target-mixed", cfg(
            sens = floatArrayOf(10f, 16f, 22f), inertia = booleanArrayOf(true, true, true),
            routes = listOf(
                Route(2, Route.MOUSE_X, 1f), Route(1, Route.MOUSE_Y, 1f), Route(0, Route.WHEEL, 0.5f),
                Route(0, Route.HWHEEL, 1f), Route(2, Route.PAD + 2, 1f), Route(1, Route.PAD + 3, 1f),
                Route(0, Route.PAD + 4, 1f), Route(2, Route.MOUSE_X, -0.25f),
            ),
        ), 0f, 0f, 6) { walk(it, 30, floatArrayOf(60f, -40f, 80f), 30f, 0.002f) { i -> if (i < 12) ALL else if (i < 20) 0b010 else ALL } }
        add("tightening", cfg(), 2.5f, 0f, 7) { walk(it, 30, floatArrayOf(0.5f, 1.5f, -2f), 2.5f, 0.01f) }
        add("smoothing", cfg(), 0f, 4f, 8) { rnd ->
            walk(rnd, 12, floatArrayOf(0f, 1f, 1.5f), 1f, 0.01f) + walk(rnd, 10, floatArrayOf(0f, -3f, 3f), 2f, 0.008f) +
                walk(rnd, 8, floatArrayOf(10f, 20f, -15f), 5f, 0.001f)
        }
        add("tightening-and-smoothing", cfg(sens = floatArrayOf(8f, 24f, 16f)), 1.25f, 6f, 9) { rnd ->
            walk(rnd, 15, floatArrayOf(0.2f, 0.8f, 1f), 1.5f, 0.01f) + walk(rnd, 15, floatArrayOf(5f, -6f, 7f), 4f, 0.004f)
        }
        add("glide-smooth", cfg(inertia = booleanArrayOf(true, true, true), glideMs = floatArrayOf(250f, 250f, 250f)), 0f, 0f, 10) { rnd ->
            walk(rnd, 10, floatArrayOf(0f, -40f, 120f), 10f, 0.002f) + walk(rnd, 25, floatArrayOf(0f, 0f, 0f), 2f, 0.002f) { 0 }
        }
        add("glide-linear", cfg(
            inertia = booleanArrayOf(true, true, true), glideMs = floatArrayOf(400f, 400f, 400f), linear = booleanArrayOf(true, true, true),
        ), 0f, 0f, 11) { rnd ->
            walk(rnd, 10, floatArrayOf(0f, 70f, -90f), 10f, 0.002f) + walk(rnd, 25, floatArrayOf(0f, 0f, 0f), 2f, 0.004f) { 0 }
        }
        add("glide-clamped-and-mixed", cfg(
            sens = floatArrayOf(16f, 12f, 20f), inertia = booleanArrayOf(false, true, true),
            glideMs = floatArrayOf(325f, 50f, 3000f), linear = booleanArrayOf(false, false, true),
        ), 0f, 0f, 12) { rnd ->
            walk(rnd, 8, floatArrayOf(30f, 60f, 80f), 10f, 0.002f) + walk(rnd, 20, floatArrayOf(30f, 0f, 0f), 5f, 0.005f) { 0b001 }
        }
        add("ratchet-toggling", cfg(inertia = booleanArrayOf(true, true, true), glideMs = floatArrayOf(150f, 200f, 300f)), 0f, 0f, 13) { rnd ->
            val masks = intArrayOf(ALL, ALL, 0, 0, ALL, 0b100, 0b100, 0, 0b011, ALL, 0, 0, 0, ALL, 0b010, 0)
            walk(rnd, 32, floatArrayOf(20f, -60f, 100f), 60f, 0.002f) { i -> masks[i / 2] }
        }
        add("glide-too-slow-to-start", cfg(inertia = booleanArrayOf(true, true, true)), 0f, 0f, 14) { rnd ->
            walk(rnd, 10, floatArrayOf(1f, -2f, 3f), 0.5f, 0.002f) + walk(rnd, 10, floatArrayOf(0f, 0f, 0f), 1f, 0.002f) { 0 }
        }
        add("dt-0-1-2-50ms", cfg(
            inertia = booleanArrayOf(true, true, true),
            routes = MOUSE + listOf(Route(0, Route.WHEEL, 1f), Route(1, Route.PAD + 1, 1f)),
        ), 1f, 3f, 15) { rnd ->
            val dts = floatArrayOf(0f, 0.001f, 0.002f, 0.05f, 0f, 0.002f, 0.001f, 0.05f)
            List(32) { i ->
                fun r() = (rnd.nextFloat() * 2f - 1f) * 150f
                Step(r(), r(), r(), dts[i % dts.size], if (i in 20..27) 0 else ALL)
            }
        }
        add("long-random", cfg(
            sens = floatArrayOf(14f, 18f, 11f), inertia = booleanArrayOf(true, false, true), linear = booleanArrayOf(false, false, true),
            glideMs = floatArrayOf(200f, 325f, 500f),
            routes = MOUSE + listOf(Route(0, Route.HWHEEL, 1f), Route(2, Route.PAD + 2, 0.8f), Route(1, Route.PAD + 5, 1f)),
        ), 0.75f, 2.5f, 16) { rnd ->
            List(48) {
                fun r() = (rnd.nextFloat() * 2f - 1f) * (if (rnd.nextInt(4) == 0) 400f else 15f)
                Step(r(), r(), r(), (1 + rnd.nextInt(4)) / 1000f, rnd.nextInt(8))
            }
        }
        val strong = GyroCurve.STRONG
        val wide = GyroCurve(listOf(CurvePoint(5f, 0.6f), CurvePoint(40.3f, 1f), CurvePoint(90f, 1.75f), CurvePoint(180f, 2.4f), CurvePoint(400f, 4f), CurvePoint(800f, 4.5f)))
        add("accel-whole-phone", cfg(accel = List(3) { strong }), 0f, 0f, 17) { rnd ->
            walk(rnd, 12, floatArrayOf(2f, -3f, 4f), 6f, 0.002f) + walk(rnd, 12, floatArrayOf(60f, -80f, 150f), 120f, 0.002f) +
                walk(rnd, 12, floatArrayOf(-300f, 200f, -500f), 200f, 0.001f)
        }
        add("accel-axis-speed-per-axis", cfg(
            sens = floatArrayOf(12f, 16f, 20f),
            accel = listOf(GyroCurve.OFF, wide.copy(axisSpeed = true), GyroCurve.LIGHT.copy(axisSpeed = true)),
            routes = MOUSE + listOf(Route(0, Route.WHEEL, 1f), Route(1, Route.PAD + 1, 0.5f)),
        ), 0f, 0f, 18) { rnd ->
            List(40) {
                fun r() = (rnd.nextFloat() * 2f - 1f) * (if (rnd.nextInt(3) == 0) 600f else 40f)
                Step(r(), r(), r(), (1 + rnd.nextInt(3)) / 1000f, ALL)
            }
        }
        add("accel-glide-keeps-gain", cfg(
            inertia = booleanArrayOf(true, true, true), glideMs = floatArrayOf(300f, 300f, 300f),
            linear = booleanArrayOf(false, true, false), accel = List(3) { wide },
        ), 0f, 0f, 19) { rnd ->
            val masks = intArrayOf(ALL, ALL, ALL, 0, 0, 0, ALL, 0b100, 0b100, 0, 0)
            walk(rnd, 33, floatArrayOf(40f, -120f, 250f), 60f, 0.002f) { i -> masks[i / 3] }
        }
        add("accel-filters-and-sticks", cfg(
            sens = floatArrayOf(16f, 10f, 22f), accel = listOf(strong, wide, strong.copy(axisSpeed = true)),
            routes = listOf(Route(2, Route.MOUSE_X, 1f), Route(1, Route.MOUSE_Y, -1f), Route(2, Route.PAD + 2, 1f), Route(0, Route.PAD + 4, 1f)),
        ), 1.5f, 5f, 20) { rnd ->
            walk(rnd, 15, floatArrayOf(0.5f, 1f, -1.5f), 2f, 0.004f) + walk(rnd, 15, floatArrayOf(30f, -90f, 200f), 150f, 0.002f)
        }
        add("accel-smooth", cfg(
            sens = floatArrayOf(16f, 12f, 20f),
            accel = listOf(wide.copy(smooth = true), strong.copy(smooth = true, axisSpeed = true), GyroCurve(listOf(CurvePoint(30f, 2f), CurvePoint(60f, 0.8f), CurvePoint(120f, 1.6f)), smooth = true)),
            routes = MOUSE + listOf(Route(0, Route.WHEEL, 1f), Route(0, Route.PAD + 0, 1f)),
        ), 0f, 0f, 21) { rnd ->
            List(48) {
                fun r() = (rnd.nextFloat() * 2f - 1f) * (if (rnd.nextInt(3) == 0) 700f else 60f)
                Step(r(), r(), r(), (1 + rnd.nextInt(3)) / 1000f, ALL)
            }
        }
        return list
    }

    // ---- writing ----

    private fun render(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append(" \"_note\": \"GyroPhone gyro math v1 vectors, written by core GyroMathVectorsTest (GYRO_REGEN_VECTORS=1). ")
        sb.append("Each case runs a fresh GyroMath; every *Bits is the float's IEEE-754 bits as a signed Int, the exact value. ")
        sb.append("dx, dy, wheel, hwheel and padBits must match exactly.\",\n")
        sb.append(" \"cases\": [\n")
        val cs = cases()
        cs.forEachIndexed { ci, c ->
            val m = GyroMath()
            val cfg = c.config
            sb.append("  {\n")
            sb.append("   \"name\": \"").append(c.name).append("\",\n")
            sb.append("   \"config\": {")
            sb.append("\"sensitivity\": ").append(floats(cfg.sensitivity)).append(", \"sensitivityBits\": ").append(bits(cfg.sensitivity))
            sb.append(", \"inertia\": ").append(cfg.inertia.joinToString(", ", "[", "]"))
            sb.append(", \"glideMs\": ").append(floats(cfg.glideMs)).append(", \"glideMsBits\": ").append(bits(cfg.glideMs))
            sb.append(", \"linear\": ").append(cfg.linear.joinToString(", ", "[", "]"))
            sb.append(", \"curves\": ").append(cfg.curves.joinToString(", ", "[", "]") { floats(it) })
            sb.append(", \"curvesBits\": ").append(cfg.curves.joinToString(", ", "[", "]") { bits(it) })
            sb.append(", \"axisSpeed\": ").append(cfg.axisSpeed.joinToString(", ", "[", "]"))
            sb.append(", \"smooth\": ").append(cfg.smooth.joinToString(", ", "[", "]"))
            sb.append(", \"routes\": [")
            sb.append(cfg.routes.joinToString(", ") { r ->
                "{\"axis\": ${r.axis}, \"target\": ${r.target}, \"scale\": ${f(r.scale)}, \"scaleBits\": ${r.scale.toRawBits()}}"
            })
            sb.append("]},\n")
            sb.append("   \"tightening\": ").append(f(c.tightening)).append(", \"tighteningBits\": ").append(c.tightening.toRawBits()).append(",\n")
            sb.append("   \"smoothing\": ").append(f(c.smoothing)).append(", \"smoothingBits\": ").append(c.smoothing.toRawBits()).append(",\n")
            sb.append("   \"steps\": [\n")
            c.steps.forEachIndexed { si, s ->
                val r = m.step(s.yaw, s.pitch, s.roll, s.dt, cfg, s.axes, c.tightening, c.smoothing)
                sb.append("    {\"yaw\": ").append(f(s.yaw)).append(", \"yawBits\": ").append(s.yaw.toRawBits())
                sb.append(", \"pitch\": ").append(f(s.pitch)).append(", \"pitchBits\": ").append(s.pitch.toRawBits())
                sb.append(", \"roll\": ").append(f(s.roll)).append(", \"rollBits\": ").append(s.roll.toRawBits())
                sb.append(", \"dt\": ").append(f(s.dt)).append(", \"dtBits\": ").append(s.dt.toRawBits())
                sb.append(", \"axes\": ").append(s.axes)
                sb.append(", \"dx\": ").append(r.dx).append(", \"dy\": ").append(r.dy)
                sb.append(", \"wheel\": ").append(r.wheel).append(", \"hwheel\": ").append(r.hwheel)
                sb.append(", \"pad\": ").append(floats(r.pad)).append(", \"padBits\": ").append(bits(r.pad))
                sb.append(", \"coasting\": ").append(m.coasting)
                sb.append(if (si < c.steps.size - 1) "},\n" else "}\n")
            }
            sb.append("   ]\n")
            sb.append(if (ci < cs.size - 1) "  },\n" else "  }\n")
        }
        sb.append(" ],\n")
        sb.append(" \"tail\": ").append(tail()).append("\n")
        sb.append("}\n")
        return sb.toString()
    }

    /**
     * One STATE_GYRO tail: ten samples (so the ring has wrapped), a stop and one more, a config, and
     * a clock offset; its hex and every field decoded.
     */
    private fun tail(): String {
        var ns = 1_000_000_000L
        val feed = PcGyroFeed()
        feed.monoNs = { ns }
        val rnd = Random(99)
        var t = 5_000_000L
        for (i in 0 until 10) {
            val dt = (500 + rnd.nextInt(2500)) / 1e6f
            t += (dt * 1e6f).toLong()
            ns += 1_000_000L
            fun r() = (rnd.nextFloat() * 2f - 1f) * 300f
            feed.add(r(), r(), r(), dt, i and 7, t)
        }
        feed.still()
        ns += 1_000_000L; t += 40_000L
        feed.add(12.34f, -56.78f, 0.03f, 0.04f, 0b110, t)
        val cfg = cfg(
            sens = floatArrayOf(16f, 12.5f, 30f), inertia = booleanArrayOf(true, false, true),
            glideMs = floatArrayOf(250f, 325f, 600f), linear = booleanArrayOf(false, true, true),
            routes = listOf(
                Route(2, Route.MOUSE_X, 1f), Route(1, Route.MOUSE_Y, -0.5f), Route(0, Route.WHEEL, 2f),
                Route(0, Route.HWHEEL, 1f), Route(1, Route.PAD + 3, 1.5f),
            ),
            accel = listOf(GyroCurve.STRONG, GyroCurve.OFF, GyroCurve.LIGHT.copy(axisSpeed = true, smooth = true)),
        )
        feed.setConfig(cfg, 1.5f, 4f)
        val offset = -123_456_789L
        val b = ByteArray(PcGyroFeed.TAIL_SIZE)
        feed.encodeTail(b, 0, offset)

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"hex\": \"").append(b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }).append("\",\n")
        sb.append("  \"clockOffsetUs\": ").append(offset).append(",\n")
        sb.append("  \"flags\": ").append(b.getU8(0)).append(", \"n\": ").append(b.getU8(1))
        sb.append(", \"seq\": ").append(b.getU16(2)).append(", \"timeUs\": ").append(b.getU32(4)).append(",\n")
        sb.append("  \"samples\": [\n")
        val n = b.getU8(1)
        for (j in 0 until n) {
            val o = PcGyroFeed.SAMPLES_OFFSET + j * PcGyroFeed.SAMPLE_SIZE
            sb.append("   {\"dtUs\": ").append(b.getU16(o)).append(", \"yaw\": ").append(b.getI16(o + 2))
            sb.append(", \"pitch\": ").append(b.getI16(o + 4)).append(", \"roll\": ").append(b.getI16(o + 6))
            sb.append(", \"axes\": ").append(b.getU8(o + 8)).append(if (j < n - 1) "},\n" else "}\n")
        }
        sb.append("  ],\n")
        sb.append("  \"totals\": [")
        sb.append((0 until 3).joinToString(", ") { b.getI64(PcGyroFeed.TOTALS_OFFSET + it * 8).toString() }).append("],\n")
        val c = PcGyroFeed.CONFIG_OFFSET
        fun f32(o: Int) = Float.fromBits(b.getI32(o))
        sb.append("  \"config\": {")
        sb.append("\"sensitivity\": ").append(floats(FloatArray(3) { f32(c + it * 4) }))
        sb.append(", \"glideMs\": ").append(floats(FloatArray(3) { f32(c + 12 + it * 4) }))
        sb.append(", \"glideBits\": ").append(b.getU8(c + 24)).append(", \"routeCount\": ").append(b.getU8(c + 25))
        sb.append(", \"tightening\": ").append(f(f32(c + 26))).append(", \"smoothing\": ").append(f(f32(c + 30)))
        sb.append(", \"routes\": [")
        sb.append((0 until b.getU8(c + 25)).joinToString(", ") {
            val o = c + PcGyroFeed.ROUTES_OFFSET + it * PcGyroFeed.ROUTE_SIZE
            "{\"axis\": ${b.getU8(o)}, \"target\": ${b.getU8(o + 1)}, \"scale\": ${f(f32(o + 2))}}"
        })
        sb.append("], \"curves\": [")
        sb.append((0 until 3).joinToString(", ") { a ->
            val o = c + PcGyroFeed.CURVES_OFFSET + a * PcGyroFeed.CURVE_SIZE
            val head = b.getU8(o)
            val pts = (0 until (head and 0x0F)).joinToString(", ") { j -> "${b.getU16(o + 1 + j * 4)}, ${b.getU16(o + 3 + j * 4)}" }
            "{\"axisSpeed\": ${head and PcGyroFeed.CURVE_AXIS_SPEED != 0}, \"smooth\": ${head and PcGyroFeed.CURVE_SMOOTH != 0}, \"q\": [$pts]}"
        })
        sb.append("]}\n")
        sb.append(" }")
        return sb.toString()
    }

    private fun f(v: Float): String = v.toString()
    private fun floats(a: FloatArray): String = a.joinToString(", ", "[", "]") { f(it) }
    private fun bits(a: FloatArray): String = a.joinToString(", ", "[", "]") { it.toRawBits().toString() }

    companion object {
        private const val PATH = "src/test/resources/protocol/v1/gyro-math-v1.json"
        private const val ALL = MappingEngine.ALL_GYRO

        /** The default gyro mouse: shown yaw (stored roll) to x, pitch to y. */
        private val MOUSE = listOf(Route(2, Route.MOUSE_X, 1f), Route(1, Route.MOUSE_Y, 1f))
    }
}
