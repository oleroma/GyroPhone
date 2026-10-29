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

    /** An aircraft-style horizon against gravity, with a heading tape. */
    HORIZON("Attitude indicator"),
}

/**
 * A gyro display's settings ([TouchControl.display]). [gravity] (PHONE): its tilt held to the
 * world, so it never drifts; off, turned by the gyro since the last reset. [resetOnRatchet]: each
 * ratchet press sets the gyro-mode phone and the heading tape straight. [levelDeg] (PHONE,
 * HORIZON): the real pose shown square on, 0 upright facing the player, 90 flat screen up, 180
 * upright facing away. [colors]: ARGB per [GyroDisplayColor.key], missing = its default.
 */
@Serializable
data class GyroDisplay(
    val kind: GyroDisplayKind,
    val gravity: Boolean = false,
    val resetOnRatchet: Boolean = true,
    val levelDeg: Int = 0,
    val ghosts: Boolean = false,
    val axes: Boolean = false,
    /** GRAPH: its full height, ± this many deg/s. */
    val graphRange: Int = 360,
    val graphSeconds: Int = 3,
    val opacity: Float = 0.7f,
    val colors: Map<String, Long> = emptyMap(),
) {
    /** It holds its pose to gravity (and so needs the accelerometer). */
    val needsGravity: Boolean get() = (kind == GyroDisplayKind.PHONE && gravity) || kind == GyroDisplayKind.HORIZON

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
        }
    }
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
    ;

    companion object {
        fun of(kind: GyroDisplayKind): List<GyroDisplayColor> = entries.filter { it.kind == kind }
    }
}
