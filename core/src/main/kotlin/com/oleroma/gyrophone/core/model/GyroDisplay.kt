package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

/** What a gyro display ([TouchKind.DISPLAY]) shows. */
@Serializable
enum class GyroDisplayKind(val label: String) {
    /** A 3D phone turned by the gyro. */
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
    ;

    /** It shows the phone's pose ([GyroDisplay] pose settings apply). */
    val pose: Boolean get() = this == PHONE || this == HORIZON || this == DISK || this == BALL
}

/**
 * A gyro display's settings ([TouchControl.display]). The pose ones apply to the kinds that show
 * the phone's pose ([GyroDisplayKind.pose]):
 * - [gravity]: its tilt held to the world, so it never drifts; off, turned by the gyro (only the
 *   axes that move the mouse) since the last reset. [compass] (with gravity): its heading held to
 *   north by the magnetometer.
 * - [resetOnRatchet]: each ratchet press sets it straight: the whole pose, or with
 *   [resetHeadingOnly] (always with gravity) only the turn round the vertical.
 * - [ease]: it goes back to straight on its own (the heading only with gravity), starting
 *   [easeDelayMs] after the phone stops turning, or with [easeAfterRatchet] after a ratchet is let
 *   go; [easeSeconds] to get (almost) all the way back, or with [easeBySpeed] at [easeSpeed] deg/s.
 * - [levelDeg]: the real pose shown square on, 0 upright facing the player, 90 flat screen up,
 *   180 upright facing away.
 *
 * [colors]: ARGB per [GyroDisplayColor.key], missing = its default.
 */
@Serializable
data class GyroDisplay(
    val kind: GyroDisplayKind,
    val gravity: Boolean = false,
    val resetOnRatchet: Boolean = true,
    val resetHeadingOnly: Boolean = false,
    val ease: Boolean = false,
    val easeAfterRatchet: Boolean = false,
    val easeDelayMs: Int = 300,
    val easeBySpeed: Boolean = false,
    val easeSeconds: Float = 0.5f,
    val easeSpeed: Int = 90,
    val compass: Boolean = false,
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
    /**
     * BALL: a trackball. While the gyro moves the mouse the ball holds still against the world (it
     * rolls the other way on screen); a ratchet (per axis) stops it, so it turns with the phone.
     * Every other mouse count sent (touchpad, buttons, controllers, keys, glides) turns it too, as
     * the gyro turn that would send it. With [trackballRws] it turns exactly as far as the game's
     * camera once the game's turn is known ([GyroSettings.gameCountsPer360]), else as far as the
     * phone; without, [trackballSpin] times the phone's turn.
     * No gravity, compass, reset or ease back.
     */
    val trackball: Boolean = false,
    val trackballSpin: Float = 1f,
    val trackballRws: Boolean = true,
    val opacity: Float = 0.7f,
    val colors: Map<String, Long> = emptyMap(),
) {
    /** It holds its pose to gravity (and so needs the accelerometer). */
    val needsGravity: Boolean get() = kind.pose && gravity && !isTrackball

    /** The shape a BALL shows. */
    val ballShape: BallShape get() = if (icosphere && shape == BallShape.GLOBE) BallShape.ICOSPHERE else shape

    /** A [trackball] ball. */
    val isTrackball: Boolean get() = kind == GyroDisplayKind.BALL && trackball

    /** It holds its heading to north (and so needs the magnetometer). */
    val needsCompass: Boolean get() = needsGravity && compass

    /** Colour [c] as ARGB: its own or the default. */
    fun color(c: GyroDisplayColor): Long = colors[c.key] ?: c.default

    fun withColor(c: GyroDisplayColor, argb: Long?): GyroDisplay =
        copy(colors = if (argb == null || argb == c.default) colors - c.key else colors + (c.key to argb))

    companion object {
        /** Size (of the shorter side) and height / width a new one of [kind] starts with. */
        fun newSize(kind: GyroDisplayKind): Pair<Float, Float> = when (kind) {
            GyroDisplayKind.PHONE -> 0.5f to 1f
            GyroDisplayKind.RATCHET_RING -> 0.16f to 1f
            GyroDisplayKind.GRAPH -> 1.1f to 0.3f
            GyroDisplayKind.HORIZON -> 0.4f to 1.25f
            GyroDisplayKind.DISK -> 0.4f to 1f
            GyroDisplayKind.BALL -> 0.4f to 1f
        }
    }
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
    ;

    companion object {
        fun of(kind: GyroDisplayKind): List<GyroDisplayColor> = entries.filter { it.kind == kind }
    }
}
