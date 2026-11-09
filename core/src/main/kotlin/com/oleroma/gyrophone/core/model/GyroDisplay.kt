package com.oleroma.gyrophone.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlin.math.cos
import kotlin.math.sin

/** What a gyro display ([TouchKind.DISPLAY]) shows. */
@Serializable
enum class GyroDisplayKind(val label: String) {
    /** A 3D phone turned by the gyro. Also reads the removed "3D camera" (CAMERA), so a saved one cannot hide the profile. */
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("CAMERA")
    PHONE("3D phone"),

    /** Yaw / pitch / roll arcs, lit while that axis moves the mouse, dim while a ratchet pauses it. */
    RATCHET_RING("Ratchet ring"),

    /** A scrolling graph of yaw / pitch / roll speed, ratchet presses shaded. */
    GRAPH("Speed graph"),

    /** An aircraft-style horizon, with a heading tape. */
    HORIZON("Attitude indicator"),

    /** A disk with clock marks that spins with the heading, a dot showing the tilt. */
    DISK("Bubble disk"),

    /** A 3D shape ([BallShape]: a ball with lines on it, a solid, a torus...), turned with the phone. */
    BALL("3D shape"),

    /** A see-through sphere seen from a fixed angle, a laser at its centre shining out of the phone's back onto its inside. */
    LASER("Pointer"),

    ;

    /** It shows the phone's pose ([GyroDisplay] pose settings apply). */
    val pose: Boolean get() = this == PHONE || this == HORIZON || this == DISK || this == BALL || this == LASER
}

/**
 * A gyro display's settings ([TouchControl.display]), structured as the PC overlays' are
 * (`OverlaySettings` in the companion). The pose ones apply to the kinds that show the phone's
 * pose ([GyroDisplayKind.pose]):
 * - [gravity]: its tilt held to the world, so it never drifts; off, turned by the gyro (only the
 *   axes that move the mouse) since it started. [compass] (with gravity): its heading held by the
 *   magnetometer to the way the phone faced when the app opened, edit mode closed or the workspace
 *   changed (a ratchet press aims it again): 0° there, wherever north is.
 * - [ease]: it goes back to straight on its own (the heading only with gravity, or with
 *   [easeHeadingOnly]): with [easeOnRatchet] while a ratchet is held, until straight (stopping when
 *   let go), else whenever the phone stops turning; starting [easeDelayMs] later; 95% of the way
 *   back in [easeSeconds], or with [easeBySpeed] at [easeSpeed] deg/s.
 * - [rest] (Level): the resting grip as a quaternion w, x, y, z: held this way it shows face-on,
 *   its screen flat toward the viewer. Only with gravity (without, Level starts the pose again);
 *   null = upright, facing the player.
 * - [motionScale]: turns shown this many times larger.
 *
 * Constructor defaults are those of displays saved before [v] 2 (saved files leave defaults out);
 * a new one starts from [create]. Older ones are carried over by [migrated].
 *
 * [colors]: ARGB per [GyroDisplayColor.key], missing = its default.
 */
@Serializable
data class GyroDisplay(
    val kind: GyroDisplayKind,
    /** Settings layout: 1 = before the PC overlays' (ratchet reset, level angle), 2 = as the PC's, 3 = [turnsWith]. */
    val v: Int = 1,
    val gravity: Boolean = false,
    val compass: Boolean = false,
    val ease: Boolean = false,
    val easeOnRatchet: Boolean = true,
    val easeHeadingOnly: Boolean = false,
    val easeDelayMs: Int = 300,
    val easeBySpeed: Boolean = false,
    val easeSeconds: Float = 0.5f,
    val easeSpeed: Int = 90,
    val rest: List<Float>? = null,
    /**
     * Which way was up at the last Level (the accelerometer in the phone's frame, unit length), kept
     * with or without gravity: "Fade when phone rests" ([RestFade]) counts the phone flat near it.
     */
    val levelUp: List<Float>? = null,
    val motionScale: Float = 1f,
    /** v1: each ratchet press set it straight (the whole pose, or with [resetHeadingOnly] the heading). */
    val resetOnRatchet: Boolean = true,
    val resetHeadingOnly: Boolean = false,
    /** v1: easing started when the ratchet was let go, instead of when the phone stopped. */
    val easeAfterRatchet: Boolean = false,
    /** v1: the real pose shown square on, 0 upright facing the player, 90 flat screen up. */
    val levelDeg: Int = 0,
    val ghosts: Boolean = false,
    val axes: Boolean = false,
    /** GRAPH: its full height, ± this many deg/s. */
    val graphRange: Int = 360,
    val graphSeconds: Int = 3,
    /** DISK: the tilt that puts the dot on the rim, in degrees. */
    val diskRange: Int = 30,
    val crosshair: Boolean = true,
    /** BALL: an icosphere instead of the globe (from before [shape]; see [ballShape]). */
    val icosphere: Boolean = false,
    /** BALL: what it shows; [subdivision] (icosphere) 0 to 3. */
    val shape: BallShape = BallShape.GLOBE,
    val subdivision: Int = 1,
    /** BALL: edges bent onto a ball (round rings for the torus and gimbal), or straight. */
    val smooth: Boolean = true,
    /** BALL: the edges round the back, faint. */
    val hiddenEdges: Boolean = true,
    /** BALL: the faces filled, shaded by how squarely they face the viewer (a smooth ball: one shaded disk). */
    val fillFaces: Boolean = true,
    /** BALL: the equator line (globe only), the mark where the screen faces and the mark at the top. */
    val equator: Boolean = true,
    val screenMark: Boolean = true,
    val topMark: Boolean = true,
    /** BALL, v2: a trackball ([turnsWith] [TurnsWith.TRACKBALL] since v3). */
    val trackball: Boolean = false,
    /**
     * [TurnsWith.CAMERA] and [TurnsWith.TRACKBALL]: with [trackballRws] as far as the game's camera
     * once the game's turn is known ([GyroSettings.gameCountsPer360]), else as far as the phone;
     * without, [trackballSpin] times the phone's turn.
     */
    val trackballSpin: Float = 1f,
    val trackballRws: Boolean = true,
    /**
     * [TurnsWith.TRACKBALL]: which gyro turn rolls it about each of its axes, one byte each: about
     * its up axis Y (yaw, turning left and right), its right axis X (pitch), the axis along the beam Z (roll,
     * through the screen at rest); each byte 1 yaw (stored), 2 pitch, 3 roll (stored), 0 none, | 0x80 the other way, | [TRACKBALL_STOP], | [TRACKBALL_LOCK]
     * locked (held at 0: then it turns as a gimbal, as a game camera does).
     * The default turns it as the Camera mode does with the default gyro mouse ([TRACKBALL_AS_CAMERA]).
     */
    val trackballAxes: Int = TRACKBALL_AS_CAMERA,
    /**
     * [TurnsWith.TRACKBALL]: easing each of its axes (as [trackballAxes]: up, right, through the
     * screen) back to straight, each with its own options. Easing any turns it as a gimbal, as a lock does.
     */
    val trackballEase: List<AxisEase> = List(3) { AxisEase() },
    /**
     * [TurnsWith.TRACKBALL], Turns about: its own axes as it now points (up and down about its own
     * right, left and right about its own up), so the laser always moves square to where it aims and
     * never rolls by itself; a None axis never turns it and there are no stops (no poles). Moving in
     * a loop leaves it a little rolled, which roll's ease back levels out. Off: the sphere's axes, as
     * a game camera (a gimbal: left and right about the vertical, up and down about its turned right,
     * then about the beam), a None axis held at 0.
     */
    val trackballOwnAxes: Boolean = false,
    /** Branch builds before v3: a trackball turning as the camera ([TurnsWith.CAMERA] since). */
    val trackballInvert: Boolean = false,
    /** PHONE and BALL: seen from a fixed angle (30° round, 30° above) with the world's axes, as the laser pointer is, instead of face-on. */
    val axonometric: Boolean = false,
    /** LASER: the 15° grid on the sphere, and the laser's three axes (turning with it). */
    val laserGrid: Boolean = true,
    val laserAxes: Boolean = true,
    /** LASER, Sphere look: the gimbal's yaw, pitch and roll rings ([com.oleroma.gyrophone.core.engine.LaserTrace.gimbalRing]), a locked axis's faint. */
    val laserGimbal: Boolean = false,
    /** LASER: seen from the fixed axonometric angle (30° round, 30° above); off, face-on. */
    val laserAxonometric: Boolean = true,
    /** LASER: the beam stays pointing ahead and the sphere (grid, axes, paint) turns under it instead. */
    val laserSphereTurns: Boolean = false,
    /** LASER: a rifle aiming instead of the sphere and beam; Draw mode still paints where it points. */
    val laserRifle: Boolean = false,
    /**
     * LASER: a crosshair where the beam lands instead of the sphere and beam (not with [laserRifle]):
     * lying on the sphere facing its centre, shape [crosshairShape]
     * ([com.oleroma.gyrophone.core.engine.LaserTrace.CROSSHAIRS]), arms [crosshairSize] degrees long
     * from a [crosshairGap] degree gap, lines [crosshairThickness] dp, a centre dot, a dark outline;
     * turning about the beam as the pose does (a trackball's roll axis as mapped).
     */
    val laserCrosshair: Boolean = false,
    val crosshairShape: Int = 1,
    val crosshairSize: Int = 6,
    val crosshairGap: Int = 2,
    val crosshairThickness: Float = 2f,
    val crosshairDot: Boolean = true,
    val crosshairOutline: Boolean = true,
    /** LASER turned as a trackball: which way of it the laser faces ([com.oleroma.gyrophone.core.engine.LaserTrace.FACES], 0 −Z into the screen). */
    val laserFaces: Int = 0,
    /** LASER: Draw mode, the beam paints the sphere's inside, [laserBrush] degrees round; it fades after [laserFadeMs] ms (0 = never). */
    val laserDraw: Boolean = false,
    val laserBrush: Int = 4,
    val laserFadeMs: Int = 0,
    /** LASER Draw mode: keeps only the newest [laserLength] degrees of painted line (0 = all); with fading it also fades along that length. */
    val laserLength: Int = 0,
    /** LASER Draw mode: a ratchet press clears the drawing. */
    val laserClearOnRatchet: Boolean = false,
    /**
     * LASER Draw mode: while GyroPhone sends this output ([com.oleroma.gyrophone.core.engine.LaserFire],
     * default the left mouse button) it paints in the Fire colours; with [laserFireOnly] only then.
     */
    val laserFire: Int = com.oleroma.gyrophone.core.engine.LaserFire.DEFAULT,
    val laserFireOnly: Boolean = false,
    /** LASER: the paint's and crosshair's way colours even out small shakes. */
    val laserSmoothColours: Boolean = true,
    /**
     * LASER Draw mode's strokes: [laserColourSteps] shades per colour
     * channel (4..32); [laserCurve] rounds the corners between points (0 straight .. 1 round);
     * [laserLineSmoothing] evens out hand shake in the painted path (0 off .. 1 strong).
     */
    val laserColourSteps: Int = 16,
    val laserCurve: Float = 0.5f,
    val laserLineSmoothing: Float = 0f,
    /**
     * LASER: [laserPaint3d] lights the paint by the sphere's light (from the upper left) as strongly as [laserLighting] (0..1).
     * The Sphere look's [laserGloss] (a shine where the light hits) and [laserRimGlow] (a soft bright edge).
     */
    val laserPaint3d: Boolean = true,
    /** LASER: where the light comes from: [laserLightFrom] degrees round the picture (0 right, 90 top, 180 left), [laserLightHeight] degrees up toward the viewer. */
    val laserLightFrom: Int = 130,
    val laserLightHeight: Int = 58,
    /** LASER Draw mode: the glow round the paint (0 none .. 1 strong), a dark [GyroDisplayColor.LASER_OUTLINE] edge, older paint darker. */
    val laserGlow: Float = 0.5f,
    val laserOutline: Boolean = false,
    val laserAgeDim: Boolean = false,
    /** LASER Draw mode: the paint gets thinner toward the sphere's rim (and with [perspective] bigger nearer), changing smoothly along the line. */
    val laserPaintWidth: Boolean = true,
    val laserLighting: Float = 0.6f,
    val laserGloss: Boolean = true,
    val laserRimGlow: Boolean = true,
    /** 3D phone, 3D shape and laser pointer: seen in perspective (nearer parts bigger); off, flat (no perspective). */
    val perspective: Boolean = true,
    /** What turns it (the pose kinds): the phone, the game's camera, or a trackball. */
    val turnsWith: TurnsWith = TurnsWith.PHONE,
    val opacity: Float = 0.7f,
    val colors: Map<String, Long> = emptyMap(),
) {
    /** It turns with the game's camera ([TurnsWith.CAMERA] or [TurnsWith.TRACKBALL]): no gravity, compass or turn amount. */
    val followsCamera: Boolean get() = kind.pose && turnsWith != TurnsWith.PHONE

    /** [rest] as an array, only while it applies (with gravity), else null. */
    val restNow: FloatArray? get() = if (needsGravity) rest?.takeIf { it.size == 4 }?.toFloatArray() else null

    /**
     * This one in the current layout ([v] 2), carried over as the PC overlays' were: Reset on
     * ratchet becomes Ease on ratchet (its Heading only with it); off, easing stays as it was, when
     * the phone stops ("When the ratchet is let go" is gone). The level angle becomes the resting grip.
     */
    fun migrated(): GyroDisplay {
        if (v >= VERSION) return this
        val turns = when {
            turnsWith != TurnsWith.PHONE -> turnsWith
            kind == GyroDisplayKind.BALL && trackball -> if (trackballInvert) TurnsWith.CAMERA else TurnsWith.TRACKBALL
            else -> TurnsWith.PHONE
        }
        if (v >= 2) return copy(v = VERSION, turnsWith = turns, trackball = false, trackballInvert = false)
        val ratchet = resetOnRatchet
        val restQ = if (levelDeg == 0) null else {
            // Tipped back levelDeg about the phone's right edge (the screen toward the sky).
            val a = Math.toRadians(levelDeg.toDouble()) / 2
            listOf(cos(a).toFloat(), -sin(a).toFloat(), 0f, 0f)
        }
        return copy(
            v = VERSION,
            ease = ratchet || ease,
            easeOnRatchet = ratchet,
            easeHeadingOnly = ratchet && resetHeadingOnly,
            rest = restQ,
            resetOnRatchet = true, resetHeadingOnly = false, easeAfterRatchet = false, levelDeg = 0,
            turnsWith = turns, trackball = false, trackballInvert = false,
        )
    }

    /** It holds its pose to gravity (and so needs the accelerometer). */
    val needsGravity: Boolean get() = kind.pose && gravity && !followsCamera

    /** The shape a BALL shows. */
    val ballShape: BallShape get() = if (icosphere && shape == BallShape.GLOBE) BallShape.ICOSPHERE else shape

    /** A [trackball] ball. */
    val isTrackball: Boolean get() = kind.pose && turnsWith == TurnsWith.TRACKBALL

    /** It holds its heading to north (and so needs the magnetometer). */
    val needsCompass: Boolean get() = needsGravity && compass

    /** Colour [c] as ARGB: its own or the default. */
    fun color(c: GyroDisplayColor): Long = colors[c.key] ?: c.default

    fun withColor(c: GyroDisplayColor, argb: Long?): GyroDisplay =
        copy(colors = if (argb == null || argb == c.default) colors - c.key else colors + (c.key to argb))

    companion object {
        /** The current settings layout ([v]). */
        const val VERSION = 3

        /** A new display of [kind], with the PC overlays' defaults: gravity, easing back on ratchet over 2 s. */
        fun create(kind: GyroDisplayKind) = GyroDisplay(kind, v = VERSION, gravity = true, ease = true, easeSeconds = 2f)

        /** Size (of the shorter side) and height / width a new one of [kind] starts with. */
        fun newSize(kind: GyroDisplayKind): Pair<Float, Float> = when (kind) {
            GyroDisplayKind.PHONE -> 0.5f to 1f
            GyroDisplayKind.RATCHET_RING -> 0.16f to 1f
            GyroDisplayKind.GRAPH -> 1.1f to 0.3f
            GyroDisplayKind.HORIZON -> 0.4f to 1.25f
            GyroDisplayKind.DISK -> 0.4f to 1f
            GyroDisplayKind.BALL -> 0.4f to 1f
            GyroDisplayKind.LASER -> 0.45f to 1f
        }
    }
}

/** What turns a pose display ([GyroDisplay.turnsWith]). */
@Serializable
enum class TurnsWith(val label: String) {
    /** How the phone is held: gravity, compass, ease back. */
    PHONE("Phone"),

    /** Where the game's camera points: every camera move (gyro, touchpad, buttons...), as far as the camera turns. */
    CAMERA("Camera"),

    /** As [CAMERA] but rolling the other way, like a ball under the hand: still against the world while the gyro aims. */
    TRACKBALL("Trackball"),
}

/** What a 3D shape display ([GyroDisplayKind.BALL]) shows. */
@Serializable
enum class BallShape(val label: String) {
    GLOBE("Globe"),
    ICOSPHERE("Icosphere"),
    CUBE("Cube"),
    TETRAHEDRON("Tetrahedron"),
    DODECAHEDRON("Dodecahedron"),
    TORUS("Torus"),

    /** Three rings, one per axis (yaw, pitch, roll), lit while that axis moves the mouse. */
    GIMBAL("Gimbal rings"),
    PLANE("Paper plane"),

    /** A camera seen from behind: its lens points into the screen, where the camera looks. */
    CAMERA("Camera"),
}

/** A colour a gyro display can change, per [kind]; [default] is ARGB. */
enum class GyroDisplayColor(val kind: GyroDisplayKind, val key: String, val label: String, val default: Long) {
    PHONE_FRONT(GyroDisplayKind.PHONE, "front", "Front", 0xFF1B2330),
    PHONE_BACK(GyroDisplayKind.PHONE, "back", "Back", 0xFF3A4352),
    PHONE_SIDE(GyroDisplayKind.PHONE, "side", "Sides", 0xFF59647A),
    PHONE_EDGE(GyroDisplayKind.PHONE, "edge", "Outline", 0xCCFFFFFF),
    PHONE_X(GyroDisplayKind.PHONE, "x", "Right arrow", 0xFFFF5A5A),
    PHONE_Y(GyroDisplayKind.PHONE, "y", "Up arrow", 0xFF5ADB6E),
    PHONE_Z(GyroDisplayKind.PHONE, "z", "Screen arrow", 0xFF5AA8FF),
    RING_YAW(GyroDisplayKind.RATCHET_RING, "yaw", "Yaw", 0xFFFF5A5A),
    RING_PITCH(GyroDisplayKind.RATCHET_RING, "pitch", "Pitch", 0xFF5ADB6E),
    RING_ROLL(GyroDisplayKind.RATCHET_RING, "roll", "Roll", 0xFF5AA8FF),
    RING_TEXT(GyroDisplayKind.RATCHET_RING, "text", "Letters", 0xE6FFFFFF),
    GRAPH_YAW(GyroDisplayKind.GRAPH, "yaw", "Yaw", 0xFFFF5A5A),
    GRAPH_PITCH(GyroDisplayKind.GRAPH, "pitch", "Pitch", 0xFF5ADB6E),
    GRAPH_ROLL(GyroDisplayKind.GRAPH, "roll", "Roll", 0xFF5AA8FF),
    GRAPH_BACK(GyroDisplayKind.GRAPH, "back", "Background", 0x59000000),
    GRAPH_HELD(GyroDisplayKind.GRAPH, "held", "Ratchet shading", 0x1FFFFFFF),
    HORIZON_SKY(GyroDisplayKind.HORIZON, "sky", "Sky", 0xFF2F6DB5),
    HORIZON_GROUND(GyroDisplayKind.HORIZON, "ground", "Ground", 0xFF7A5534),
    HORIZON_LINE(GyroDisplayKind.HORIZON, "line", "Horizon line", 0xFFFFFFFF),
    HORIZON_LADDER(GyroDisplayKind.HORIZON, "ladder", "Pitch ladder", 0xB3FFFFFF),
    HORIZON_AIRCRAFT(GyroDisplayKind.HORIZON, "aircraft", "Aircraft symbol", 0xFFFFC94D),
    HORIZON_RIM(GyroDisplayKind.HORIZON, "rim", "Rim", 0xCCFFFFFF),
    HORIZON_TAPE(GyroDisplayKind.HORIZON, "tape", "Heading tape", 0x66000000),
    HORIZON_TEXT(GyroDisplayKind.HORIZON, "text", "Heading numbers", 0xFFFFFFFF),
    DISK_FACE(GyroDisplayKind.DISK, "face", "Disk", 0x66000000),
    DISK_RIM(GyroDisplayKind.DISK, "rim", "Rim", 0xCCFFFFFF),
    DISK_MARKS(GyroDisplayKind.DISK, "marks", "Marks", 0xCCFFFFFF),
    DISK_NORTH(GyroDisplayKind.DISK, "north", "12 o'clock mark", 0xFFFF5A5A),
    DISK_CROSS(GyroDisplayKind.DISK, "cross", "Crosshair", 0x80FFFFFF),
    DISK_DOT(GyroDisplayKind.DISK, "dot", "Dot", 0xFFFFC94D),
    BALL_BODY(GyroDisplayKind.BALL, "body", "Fill", 0xFF2A3446),
    BALL_LINES(GyroDisplayKind.BALL, "lines", "Lines", 0x99FFFFFF),
    BALL_EQUATOR(GyroDisplayKind.BALL, "equator", "Equator", 0xFF5AA8FF),
    BALL_MARK(GyroDisplayKind.BALL, "mark", "Screen mark", 0xFFFFC94D),
    BALL_POLE(GyroDisplayKind.BALL, "pole", "Top mark", 0xFFFF5A5A),
    BALL_GIMBAL_YAW(GyroDisplayKind.BALL, "gyaw", "Gimbal yaw ring", 0xFFFF5A5A),
    BALL_GIMBAL_PITCH(GyroDisplayKind.BALL, "gpitch", "Gimbal pitch ring", 0xFF5ADB6E),
    BALL_GIMBAL_ROLL(GyroDisplayKind.BALL, "groll", "Gimbal roll ring", 0xFF5AA8FF),
    LASER_SPHERE(GyroDisplayKind.LASER, "sphere", "Sphere", 0x332A3446),
    LASER_GRID(GyroDisplayKind.LASER, "grid", "Grid", 0x66FFFFFF),
    LASER_X(GyroDisplayKind.LASER, "x", "Pitch axis (X, right)", 0xFFFF5A5A),
    LASER_Y(GyroDisplayKind.LASER, "y", "Yaw axis (Y, up)", 0xFF5ADB6E),
    LASER_Z(GyroDisplayKind.LASER, "z", "Roll axis (Z, along the beam)", 0xFF5AA8FF),
    LASER_BEAM(GyroDisplayKind.LASER, "beam", "Beam", 0xFFFF3B3B),
    LASER_DOT(GyroDisplayKind.LASER, "dot", "Dot", 0xFFFFFFFF),
    // The colour of each way the beam moves (paint and crosshair), as a normal map: 0.5 + 0.5 × the way.
    LASER_MOVE_RIGHT(GyroDisplayKind.LASER, "move_right", "Moving right", 0xFFFF8080),
    LASER_MOVE_LEFT(GyroDisplayKind.LASER, "move_left", "Moving left", 0xFF008080),
    LASER_MOVE_UP(GyroDisplayKind.LASER, "move_up", "Moving up", 0xFF80FF80),
    LASER_MOVE_DOWN(GyroDisplayKind.LASER, "move_down", "Moving down", 0xFF800080),
    LASER_MOVE_TOWARD(GyroDisplayKind.LASER, "move_toward", "Moving toward you", 0xFF8080FF),
    LASER_MOVE_AWAY(GyroDisplayKind.LASER, "move_away", "Moving away", 0xFF808000),
    LASER_GLOSS(GyroDisplayKind.LASER, "gloss", "Gloss", 0x99FFFFFF),
    LASER_RIM(GyroDisplayKind.LASER, "rim", "Rim glow", 0x66BFE3FF),
    LASER_OUTLINE(GyroDisplayKind.LASER, "outline", "Paint outline", 0xB3000000),
    LASER_RIFLE(GyroDisplayKind.LASER, "rifle", "Rifle", 0xFF5A616C),
    LASER_FIRE(GyroDisplayKind.LASER, "fire", "Fire", 0xFFFF4A3D),
    LASER_FIRE_BEHIND(GyroDisplayKind.LASER, "fire_behind", "Fire behind", 0xFF8A1E18),
    LASER_CROSSHAIR_OUTLINE(GyroDisplayKind.LASER, "crosshair_outline", "Crosshair outline", 0xFF0E1116),
    ;

    companion object {
        fun of(kind: GyroDisplayKind): List<GyroDisplayColor> = entries.filter { it.kind == kind }

        /** The laser's way colours, in [com.oleroma.gyrophone.core.engine.LaserTrace.directionColor]'s order. */
        val LASER_MOVES = listOf(LASER_MOVE_RIGHT, LASER_MOVE_LEFT, LASER_MOVE_UP, LASER_MOVE_DOWN, LASER_MOVE_TOWARD, LASER_MOVE_AWAY)
    }
}

/**
 * A trackball axis easing back to straight ([GyroDisplay.trackballEase]), as [GyroDisplay.ease] for
 * the whole pose: [on]; with [onRatchet] while a ratchet is held (stopping when let go), else
 * whenever the phone stops turning; starting [delayMs] later; 95% of the way back in [seconds], or
 * with [bySpeed] at [speed] deg/s.
 */
@Serializable
data class AxisEase(
    val on: Boolean = false,
    val onRatchet: Boolean = true,
    val delayMs: Int = 300,
    val bySpeed: Boolean = false,
    val seconds: Float = 2f,
    val speed: Int = 90,
)

/** Trackball axis [i]'s easing ([GyroDisplay.trackballEase]; off when not saved). */
fun GyroDisplay.easeOf(i: Int): AxisEase = trackballEase.getOrNull(i) ?: AxisEase()

/** This display with trackball axis [i] easing as [e]. */
fun GyroDisplay.withEase(i: Int, e: AxisEase): GyroDisplay = copy(trackballEase = List(3) { if (it == i) e else easeOf(it) })

/**
 * The gimbal axes held at 0 (bit 0 yaw, 1 pitch, 2 roll): a trackball's locked rows, Camera's roll
 * (it never rolls), none turning with the phone.
 */
val GyroDisplay.gimbalLocks: Int
    get() = when (turnsWith) {
        TurnsWith.TRACKBALL -> trackballLocks(trackballAxes)
        TurnsWith.CAMERA -> 4
        else -> 0
    }

/**
 * A trackball's locked axes in [axes] ([GyroDisplay.trackballAxes]; bit 0 yaw, 1 pitch, 2 roll): those
 * no gyro turn drives (None), held at 0; and those saved with [TRACKBALL_LOCK] before None meant it.
 */
fun trackballLocks(axes: Int): Int = (0..2).fold(0) { acc, i ->
    val code = axes shr (8 * i) and 0xFF
    if (code and 0x1F == 0 || code and TRACKBALL_LOCK != 0) acc or (1 shl i) else acc
}

/**
 * In a [GyroDisplay.trackballAxes] byte: that axis is locked, held at 0 whatever turns the others
 * make, as Camera's roll is. With any axis locked, stopped ([TRACKBALL_STOP]) or easing back the
 * trackball turns as a gimbal (left and right about the vertical, then up and down about its own
 * right, then about where it faces).
 */
const val TRACKBALL_LOCK = 0x40

/**
 * In a [GyroDisplay.trackballAxes] byte: that axis stops at ±90° from straight (up and down at ±89°,
 * the poles); without, it turns all the way round (up and down over the top, left and right then
 * staying as seen).
 */
const val TRACKBALL_STOP = 0x20

/**
 * [GyroDisplay.trackballAxes] turning a trackball as the camera turns with the default gyro mouse
 * (roll on mouse X, pitch on mouse Y): left and right by roll (stored), up and down by pitch, the
 * axis through the screen locked (no roll, stopping at straight up or down).
 */
const val TRACKBALL_AS_CAMERA = 3 or ((2 or TRACKBALL_STOP) shl 8) or (TRACKBALL_LOCK shl 16)
