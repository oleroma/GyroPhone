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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import com.oleroma.gyrophone.core.engine.GyroDisplayFeed
import com.oleroma.gyrophone.core.engine.LaserPaint
import com.oleroma.gyrophone.core.engine.CrosshairKick
import com.oleroma.gyrophone.core.engine.LaserTrace
import com.oleroma.gyrophone.core.engine.MuzzleFlash
import com.oleroma.gyrophone.core.engine.Guns
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
    // Each pointer's Aim display now, eased (0 .. 1): the crosshair shrinks and the rifle shoulders by it.
    val aims = remember { HashMap<String, Float>() }
    // Each crosshair's fire kick (CrosshairKick), stepped once a frame.
    val kicks = remember { HashMap<String, CrosshairKick.State>() }
    // Each gun's raise now, eased (0 lowered .. 1 up): with Lowered when idle it comes up while aiming or firing.
    val raises = remember { HashMap<String, Float>() }
    // Each gun's hip fire now, eased (0 .. 1): firing without aiming pulls it back toward you.
    val hips = remember { HashMap<String, Float>() }
    // The pose each display showed when it last moved visibly ([VISIBLE_TURN]).
    val seen = remember { HashMap<String, FloatArray>() }
    // Redraws only while some display's data changes (and a moment after, so ghost trails catch up):
    // a still pose, as in edit mode, with the gyro off or the phone lying still (its sensor noise
    // moves the pose by less than shows), draws nothing new.
    LaunchedEffect(Unit) {
        val pose = FloatArray(9)
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
            var moved = false
            for (b in shown) {
                val f = runtime.gyroFeed(b.control.id)
                sig = sig * 31 + System.identityHashCode(f)
                sig = sig * 31 + (f?.trace?.version ?: 0L)
                sig = sig * 31 + (f?.laser?.version ?: 0L)
                if (f == null) continue
                // Held, firing or aiming change what a pointer paints even on a still phone.
                sig = sig * 31 + (if (f.ratchetHeld) 1 else 0) + (if (f.firing) 2 else 0) + (if (f.aiming) 4 else 0)
                val d = f.display
                if (d.isTrackball) sig = sig * 31 + f.trackballActive()
                if (!d.kind.pose) continue
                // The pose as shown: redrawn once it moved by a visible amount since it last did.
                f.shown(pose)
                val was = seen[b.control.id]
                if (was == null || movedVisibly(was, pose)) {
                    seen[b.control.id] = pose.copyInto(was ?: FloatArray(9))
                    moved = true
                }
            }
            if (moved || sig != last || frame == 0L) {
                last = sig
                until = t + (GHOSTS + 2) * GHOST_MS
            }
            // Aiming eases in and out over AIM_EASE_MS.
            for (b in shown) {
                val id = b.control.id
                val was = aims[id] ?: 0f
                val goal = if (runtime.gyroFeed(id)?.aiming == true) 1f else 0f
                if (was == goal) continue
                val step = if (dt <= 0f) 1f else dt / AIM_EASE_MS
                aims[id] = if (goal > was) minOf(goal, was + step) else maxOf(goal, was - step)
                until = maxOf(until, t + 20)
            }
            // A crosshair or rifle kicks on each shot and bounces back, its muzzle flash in time.
            for (b in shown) {
                val d = b.control.display ?: continue
                // Draw each shot counts the shots on the Sphere look too.
                if (d.kind != GyroDisplayKind.LASER || !(d.laserCrosshair || d.laserRifle || (d.laserShotDraw && d.laserDraw))) continue
                val k = kicks.getOrPut(b.control.id) { CrosshairKick.State() }
                val firing = runtime.gyroFeed(b.control.id)?.firing == true
                val gun = d.gun()
                if (k.step(t, firing, d.crosshairKick, CrosshairKick.periodMs(d.rpmOf(gun)), d.countsShots, gun.recoil, gun.pellets)) until = maxOf(until, t + 20)
                // A gun lowered when idle comes up while aiming or firing.
                val up = if (!d.laserRifle || !d.gunLowered) 1f
                else if (firing || runtime.gyroFeed(b.control.id)?.aiming == true) 1f else 0f
                val was = raises[b.control.id] ?: up
                if (was != up) {
                    // Up quickly; down (straight from hip fire, as you let go) over Hip fire recovery, in step with it.
                    val ms = if (up > was) AIM_EASE_MS else d.gunHipRecoverMs.coerceIn(0, 2000).toFloat()
                    val step = if (dt <= 0f || ms <= 0f) 1f else dt / ms
                    raises[b.control.id] = if (up > was) minOf(up, was + step) else maxOf(up, was - step)
                    until = maxOf(until, t + 20)
                } else raises[b.control.id] = up
                // Hip fire: firing without aiming; letting go starts the recovery at once (the last kick plays out on top).
                val hipGoal = if (d.laserRifle && firing && runtime.gyroFeed(b.control.id)?.aiming != true) 1f else 0f
                val hipWas = hips[b.control.id] ?: hipGoal
                if (hipWas != hipGoal) {
                    // Into hip fire quickly; back out over Hip fire recovery.
                    val ms = if (hipGoal > hipWas) AIM_EASE_MS else d.gunHipRecoverMs.coerceIn(0, 2000).toFloat()
                    val step = if (dt <= 0f || ms <= 0f) 1f else dt / ms
                    hips[b.control.id] = if (hipGoal > hipWas) minOf(hipGoal, hipWas + step) else maxOf(hipGoal, hipWas - step)
                    until = maxOf(until, t + 20)
                } else hips[b.control.id] = hipGoal
            }
            if (t <= until) frame = t
        }
    }
    val st = remember { VisState() }
    // Its own layer: the overlays drawn over it redraw without drawing the displays again.
    Canvas(modifier.graphicsLayer()) {
        st.now = frame
        for (b in boxes) {
            val shownAs = b.control.display ?: continue
            val k = fades[b.control.id] ?: 1f
            val d = if (k < 1f) shownAs.copy(opacity = shownAs.opacity * k) else shownAs
            st.aim = aims[b.control.id] ?: 0f
            st.kick = kicks[b.control.id]?.value ?: 0f
            st.shotAt = kicks[b.control.id]?.shotAt ?: -1L
            st.shots = kicks[b.control.id]
            st.lower = 1f - (raises[b.control.id] ?: 1f)
            st.hip = (hips[b.control.id] ?: 0f) * shownAs.gunHipPull.coerceIn(0f, 1f)
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

/** The pose matrix [now] moved from [was] by more than [VISIBLE_TURN] in any entry (a turn of about that many radians). */
private fun movedVisibly(was: FloatArray, now: FloatArray): Boolean {
    for (i in 0..8) if (kotlin.math.abs(now[i] - was[i]) > VISIBLE_TURN) return true
    return false
}

/** The parallax edge ([TouchVisuals.gyroEdge]): outlines just inside the screen's edge that sway as the phone turns. */
@Composable
fun GyroEdgeLayer(runtime: GamepadRuntime, v: TouchVisuals, modifier: Modifier = Modifier) {
    var frame by remember { mutableLongStateOf(0L) }
    val st = remember { VisState() }
    val visuals by rememberUpdatedState(v)
    val density = LocalDensity.current.density
    // Redrawn only when where it is drawn moved by a visible amount (or its tint changed): a still phone draws nothing new.
    LaunchedEffect(Unit) {
        val drawn = FloatArray(EDGE_STATE)
        val now = FloatArray(EDGE_STATE)
        while (true) withFrameMillis { t ->
            st.edgeState(runtime, visuals, density, now)
            var moved = frame == 0L
            for (i in 0 until EDGE_STATE) if (kotlin.math.abs(now[i] - drawn[i]) > EDGE_STEP[i]) moved = true
            if (moved) {
                now.copyInto(drawn)
                frame = t
            }
        }
    }
    // Its own layer: its redraws leave the gyro displays alone.
    Canvas(modifier.graphicsLayer()) {
        @Suppress("UNUSED_EXPRESSION") frame // redrawn when it moved
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

    /** The display being drawn's Aim display now, eased (0 .. 1). */
    var aim = 0f
    /** The crosshair's fire kick now ([CrosshairKick.State.value]). */
    var kick = 0f

    /** How far the gun is lowered now (0 up .. 1 lowered when idle). */
    var lower = 0f

    /** Hip fire now (0 .. 1, eased, times [GyroDisplay.gunHipPull]): firing without aiming, the gun pulled back toward you. */
    var hip = 0f

    /** When the last Fire display shot was ([CrosshairKick.State.shotAt]), for its muzzle flash; −1 none. */
    var shotAt = -1L

    /** The display's shots ([CrosshairKick.State]): their spread and Draw each shot's hits; null none. */
    var shots: CrosshairKick.State? = null
    private val shotDir = FloatArray(3)
    private val turnedCopy = FloatArray(9)
    private val hitU = FloatArray(3)
    private val hitV = FloatArray(3)
    private val sightAt = FloatArray(3)

    // The last gunFrameScale: its inputs and result.
    private var frameKey = ""
    private var frameScale = 1f
    private val restPose = FloatArray(9)

    /** [LaserTrace.gunFrameScale] for [d]'s gun, fully hip fired and kicked, kept while its inputs stay the same. */
    private fun gunFrameScale(d: GyroDisplay): Float {
        val gun = d.gun()
        val kickK = d.kickOf(gun).coerceIn(0f, 3f) * 2f
        val hip = d.gunHipPull.coerceIn(0f, 1f)
        val key = "${gun.key} $hip $kickK ${d.laserAxonometric} ${d.perspective}"
        if (key != frameKey) {
            restPose.fill(0f); restPose[0] = 1f; restPose[4] = 1f; restPose[8] = 1f
            LaserTrace.aim(restPose)
            frameScale = LaserTrace.gunFrameScale(restPose, d.laserAxonometric, d.perspective, gun, hip, kickK)
            frameKey = key
        }
        return frameScale
    }

    /** The muzzle flash's age now (ms), or −1 when there is none or [GyroDisplay.muzzleFlash] is off. */
    private fun flashMs(d: GyroDisplay): Float =
        if (!d.muzzleFlash || shotAt < 0L) -1f else (now - shotAt).toFloat().takeIf { it < MuzzleFlash.LENGTH_MS } ?: -1f

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
    private val way = FloatArray(3)
    // Each pointer's paint drawing (its map's upload).
    private val paintShaders = java.util.WeakHashMap<LaserTrace, PointerPaintShader>()
    private val viewOf: (Float, Float, Float, FloatArray) -> Unit = { x, y, z, out -> LaserTrace.view(x, y, z, out, axon = laserAxon, turned = laserTurned) }
    private val turnBase = FloatArray(9)
    private val nv = FloatArray(3)
    private val hv = FloatArray(3)
    private val sceneOf: (Float, Float, Float, FloatArray) -> Unit = { x, y, z, out -> LaserTrace.view(x, y, z, out, axon = laserAxon) }
    private val moveColors = LongArray(6)
    // The Snake's: a span's four curve points (x, y, z each), the way at its ends, Fire/Aim colours, its pen.
    private val cp = FloatArray(12)
    private val wayA = FloatArray(3)
    private val wayB = FloatArray(3)
    private val snakeFire = IntArray(4)
    private val snakePaint = Paint()
    private var snakeXY = FloatArray(0)
    private var snakeW = FloatArray(0)
    private var snakeA = FloatArray(0)
    private var snakeCol = IntArray(0)
    // Reused from frame to frame: the paint's look, the gimbal rings' points, the rifle's muzzle.
    private val paintLook = PointerPaintShader.Look()
    private val gimbalPts = Array(3) { FloatArray(65 * 3) }
    private val muzzleAt = FloatArray(3)
    // Made again only when what they are made from changes.
    private var thinCache: Stroke? = null
    private var dashCache: PathEffect? = null
    private var strokesFor = 0f
    private var sphereFill: Brush? = null
    private val sphereKey = FloatArray(3)
    private var turnedGrid: Pair<List<FloatArray>, List<FloatArray>>? = null
    private val turnedGridOf = FloatArray(9)
    private var turnedGridAxon = false
    private var sphereKeyLit = Color.Unspecified
    private var sphereKeyColor = Color.Unspecified

    private fun thinStroke(density: Float): Stroke {
        val t = thinCache
        if (density == strokesFor && t != null) return t
        strokesFor = density
        dashCache = PathEffect.dashPathEffect(floatArrayOf(4f * density, 3f * density))
        return Stroke(density, cap = StrokeCap.Round, join = StrokeJoin.Round).also { thinCache = it }
    }

    private fun lockDash(density: Float): PathEffect {
        thinStroke(density)
        return dashCache!!
    }

    /** [LaserTrace.grid], a turned one made again only when the turn changed. */
    private fun laserGrid(axon: Boolean, turned: FloatArray?): Pair<List<FloatArray>, List<FloatArray>> {
        if (turned == null) return LaserTrace.grid(axon)
        val g = turnedGrid
        if (g != null && axon == turnedGridAxon && turned.contentEquals(turnedGridOf)) return g
        turned.copyInto(turnedGridOf)
        turnedGridAxon = axon
        return LaserTrace.grid(axon, turned).also { turnedGrid = it }
    }

    /** The Sphere look's shading, lit from upper left: the same one while its colours and place stay. */
    private fun sphereBrush(lit: Color, sphere: Color, c: Offset, r: Float): Brush {
        val k = sphereKey
        val b = sphereFill
        if (b != null && lit == sphereKeyLit && sphere == sphereKeyColor && k[0] == c.x && k[1] == c.y && k[2] == r) return b
        sphereKeyLit = lit
        sphereKeyColor = sphere
        k[0] = c.x; k[1] = c.y; k[2] = r
        return Brush.radialGradient(listOf(lit, sphere), Offset(c.x - r * 0.35f, c.y - r * 0.4f), r * 1.3f).also { sphereFill = it }
    }

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
        // Aiming shrinks it to the Aim display's size.
        val k = 1f + (d.laserAimSize.coerceIn(0.1f, 1f) - 1f) * aim
        // Firing kicks it out ([CrosshairKick]): the lines alone, or evenly with their thickness and the dot.
        val kk = 1f + d.kickOf().coerceIn(0f, 3f) * d.gun().recoil.strength * kick
        val even = !d.crosshairKickLinesOnly
        val ch = LaserTrace.crosshair(
            m, beam, d.crosshairShape, d.crosshairSize * rad * k * kk, d.crosshairGap * rad * k * kk, d.crosshairDot,
            d.crosshairSize * rad * k * (if (even) kk else 1f),
        )
        // Aiming turns it laser red; each shot flashes white, then yellow, then orange, and back.
        var color = if (aim > 0f) lerp(moveColor, col(GyroDisplayColor.LASER_CROSSHAIR_AIM), aim) else moveColor
        val fm = flashMs(d)
        val glow = MuzzleFlash.glow(fm)
        if (fm >= 0f) color = Color(MuzzleFlash.color(fm, col(GyroDisplayColor.LASER_FLASH).toArgb(), col(GyroDisplayColor.LASER_FLASH_END).toArgb(), color.toArgb()))
        if (held) color = color.copy(alpha = color.alpha * 0.35f)
        val outline = col(GyroDisplayColor.LASER_CROSSHAIR_OUTLINE).let { it.copy(alpha = minOf(it.alpha, color.alpha)) }
        val w = d.crosshairThickness.coerceIn(0.5f, 8f) * density * (if (even) kk.coerceAtLeast(0.2f) else 1f)
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
        if (glow > 0f) {
            // The flash's burst of light: two soft, wide strokes under the lines.
            for ((wide, a) in arrayOf(w + 10f * density to 0.22f, w + 5f * density to 0.4f)) {
                val st = Stroke(wide, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val gc = color.copy(alpha = color.alpha * a * glow)
                for (l in ch.lines) drawPath(pathOf(l), gc, style = st)
                if (dot != null) drawPath(pathOf(dot), gc, style = st)
            }
        }
        val line = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (l in ch.lines) drawPath(pathOf(l), color, style = line)
        if (dot != null) drawPath(pathOf(dot), color)
    }

    /**
     * The gun's muzzle blast, [ms] after a shot ([MuzzleFlash]): a flame plume out along the barrel and four short
     * jets slanting out round it, as wide as [gun]'s muzzle ([Guns.Gun.bore]) and as long as its calibre
     * ([Guns.Gun.flash]), white, then [hot], then [end] as they shrink and fade, with a white-hot core; each shot
     * its own lengths and turn.
     */
    private fun DrawScope.muzzleFlash(c: Offset, r: Float, ms: Float, hot: Color, end: Color, gun: Guns.Gun, kickK: Float) {
        val g = MuzzleFlash.glow(ms)
        if (g <= 0f) return
        val fire = Color(MuzzleFlash.color(ms, hot.toArgb(), end.toArgb(), end.copy(alpha = 0f).toArgb()))
        val mz = muzzleAt.also { LaserTrace.muzzle(aim, it, kick * kickK, gun, lower, hip) }
        val dir = FloatArray(3)
        fun at(x: Float, y: Float, z: Float): Offset =
            laserAt(c, r, m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z)
        val tip = at(mz[0], mz[1], mz[2])
        val step = shotAt
        fun rnd(i: Int): Float = (((step * 7919L + i * 104729L) xor (step shr 3)) % 1000L).let { kotlin.math.abs(it) / 1000f }
        // Bursts out at once, then shrinks back as it cools.
        val grow = 0.55f + 0.45f * kotlin.math.sqrt(g)
        val width = gun.bore * gun.fit * 2.4f * r * grow
        // One flame from the muzzle along gun direction (dx, dy, dz), [len] long, [w] wide at its widest.
        fun flame(dx: Float, dy: Float, dz: Float, len: Float, w: Float, color: Color) {
            LaserTrace.gunDir(dx, dy, dz, dir, kick * kickK, gun, lower)
            val e = at(mz[0] + dir[0] * len, mz[1] + dir[1] * len, mz[2] + dir[2] * len)
            val fx = e.x - tip.x
            val fy = e.y - tip.y
            val fl = kotlin.math.sqrt(fx * fx + fy * fy)
            if (fl < w * 0.5f) {
                // Seen end on: a round burst.
                drawCircle(color, w * 0.7f, tip)
                return
            }
            val px = -fy / fl * w / 2f
            val py = fx / fl * w / 2f
            crossPath.reset()
            crossPath.moveTo(tip.x + px * 0.6f, tip.y + py * 0.6f)
            crossPath.lineTo(tip.x + fx * 0.35f + px * 1.3f, tip.y + fy * 0.35f + py * 1.3f)
            crossPath.lineTo(e.x, e.y)
            crossPath.lineTo(tip.x + fx * 0.35f - px * 1.3f, tip.y + fy * 0.35f - py * 1.3f)
            crossPath.lineTo(tip.x - px * 0.6f, tip.y - py * 0.6f)
            crossPath.close()
            drawPath(crossPath, color)
        }
        drawCircle(fire.copy(alpha = fire.alpha * 0.3f * g), width * 2.2f, tip)
        val len = gun.flash * gun.fit * grow * (0.8f + 0.4f * rnd(0))
        val turn = rnd(1) * 1.5708f
        val slant = 0.9f // about 52° off the barrel
        val core = Color.White.copy(alpha = fire.alpha * g)
        for (k in 0 until 4) {
            val a = turn + k * 1.5708f
            val sl = len * (0.3f + 0.2f * rnd(k + 2))
            val dx = kotlin.math.cos(a) * kotlin.math.sin(slant)
            val dy = kotlin.math.sin(a) * kotlin.math.sin(slant)
            val dz = -kotlin.math.cos(slant)
            flame(dx, dy, dz, sl, width * 0.6f, fire)
        }
        flame(0f, 0f, -1f, len, width, fire)
        flame(0f, 0f, -1f, len * 0.45f, width * 0.5f, core)
        drawCircle(core, width * 0.45f, tip)
    }

    /**
     * The rifle's laser sight while aiming ([aim] eases it in): a thin line in [color] from its muzzle
     * along the barrel to where it lands on the sphere, and a dot there with a soft halo.
     */
    private fun DrawScope.laserSight(c: Offset, r: Float, color: Color, gun: Guns.Gun, kickK: Float) {
        val mz = muzzleAt.also { LaserTrace.muzzle(aim, it, kick * kickK, gun, lower, hip) }
        val x = mz[0]; val y = mz[1]; val z = mz[2]
        val tip = laserAt(c, r, m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z)
        // Straight along the barrel: the dot kicks with it (the paint stays on the aim).
        LaserTrace.sightHit(m, aim, kick * kickK, gun, lower, hip, sightAt)
        val hit = laserAt(c, r, sightAt[0], sightAt[1], sightAt[2])
        val a = color.alpha * aim
        drawLine(color.copy(alpha = a * 0.25f), tip, hit, 4f * density, StrokeCap.Round)
        drawLine(color.copy(alpha = a * 0.8f), tip, hit, 1.2f * density, StrokeCap.Round)
        val size = r * 0.025f
        drawCircle(color.copy(alpha = a * 0.3f), size * 2.5f, hit)
        drawCircle(color.copy(alpha = a), size, hit)
        drawCircle(Color.White.copy(alpha = a * 0.9f), size * 0.4f, hit)
    }

    /**
     * Snake: the line along the newest stretch of [t]'s strokes, its [front] (near) or far pieces,
     * [GyroDisplay.laserSnakeBrush] wide at the head (shots and aiming their own brushes), thinning by
     * [GyroDisplay.laserSnakeTaper] and fading by [GyroDisplay.laserSnakeFade] toward its tail, with a soft
     * [GyroDisplay.laserSnakeGlow]; coloured as the paint (the Moving colours by where it is in the scene, or by
     * the way it moved; shots in the Fire colours), shaded as the paint. It follows a smooth curve through the
     * stroke points ([LaserTrace.curve]), cut into steps of at most [LaserTrace.CURVE_STEP_DEG] with the colour,
     * width and fade blended along each, so a fast turn (points far apart) stays round and the joints don't show.
     * The pieces go newest first into one layer, each only where the layer is still empty (DST_OVER), so
     * overlaps neither darken nor show the older piece's round end; the layer is laid on at the colours' opacity.
     */
    private fun DrawScope.snake(t: LaserTrace, d: GyroDisplay, front: Boolean, c: Offset, r: Float, col: (GyroDisplayColor) -> Color) {
        val s = t.strokes
        val n = s.count
        if (n < 2) return
        val len = d.laserSnakeDeg.toFloat().coerceAtLeast(1f)
        snakeFire[0] = col(GyroDisplayColor.LASER_FIRE).toArgb(); snakeFire[1] = col(GyroDisplayColor.LASER_FIRE_BEHIND).toArgb()
        snakeFire[2] = col(GyroDisplayColor.LASER_AIM).toArgb(); snakeFire[3] = col(GyroDisplayColor.LASER_AIM_BEHIND).toArgb()
        val ld = PointerPaintShader.lightDir(d.laserLightFrom)
        val reach = if (d.laserSmoothColours) LaserTrace.SMOOTH_REACH else 0
        val taper = d.laserSnakeTaper.coerceIn(0f, 1f)
        val fade = d.laserSnakeFade.coerceIn(0f, 1f)
        fun brushPx(deg: Int) = maxOf(1.5f * density, r * Math.toRadians(deg.coerceIn(1, 20).toDouble()).toFloat() * BRUSH_WIDTH)
        val paintPx = brushPx(d.laserSnakeBrush)
        val firePx = brushPx(d.laserFireBrush)
        val aimPx = brushPx(d.laserAimBrush)
        // The pieces of this half, oldest first: from, to, width, colour, opacity.
        var m = 0
        var hasPrev = false
        var prevX = 0f
        var prevY = 0f
        var prevFront = false
        for (i in 1 until n) {
            if (s.gap(i)) { hasPrev = false; continue }
            // The span from point i − 1 to i, with its neighbours in the same line (or the end itself).
            val i0 = if (i >= 2 && !s.gap(i - 1)) i - 2 else i - 1
            val i3 = if (i + 1 < n && !s.gap(i + 1)) i + 1 else i
            s.point(i0, cp, 0); s.point(i - 1, cp, 3); s.point(i, cp, 6); s.point(i3, cp, 9)
            val dot = (cp[3] * cp[6] + cp[4] * cp[7] + cp[5] * cp[8]).coerceIn(-1f, 1f)
            val deg = Math.toDegrees(kotlin.math.acos(dot).toDouble()).toFloat()
            val steps = kotlin.math.ceil(deg / LaserTrace.CURVE_STEP_DEG).toInt().coerceIn(1, LaserTrace.CURVE_MAX_STEPS)
            val kind = s.kind(i)
            val headPx = when (kind) { LaserPaint.FIRE -> firePx; LaserPaint.AIM -> aimPx; else -> paintPx }
            val tailA = 1f - s.behindNewest(i - 1) / len
            val tailB = 1f - s.behindNewest(i) / len
            val byWay = kind == LaserPaint.PAINT && d.laserColourByWay
            if (byWay) {
                // The way blends from the span before's to this span's.
                s.direction(i, reach, wayB)
                if (i0 < i - 1) s.direction(i - 1, reach, wayA) else wayB.copyInto(wayA)
            }
            if (!hasPrev) {
                val p = laserAt(c, r, cp[3], cp[4], cp[5])
                prevX = p.x; prevY = p.y; prevFront = pv[2] > 0f
                hasPrev = true
            }
            for (j in 1..steps) {
                val f = j.toFloat() / steps
                if (j == steps) { la[0] = cp[6]; la[1] = cp[7]; la[2] = cp[8] }
                else LaserTrace.curve(cp, 0, cp, 3, cp, 6, cp, 9, f, la)
                val p = laserAt(c, r, la[0], la[1], la[2])
                val z = pv[2]
                val isFront = z > 0f
                if (isFront == front && prevFront == front) {
                    if (byWay) {
                        for (q in 0..2) way[q] = wayA[q] + (wayB[q] - wayA[q]) * f
                        LaserTrace.unit(way)
                    }
                    val tail = (tailA + (tailB - tailA) * f).coerceIn(0f, 1f)
                    if (m == snakeCol.size) growSnake()
                    snakeXY[m * 4] = prevX; snakeXY[m * 4 + 1] = prevY; snakeXY[m * 4 + 2] = p.x; snakeXY[m * 4 + 3] = p.y
                    snakeW[m] = headPx * (1f - taper * (1f - tail)) * (if (laserPersp) LaserTrace.perspective(z) else 1f)
                    snakeA[m] = 1f - fade * (1f - tail)
                    snakeCol[m] = snakeShade(d, kind, byWay, z, front, ld)
                    m++
                }
                prevX = p.x; prevY = p.y; prevFront = isFront
            }
        }
        if (m == 0) return
        val canvas = drawContext.canvas.nativeCanvas
        val alpha = moveColors.sumOf { it shr 24 and 0xFF } / (6f * 255f)
        val e = r * 1.3f
        snakePaint.strokeCap = Paint.Cap.ROUND
        snakePaint.style = Paint.Style.STROKE
        snakePaint.isAntiAlias = true
        snakePaint.blendMode = android.graphics.BlendMode.DST_OVER
        // Glow: wider, fainter copies under the line, each its own layer, so together they fall off softly.
        val glow = d.laserSnakeGlow.coerceIn(0f, 1f)
        if (glow > 0f) for ((wide, share) in SNAKE_GLOW) {
            val layer = canvas.saveLayerAlpha(c.x - e, c.y - e, c.x + e, c.y + e, (255f * alpha * glow * share).toInt().coerceIn(0, 255))
            snakePieces(canvas, m, wide)
            canvas.restoreToCount(layer)
        }
        val layer = canvas.saveLayerAlpha(c.x - e, c.y - e, c.x + e, c.y + e, (255f * alpha).toInt().coerceIn(0, 255))
        snakePieces(canvas, m, 1f)
        canvas.restoreToCount(layer)
        snakePaint.blendMode = null
    }

    /** The Snake's [m] pieces, newest first, [wide] times their width. */
    private fun snakePieces(canvas: android.graphics.Canvas, m: Int, wide: Float) {
        for (k in m - 1 downTo 0) {
            val a = (snakeA[k] * 255f + 0.5f).toInt().coerceIn(0, 255)
            if (a == 0) continue
            snakePaint.color = (snakeCol[k] and 0xFFFFFF) or (a shl 24)
            snakePaint.strokeWidth = snakeW[k] * wide
            canvas.drawLine(snakeXY[k * 4], snakeXY[k * 4 + 1], snakeXY[k * 4 + 2], snakeXY[k * 4 + 3], snakePaint)
        }
    }

    private fun growSnake() {
        val size = maxOf(256, snakeCol.size * 2)
        snakeXY = snakeXY.copyOf(size * 4)
        snakeW = snakeW.copyOf(size)
        snakeA = snakeA.copyOf(size)
        snakeCol = snakeCol.copyOf(size)
    }

    /**
     * The Snake's opaque colour (ARGB) at the point in [la] (shown frame), depth [z]: the Fire/Aim colours by
     * where it is for those [kind]s, else the way in [way] ([byWay]) or where it is; shaded and lit as the paint.
     */
    private fun snakeShade(d: GyroDisplay, kind: Int, byWay: Boolean, z: Float, front: Boolean, ld: FloatArray): Int {
        val turned = laserTurned
        // The scene's frame (without the sphere's turn): what the colour goes by.
        val gx: Float
        val gy: Float
        val gz: Float
        if (turned == null) {
            gx = la[0]; gy = la[1]; gz = la[2]
        } else {
            gx = turned[0] * la[0] + turned[3] * la[1] + turned[6] * la[2]
            gy = turned[1] * la[0] + turned[4] * la[1] + turned[7] * la[2]
            gz = turned[2] * la[0] + turned[5] * la[1] + turned[8] * la[2]
        }
        var cr: Float
        var cg: Float
        var cb: Float
        if (kind != LaserPaint.PAINT) {
            val k = LaserTrace.behind(gz)
            val ca = snakeFire[if (kind == LaserPaint.FIRE) 0 else 2]
            val cbh = snakeFire[if (kind == LaserPaint.FIRE) 1 else 3]
            fun ch(a: Int, b: Int, sh: Int): Float { val x = (a shr sh and 0xFF) / 255f; return x + ((b shr sh and 0xFF) / 255f - x) * k }
            cr = ch(ca, cbh, 16); cg = ch(ca, cbh, 8); cb = ch(ca, cbh, 0)
        } else {
            if (!byWay) { way[0] = gx; way[1] = gy; way[2] = gz }
            val argb = LaserTrace.directionColor(way, moveColors).toInt()
            cr = (argb shr 16 and 0xFF) / 255f; cg = (argb shr 8 and 0xFF) / 255f; cb = (argb and 0xFF) / 255f
        }
        val facing = kotlin.math.abs(z).coerceAtMost(1f)
        var k = (1f + (0.25f + 0.75f * facing - 1f) * d.laserShade) * (if (front) 1f else d.laserInside)
        // The scene's light (as the paint's shader): the side you see, outside near, inside far.
        LaserTrace.view(la[0], la[1], la[2], nv, axon = laserAxon, turned = turned)
        if (!front) for (q in 0..2) nv[q] = -nv[q]
        val diffuse = (nv[0] * ld[0] + nv[1] * ld[1] + nv[2] * ld[2]).coerceAtLeast(0f)
        k *= 1f + (0.3f + 0.9f * diffuse - 1f) * d.laserLight
        hv[0] = ld[0]; hv[1] = ld[1]; hv[2] = ld[2] + 1f
        LaserTrace.unit(hv)
        val spec = d.laserShine * Math.pow((nv[0] * hv[0] + nv[1] * hv[1] + nv[2] * hv[2]).coerceAtLeast(0f).toDouble(), 24.0).toFloat()
        fun b(v: Float) = ((v * k + spec).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (b(cr) shl 16) or (b(cg) shl 8) or b(cb)
    }

    /** Picture point of shown-frame direction ([x], [y], [z]) on a sphere of radius [r] at [c]; its depth (toward the viewer +) left in [pv]. */
    private fun laserAt(c: Offset, r: Float, x: Float, y: Float, z: Float): Offset {
        LaserTrace.view(x, y, z, pv, axon = laserAxon, turned = laserTurned)
        val k = if (laserPersp) LaserTrace.perspective(pv[2]) else 1f
        return Offset(c.x + pv[0] * r * k, c.y - pv[1] * r * k)
    }

    /**
     * Draw each shot's hits ([LaserTrace.hits]) on the near or far ([front]) half, in the Fire colours (Fire
     * behind on the far half), each a little disc lying on the sphere as [GyroDisplay.laserHitLook] shows it:
     * a dot, a ring, a cross, or a bullet hole (a dark centre in a bright rim).
     */
    private fun DrawScope.hits(t: LaserTrace, d: GyroDisplay, front: Boolean, c: Offset, r: Float, col: (GyroDisplayColor) -> Color) {
        if (t.hitCount == 0) return
        val look = d.laserHitLook.coerceIn(0, LaserTrace.HIT_LOOKS.size - 1)
        val color = col(if (front) GyroDisplayColor.LASER_FIRE else GyroDisplayColor.LASER_FIRE_BEHIND)
        val rim = path
        val hole = path2
        rim.reset()
        hole.reset()
        val h = t.hits
        for (n in 0 until t.hitCount) {
            val x = h[n * 4]; val y = h[n * 4 + 1]; val z = h[n * 4 + 2]
            val p0 = laserAt(c, r, x, y, z)
            if ((pv[2] > 0f) != front) continue
            // Across the sphere there: right (round its up) and up.
            if (kotlin.math.abs(y) > 0.99f) { hitU[0] = 1f; hitU[1] = 0f; hitU[2] = 0f }
            else { hitU[0] = -z; hitU[1] = 0f; hitU[2] = x; LaserTrace.unit(hitU) }
            hitV[0] = y * hitU[2] - z * hitU[1]; hitV[1] = z * hitU[0] - x * hitU[2]; hitV[2] = x * hitU[1] - y * hitU[0]
            val k = kotlin.math.tan(h[n * 4 + 3])
            val pa = laserAt(c, r, x + hitU[0] * k, y + hitU[1] * k, z + hitU[2] * k)
            val ax = pa.x - p0.x; val ay = pa.y - p0.y
            val pb = laserAt(c, r, x + hitV[0] * k, y + hitV[1] * k, z + hitV[2] * k)
            val bx = pb.x - p0.x; val by = pb.y - p0.y
            if (look == 2) {
                rim.moveTo(p0.x - ax - bx, p0.y - ay - by); rim.lineTo(p0.x + ax + bx, p0.y + ay + by)
                rim.moveTo(p0.x - ax + bx, p0.y - ay + by); rim.lineTo(p0.x + ax - bx, p0.y + ay - by)
                continue
            }
            fun disc(into: Path, s: Float) {
                for (i in 0 until HIT_SIDES) {
                    val a = i * 2f * Math.PI.toFloat() / HIT_SIDES
                    val cs = kotlin.math.cos(a) * s; val sn = kotlin.math.sin(a) * s
                    val px = p0.x + ax * cs + bx * sn; val py = p0.y + ay * cs + by * sn
                    if (i == 0) into.moveTo(px, py) else into.lineTo(px, py)
                }
                into.close()
            }
            disc(rim, 1f)
            if (look == 3) disc(hole, 0.55f)
        }
        val w = maxOf(1.2f * density, 0.5f)
        when (look) {
            1 -> drawPath(rim, color, style = Stroke(w * 1.3f, join = StrokeJoin.Round))
            2 -> drawPath(rim, color, style = Stroke(w * 1.5f, cap = StrokeCap.Round))
            3 -> {
                drawPath(rim, color)
                drawPath(hole, Color(0.06f, 0.05f, 0.04f, 0.9f * color.alpha))
            }
            else -> drawPath(rim, color)
        }
    }

    /**
     * The laser pointer: a see-through sphere seen from a fixed angle (30° round, 30° above), its
     * grid and axes, the beam from its centre out of the phone's back to the inside, a dot and a roll
     * tick where it hits; Draw mode's paint ([GyroDisplayFeed.laser]). Far
     * half first, then the beam, then the near half (fainter, seen through the shell).
     */
    private fun DrawScope.drawLaser(feed: GyroDisplayFeed?, d: GyroDisplay, box: Rect, col: (GyroDisplayColor) -> Color) {
        poseOf(feed, d)
        // The pose before a trackball's Pointer faces: Fixed pointer mode turns the sphere by this, so the
        // beam stays pointing the way it faces.
        m.copyInto(turnBase)
        // Turned by the phone it aims with its roll axis; a trackball faces the way picked; a game camera already aims ahead.
        when (d.turnsWith) {
            com.oleroma.gyrophone.core.model.TurnsWith.PHONE -> LaserTrace.aim(m)
            com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL -> LaserTrace.faces(m, d.laserFaces)
            else -> {}
        }
        laserAxon = d.laserAxonometric
        laserPersp = d.perspective
        // Sphere turns: everything on it is seen from the pose, so the beam stays ahead.
        laserTurned = if (!d.laserSphereTurns) null else if (d.turnsWith == com.oleroma.gyrophone.core.model.TurnsWith.TRACKBALL) turnBase else m
        // The gun look drawn a little smaller where hip fire would push the gun out of its frame.
        val r = minOf(box.width, box.height) / 2f * 0.95f * (if (d.laserRifle) gunFrameScale(d) else 1f)
        val c = box.center
        val held = feed?.ratchetHeld == true
        LaserTrace.beam(m, beam)
        // The way colours (opacity applied), in LaserTrace.directionColor's order.
        for ((i, mc) in GyroDisplayColor.LASER_MOVES.withIndex()) moveColors[i] = col(mc).toArgb().toLong() and 0xFFFFFFFFL
        val moveAlpha = moveColors.sumOf { it shr 24 and 0xFF } / (6f * 255f)
        val lt = feed?.laser
        if (lt != null) {
            val v = feed?.pose?.version ?: 0L
            if (v != lt.fedVersion) {
                lt.fedVersion = v
                lt.add(
                    // Draw each shot: firing paints its hits, not a Fire line.
                    beam[0], beam[1], beam[2], now, d.laserDraw, held, d.laserClearOnRatchet,
                    feed?.firing == true && !(d.laserShotDraw && d.laserDraw), d.laserFireOnly,
                    d.laserSmoothColours, d.laserLineSmoothing,
                    brushDeg = d.laserBrush.coerceIn(1, 20) * BRUSH_WIDTH,
                    snakeDeg = if (d.laserSnake) d.laserSnakeDeg.toFloat() else 0f,
                    wayColors = if (d.laserColourByWay) moveColors else null,
                    mapWidth = PAINT_WIDTHS[d.laserPaintDetail.coerceIn(0, 2)],
                    soft = d.laserSoftness,
                    aim = feed?.aiming == true,
                    fireBrushDeg = d.laserFireBrush.coerceIn(1, 20) * BRUSH_WIDTH,
                    aimBrushDeg = d.laserAimBrush.coerceIn(1, 20) * BRUSH_WIDTH,
                    clearSnakeOnPress = d.laserSnakeClearOnRatchet,
                )
            }
        }
        val rifle = d.laserRifle
        val crosshair = d.laserCrosshair && !rifle
        val ks = shots
        val rad = (Math.PI / 180).toFloat()
        val gunNow = d.gun()
        // Simulate spread: how far off a shot lands now (radians, the gun's spread, bloomed).
        val spread = if (ks == null || !d.laserSpread) 0f else d.spreadOf(gunNow).coerceIn(0f, 20f) * rad * (if (d.laserBloom) ks.bloom else 1f)
        // Draw each shot: each new shot marks its hits (its pellets) where they land.
        if (lt != null && ks != null && ks.shots != lt.hitShots) {
            lt.hitShots = ks.shots
            if (d.laserShotDraw && d.laserDraw && !held) {
                val half = d.laserFireBrush.coerceIn(1, 20) * BRUSH_WIDTH / 2f * rad * (if (ks.pellets > 1) 0.5f else 1f)
                for (i in 0 until ks.pellets) {
                    LaserTrace.shotPoint(m, beam, ks.offsets[i * 2] * spread, ks.offsets[i * 2 + 1] * spread, shotDir)
                    lt.addHit(shotDir[0], shotDir[1], shotDir[2], half)
                }
            }
        }
        // The crosshair and gun kick toward where the shot landed and spring back with the kick.
        if ((rifle || crosshair) && ks != null && spread > 0f && ks.shotAt >= 0L && kick != 0f) {
            if (laserTurned === m) laserTurned = m.copyInto(turnedCopy)
            LaserTrace.shotPoint(m, beam, ks.towardX * spread, ks.towardY * spread, shotDir)
            LaserTrace.turnToward(m, beam, shotDir, kick.coerceIn(-0.5f, 1.5f))
        }
        val sphere = col(GyroDisplayColor.LASER_SPHERE)
        if (!rifle && !crosshair) {
        val lit = Color(sphere.red + (1 - sphere.red) * 0.35f, sphere.green + (1 - sphere.green) * 0.35f, sphere.blue + (1 - sphere.blue) * 0.35f, sphere.alpha)
        drawCircle(sphereBrush(lit, sphere, c, r), r, c)
        }
        val grid = col(GyroDisplayColor.LASER_GRID)
        val thin = thinStroke(density)
        val (gridFront, gridBack) = laserGrid(laserAxon, laserTurned)
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
        val dash = lockDash(density)
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
        // Draw mode's paint: the paint map drawn on the sphere by the GPU, its near or far half.
        val look = if (lt?.paint == null || !d.laserDraw) null else paintLook.apply {
            eye = if (laserPersp) LaserTrace.CAMERA_DISTANCE else 0f
            byWay = d.laserColourByWay; glow = d.laserGlow; outline = d.laserOutline
            shade = d.laserShade; inside = d.laserInside; light = d.laserLight; lightFrom = d.laserLightFrom; shine = d.laserShine
            for (i in 0..5) moves[i] = moveColors[i].toInt()
            fireAhead = col(GyroDisplayColor.LASER_FIRE).toArgb(); fireBehind = col(GyroDisplayColor.LASER_FIRE_BEHIND).toArgb()
            aimAhead = col(GyroDisplayColor.LASER_AIM).toArgb(); aimBehind = col(GyroDisplayColor.LASER_AIM_BEHIND).toArgb()
            fireGlow = d.laserFireGlow; aimGlow = d.laserAimGlow
            outlineColor = col(GyroDisplayColor.LASER_OUTLINE).toArgb(); wayAlpha = moveAlpha
        }
        fun paint(front: Boolean) {
            val t = lt ?: return
            val lp = t.paint
            if (lp != null && look != null) {
                paintShaders.getOrPut(t) { PointerPaintShader() }.draw(drawContext.canvas.nativeCanvas, lp, front, c.x, c.y, r, viewOf, sceneOf, look, now)
            }
            if (d.laserSnake) snake(t, d, front, c, r, col)
            if (d.laserShotDraw && d.laserDraw) hits(t, d, front, c, r, col)
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
            // The gun look: no sphere or beam; the paint still lands where it aims.
            axes(false)
            paint(false)
            val body = col(GyroDisplayColor.LASER_RIFLE)
            val gun = d.gun()
            // Kick size scales the gun's own recoil (50% is the gun as it is).
            val kickK = d.kickOf().coerceIn(0f, 3f) * 2f
            for (f in LaserTrace.rifle(m, laserAxon, laserTurned, laserPersp, aim, kick * kickK, gun, lower, d.gunScope, hip)) {
                path.reset()
                for (i in 0 until f.xy.size / 2) {
                    val x = c.x + f.xy[i * 2] * r
                    val y = c.y - f.xy[i * 2 + 1] * r
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                val k = f.light
                drawPath(path, Color(body.red * k, body.green * k, body.blue * k, body.alpha))
                drawPath(path, Color(body.red * 0.5f, body.green * 0.5f, body.blue * 0.5f, body.alpha), style = Stroke(density * 0.75f, join = StrokeJoin.Round))
            }
            if (aim > 0f) laserSight(c, r, col(GyroDisplayColor.LASER_AIM), gun, kickK)
            val fm = flashMs(d)
            if (fm >= 0f) muzzleFlash(c, r, fm, col(GyroDisplayColor.LASER_FLASH), col(GyroDisplayColor.LASER_FLASH_END), gun, kickK)
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
        val gimbal = if (d.laserGimbal) gimbalPts.also { for (i in 0..2) LaserTrace.gimbalRing(i, m, out = it[i]) } else null
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

    private val sway = FloatArray(3)

    /** The edge's sway now (x, y degrees and its turn) into [sway]. */
    private fun edgeSway(runtime: GamepadRuntime, v: TouchVisuals) {
        if (v.gyroEdgeTilt) {
            runtime.worldPose.matrix(raw)
            level(v.gyroLevelDeg)
            // Tilt as if turning: rolling right reads as a turn right, pitching up as up.
            sway[0] = -horizonRollDeg()
            sway[1] = pitchDeg()
            sway[2] = 0f
        } else {
            val s = runtime.gyroSway
            sway[0] = s.x; sway[1] = s.y; sway[2] = s.rot
        }
    }

    /**
     * What the edge's picture depends on that moves, into [out] ([EDGE_STATE] numbers): its outer
     * outline's shift in pixels (x, y), its turn in degrees and the live gyro axes (its tint).
     * The edge is drawn again once one moved by its [EDGE_STEP].
     */
    fun edgeState(runtime: GamepadRuntime, v: TouchVisuals, density: Float, out: FloatArray) {
        edgeSway(runtime, v)
        val k = EDGE_PX_PER_DEG * density * v.gyroEdgeStrength
        out[0] = sway[0] * k
        out[1] = sway[1] * k
        out[2] = sway[2]
        out[3] = runtime.gyroLiveAxes.toFloat()
    }

    fun drawEdge(scope: DrawScope, runtime: GamepadRuntime, v: TouchVisuals) = with(scope) {
        val op = v.gyroOpacity.coerceIn(0.05f, 1f)
        val live = runtime.gyroLiveAxes
        edgeSway(runtime, v)
        val sx = sway[0]
        val sy = sway[1]
        val rot = sway[2]
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

/** How long a pointer's Aim display takes to ease fully in or out. */
private const val AIM_EASE_MS = 120f

/** The world's axes in the axonometric view: right, up, toward you. */
// By turn, as the gimbal's rings: right is the pitch axis (green), up roll (blue), toward you yaw (red).
private val WORLD_AXES = arrayOf(Color(0xFF5ADB6E), Color(0xFF5AA8FF), Color(0xFFFF5A5A))

/** The paint map's width in texels for Paint detail low, normal, high. */
private val PAINT_WIDTHS = intArrayOf(512, 1024, 2048)

/** A pointer's paint is this many degrees wide per Brush degree. */
private const val BRUSH_WIDTH = 0.6f

// A Draw each shot hit's corners.
private const val HIT_SIDES = 12

// The Snake's glow: copies this many times its width, at this share of the glow's strength (widest first).
private val SNAKE_GLOW = listOf(3.2f to 0.18f, 2.2f to 0.3f, 1.5f to 0.45f)

/** The edge's swing per degree, its furthest swing, each layer's step inwards and the outer corner radius. */
private const val EDGE_PX_PER_DEG = 1.5f
private const val EDGE_MAX_DP = 80f
private const val EDGE_GAP_DP = 10f
private const val EDGE_CORNER_DP = 34f

// The parallax edge's numbers that move ([VisState.edgeState]), and how far one moves before it is drawn again
// (a quarter pixel; a turn of a hundredth of a degree).
private const val EDGE_STATE = 4
private val EDGE_STEP = floatArrayOf(0.25f, 0.25f, 0.01f, 0.5f)

// How far a gyro display's shown pose (a matrix entry) moves before it is drawn again: about 0.05°.
private const val VISIBLE_TURN = 0.0009f


/** Yaw, pitch, roll: the edge's tint. */
private val AXIS_COLORS = arrayOf(Color(0xFFFF5A5A), Color(0xFF5ADB6E), Color(0xFF5AA8FF))
private val RING_AXES = MotionAxis.shown.toTypedArray()
private val RING_LETTERS = arrayOf("Y", "P", "R")

/** The laser pointer's axis letters, X, Y, Z: pitch, yaw, roll as it aims (facing −Z). */
private val LASER_AXIS_LETTERS = arrayOf("P", "Y", "R")
