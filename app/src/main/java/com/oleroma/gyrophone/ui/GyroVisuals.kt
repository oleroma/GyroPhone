package com.oleroma.gyrophone.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.oleroma.gyrophone.core.engine.GyroDisplayFeed
import com.oleroma.gyrophone.core.engine.LaserTrace
import com.oleroma.gyrophone.core.engine.RestDetector
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.BallShape
import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayColor
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.gimbalLocks
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** A gyro display ([control]) and the box it is drawn in, in pad pixels. */
class DisplayBox(val control: TouchControl, val box: Rect)

/**
 * The gyro displays placed on the pad ([com.oleroma.gyrophone.core.model.TouchKind.DISPLAY]), each
 * drawn flat in its [DisplayBox] (no 3D library) from the runtime's data for it, once per screen
 * frame. In edit mode each also gets its box, the [selected] ones lit in their [selectedColor].
 */
@Composable
fun GyroDisplaysLayer(
    runtime: GamepadRuntime,
    boxes: List<DisplayBox>,
    editing: Boolean,
    selected: Set<String>,
    selectedColor: (String) -> Color,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableLongStateOf(0L) }
    val shown by rememberUpdatedState(boxes)
    val inEdit by rememberUpdatedState(editing)
    // "Fade when phone rests": each display's share of its opacity now (1 = not faded).
    val fades = remember { HashMap<String, Float>() }
    // Redraws only while some display's data changes (and a moment after, so ghost trails catch up):
    // a still pose, as in edit mode or with the gyro off, draws nothing new.
    LaunchedEffect(Unit) {
        var last = 0L
        var until = 0L
        var lastT = 0L
        while (true) withFrameMillis { t ->
            val dt = if (lastT == 0L) 0f else (t - lastT).coerceIn(0L, 100L).toFloat()
            lastT = t
            val rf = runtime.touchVisuals.value.restFade
            val nowNs = System.nanoTime()
            for (b in shown) {
                val id = b.control.id
                val was = fades[id] ?: 1f
                val next = RestDetector.step(was, !inEdit && runtime.restDetector.resting(id, nowNs), dt, rf)
                if (next != was) {
                    fades[id] = next
                    until = maxOf(until, t)
                }
            }
            var sig = runtime.gyroLiveAxes.toLong()
            for (b in shown) {
                val f = runtime.gyroFeed(b.control.id)
                sig = sig * 31 + System.identityHashCode(f)
                sig = sig * 31 + (f?.pose?.version ?: 0L)
                sig = sig * 31 + (f?.trace?.version ?: 0L)
                sig = sig * 31 + (f?.laser?.version ?: 0L)
            }
            if (sig != last || frame == 0L) {
                last = sig
                until = t + (GHOSTS + 2) * GHOST_MS
            }
            // A laser pointer's fading paint: draw on until it is gone.
            for (b in shown) {
                val l = runtime.gyroFeed(b.control.id)?.laser ?: continue
                val d = b.control.display ?: continue
                if (l.fading(d.laserFadeMs)) until = maxOf(until, t + 50)
            }
            // A rifle's muzzle flash flickers while it fires.
            for (b in shown) {
                val d = b.control.display ?: continue
                if (d.laserRifle && runtime.gyroFeed(b.control.id)?.firing == true) until = maxOf(until, t + 50)
            }
            if (t <= until) frame = t
        }
    }
    val st = remember { VisState() }
    Canvas(modifier) {
        st.now = frame
        for (b in boxes) {
            val shownAs = b.control.display ?: continue
            val k = fades[b.control.id] ?: 1f
            val d = if (k < 1f) shownAs.copy(opacity = shownAs.opacity * k) else shownAs
            st.drawDisplay(this, runtime.gyroFeed(b.control.id), d, b.control.id, b.box, runtime.gyroLiveAxes)
            if (editing) {
                val sel = b.control.id in selected
                drawRect(
                    (if (sel) selectedColor(b.control.id) else Color.White.copy(alpha = 0.45f)),
                    b.box.topLeft, b.box.size,
                    style = Stroke(
                        (if (sel) 2.5f else 1.5f) * density,
                        pathEffect = if (sel) null else PathEffect.dashPathEffect(floatArrayOf(8 * density, 6 * density)),
                    ),
                )
            }
        }
    }
}

/** The parallax edge ([TouchVisuals.gyroEdge]): outlines just inside the screen's edge that sway as the phone turns. */
@Composable
fun GyroEdgeLayer(runtime: GamepadRuntime, v: TouchVisuals, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { frame = it }
    }
    val st = remember { VisState() }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame // redraw each frame
        st.drawEdge(this, runtime, v)
    }
}

/** Buffers reused from frame to frame, so drawing allocates little. */
private class VisState {
    val portrait = PhoneModel(landscape = false)
    val landscape = PhoneModel(landscape = true)
    val raw = FloatArray(9)
    val m = FloatArray(9)
    val path = Path()
    val path2 = Path()
    val facePath = Path()

    /** The path [penTo] draws into. */
    var pen = path
    var now = 0L

    /** Per phone with a ghost trail: its last [GHOSTS] poses. */
    val ghosts = HashMap<String, Ghosts>()

    class Ghosts {
        val poses = FloatArray(GHOSTS * 9)
        var count = 0
        var head = 0
        var t = 0L
    }

    val traceYaw = FloatArray(1024)
    val tracePitch = FloatArray(1024)
    val traceRoll = FloatArray(1024)
    val traceHeld = BooleanArray(1024)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /** [m] = the pose of [raw] seen from the level angle (the parallax edge): the pose [levelDeg] back from upright shows square on. */
    fun level(levelDeg: Int) {
        val a = Math.toRadians(levelDeg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        for (j in 0..2) {
            m[j] = raw[j]
            m[3 + j] = c * raw[3 + j] - s * raw[6 + j]
            m[6 + j] = s * raw[3 + j] + c * raw[6 + j]
        }
    }

    /** How far the horizon turns on screen (clockwise +, against the phone's roll) and the pitch (nose up +), in degrees, of the pose in [m]. */
    fun horizonRollDeg() = Math.toDegrees(atan2(m[3], m[4]).toDouble()).toFloat()
    fun pitchDeg() = Math.toDegrees(asin((-m[5]).coerceIn(-1f, 1f)).toDouble()).toFloat()

    fun drawDisplay(scope: DrawScope, feed: GyroDisplayFeed?, d: GyroDisplay, id: String, box: Rect, live: Int) = with(scope) {
        val op = d.opacity.coerceIn(0.05f, 1f)
        fun col(c: GyroDisplayColor) = Color(d.color(c).toInt()).let { it.copy(alpha = it.alpha * op) }
        when (d.kind) {
            GyroDisplayKind.PHONE -> {
                poseOf(feed, d)
                val model = if (size.width >= size.height) landscape else portrait
                model.perspective = d.perspective
                // The model's long side is 2 units, its box about 2.3 of them.
                val unit = minOf(box.width, box.height) / 2.3f
                if (d.ghosts) {
                    val g = ghosts.getOrPut(id) { Ghosts() }
                    if (now - g.t >= GHOST_MS) {
                        g.t = now
                        m.copyInto(g.poses, g.head * 9)
                        g.head = (g.head + 1) % GHOSTS
                        if (g.count < GHOSTS) g.count++
                    }
                    // Oldest first, fainter the older.
                    for (i in g.count downTo 1) {
                        val at = ((g.head - i + GHOSTS) % GHOSTS) * 9
                        model.drawOutline(this, g.poses, at, path, box.center, unit, col(GyroDisplayColor.PHONE_EDGE).copy(alpha = col(GyroDisplayColor.PHONE_EDGE).alpha * 0.4f * (1f - (i - 1f) / GHOSTS)))
                    }
                } else ghosts.remove(id)
                if (d.axonometric) worldAxes(box.center, unit * 1.25f, false, op, d.perspective)
                model.draw(
                    this, m, path, box.center, unit,
                    col(GyroDisplayColor.PHONE_FRONT), col(GyroDisplayColor.PHONE_BACK), col(GyroDisplayColor.PHONE_SIDE),
                    col(GyroDisplayColor.PHONE_EDGE),
                    if (d.axes) arrayOf(col(GyroDisplayColor.PHONE_X), col(GyroDisplayColor.PHONE_Y), col(GyroDisplayColor.PHONE_Z)) else null,
                )
                if (d.axonometric) worldAxes(box.center, unit * 1.25f, true, op, d.perspective)
            }

            GyroDisplayKind.RATCHET_RING -> drawRing(box, live, col(GyroDisplayColor.RING_TEXT),
                arrayOf(col(GyroDisplayColor.RING_YAW), col(GyroDisplayColor.RING_PITCH), col(GyroDisplayColor.RING_ROLL)))

            GyroDisplayKind.GRAPH -> drawGraph(feed, d, box, ::col)
            GyroDisplayKind.HORIZON -> drawHorizon(feed, d, box, ::col)
            GyroDisplayKind.DISK -> drawDisk(feed, d, box, ::col)
            GyroDisplayKind.BALL -> drawBall(feed, d, box, live, ::col)
            GyroDisplayKind.LASER -> drawLaser(feed, d, box, ::col)
        }
    }

    private val beam = FloatArray(3)
    private val tick = FloatArray(3)
    private val la = FloatArray(3)
    private val lb = FloatArray(3)
    private val pv = FloatArray(3)
    private var laserAxon = true
    private var laserTurned: FloatArray? = null
    private var laserPersp = false
    // The 3D shape seen in perspective ([GyroDisplay.perspective]).
    private var ballPersp = false
    // Paint by the way it moves: a path per (age step, colour), kept for reuse.
    private val movePaths = HashMap<Int, Path>()
    private val movePool = ArrayList<Path>()
    private val way = FloatArray(3)
    // A stroke run's picture points and the path key of the piece ending at each.
    private var vx = FloatArray(1024)
    private var vy = FloatArray(1024)
    private var vk = IntArray(1024)
    private var vw = FloatArray(1024)
    // Varying width: the run's centre line, its corners rounded, as dense points (x, y, width, key of the piece ending there).
    private var rx = FloatArray(4096)
    private var ry = FloatArray(4096)
    private var rw = FloatArray(4096)
    private var rk = IntArray(4096)
    private val glowPaths = HashMap<Int, Path>()
    private val nA = FloatArray(2)
    private val nB = FloatArray(2)
    private val moveColors = LongArray(6)

    private val crossPath = Path()
    /** A small padlock at [p] in [color]: a locked (None) axis's tip. */
    private fun DrawScope.padlock(p: Offset, color: Color) {
        val s = 3.5f * density
        drawRect(color, Offset(p.x - s, p.y - s * 0.4f), androidx.compose.ui.geometry.Size(2 * s, 1.6f * s))
        drawArc(
            color, 180f, 180f, false, Offset(p.x - s * 0.65f, p.y - s * 1.5f), androidx.compose.ui.geometry.Size(1.3f * s, 2.2f * s),
            style = Stroke(1.3f * density),
        )
    }

    /**
     * The crosshair look's crosshair ([LaserTrace.crosshair]) where [beam] lands on the sphere of
     * radius [r] at [c]: in [moveColor] (the colour of the way the beam last moved), a dark outline under it,
     * dimmed while a ratchet is [held].
     */
    private fun DrawScope.drawCrosshair(d: GyroDisplay, c: Offset, r: Float, held: Boolean, moveColor: Color, col: (GyroDisplayColor) -> Color) {
        val rad = (Math.PI / 180).toFloat()
        val ch = LaserTrace.crosshair(m, beam, d.crosshairShape, d.crosshairSize * rad, d.crosshairGap * rad, d.crosshairDot)
        var color = moveColor
        if (held) color = color.copy(alpha = color.alpha * 0.35f)
        val outline = col(GyroDisplayColor.LASER_CROSSHAIR_OUTLINE).let { it.copy(alpha = minOf(it.alpha, color.alpha)) }
        val w = d.crosshairThickness.coerceIn(0.5f, 8f) * density
        fun pathOf(pts: FloatArray): Path {
            crossPath.reset()
            var i = 0
            while (i < pts.size) {
                val p = laserAt(c, r, pts[i], pts[i + 1], pts[i + 2])
                if (i == 0) crossPath.moveTo(p.x, p.y) else crossPath.lineTo(p.x, p.y)
                i += 3
            }
            return crossPath
        }
        val dot = ch.dot
        if (d.crosshairOutline) {
            val ow = Stroke(w + 2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (l in ch.lines) drawPath(pathOf(l), outline, style = ow)
            if (dot != null) drawPath(pathOf(dot), outline, style = Stroke(2f * density, join = StrokeJoin.Round))
        }
        val line = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (l in ch.lines) drawPath(pathOf(l), color, style = line)
        if (dot != null) drawPath(pathOf(dot), color)
    }

    /**
     * The rifle's muzzle flash: a flickering star at its muzzle tip ([LaserTrace.MUZZLE]) in [fire]
     * with a white-hot middle, longest along the barrel.
     */
    private fun DrawScope.muzzleFlash(c: Offset, r: Float, fire: Color) {
        val mz = LaserTrace.MUZZLE
        fun at(k: Float): Offset {
            val x = mz[0]; val y = mz[1]; val z = mz[2] - k
            return laserAt(c, r, m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z)
        }
        val tip = at(0f)
        val ahead = at(0.1f)
        val fx = ahead.x - tip.x
        val fy = ahead.y - tip.y
        val fl = kotlin.math.sqrt(fx * fx + fy * fy).coerceAtLeast(1e-3f)
        // Flickers: a new shape every 35 ms.
        val step = now / 35L
        fun rnd(i: Int): Float = (((step * 7919L + i * 104729L) xor (step shr 3)) % 1000L).let { kotlin.math.abs(it) / 1000f }
        val size = r * (0.07f + 0.04f * rnd(0))
        val turn = rnd(1) * 6.2832f
        crossPath.reset()
        val spikes = 8
        for (j in 0 until spikes * 2) {
            val a = turn + j * Math.PI.toFloat() / spikes
            val ca = kotlin.math.cos(a)
            val sa = kotlin.math.sin(a)
            // Along the barrel the star stretches out (up to 2.2×).
            val along = kotlin.math.abs(ca * fx / fl + sa * fy / fl)
            val len = if (j % 2 == 0) size * (0.6f + 0.6f * rnd(j + 2)) * (1f + 1.2f * along * along) else size * 0.28f
            val p = Offset(tip.x + ca * len, tip.y + sa * len)
            if (j == 0) crossPath.moveTo(p.x, p.y) else crossPath.lineTo(p.x, p.y)
        }
        crossPath.close()
        drawCircle(fire.copy(alpha = fire.alpha * 0.3f), size * 1.3f, tip)
        drawPath(crossPath, fire)
        drawCircle(Color.White.copy(alpha = fire.alpha * 0.9f), size * 0.25f, tip)
    }

    /** Picture point of shown-frame direction ([x], [y], [z]) on a sphere of radius [r] at [c]; its depth (toward the viewer +) left in [pv]. */
    private fun laserAt(c: Offset, r: Float, x: Float, y: Float, z: Float): Offset {
        LaserTrace.view(x, y, z, pv, axon = laserAxon, turned = laserTurned)
        val k = if (laserPersp) LaserTrace.perspective(pv[2]) else 1f
        return Offset(c.x + pv[0] * r * k, c.y - pv[1] * r * k)
    }

    /**
     * The laser pointer: a see-through sphere seen from a fixed angle (30° round, 30° above), its
     * grid and axes, the beam from its centre out of the phone's back to the inside, a dot and a roll
     * tick where it hits; Draw mode's paint ([GyroDisplayFeed.laser]). Far
     * half first, then the beam, then the near half (fainter, seen through the shell).
     */
    private fun DrawScope.drawLaser(feed: GyroDisplayFeed?, d: GyroDisplay, box: Rect, col: (GyroDisplayColor) -> Color) {
        poseOf(feed, d)
        // Turned by the phone it aims with its roll axis; a trackball faces the way picked; a game camera already aims ahead.
        when (d.turnsWith) {
            com.oleroma.gyrophone.core.model.TurnsWith.PHONE -> LaserTrace.aim(m)
            com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL -> LaserTrace.faces(m, d.laserFaces)
            else -> {}
        }
        laserAxon = d.laserAxonometric
        laserPersp = d.perspective
        // Sphere turns: everything on it is seen from the pose, so the beam stays ahead.
        laserTurned = if (d.laserSphereTurns) m else null
        val r = minOf(box.width, box.height) / 2f * 0.95f
        val c = box.center
        val fadeMs = d.laserFadeMs
        val held = feed?.ratchetHeld == true
        LaserTrace.beam(m, beam)
        val lt = feed?.laser
        if (lt != null) {
            val v = feed?.pose?.version ?: 0L
            if (v != lt.fedVersion) {
                lt.fedVersion = v
                lt.add(beam[0], beam[1], beam[2], now, d.laserDraw, held, d.laserClearOnRatchet, feed?.firing == true, d.laserFireOnly, d.laserSmoothColours, d.laserLineSmoothing)
            }
            lt.expire(now, fadeMs, d.laserLength.toFloat())
        }
        val rifle = d.laserRifle
        val crosshair = d.laserCrosshair && !rifle
        val sphere = col(GyroDisplayColor.LASER_SPHERE)
        if (!rifle && !crosshair) {
        val lit = Color(sphere.red + (1 - sphere.red) * 0.35f, sphere.green + (1 - sphere.green) * 0.35f, sphere.blue + (1 - sphere.blue) * 0.35f, sphere.alpha)
        drawCircle(Brush.radialGradient(listOf(lit, sphere), Offset(c.x - r * 0.35f, c.y - r * 0.4f), r * 1.3f), r, c)
        }
        val grid = col(GyroDisplayColor.LASER_GRID)
        val thin = Stroke(density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val (gridFront, gridBack) = LaserTrace.grid(laserAxon, laserTurned)
        // A grid point (on the unit sphere, its depth from x and y, + on the near half) in the picture, with perspective.
        fun gridK(x: Float, y: Float, near: Boolean): Float {
            if (!laserPersp) return r
            val z = kotlin.math.sqrt((1f - x * x - y * y).coerceAtLeast(0f))
            return r * LaserTrace.perspective(if (near) z else -z)
        }
        fun gridPath(lines: List<FloatArray>, near: Boolean): Path {
            path.reset()
            for (l in lines) {
                var k = gridK(l[0], l[1], near)
                path.moveTo(c.x + l[0] * k, c.y - l[1] * k)
                var i = 2
                while (i < l.size) {
                    k = gridK(l[i], l[i + 1], near)
                    path.lineTo(c.x + l[i] * k, c.y - l[i + 1] * k)
                    i += 2
                }
            }
            return path
        }
        val axisColors = arrayOf(col(GyroDisplayColor.LASER_X), col(GyroDisplayColor.LASER_Y), col(GyroDisplayColor.LASER_Z))
        // Locked (None) gimbal axes, those stopping at ±90°, and those the gyro is turning now (bit 0 yaw, 1 pitch, 2 roll).
        val locks = d.gimbalLocks
        val active = if (d.turnsWith == com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL) feed?.trackballActive() ?: 0 else 0
        val dash = PathEffect.dashPathEffect(floatArrayOf(4f * density, 3f * density))
        fun axes(front: Boolean) {
            if (!d.laserAxes) return
            for (i in 0..2) {
                // Laser axis i (X pitch, Y yaw, Z roll) as a gimbal axis.
                val g = GIMBAL_COLOR.indexOf(i)
                val locked = locks and (1 shl g) != 0
                val lit = active and (1 shl g) != 0
                // Turning with the laser: its own axis i, column i of its pose.
                val ax = m[i]
                val ay = m[3 + i]
                val az = m[6 + i]
                for (sign in intArrayOf(1, -1)) {
                    val p = laserAt(c, r, sign * ax, sign * ay, sign * az)
                    if ((pv[2] > 0f) != front) continue
                    val a = if (locked) LOCKED_AXIS else axisColors[i]
                    drawLine(
                        a.copy(alpha = a.alpha * (if (sign > 0) 1f else 0.4f) * (if (front) 0.7f else 1f)), c, p,
                        (if (lit) 3f else 1.5f) * density, StrokeCap.Round, if (locked) dash else null,
                    )
                    if (sign > 0) {
                        if (locked) padlock(p, a) else drawCircle(a, (if (lit) 3.5f else 2.5f) * density, p)
                        // Its letter just beyond the tip: P (X), Y (Y), R (Z), as the laser aims.
                        val k = 1f + 12f * density / r
                        val q = laserAt(c, r, k * ax, k * ay, k * az)
                        text.textSize = 11f * density
                        text.color = a.copy(alpha = a.alpha * (if (front) 0.8f else 1f)).toArgb()
                        drawContext.canvas.nativeCanvas.drawText(LASER_AXIS_LETTERS[i], q.x, q.y + text.textSize * 0.35f, text)
                    }
                }
            }
        }
        // The way colours (opacity applied), in LaserTrace.directionColor's order.
        for ((i, mc) in GyroDisplayColor.LASER_MOVES.withIndex()) moveColors[i] = col(mc).toArgb().toLong() and 0xFFFFFFFFL
        val reach = if (d.laserSmoothColours) LaserTrace.SMOOTH_REACH else 0
        val moveAlpha = moveColors.sumOf { it shr 24 and 0xFF } / (6f * 255f)
        // Strokes: each piece of the way between two points (long hops bent along
        // the sphere) goes into the path of its side, strength and colour: the colour of the way it
        // moves ([moveColors], blended as a normal map), or while firing a Fire colour step (from
        // [fireAhead] straight ahead to [fireBehind] turned all the way round), so a few paths draw them all.
        fun lines(ring: LaserTrace.Ring, front: Boolean, life: (Int, Long) -> Float, width: Float, glow: Boolean, fireAhead: Color, fireBehind: Color) {
            if (ring.count < 2) return
            for (p in movePaths.values) {
                p.reset()
                movePool.add(p)
            }
            movePaths.clear()
            for (p in glowPaths.values) {
                p.reset()
                movePool.add(p)
            }
            glowPaths.clear()
            val steps = d.laserColourSteps.coerceIn(4, 32)
            val paint3d = d.laserPaint3d
            // Varying width: thinner toward the rim (and with perspective bigger nearer), changing smoothly along the ribbon.
            val varyWidth = d.laserPaintWidth
            val fa = fireAhead.toArgb().toLong() and 0xFFFFFFFFL
            val fb = fireBehind.toArgb().toLong() and 0xFFFFFFFFL
            // The Fire colour [h] of the way from ahead (0) to behind (1), in [HUES] steps.
            fun fireArgb(h: Float): Long {
                val t = (h * (HUES - 1) + 0.5f).toInt().coerceIn(0, HUES - 1) / (HUES - 1f)
                var out = 0L
                for (ch in 0..3) {
                    val a = (fa shr (ch * 8) and 0xFF).toFloat()
                    val b = (fb shr (ch * 8) and 0xFF).toFloat()
                    out = out or ((a + (b - a) * t + 0.5f).toLong().coerceIn(0L, 255L) shl (ch * 8))
                }
                return out
            }
            val curve = d.laserCurve.coerceIn(0f, 1f)
            // The visible run of picture points being gathered (vx, vy), with the path key of the piece ending at each.
            var n = 0
            val wNow = width * (if (front) 0.6f else 1f)
            fun startWidth(z: Float): Float =
                if (!varyWidth) 1f else (if (laserPersp) LaserTrace.perspective(z) else 1f) * LaserTrace.paintWidth(z)
            fun pathFor(key: Int): Path = movePaths.getOrPut(key) { movePool.removeLastOrNull() ?: Path() }
            // The dense run as filled ribbons: each piece a quad whose ends sit across the line at
            // that point's width (corners mitred, so neighbours share their edge and leave no gap
            // or overlap), into its key's path, and a 3× wider one into its glow path.
            fun ribbons(m: Int) {
                var lnx = 0f
                var lny = 0f
                fun normalAt(i: Int, out: FloatArray) {
                    // The unit normal of the hop into point i and of the hop out of it, averaged (mitre, at most 2×).
                    var ax = 0f
                    var ay = 0f
                    var hops = 0
                    fun add(a: Int, b: Int) {
                        val dx = rx[b] - rx[a]
                        val dy = ry[b] - ry[a]
                        val l = kotlin.math.sqrt(dx * dx + dy * dy)
                        if (l > 1e-4f) {
                            ax += -dy / l; ay += dx / l
                            hops++
                        }
                    }
                    if (i > 0) add(i - 1, i)
                    if (i < m - 1) add(i, i + 1)
                    val l = kotlin.math.sqrt(ax * ax + ay * ay)
                    if (l < 1e-4f) {
                        out[0] = lnx; out[1] = lny
                        return
                    }
                    var nx = ax / l
                    var ny = ay / l
                    // Mitre: half the turn's cosine, so the edge stays the width off both hops.
                    val single = if (hops == 2) l / 2f else 1f
                    val k = 1f / single.coerceAtLeast(0.5f)
                    nx *= k; ny *= k
                    out[0] = nx; out[1] = ny
                    lnx = ax / l; lny = ay / l
                }
                normalAt(0, nA)
                for (i in 1 until m) {
                    normalAt(i, nB)
                    val key = rk[i]
                    if (key >= 0) {
                        for (g in 0..1) {
                            if (g == 1 && !glow) break
                            val s = if (g == 0) 0.5f else 1.5f
                            val path = if (g == 0) pathFor(key) else glowPaths.getOrPut(key) { movePool.removeLastOrNull() ?: Path() }
                            val wa = rw[i - 1] * s
                            val wb = rw[i] * s
                            path.moveTo(rx[i - 1] + nA[0] * wa, ry[i - 1] + nA[1] * wa)
                            path.lineTo(rx[i] + nB[0] * wb, ry[i] + nB[1] * wb)
                            path.lineTo(rx[i] - nB[0] * wb, ry[i] - nB[1] * wb)
                            path.lineTo(rx[i - 1] - nA[0] * wa, ry[i - 1] - nA[1] * wa)
                            path.close()
                        }
                    }
                    nA[0] = nB[0]; nA[1] = nB[1]
                }
            }
            // Draws the run: straight hops, or with [curve] its corners rounded (each corner a quadratic
            // curve split in two halves, one in each neighbouring piece's path).
            fun flush() {
                if (n >= 2) {
                    var px = vx[0]
                    var py = vy[0]
                    var pw = vw[0]
                    var m = 0
                    fun dense(x: Float, y: Float, w: Float, key: Int) {
                        if (m == rx.size) {
                            rx = rx.copyOf(m * 2); ry = ry.copyOf(m * 2); rw = rw.copyOf(m * 2); rk = rk.copyOf(m * 2)
                        }
                        rx[m] = x; ry[m] = y; rw[m] = w; rk[m] = key
                        m++
                    }
                    dense(px, py, pw, -1)
                    // The centre line, piece by piece, as dense ribbon points.
                    fun line(key: Int, x: Float, y: Float, w: Float) {
                        dense(x, y, w, key)
                        px = x; py = y; pw = w
                    }
                    fun quad(key: Int, cx: Float, cy: Float, x: Float, y: Float, w: Float) {
                        val x0 = px
                        val y0 = py
                        val w0 = pw
                        for (j in 1..RIBBON_QUAD_STEPS) {
                            val t = j / RIBBON_QUAD_STEPS.toFloat()
                            val u = 1f - t
                            dense(u * u * x0 + 2f * u * t * cx + t * t * x, u * u * y0 + 2f * u * t * cy + t * t * y, w0 + (w - w0) * t, key)
                        }
                        px = x; py = y; pw = w
                    }
                    if (curve <= 0f || n == 2) {
                        for (k in 1 until n) line(vk[k], vx[k], vy[k], vw[k])
                    } else {
                        var mx = (vx[0] + vx[1]) / 2f
                        var my = (vy[0] + vy[1]) / 2f
                        var mw = (vw[0] + vw[1]) / 2f
                        line(vk[1], mx, my, mw)
                        for (k in 1..n - 2) {
                            val cx = vx[k]
                            val cy = vy[k]
                            val cw = vw[k]
                            val nx = (cx + vx[k + 1]) / 2f
                            val ny = (cy + vy[k + 1]) / 2f
                            val nw = (cw + vw[k + 1]) / 2f
                            val ax = cx + (mx - cx) * curve
                            val ay = cy + (my - cy) * curve
                            val bx = cx + (nx - cx) * curve
                            val by = cy + (ny - cy) * curve
                            line(vk[k], ax, ay, cw + (mw - cw) * curve)
                            val cqx = (ax + 2f * cx + bx) / 4f
                            val cqy = (ay + 2f * cy + by) / 4f
                            quad(vk[k], (ax + cx) / 2f, (ay + cy) / 2f, cqx, cqy, cw)
                            quad(vk[k + 1], (cx + bx) / 2f, (cy + by) / 2f, bx, by, cw + (nw - cw) * curve)
                            line(vk[k + 1], nx, ny, nw)
                            mx = nx; my = ny; mw = nw
                        }
                        line(vk[n - 1], vx[n - 1], vy[n - 1], vw[n - 1])
                    }
                    if (m >= 2) ribbons(m)
                }
                n = 0
            }
            fun vertex(x: Float, y: Float, key: Int, w: Float = wNow) {
                if (n == vx.size) {
                    vx = vx.copyOf(n * 2); vy = vy.copyOf(n * 2); vk = vk.copyOf(n * 2); vw = vw.copyOf(n * 2)
                }
                vx[n] = x; vy[n] = y; vk[n] = key; vw[n] = w
                n++
            }
            for (i in 1 until ring.count) {
                if (ring.gap(i)) {
                    flush()
                    continue
                }
                ring.point(i - 1, la)
                val t = ring.point(i, lb)
                val k = life(i, t)
                if (k <= 0f) {
                    flush()
                    continue
                }
                val level = (kotlin.math.ceil(k * LEVELS).toInt()).coerceIn(1, LEVELS)
                // Painted while firing: a Fire colour (by how far round it is); else the colour of the way it moves.
                val firing = ring.fire(i)
                var base = 0L
                if (!firing) {
                    ring.direction(i, reach, way)
                    base = LaserTrace.directionColor(way, moveColors)
                }
                val dot = (la[0] * lb[0] + la[1] * lb[1] + la[2] * lb[2]).coerceIn(-1f, 1f)
                val sub = (acos(dot) / STROKE_STEP_RAD).toInt().coerceIn(1, 32)
                // The point before (the last sub-point), whichever side it is on.
                val p0 = laserAt(c, r, la[0], la[1], la[2])
                var lastX = p0.x
                var lastY = p0.y
                var lastW = wNow * maxOf(0.15f, startWidth(pv[2]))
                var lastOurs = (pv[2] > 0f) == front
                if (n == 0 && lastOurs) vertex(lastX, lastY, -1, lastW)
                for (j in 1..sub) {
                    val f = j / sub.toFloat()
                    var x = la[0] + (lb[0] - la[0]) * f
                    var y = la[1] + (lb[1] - la[1]) * f
                    var z = la[2] + (lb[2] - la[2]) * f
                    val l = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(1e-4f)
                    x /= l; y /= l; z /= l
                    val pt = laserAt(c, r, x, y, z)
                    val ours = (pv[2] > 0f) == front
                    // Crossing the rim: the piece over it is drawn on both sides, so the line has no gap there.
                    if (!ours && !lastOurs) {
                        lastX = pt.x; lastY = pt.y; lastW = wNow * maxOf(0.15f, startWidth(pv[2]))
                        continue
                    }
                    if (n == 0) vertex(lastX, lastY, -1, lastW)
                    val argb = if (firing) fireArgb(LaserTrace.behind(z)) else base
                    // 3D paint: lit by the sphere's light. Varying width: thinner toward the rim; with perspective, bigger nearer.
                    val light = if (paint3d) LaserTrace.paintLight(pv[0], pv[1], pv[2], d.laserLighting) else 1f
                    val wf = if (!varyWidth) 1f
                    else (if (laserPersp) LaserTrace.perspective(pv[2]) else 1f) * LaserTrace.paintWidth(pv[2])
                    // Colour in [steps] shades a channel: the piece's path.
                    fun q(shift: Int) = (((argb shr shift and 0xFF) / 255f) * light * (steps - 1) + 0.5f).toInt().coerceIn(0, steps - 1)
                    vertex(pt.x, pt.y, MOVE_KEYS + ((level - 1) shl 18 or (q(16) shl 10) or (q(8) shl 5) or q(0)), wNow * maxOf(0.15f, wf))
                    lastX = pt.x; lastY = pt.y; lastW = wNow * maxOf(0.15f, wf)
                    lastOurs = ours
                    if (!ours) flush()
                }
            }
            flush()
            val side = if (front) 0.5f else 1f
            fun shaded(color: Color, k: Float): Color {
                val shade = 0.35f + 0.65f * k
                return Color(color.red * shade, color.green * shade, color.blue * shade, color.alpha)
            }
            fun moveColor(key: Int): Color {
                val q = key - MOVE_KEYS
                fun ch(shift: Int) = (q shr shift and 31) / (steps - 1f)
                return Color(ch(10).coerceAtMost(1f), ch(5).coerceAtMost(1f), ch(0).coerceAtMost(1f), moveAlpha)
            }
            // Oldest first (the age step is the key's top), so newer paint lies on top.
            for ((key, path) in movePaths.entries.sortedBy { it.key }) {
                val q = key - MOVE_KEYS
                val level = (q shr 18) + 1
                val k = level / LEVELS.toFloat()
                val fade = (0.35f + 0.65f * k) * side
                val color = shaded(moveColor(key), k)
                glowPaths[key]?.let { drawPath(it, color.copy(alpha = color.alpha * fade * 0.22f)) }
                drawPath(path, color.copy(alpha = color.alpha * fade))
            }
        }
        val brushPx = maxOf(1.5f * density, r * Math.toRadians(d.laserBrush.coerceIn(1, 20).toDouble()).toFloat() * 0.6f)
        fun paint(front: Boolean) {
            val s = lt?.strokes ?: return
            val n = s.count
            lines(
                s, front, { i, t -> LaserTrace.strokeLife(i, n, t, now, fadeMs, s.behindNewest(i), d.laserLength.toFloat()) }, brushPx, true,
                col(GyroDisplayColor.LASER_FIRE), col(GyroDisplayColor.LASER_FIRE_BEHIND),
            )
        }
        val hit = laserAt(c, r, beam[0], beam[1], beam[2])
        val hitFront = pv[2] > 0f
        val hitDepth = pv[2]
        val beamCol = col(GyroDisplayColor.LASER_BEAM).let { if (held) it.copy(alpha = it.alpha * 0.35f) else it }
        fun spot() {
            val dotCol = col(GyroDisplayColor.LASER_DOT).let { if (held) it.copy(alpha = it.alpha * 0.35f) else it }
            // Foreshortened toward the rim (seen edge-on there).
            val size = r * 0.06f * (0.5f + 0.5f * kotlin.math.abs(hitDepth))
            drawCircle(beamCol.copy(alpha = beamCol.alpha * 0.35f), size * 2f, hit)
            drawCircle(dotCol, size, hit)
            if (LaserTrace.rollTick(m, beam, tick)) {
                val k = 0.22f
                val tip = laserAt(c, r, beam[0] + tick[0] * k, beam[1] + tick[1] * k, beam[2] + tick[2] * k)
                drawLine(dotCol, hit, tip, 2f * density, StrokeCap.Round)
            }
        }
        if (rifle) {
            // The rifle look: no sphere or beam; the paint still lands where it aims.
            axes(false)
            paint(false)
            val body = col(GyroDisplayColor.LASER_RIFLE)
            for (f in LaserTrace.rifle(m, laserAxon, laserTurned, laserPersp)) {
                path.reset()
                for (i in 0 until 4) {
                    val x = c.x + f.xy[i * 2] * r
                    val y = c.y - f.xy[i * 2 + 1] * r
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                val k = f.light
                drawPath(path, Color(body.red * k, body.green * k, body.blue * k, body.alpha))
                drawPath(path, Color(body.red * 0.5f, body.green * 0.5f, body.blue * 0.5f, body.alpha), style = Stroke(density * 0.75f, join = StrokeJoin.Round))
            }
            if (feed?.firing == true) muzzleFlash(c, r, col(GyroDisplayColor.LASER_FIRE))
            paint(true)
            axes(true)
            return
        }
        if (crosshair) {
            // The crosshair look: no sphere or beam; a crosshair lying on the sphere where it aims.
            if (d.laserGrid) drawPath(gridPath(gridBack, false), grid, style = thin)
            axes(false)
            paint(false)
            drawCrosshair(d, c, r, held, Color(LaserTrace.directionColor(lt?.moving ?: way.also { it.fill(0f) }, moveColors).toInt()), col)
            paint(true)
            if (d.laserGrid) drawPath(gridPath(gridFront, true), grid.copy(alpha = grid.alpha * 0.6f), style = thin)
            axes(true)
            return
        }
        // The Sphere look's gimbal rings, turning with the laser's axes, each round its own: their far halves
        // now, near halves over the beam.
        val gimbal = if (d.laserGimbal) Array(3) { LaserTrace.gimbalRing(it, m) } else null
        fun rings(front: Boolean) {
            if (gimbal == null) return
            for (i in 0..2) {
                val pts = gimbal[i]
                val a = axisColors[GIMBAL_COLOR[i]]
                val locked = locks and (1 shl i) != 0
                val lit = active and (1 shl i) != 0
                val color = a.copy(alpha = a.alpha * (if (locked) 0.25f else if (lit) 1f else 0.75f) * (if (front) 1f else 0.45f))
                path.reset()
                var down = false
                var j = 0
                while (j < pts.size) {
                    val p = laserAt(c, r, pts[j], pts[j + 1], pts[j + 2])
                    if ((pv[2] > 0f) == front) {
                        if (down) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                        down = true
                    } else down = false
                    j += 3
                }
                val w = (if (lit) 3.5f else if (front) 2.5f else 1.5f) * density
                drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = if (locked) dash else null))
            }
        }
        if (d.laserGrid) drawPath(gridPath(gridBack, false), grid, style = thin)
        rings(false)
        axes(false)
        paint(false)
        if (!hitFront) spot()
        drawLine(beamCol, c, hit, 2.5f * density, StrokeCap.Round)
        drawCircle(beamCol, 3f * density, c)
        paint(true)
        if (d.laserGrid) drawPath(gridPath(gridFront, true), grid.copy(alpha = grid.alpha * 0.6f), style = thin)
        rings(true)
        axes(true)
        if (hitFront) spot()
        if (d.laserRimGlow) {
            // A soft bright edge round the sphere's outline, inside and a little out.
            val rim = col(GyroDisplayColor.LASER_RIM)
            val out = r * 1.08f
            drawCircle(
                Brush.radialGradient(0.78f to Color.Transparent, 0.925f to rim, 1f to Color.Transparent, center = c, radius = out),
                out, c,
            )
        }
        if (d.laserGloss) {
            // The shine where the light (upper left) reflects toward the viewer: halfway between them.
            val gloss = col(GyroDisplayColor.LASER_GLOSS)
            val at = Offset(c.x - 0.18f * r, c.y - 0.21f * r)
            val gr = r * 0.24f
            drawCircle(Brush.radialGradient(listOf(gloss, gloss.copy(alpha = 0f)), center = at, radius = gr), gr, at)
        }
        drawCircle(grid, r, c, style = Stroke(1.5f * density))
    }

    /**
     * The world's axes in the axonometric view (pitch right green, roll up blue, yaw toward you red), [len] long
     * from [c]: the halves behind the middle ([front] false) or in front of it.
     */
    private fun DrawScope.worldAxes(c: Offset, len: Float, front: Boolean, op: Float, persp: Boolean = false) {
        for (i in 0..2) {
            for (sign in intArrayOf(1, -1)) {
                LaserTrace.view(if (i == 0) sign.toFloat() else 0f, if (i == 1) sign.toFloat() else 0f, if (i == 2) sign.toFloat() else 0f, pv)
                if ((pv[2] > 0f) != front) continue
                val a = WORLD_AXES[i].copy(alpha = op * (if (sign > 0) 0.9f else 0.35f))
                val k = if (persp) len * LaserTrace.perspective(pv[2]) else len
                val p = Offset(c.x + pv[0] * k, c.y - pv[1] * k)
                drawLine(a, c, p, 1.5f * density, StrokeCap.Round)
                if (sign > 0) drawCircle(a, 2.5f * density, p)
            }
        }
    }

    /** [m] (and [raw]) = [feed]'s pose as shown ([GyroDisplayFeed.shown]: from the resting grip, scaled), straight without one. */
    @Suppress("UNUSED_PARAMETER")
    private fun poseOf(feed: GyroDisplayFeed?, d: GyroDisplay) {
        if (feed == null) m.fill(0f).also { m[0] = 1f; m[4] = 1f; m[8] = 1f } else feed.shown(m)
        // Axonometric: seen from 30° round and 30° above, as the laser pointer is.
        if (d.axonometric && (d.kind == GyroDisplayKind.PHONE || d.kind == GyroDisplayKind.BALL)) LaserTrace.axonometric(m)
        m.copyInto(raw)
    }

    /** The heading of [raw] in degrees (right +), as [com.oleroma.gyrophone.core.engine.GyroPose.heading]. */
    private fun headingDeg(): Float =
        if (raw[0] * raw[0] + raw[6] * raw[6] > 0.04f) Math.toDegrees(atan2(raw[6], raw[0]).toDouble()).toFloat() else 0f

    private fun DrawScope.drawDisk(feed: GyroDisplayFeed?, d: GyroDisplay, box: Rect, col: (GyroDisplayColor) -> Color) {
        poseOf(feed, d)
        val r = minOf(box.width, box.height) / 2f * 0.95f
        val c = box.center
        drawCircle(col(GyroDisplayColor.DISK_FACE), r, c)
        drawCircle(col(GyroDisplayColor.DISK_RIM), r, c, style = Stroke(1.5f * density))
        // The card spins against the heading, as a compass card does: the 12 o'clock mark stays put in the world.
        rotate(-headingDeg(), c) {
            val marks = col(GyroDisplayColor.DISK_MARKS)
            for (i in 0 until 12) {
                val a = Math.toRadians(i * 30.0 - 90.0)
                val inner = if (i % 3 == 0) 0.74f else 0.84f
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                drawLine(
                    if (i == 0) col(GyroDisplayColor.DISK_NORTH) else marks,
                    Offset(c.x + ca * r * inner, c.y + sa * r * inner), Offset(c.x + ca * r * 0.95f, c.y + sa * r * 0.95f),
                    (if (i == 0) 3.5f else if (i % 3 == 0) 2.5f else 1.5f) * density, StrokeCap.Round,
                )
            }
            if (d.crosshair) {
                val cross = col(GyroDisplayColor.DISK_CROSS)
                drawLine(cross, Offset(c.x - r * 0.66f, c.y), Offset(c.x + r * 0.66f, c.y), density)
                drawLine(cross, Offset(c.x, c.y - r * 0.66f), Offset(c.x, c.y + r * 0.66f), density)
                drawCircle(cross, r * 0.33f, c, style = Stroke(density))
            }
        }
        // The dot: where the screen points, off centre by the tilt, on the rim at the range.
        val nx = m[2]
        val ny = m[5]
        val flat = hypot(nx, ny)
        val tilt = Math.toDegrees(acos(m[8].coerceIn(-1f, 1f).toDouble())).toFloat()
        val off = (tilt / d.diskRange.coerceAtLeast(5)).coerceAtMost(1f) * r * 0.86f
        val p = if (flat > 1e-4f) Offset(c.x + nx / flat * off, c.y - ny / flat * off) else c
        val dot = col(GyroDisplayColor.DISK_DOT)
        drawCircle(dot, r * 0.09f, p)
        drawCircle(Color.Black.copy(alpha = 0.4f * dot.alpha), r * 0.09f, p, style = Stroke(density))
    }

    private fun DrawScope.drawBall(feed: GyroDisplayFeed?, d: GyroDisplay, box: Rect, live: Int, col: (GyroDisplayColor) -> Color) {
        poseOf(feed, d)
        ballPersp = d.perspective
        // Axonometric: the world's axes poke out of the shape, so it is shorter.
        val r = minOf(box.width, box.height) / 2f * (if (d.axonometric) 0.78f else 0.95f)
        val c = box.center
        if (d.axonometric) worldAxes(c, r * 1.22f, false, d.opacity.coerceIn(0.05f, 1f), d.perspective)
        val lines = col(GyroDisplayColor.BALL_LINES)
        val faint = lines.copy(alpha = lines.alpha * HIDDEN_ALPHA)
        val body = col(GyroDisplayColor.BALL_BODY)
        val stroke = Stroke(density, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val shape = d.ballShape
        // Front lines (and the silhouette) go into one path, back ones into another, each stroked
        // once: where lines meet or cross, their colour does not add up.
        path.reset()
        path2.reset()
        pen = path
        penBack = if (d.hiddenEdges) path2 else null
        val mesh = Meshes.of(shape, d.subdivision, d.smooth)
        when {
            shape == BallShape.GIMBAL -> drawGimbal(c, r, d, live, col)

            mesh == null -> {
                // The globe: latitudes every 30° round the phone's up axis, meridians every 30°.
                val steps = if (d.smooth) SPHERE_STEPS else POLY_STEPS
                for (lat in -60..60 step 30) {
                    if (lat == 0 && d.equator) continue
                    val a = Math.toRadians(lat.toDouble())
                    sphereCircle(c, r, sin(a).toFloat(), 0f, 1f, 0f, cos(a).toFloat(), 0f, 0f, 1f, 1f, 0f, 0f, steps)
                }
                for (lon in 0 until 180 step 30) {
                    val a = Math.toRadians(lon.toDouble())
                    sphereCircle(c, r, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, sin(a).toFloat(), 0f, cos(a).toFloat(), steps)
                }
                path.addOval(Rect(c, r))
                drawPath(path2, faint, style = stroke)
                if (d.fillFaces) drawBody(c, r, body)
                drawPath(path, lines, style = stroke)
                if (d.equator) {
                    path2.reset()
                    pen = path2
                    penBack = null
                    sphereCircle(c, r, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, steps)
                    pen = path
                    drawPath(path2, col(GyroDisplayColor.BALL_EQUATOR), style = Stroke(2.5f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }

            d.smooth && mesh.onSphere -> {
                // Each edge bent onto the ball, cut exactly where it crosses the silhouette.
                val v = mesh.vertices
                val e = mesh.edges
                for (k in 0 until e.size / 2) {
                    val ia = e[k * 2] * 3
                    val ib = e[k * 2 + 1] * 3
                    val n = mesh.edgePieces[k]
                    penUp()
                    for (j in 0..n) {
                        val t = j / n.toFloat()
                        val px = v[ia] + (v[ib] - v[ia]) * t
                        val py = v[ia + 1] + (v[ib + 1] - v[ia + 1]) * t
                        val pz = v[ia + 2] + (v[ib + 2] - v[ia + 2]) * t
                        val l = kotlin.math.sqrt(px * px + py * py + pz * pz)
                        penTo(c, r, px / l, py / l, pz / l, sphere = true)
                    }
                }
                path.addOval(Rect(c, r))
                drawPath(path2, faint, style = stroke)
                if (d.fillFaces) drawBody(c, r, body)
                drawPath(path, lines, style = stroke)
            }

            else -> {
                mesh.project(m, if (ballPersp) LaserTrace.CAMERA_DISTANCE else 0f)
                val e = mesh.edges
                for (k in 0 until e.size / 2) {
                    val ia = e[k * 2]
                    val ib = e[k * 2 + 1]
                    val front = mesh.edgeFront(k)
                    val into = if (front) path else if (d.hiddenEdges) path2 else continue
                    into.moveTo(c.x + mesh.tx[ia] * r, c.y - mesh.ty[ia] * r)
                    into.lineTo(c.x + mesh.tx[ib] * r, c.y - mesh.ty[ib] * r)
                }
                drawPath(path2, faint, style = stroke)
                if (!d.fillFaces) {
                    drawPath(path, lines, style = stroke)
                } else if (mesh.convex) {
                    for (f in mesh.faces.indices) if (mesh.faceFront[f]) drawFace(mesh, f, c, r, body)
                    drawPath(path, lines, style = stroke)
                } else {
                    // Far to near, each face with its own outline, so near ones cover far ones.
                    mesh.sortFarToNear()
                    for (f in mesh.order) {
                        if (!mesh.faceFront[f]) continue
                        drawFace(mesh, f, c, r, body)
                        drawPath(facePath, lines, style = stroke)
                    }
                }
            }
        }
        penBack = null
        if (d.screenMark) sphereDot(c, r, 0f, 0f, 1f, col(GyroDisplayColor.BALL_MARK))
        if (d.topMark) sphereDot(c, r, 0f, 1f, 0f, col(GyroDisplayColor.BALL_POLE))
        if (d.axonometric) worldAxes(c, r * 1.22f, true, d.opacity.coerceIn(0.05f, 1f), d.perspective)
    }

    /** A smooth ball's fill: one disk, lit from the top left. */
    private fun DrawScope.drawBody(c: Offset, r: Float, body: Color) {
        val lit = Color(body.red + (1 - body.red) * 0.35f, body.green + (1 - body.green) * 0.35f, body.blue + (1 - body.blue) * 0.35f, body.alpha)
        drawCircle(Brush.radialGradient(listOf(lit, body), Offset(c.x - r * 0.35f, c.y - r * 0.4f), r * 1.3f), r, c)
    }

    /** Face [f] of [mesh] (projected) filled with [body], darker the more it turns away; its outline left in [facePath]. */
    private fun DrawScope.drawFace(mesh: Mesh, f: Int, c: Offset, r: Float, body: Color) {
        val face = mesh.faces[f]
        facePath.reset()
        facePath.moveTo(c.x + mesh.tx[face[0]] * r, c.y - mesh.ty[face[0]] * r)
        for (i in 1 until face.size) facePath.lineTo(c.x + mesh.tx[face[i]] * r, c.y - mesh.ty[face[i]] * r)
        facePath.close()
        val k = 0.45f + 0.55f * mesh.faceShade[f]
        drawPath(facePath, Color(body.red * k, body.green * k, body.blue * k, body.alpha))
    }

    /** Three rings, one per axis (yaw round up, pitch round right, roll round the screen), lit while that axis moves the mouse. */
    private fun DrawScope.drawGimbal(c: Offset, r: Float, d: GyroDisplay, live: Int, col: (GyroDisplayColor) -> Color) {
        val steps = if (d.smooth) SPHERE_STEPS else POLY_STEPS
        val colors = arrayOf(col(GyroDisplayColor.BALL_GIMBAL_YAW), col(GyroDisplayColor.BALL_GIMBAL_PITCH), col(GyroDisplayColor.BALL_GIMBAL_ROLL))
        val width = 2.5f * density
        // Back halves first (faint, under everything), then the front halves.
        for (pass in 0..1) {
            for ((i, a) in RING_AXES.withIndex()) {
                val on = live and MappingEngine.gyroBit(a) != 0
                val color = colors[i].copy(alpha = colors[i].alpha * (if (on) 1f else 0.3f))
                val rho = GIMBAL_RADII[i]
                path.reset()
                path2.reset()
                pen = path
                penBack = path2
                penUp()
                for (j in 0..steps) {
                    val t = j * (2 * Math.PI / steps)
                    val ct = (rho * cos(t)).toFloat()
                    val st = (rho * sin(t)).toFloat()
                    when (i) {
                        0 -> penTo(c, r, ct, 0f, st, sphere = false)
                        1 -> penTo(c, r, 0f, ct, st, sphere = false)
                        else -> penTo(c, r, ct, st, 0f, sphere = false)
                    }
                }
                if (pass == 0) {
                    if (d.hiddenEdges) drawPath(path2, color.copy(alpha = color.alpha * HIDDEN_ALPHA), style = Stroke(width, cap = StrokeCap.Round))
                } else drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        path.reset()
        pen = path
    }

    /**
     * Adds to [pen] (and the back to [penBack]) a circle on the unit ball [h] along ([ax], [ay], [az]), radius [rho], spanned
     * by ([ux], [uy], [uz]) and ([vx], [vy], [vz]) in the phone's frame, in [steps] pieces, turned by [m], ball radius [r] at [c].
     */
    private fun sphereCircle(
        c: Offset, r: Float, h: Float, ax: Float, ay: Float, az: Float, rho: Float,
        ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float, steps: Int,
    ) {
        penUp()
        for (i in 0..steps) {
            val t = i * (2 * Math.PI / steps)
            val ct = cos(t).toFloat()
            val st = sin(t).toFloat()
            penTo(c, r, h * ax + rho * (ct * ux + st * vx), h * ay + rho * (ct * uy + st * vy), h * az + rho * (ct * uz + st * vz), sphere = true)
        }
    }

    /** The pen's last point in the view frame, or none since [penUp]. */
    private var penX = 0f
    private var penY = 0f
    private var penZ = 0f
    private var penDown = false

    /** Where [penTo] puts the parts behind (z < 0), or null to leave them out. */
    private var penBack: Path? = null

    private fun penUp() {
        penDown = false
    }

    /**
     * Adds the line to the phone-frame point ([px], [py], [pz]), turned by [m], scale [r] at [c]:
     * the front part to [pen], the back part to [penBack], cut exactly where it crosses z = 0, which
     * on the unit ball ([sphere]) is pushed out onto the silhouette so no line stops short of it.
     */
    private fun penTo(c: Offset, r: Float, px: Float, py: Float, pz: Float, sphere: Boolean) {
        val x = m[0] * px + m[1] * py + m[2] * pz
        val y = m[3] * px + m[4] * py + m[5] * pz
        val z = m[6] * px + m[7] * py + m[8] * pz
        val pk = if (ballPersp) r * LaserTrace.perspective(z) else r
        val sx = c.x + x * pk
        val sy = c.y - y * pk
        val back = penBack
        if (!penDown) {
            if (z >= 0f) pen.moveTo(sx, sy) else back?.moveTo(sx, sy)
        } else {
            val wasFront = penZ >= 0f
            val front = z >= 0f
            if (wasFront == front) {
                if (front) pen.lineTo(sx, sy) else back?.lineTo(sx, sy)
            } else {
                val t = penZ / (penZ - z)
                var rx = penX + (x - penX) * t
                var ry = penY + (y - penY) * t
                if (sphere) {
                    val l = hypot(rx, ry)
                    if (l > 0f) { rx /= l; ry /= l }
                }
                val cx = c.x + rx * r
                val cy = c.y - ry * r
                if (wasFront) {
                    pen.lineTo(cx, cy)
                    back?.moveTo(cx, cy); back?.lineTo(sx, sy)
                } else {
                    back?.lineTo(cx, cy)
                    pen.moveTo(cx, cy); pen.lineTo(sx, sy)
                }
            }
        }
        penX = x; penY = y; penZ = z
        penDown = true
    }

    /** A mark on the ball's surface at the phone-frame direction ([px], [py], [pz]), shown while it faces the player. */
    private fun DrawScope.sphereDot(c: Offset, r: Float, px: Float, py: Float, pz: Float, color: Color) {
        val z = m[6] * px + m[7] * py + m[8] * pz
        if (z <= 0f) return
        val pk = if (ballPersp) r * LaserTrace.perspective(z) else r
        val x = c.x + (m[0] * px + m[1] * py + m[2] * pz) * pk
        val y = c.y - (m[3] * px + m[4] * py + m[5] * pz) * pk
        // Foreshortened toward the rim.
        drawCircle(color, r * 0.1f * (0.4f + 0.6f * z), Offset(x, y))
    }

    private fun DrawScope.drawRing(box: Rect, live: Int, letters: Color, colors: Array<Color>) {
        val r = minOf(box.width, box.height) / 2f * 0.8f
        val c = box.center
        val w = r * 0.28f
        val tl = Offset(c.x - r, c.y - r)
        for ((i, a) in RING_AXES.withIndex()) {
            val on = live and MappingEngine.gyroBit(a) != 0
            drawArc(
                colors[i].copy(alpha = colors[i].alpha * (if (on) 1f else 0.18f)),
                startAngle = -90f - 55f + i * 120f, sweepAngle = 110f, useCenter = false,
                topLeft = tl, size = Size(r * 2, r * 2), style = Stroke(w, cap = StrokeCap.Round),
            )
            // Each arc's letter: Y, P, R.
            val mid = Math.toRadians((-90.0 + i * 120.0))
            text.textSize = r * 0.55f
            text.color = letters.copy(alpha = letters.alpha * (if (on) 1f else 0.35f)).toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                RING_LETTERS[i], c.x + (r * 0.45f * cos(mid)).toFloat(),
                c.y + (r * 0.45f * sin(mid)).toFloat() + text.textSize * 0.35f, text,
            )
        }
    }

    private fun DrawScope.drawGraph(feed: GyroDisplayFeed?, d: GyroDisplay, box: Rect, col: (GyroDisplayColor) -> Color) {
        val radius = CornerRadius(8 * density)
        drawRoundRect(col(GyroDisplayColor.GRAPH_BACK), box.topLeft, box.size, radius)
        val mid = box.center.y
        drawLine(Color.White.copy(alpha = 0.2f * d.opacity), Offset(box.left, mid), Offset(box.right, mid), density)
        val trace = feed?.trace ?: return
        val want = (d.graphSeconds * trace.rateHz).coerceIn(2, traceYaw.size)
        val n = trace.read(want, traceYaw, tracePitch, traceRoll, traceHeld)
        if (n < 2) return
        // The newest bucket sits at the right edge; a part-full window starts mid-way.
        val step = box.width / (want - 1)
        val x0 = box.right - (n - 1) * step
        val held = col(GyroDisplayColor.GRAPH_HELD)
        var j = 0
        while (j < n) {
            if (!traceHeld[j]) { j++; continue }
            val s = j
            while (j < n && traceHeld[j]) j++
            drawRect(held, Offset(x0 + s * step, box.top), Size(maxOf(step, (j - s) * step), box.height))
        }
        val range = d.graphRange.coerceAtLeast(30).toFloat()
        val half = box.height / 2 * 0.92f
        val lines = arrayOf(GyroDisplayColor.GRAPH_YAW, GyroDisplayColor.GRAPH_PITCH, GyroDisplayColor.GRAPH_ROLL)
        for ((i, data) in arrayOf(traceYaw, tracePitch, traceRoll).withIndex()) {
            path.reset()
            for (k in 0 until n) {
                val y = mid - (data[k] / range).coerceIn(-1f, 1f) * half
                if (k == 0) path.moveTo(x0, y) else path.lineTo(x0 + k * step, y)
            }
            drawPath(path, col(lines[i]), style = Stroke(1.6f * density, join = StrokeJoin.Round))
        }
    }

    private fun DrawScope.drawHorizon(feed: GyroDisplayFeed?, d: GyroDisplay, box: Rect, col: (GyroDisplayColor) -> Color) {
        poseOf(feed, d)
        // The tape on top, the dial under it: fit both in the box.
        val r = minOf(box.width / 2f, box.height / (2f + TAPE + TAPE_GAP)) * 0.97f
        val tape = r * TAPE
        val gap = r * TAPE_GAP
        val c = Offset(box.center.x, box.center.y + (tape + gap) / 2)
        val roll = horizonRollDeg()
        val pitch = pitchDeg()
        val k = r / 40f // px per degree of pitch
        path.reset()
        path.addOval(Rect(c, r))
        val line = col(GyroDisplayColor.HORIZON_LINE)
        val ladder = col(GyroDisplayColor.HORIZON_LADDER)
        clipPath(path) {
            rotate(roll, c) {
                val hy = c.y + pitch * k
                drawRect(col(GyroDisplayColor.HORIZON_SKY), Offset(c.x - 2 * r, hy - 4 * r), Size(4 * r, 4 * r))
                drawRect(col(GyroDisplayColor.HORIZON_GROUND), Offset(c.x - 2 * r, hy), Size(4 * r, 4 * r))
                drawLine(line, Offset(c.x - 2 * r, hy), Offset(c.x + 2 * r, hy), 1.5f * density)
                // Pitch ladder every 10°.
                var a = -60
                while (a <= 60) {
                    if (a != 0) {
                        val y = hy - a * k
                        val w = if (a % 20 == 0) r * 0.4f else r * 0.22f
                        drawLine(ladder, Offset(c.x - w, y), Offset(c.x + w, y), density)
                    }
                    a += 10
                }
            }
        }
        drawCircle(col(GyroDisplayColor.HORIZON_RIM), r, c, style = Stroke(1.5f * density))
        // The fixed aircraft symbol.
        val sym = col(GyroDisplayColor.HORIZON_AIRCRAFT)
        drawLine(sym, Offset(c.x - r * 0.55f, c.y), Offset(c.x - r * 0.18f, c.y), 2.5f * density, StrokeCap.Round)
        drawLine(sym, Offset(c.x + r * 0.18f, c.y), Offset(c.x + r * 0.55f, c.y), 2.5f * density, StrokeCap.Round)
        drawCircle(sym, 2.5f * density, c)
        // Heading tape: ticks every 10°, numbers every 30°, the heading under the middle mark.
        val tTop = c.y - r - gap - tape
        val tRect = Rect(c.x - r, tTop, c.x + r, tTop + tape)
        drawRoundRect(col(GyroDisplayColor.HORIZON_TAPE), tRect.topLeft, tRect.size, CornerRadius(4 * density))
        val heading = (headingDeg() + 360f) % 360f
        val pxPerDeg = r / 45f
        val numbers = col(GyroDisplayColor.HORIZON_TEXT)
        text.textSize = tape * 0.48f
        text.color = numbers.toArgb()
        clipRect(tRect.left, tRect.top, tRect.right, tRect.bottom) {
            var deg = (heading / 10f).roundToInt() * 10 - 50
            while (deg <= heading + 50) {
                val x = c.x + (deg - heading) * pxPerDeg
                val big = deg % 30 == 0
                drawLine(
                    numbers.copy(alpha = numbers.alpha * 0.8f),
                    Offset(x, tRect.bottom), Offset(x, tRect.bottom - tape * (if (big) 0.35f else 0.2f)), density,
                )
                if (big) {
                    drawContext.canvas.nativeCanvas.drawText("${((deg % 360) + 360) % 360}", x, tRect.top + tape * 0.5f, text)
                }
                deg += 10
            }
        }
        drawLine(sym, Offset(c.x, tRect.top), Offset(c.x, tRect.bottom), 2 * density)
    }

    fun drawEdge(scope: DrawScope, runtime: GamepadRuntime, v: TouchVisuals) = with(scope) {
        val op = v.gyroOpacity.coerceIn(0.05f, 1f)
        val live = runtime.gyroLiveAxes
        val sx: Float
        val sy: Float
        val rot: Float
        if (v.gyroEdgeTilt) {
            runtime.worldPose.matrix(raw)
            level(v.gyroLevelDeg)
            // Tilt as if turning: rolling right reads as a turn right, pitching up as up.
            sx = -horizonRollDeg()
            sy = pitchDeg()
            rot = 0f
        } else {
            val s = runtime.gyroSway
            sx = s.x; sy = s.y; rot = s.rot
        }
        val dir = if (v.gyroEdgeInvert) -1f else 1f
        val k = EDGE_PX_PER_DEG * density * v.gyroEdgeStrength * dir
        val limit = EDGE_MAX_DP * density
        // Tint: white with every axis live; else the live axes' colours mixed, dimmer the fewer.
        var color = Color.White
        var dim = 1f
        if (v.gyroEdgeRatchetTint && live != MappingEngine.ALL_GYRO) {
            var r = 0f; var g = 0f; var b = 0f; var n = 0
            for ((i, a) in RING_AXES.withIndex()) {
                if (live and MappingEngine.gyroBit(a) == 0) continue
                r += AXIS_COLORS[i].red; g += AXIS_COLORS[i].green; b += AXIS_COLORS[i].blue; n++
            }
            color = if (n == 0) Color.Gray else Color(r / n, g / n, b / n)
            dim = 0.3f + 0.7f * n / 3f
        }
        val layers = v.gyroEdgeLayers.coerceIn(1, 5)
        val width = v.gyroEdgeWidthDp.coerceIn(0.5f, 8f) * density
        for (i in 0 until layers) {
            val depth = (i + 1f) / layers
            val inset = (v.gyroEdgeInsetDp + i * EDGE_GAP_DP) * density
            if (inset * 2 >= minOf(size.width, size.height)) break
            val dx = (-sx * k * depth).coerceIn(-limit, limit)
            val dy = (sy * k * depth).coerceIn(-limit, limit)
            val radius = (EDGE_CORNER_DP * density - inset * 0.3f).coerceAtLeast(6 * density)
            val alpha = op * dim * (1f - 0.55f * i / layers)
            withTransform({
                translate(dx, dy)
                rotate(-rot * 0.5f * depth * dir, center)
            }) {
                drawRoundRect(
                    color.copy(alpha = alpha), Offset(inset, inset),
                    Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(radius),
                    style = Stroke(width),
                )
            }
        }
    }
}

/** The phone's points in model units (long side 2) and the screen points they were last projected to. */
private class PhoneModel(landscape: Boolean) {
    private val hx = if (landscape) 1f else WIDTH
    private val hy = if (landscape) WIDTH else 1f

    /** Outline corners, anticlockwise; the same outline makes the body (front and back) and, inset, the display. */
    private val body = roundedRect(hx, hy, RADIUS)
    private val display = roundedRect(hx - BEZEL, hy - BEZEL, RADIUS - BEZEL)
    private val n = body.size / 2

    /** Each band strip's outward normal in the phone's plane. */
    private val normals = FloatArray(n * 2).also { out ->
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = body[j * 2] - body[i * 2]
            val dy = body[j * 2 + 1] - body[i * 2 + 1]
            val l = hypot(dx, dy)
            out[i * 2] = dy / l
            out[i * 2 + 1] = -dx / l
        }
    }

    /** The front camera: in the middle of the top edge, which lies to the left in landscape. */
    private val camX = if (landscape) -hx + BEZEL * 1.6f else 0f
    private val camY = if (landscape) 0f else hy - BEZEL * 1.6f
    private val bumpX = if (landscape) hx - 0.28f else -hx + 0.2f
    private val bumpY = if (landscape) hy - 0.2f else hy - 0.28f

    private val front = FloatArray(n * 2)
    private val back = FloatArray(n * 2)
    private val screen = FloatArray(n * 2)
    private val p = FloatArray(3)

    private var cx = 0f
    private var cy = 0f
    private var unit = 0f

    /** Seen in perspective (nearer parts bigger), as [GyroDisplay.perspective]; off, flat. */
    var perspective = true

    /** Projects (x, y, z) turned by [m] (from [at]) into [p]: screen x, y and the scale there. */
    private fun project(m: FloatArray, x: Float, y: Float, z: Float, at: Int = 0) {
        val rx = m[at] * x + m[at + 1] * y + m[at + 2] * z
        val ry = m[at + 3] * x + m[at + 4] * y + m[at + 5] * z
        val rz = m[at + 6] * x + m[at + 7] * y + m[at + 8] * z
        val k = if (perspective) unit * CAMERA / (CAMERA - rz) else unit
        p[0] = cx + rx * k
        p[1] = cy - ry * k
        p[2] = k
    }

    private fun ring(m: FloatArray, src: FloatArray, z: Float, out: FloatArray, at: Int = 0) {
        for (i in 0 until n) {
            project(m, src[i * 2], src[i * 2 + 1], z, at)
            out[i * 2] = p[0]; out[i * 2 + 1] = p[1]
        }
    }

    private fun Path.outline(pts: FloatArray) {
        reset()
        moveTo(pts[0], pts[1])
        for (i in 1 until n) lineTo(pts[i * 2], pts[i * 2 + 1])
        close()
    }

    private fun place(c: Offset, size: Float) {
        cx = c.x; cy = c.y; unit = size
    }

    /** Only the front outline of the pose in [m] from [at]: a ghost. */
    fun drawOutline(scope: DrawScope, m: FloatArray, at: Int, path: Path, c: Offset, size: Float, color: Color) = with(scope) {
        place(c, size)
        ring(m, body, DEPTH, front, at)
        path.outline(front)
        drawPath(path, color, style = Stroke(width = density, join = StrokeJoin.Round))
    }

    /** The phone turned by [m]; [axes]: arrow colours for its right, up and screen axes, or none. */
    fun draw(
        scope: DrawScope, m: FloatArray, path: Path, c: Offset, size: Float,
        frontColor: Color, backColor: Color, sideColor: Color, edge: Color, axes: Array<Color>?,
    ) = with(scope) {
        place(c, size)
        ring(m, body, DEPTH, front)
        ring(m, body, -DEPTH, back)
        val line = Stroke(width = density * 1.5f, join = StrokeJoin.Round)
        val thin = Stroke(width = density * 1f, join = StrokeJoin.Round)
        fun Color.shade(f: Float) = copy(alpha = alpha * (0.45f + 0.55f * f))
        // How squarely the front faces the player (the screen normal's z); the back's is minus it.
        val facing = m[8]
        if (-facing > 0.01f) {
            path.outline(back)
            drawPath(path, backColor.shade(-facing))
            drawPath(path, edge, style = line)
            project(m, bumpX, bumpY, -DEPTH)
            drawCircle(edge, 0.1f * p[2] * -facing, Offset(p[0], p[1]), style = line)
        }
        // The band round the sides: the strips facing the player, shaded by how squarely they do.
        for (i in 0 until n) {
            val f = m[6] * normals[i * 2] + m[7] * normals[i * 2 + 1]
            if (f <= 0f) continue
            val j = (i + 1) % n
            path.reset()
            path.moveTo(front[i * 2], front[i * 2 + 1])
            path.lineTo(front[j * 2], front[j * 2 + 1])
            path.lineTo(back[j * 2], back[j * 2 + 1])
            path.lineTo(back[i * 2], back[i * 2 + 1])
            path.close()
            drawPath(path, sideColor.shade(f))
        }
        if (facing > 0.01f) {
            path.outline(front)
            drawPath(path, frontColor.shade(facing))
            drawPath(path, edge, style = line)
            // The display: only its edge, inside thin bezels.
            ring(m, display, DEPTH, screen)
            path.outline(screen)
            drawPath(path, edge.copy(alpha = edge.alpha * 0.7f * facing), style = thin)
            project(m, camX, camY, DEPTH)
            drawCircle(edge.copy(alpha = edge.alpha * facing), CAMERA_DOT * p[2], Offset(p[0], p[1]))
        } else {
            // Seen from behind, the body's front edge still outlines the band.
            path.outline(front)
            drawPath(path, edge.copy(alpha = edge.alpha * 0.5f), style = thin)
        }
        if (axes != null) {
            project(m, 0f, 0f, 0f)
            val o = Offset(p[0], p[1])
            for (a in 0..2) {
                project(m, if (a == 0) AXIS_LEN else 0f, if (a == 1) AXIS_LEN else 0f, if (a == 2) AXIS_LEN else 0f)
                val tip = Offset(p[0], p[1])
                drawLine(axes[a], o, tip, 2f * density, StrokeCap.Round)
                drawCircle(axes[a], 3f * density, tip)
            }
        }
    }
}

/** An anticlockwise rounded rectangle of half sizes [hx], [hy] and corner radius [r], as x, y pairs. */
private fun roundedRect(hx: Float, hy: Float, r: Float): FloatArray {
    val out = FloatArray(4 * ARC * 2)
    var o = 0
    // Corner centres from top right, anticlockwise; each arc sweeps its quarter.
    val cxs = floatArrayOf(hx - r, -hx + r, -hx + r, hx - r)
    val cys = floatArrayOf(hy - r, hy - r, -hy + r, -hy + r)
    for (c in 0 until 4) {
        for (s in 0 until ARC) {
            val a = (c + s / (ARC - 1f)) * (Math.PI / 2).toFloat()
            out[o++] = cxs[c] + r * cos(a)
            out[o++] = cys[c] + r * sin(a)
        }
    }
    return out
}

/** Points per rounded corner. */
private const val ARC = 6

/** Half the short side, against half the long side 1: a modern 20:9 phone. */
private const val WIDTH = 0.46f
private const val RADIUS = 0.13f
private const val BEZEL = 0.035f

/** Half thickness. */
private const val DEPTH = 0.04f
private const val CAMERA_DOT = 0.022f
private const val AXIS_LEN = 1.25f

/** Camera distance in model units: enough perspective to read the turn, little distortion. */
private const val CAMERA = 6f

/** The heading tape's height and its gap to the dial, in dial radii. */
private const val TAPE = 0.3f
private const val TAPE_GAP = 0.12f

/** Points round each line on the 3D ball. */
private const val SPHERE_STEPS = 96

/** The same, drawn polygonal. */
private const val POLY_STEPS = 12

/** How strongly edges round the back show. */
private const val HIDDEN_ALPHA = 0.3f

/** A gimbal ring's colour, as its axis's (laser axis colours X, Y, Z): yaw Y, pitch X, roll Z. */
private val GIMBAL_COLOR = intArrayOf(1, 0, 2)

/** A locked (None) axis's colour on the laser pointer. */
private val LOCKED_AXIS = Color(0xFF8A8F98)

/** The gimbal's yaw, pitch and roll rings, in ball radii. */
private val GIMBAL_RADII = doubleArrayOf(0.95, 0.82, 0.69)

private const val GHOSTS = 6
private const val GHOST_MS = 45L

/** A laser stroke's hops longer than this are bent along the sphere in pieces. */
private val STROKE_STEP_RAD = Math.toRadians(4.0).toFloat()

/** The world's axes in the axonometric view: right, up, toward you. */
// By turn, as the gimbal's rings: right is the pitch axis (green), up roll (blue), toward you yaw (red).
private val WORLD_AXES = arrayOf(Color(0xFF5ADB6E), Color(0xFF5AA8FF), Color(0xFFFF5A5A))

/** A laser pointer's strokes fade in this many steps (one path each). */
private const val LEVELS = 8

/** A laser pointer's Fire strokes blend from Fire ahead to Fire behind in this many steps (one path each). */
private const val HUES = 8

// Paint paths' keys start here: age step (bits 18-20), width step (15-17), colour (5 bits a channel).
private const val MOVE_KEYS = 1 shl 21

// The widest a paint piece's width step reaches (×, foreshortening and perspective included).
/** Varying width: pieces each rounded corner's half-curve is cut into. */
private const val RIBBON_QUAD_STEPS = 4

/** The edge's swing per degree, its furthest swing, each layer's step inwards and the outer corner radius. */
private const val EDGE_PX_PER_DEG = 1.5f
private const val EDGE_MAX_DP = 80f
private const val EDGE_GAP_DP = 10f
private const val EDGE_CORNER_DP = 34f


/** Yaw, pitch, roll: the edge's tint. */
private val AXIS_COLORS = arrayOf(Color(0xFFFF5A5A), Color(0xFF5ADB6E), Color(0xFF5AA8FF))
private val RING_AXES = MotionAxis.shown.toTypedArray()
private val RING_LETTERS = arrayOf("Y", "P", "R")

/** The laser pointer's axis letters, X, Y, Z: pitch, yaw, roll as it aims (facing −Z). */
private val LASER_AXIS_LETTERS = arrayOf("P", "Y", "R")
