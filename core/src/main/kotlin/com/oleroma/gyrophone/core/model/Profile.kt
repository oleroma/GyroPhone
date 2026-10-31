package com.oleroma.gyrophone.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

@Serializable
/**
 * FULLSCREEN: a workspace corner (see [NavCorners]); it lives in [Profile.corners], never in a
 * workspace. DISPLAY: a gyro display ([TouchControl.display]): placed like a button in edit mode,
 * drawn under everything in play, never touched.
 */
enum class TouchKind { BUTTON, STICK, FULLSCREEN, DISPLAY }

@Serializable
enum class TouchShape { CIRCLE, SQUARE }

/** Where a control sits among overlapping ones: AUTO by size ([hitOrder]), or always in front / behind. */
@Serializable
enum class TouchLayer(val label: String) { AUTO("Auto"), FRONT("Front"), BACK("Back") }

/**
 * How a touch stick turns into directions for its Left / Right / Up / Down mappings. OVERLAP:
 * each half on its own, so a diagonal presses two; FOUR: one of 4 zones at a time; EIGHT: 8 zones,
 * a diagonal zone fires its own mapping ([InputSource.TouchStickDiagonal]), or both neighbours
 * when it has none.
 */
@Serializable
enum class StickZones(val label: String) {
    OVERLAP("4, diagonals press two"),
    FOUR("4 zones"),
    EIGHT("8 zones"),
}

/** Centre (0..1 of the pad area) and size (fraction of the pad area's shorter side). */
@Serializable
data class Placement(val x: Float, val y: Float, val size: Float)

/**
 * A control drawn on the touchscreen. Position is the centre, normalised to the pad area (0..1);
 * size is the diameter (circle) or width (square) as a fraction of the pad area's shorter
 * side (its height in landscape); a square button's height is size × [aspect]. [x], [y] and [size] are the landscape placement; [portrait] is
 * the portrait one, or null to derive it from the landscape placement.
 */
@Serializable
data class TouchControl(
    val id: String,
    val kind: TouchKind,
    val label: String,
    val x: Float,
    val y: Float,
    val size: Float = 0.16f,
    val shape: TouchShape = TouchShape.CIRCLE,
    /** Portrait placement; null = derived from the landscape one. Keep last (positional callers). */
    val portrait: Placement? = null,
    /** Height / width of a SQUARE button (circles and sticks stay even), in both orientations. */
    val aspect: Float = 1f,
    /** STICK: deflection (0..1 of the radius) that counts as centred; the rest is rescaled to 0..1. */
    val deadzone: Float = 0f,
    /** STICK: how deflection becomes directions. */
    val zones: StickZones = StickZones.OVERLAP,
    /** Before every button was a touch surface: this one was. No longer read. */
    val swipe: Boolean = false,
    /**
     * Before swipe buttons were touch surfaces: swipe sensitivity across and down (mouse counts
     * per dp). Only read for a button with no [surface] settings yet ([surfaceSettings]).
     */
    val swipeSensX: Float = 1.5f,
    val swipeSensY: Float = 1.5f,
    /**
     * Before one gesture set per swipe button: in swipe mode the press waited for a still tap's
     * lift. Only read by [Bindings.matchSwipeModes], which turns it into one-finger tap mappings.
     */
    val swipeTapOnRelease: Boolean = false,
    /**
     * Before tap and long press mappings: with a double tap mapping, the press waited out a
     * possible double tap. Only read by [ButtonActivators.upgrade], which turns it into tap and
     * long press mappings.
     */
    val doubleTapWaits: Boolean = false,
    /**
     * Before tap mappings: the release mapping fired only after a press shorter than this (ms).
     * Only read by [ButtonActivators.upgrade], which turns it into a tap mapping.
     */
    val releaseTapMs: Int = 0,
    /** BUTTON: how long a press must be held to be a long press (ms); null = the workspace's. */
    val longPressMs: Int? = null,
    /** BUTTON: the longest gap between the taps of a double tap (ms); null = the workspace's. */
    val doubleTapMs: Int? = null,
    /**
     * Zone mapping ([TouchAreaKind.ZONE]): a finger that slides in from elsewhere stops what it
     * was doing (cursor, stick, another button) while it is in the zone, and one that lands on
     * the zone but on no other control starts no cursor.
     */
    val zoneInterrupt: Boolean = false,
    /** With [zoneInterrupt]: the stopped job resumes when the finger leaves (off: stopped until it lifts). */
    val zoneResume: Boolean = false,
    /**
     * Held while the mapping set changes ([OutputTarget.Layer]), the control lets go of what it
     * pressed and takes the new set's mapping at once; off: it keeps pressing it until released.
     */
    val followLayers: Boolean = false,
    /**
     * Swipe mode: the touch surface settings this button changes from the workspace touchpad's;
     * null (a button from before) = its old swipe sensitivities, no acceleration, no inertia.
     */
    val surface: SurfaceSettings? = null,
    /** Among overlapping controls ([hitOrder]). */
    val layer: TouchLayer = TouchLayer.AUTO,
    /** A finger landing here while another control is in front also works this control. */
    val sharesTouches: Boolean = false,
    /** BUTTON: its own gyro sensitivities while held, per mapping set ([gyroIn]); none = the workspace's. */
    val gyro: List<ButtonGyro> = emptyList(),
    /** DISPLAY: what it shows and how. */
    val display: GyroDisplay? = null,
    /** Edit mode: controls sharing a group id move and size together ([ControlGroups]); null = none. */
    val group: String? = null,
) {
    /**
     * Every button is a touch surface: its fingers drive the touch inputs ([InputSource.Surface]
     * with its id) it maps or inherits from the workspace, its one-finger hold being its press.
     */
    val swipes: Boolean get() = kind == TouchKind.BUTTON

    /**
     * Its gyro sensitivities with mapping sets [sets] in use (bottom to top, Base first): each
     * set's own over the ones below.
     */
    fun gyroIn(sets: List<Int>): ButtonGyro =
        sets.fold(ButtonGyro()) { r, id -> gyro.firstOrNull { it.set == id }?.let { r.under(it) } ?: r }

    /** Copy with set [set]'s gyro sensitivities changed by [change] (one that sets nothing goes). */
    fun withGyro(set: Int, change: (ButtonGyro) -> ButtonGyro): TouchControl {
        val g = change(gyro.firstOrNull { it.set == set } ?: ButtonGyro(set))
        return copy(gyro = gyro.filterNot { it.set == set } + listOfNotNull(g.takeIf { it.any }))
    }

    /** The long-press time in effect (ms), its own or [w]'s. */
    fun longPress(w: Workspace): Int =
        (longPressMs ?: w.longPressMs).coerceIn(Workspace.MIN_LONG_PRESS_MS, Workspace.MAX_LONG_PRESS_MS)

    /** The double-tap window in effect (ms), its own or [w]'s. */
    fun doubleTap(w: Workspace): Int =
        (doubleTapMs ?: w.doubleTapMs).coerceIn(Workspace.MIN_DOUBLE_TAP_MS, Workspace.MAX_DOUBLE_TAP_MS)

    companion object {
        const val MIN_SIZE = 0.06f

        /** Fractions of the shorter side: over 1 a control can span the long side (and fill the screen). */
        const val MAX_SIZE = 3f
        const val MAX_DEADZONE = 0.6f
        const val MAX_SWIPE_SENS = 12f
        const val MAX_RELEASE_TAP_MS = 1000

        /**
         * Derived portrait layout: the landscape layout squeezed into the lower part (the top is
         * left for the touchpad), sizes scaled down, centres pulled in so nothing leaves the width.
         */
        const val PORTRAIT_TOP = 0.45f
        const val PORTRAIT_SIZE_SCALE = 0.75f
    }
}

/**
 * The explicit portrait placement, else the builtin one for a control still at its builtin
 * landscape placement (copies of the Virtual gamepad saved before portrait existed), else null.
 */
private fun TouchControl.portraitOrBuiltin(): Placement? = portrait ?: BuiltinProfiles.builtinPortrait(this)

fun TouchControl.placeX(inPortrait: Boolean): Float {
    if (!inPortrait) return x
    portraitOrBuiltin()?.let { return it.x }
    // Portrait sizes are fractions of the width, so the half-extent in x is half the size.
    val half = size * TouchControl.PORTRAIT_SIZE_SCALE / 2f
    return x.coerceIn(half, 1f - half)
}

fun TouchControl.placeY(inPortrait: Boolean): Float =
    if (!inPortrait) y else portraitOrBuiltin()?.y ?: (TouchControl.PORTRAIT_TOP + y * (1f - TouchControl.PORTRAIT_TOP))

fun TouchControl.placeSize(inPortrait: Boolean): Float =
    if (!inPortrait) size else portraitOrBuiltin()?.size ?: (size * TouchControl.PORTRAIT_SIZE_SCALE)

/**
 * Touchpad settings a swipe-mode button sets for itself; null fields follow the workspace
 * touchpad ([TouchpadSettings.with]).
 */
@Serializable
data class SurfaceSettings(
    val sensitivity: Float? = null,
    val acceleration: Float? = null,
    val scrollSpeed: Float? = null,
    val naturalScroll: Boolean? = null,
    val inertia: Boolean? = null,
    val releaseTapMs: Int? = null,
    val cursorDeadzoneDp: Float? = null,
    val catchUpMs: Int? = null,
    val deadzoneRearmMs: Int? = null,
    val deadzoneStillDp: Float? = null,
    val moveMode: MoveMode? = null,
    val movePieX: Float? = null,
    val movePieY: Float? = null,
    val moveSampleDp: Float? = null,
    val moveLock: Boolean? = null,
    val moveTurnDp: Float? = null,
    val movePieMargin: Float? = null,
    val holdStopsMove: Boolean? = null,
    val swipePieX: Float? = null,
    val swipePieY: Float? = null,
    val swipeSampleDp: Float? = null,
    val swipeDp: Float? = null,
    val holdPieX: Float? = null,
    val holdPieY: Float? = null,
    val holdSampleDp: Float? = null,
    val holdDp: Float? = null,
    val holdMode: HoldMode? = null,
    val dragStart: Boolean? = null,
    val releaseDp: Float? = null,
    val latch: Boolean? = null,
    val latchReleaseMs: Int? = null,
    /** The button's own length zones (never the touchpad's: they go with its own mappings). */
    val holdZones: List<HoldZone>? = null,
    val dirHoldZones: List<List<HoldZone>>? = null,
    /** Per direction ([Direction.ordinal]), what it sets over the parent's direction settings. */
    val dirs: List<DirSettings>? = null,
    /** Per Move X / Y of each finger count ([MoveSlot.ordinal]), what it sets over the parent's. */
    val moves: List<MoveSettings>? = null,
    /** The workspace touchpad's own on / off (a swipe button is always on). */
    val enabled: Boolean? = null,
    val inertiaGlideMs: Float? = null,
    val inertiaCurve: InertiaCurve? = null,
)

/**
 * One direction's own settings over the shared ones of its surface (null = the shared value):
 * its Swipe's pie, sample and distance, its Swipe hold's pie, sample, hold distance and latch.
 */
@Serializable
data class DirSettings(
    val swipePie: Float? = null,
    val swipeDp: Float? = null,
    val holdPie: Float? = null,
    val holdDp: Float? = null,
    val latch: Boolean? = null,
    val swipeSample: Float? = null,
    val holdSample: Float? = null,
) {
    /** These with [over]'s own values in place of theirs. */
    fun under(over: DirSettings?): DirSettings = if (over == null) this else DirSettings(
        over.swipePie ?: swipePie, over.swipeDp ?: swipeDp, over.holdPie ?: holdPie, over.holdDp ?: holdDp, over.latch ?: latch,
        over.swipeSample ?: swipeSample, over.holdSample ?: holdSample,
    )

    val isEmpty: Boolean get() = this == NONE

    companion object {
        val NONE = DirSettings()

        /** [base] with [over] on top, per direction; null when neither has any. */
        fun merge(base: List<DirSettings>?, over: List<DirSettings>?): List<DirSettings>? =
            if (base == null && over == null) null
            else List(4) { (base?.getOrNull(it) ?: NONE).under(over?.getOrNull(it)) }.takeUnless { l -> l.all { it.isEmpty } }
    }
}

/** These settings with [o]'s own values in place of theirs; length zones are only [o]'s. */
fun TouchpadSettings.with(o: SurfaceSettings?): TouchpadSettings = if (o == null) this else copy(
    sensitivity = o.sensitivity ?: sensitivity,
    acceleration = o.acceleration ?: acceleration,
    scrollSpeed = o.scrollSpeed ?: scrollSpeed,
    naturalScroll = o.naturalScroll ?: naturalScroll,
    inertia = o.inertia ?: inertia,
    releaseTapMs = o.releaseTapMs ?: releaseTapMs,
    cursorDeadzoneDp = o.cursorDeadzoneDp ?: cursorDeadzoneDp,
    catchUpMs = o.catchUpMs ?: catchUpMs,
    deadzoneRearmMs = o.deadzoneRearmMs ?: deadzoneRearmMs,
    deadzoneStillDp = o.deadzoneStillDp ?: deadzoneStillDp,
    moveMode = o.moveMode ?: moveMode,
    movePieX = o.movePieX ?: movePieX,
    movePieY = o.movePieY ?: movePieY,
    moveSampleDp = o.moveSampleDp ?: moveSampleDp,
    moveLock = o.moveLock ?: moveLock,
    moveTurnDp = o.moveTurnDp ?: moveTurnDp,
    movePieMargin = o.movePieMargin ?: movePieMargin,
    holdStopsMove = o.holdStopsMove ?: holdStopsMove,
    swipePieX = o.swipePieX ?: swipePieX,
    swipePieY = o.swipePieY ?: swipePieY,
    swipeSampleDp = o.swipeSampleDp ?: swipeSampleDp,
    swipeDp = o.swipeDp ?: swipeDp,
    holdPieX = o.holdPieX ?: holdPieX,
    holdPieY = o.holdPieY ?: holdPieY,
    holdSampleDp = o.holdSampleDp ?: holdSampleDp,
    holdDp = o.holdDp ?: holdDp,
    holdMode = o.holdMode ?: holdMode,
    dragStart = o.dragStart ?: dragStart,
    releaseDp = o.releaseDp ?: releaseDp,
    latch = o.latch ?: latch,
    latchReleaseMs = o.latchReleaseMs ?: latchReleaseMs,
    holdZones = o.holdZones.orEmpty(),
    dirHoldZones = o.dirHoldZones,
    // A direction keeps the parent's own value unless this sets its own, or the shared one.
    dirs = DirSettings.merge(
        dirs?.mapIndexed { i, it ->
            val x = Direction.entries[i].x
            DirSettings(
                swipePie = it.swipePie.takeIf { (if (x) o.swipePieX else o.swipePieY) == null },
                swipeDp = it.swipeDp.takeIf { o.swipeDp == null },
                holdPie = it.holdPie.takeIf { (if (x) o.holdPieX else o.holdPieY) == null },
                holdDp = it.holdDp.takeIf { o.holdDp == null },
                latch = it.latch.takeIf { o.latch == null },
                swipeSample = it.swipeSample.takeIf { o.swipeSampleDp == null },
                holdSample = it.holdSample.takeIf { o.holdSampleDp == null },
            )
        },
        o.dirs,
    ),
    // A Move X / Y keeps the parent's own value unless this sets its own, or the shared one.
    moves = MoveSettings.merge(
        moves?.mapIndexed { i, m ->
            val x = MoveSlot.entries[i].x
            MoveSettings(
                sensitivity = m.sensitivity.takeIf { o.sensitivity == null },
                acceleration = m.acceleration.takeIf { o.acceleration == null },
                scrollSpeed = m.scrollSpeed.takeIf { o.scrollSpeed == null },
                naturalScroll = m.naturalScroll.takeIf { o.naturalScroll == null },
                mode = m.mode.takeIf { o.moveMode == null },
                pie = m.pie.takeIf { (if (x) o.movePieX else o.movePieY) == null },
                sample = m.sample.takeIf { o.moveSampleDp == null },
                lock = m.lock.takeIf { o.moveLock == null },
                turnDp = m.turnDp.takeIf { o.moveTurnDp == null },
                margin = m.margin.takeIf { o.movePieMargin == null },
                holdStops = m.holdStops.takeIf { o.holdStopsMove == null },
                cursorDeadzoneDp = m.cursorDeadzoneDp.takeIf { o.cursorDeadzoneDp == null },
                catchUpMs = m.catchUpMs.takeIf { o.catchUpMs == null },
                deadzoneRearmMs = m.deadzoneRearmMs.takeIf { o.deadzoneRearmMs == null },
                deadzoneStillDp = m.deadzoneStillDp.takeIf { o.deadzoneStillDp == null },
                inertia = m.inertia.takeIf { o.inertia == null },
                glideMs = m.glideMs.takeIf { o.inertiaGlideMs == null },
                curve = m.curve.takeIf { o.inertiaCurve == null },
            )
        },
        o.moves,
    ),
    enabled = o.enabled ?: enabled,
    inertiaGlideMs = o.inertiaGlideMs ?: inertiaGlideMs,
    inertiaCurve = o.inertiaCurve ?: inertiaCurve,
)

/**
 * Move X ([x]) or Move Y of [fingers] fingers: one of the touch surface's motion actions, whose
 * own settings ([MoveSettings]) are [TouchpadSettings.moves] at its ordinal.
 */
enum class MoveSlot(val fingers: Int, val x: Boolean) {
    X1(1, true), Y1(1, false), X2(2, true), Y2(2, false), X3(3, true), Y3(3, false), X4(4, true), Y4(4, false);

    val label: String get() = (if (fingers == 1) "" else "$fingers-finger ") + if (x) "Move X" else "Move Y"

    companion object {
        /** Move X or Y of [fingers] fingers (1..4). */
        fun of(fingers: Int, x: Boolean): MoveSlot = entries[(fingers.coerceIn(1, 4) - 1) * 2 + if (x) 0 else 1]
    }
}

/**
 * When a Move X / Y moves: [FREE] always, [PIE] only while it heads within its pie
 * ([TouchpadSettings.movePieX]). [LARGER_AXIS] (no longer offered: two 90° pies do the same) only
 * while the travel is more its way than the other.
 */
@Serializable
enum class MoveMode(val label: String) { FREE("Free"), LARGER_AXIS("Larger axis"), PIE("Pie") }

/**
 * How a glide slows down over its glide time T: [SMOOTH] fades exponentially (T is the time
 * constant: fast at first, a long soft tail), [LINEAR] slows evenly and stops after 2·T, so both
 * travel the same distance.
 */
@Serializable
enum class InertiaCurve(val label: String) { SMOOTH("Smooth"), LINEAR("Linear") }

/**
 * One Move X / Y's own settings ([MoveSlot]) over the shared ones of its surface (null = the
 * shared value). [mode] and [pie]: when it moves ([MoveMode]). [inertia], [glideMs] and [curve]:
 * whether and how it glides after the lift ([InertiaCurve]). The cursor deadzone with its
 * catch-up and re-arm, the move sample, lock, turn, pie margin and whether a swipe hold
 * stops the Moves go for the whole motion of a finger count:
 * its Move X holds them ([MoveField.whole]).
 */
@Serializable
data class MoveSettings(
    val sensitivity: Float? = null,
    val acceleration: Float? = null,
    val scrollSpeed: Float? = null,
    val naturalScroll: Boolean? = null,
    val mode: MoveMode? = null,
    val pie: Float? = null,
    val cursorDeadzoneDp: Float? = null,
    val catchUpMs: Int? = null,
    val deadzoneRearmMs: Int? = null,
    val deadzoneStillDp: Float? = null,
    val inertia: Boolean? = null,
    val sample: Float? = null,
    val lock: Boolean? = null,
    val turnDp: Float? = null,
    val margin: Float? = null,
    val holdStops: Boolean? = null,
    val glideMs: Float? = null,
    val curve: InertiaCurve? = null,
) {
    /** These with [over]'s own values in place of theirs. */
    fun under(over: MoveSettings?): MoveSettings = if (over == null) this else MoveSettings(
        over.sensitivity ?: sensitivity, over.acceleration ?: acceleration, over.scrollSpeed ?: scrollSpeed,
        over.naturalScroll ?: naturalScroll, over.mode ?: mode, over.pie ?: pie, over.cursorDeadzoneDp ?: cursorDeadzoneDp,
        over.catchUpMs ?: catchUpMs, over.deadzoneRearmMs ?: deadzoneRearmMs, over.deadzoneStillDp ?: deadzoneStillDp,
        over.inertia ?: inertia, over.sample ?: sample, over.lock ?: lock, over.turnDp ?: turnDp,
        over.margin ?: margin, over.holdStops ?: holdStops, over.glideMs ?: glideMs, over.curve ?: curve,
    )

    val isEmpty: Boolean get() = this == NONE

    companion object {
        val NONE = MoveSettings()

        /** [base] with [over] on top, per slot; null when neither has any. */
        fun merge(base: List<MoveSettings>?, over: List<MoveSettings>?): List<MoveSettings>? =
            if (base == null && over == null) null
            else List(MoveSlot.entries.size) { (base?.getOrNull(it) ?: NONE).under(over?.getOrNull(it)) }.takeUnless { l -> l.all { it.isEmpty } }
    }
}

/**
 * How the motion of one finger count works ([TouchpadSettings.feel]): the shared settings with
 * its Move X's and Move Y's own over them.
 */
data class MotionFeel(
    val sensitivityX: Float, val sensitivityY: Float,
    val accelerationX: Float, val accelerationY: Float,
    val scrollSpeedX: Float, val scrollSpeedY: Float,
    val naturalScrollX: Boolean, val naturalScrollY: Boolean,
    val modeX: MoveMode, val modeY: MoveMode,
    val pieX: Float, val pieY: Float,
    val cursorDeadzoneDp: Float, val catchUpMs: Int, val deadzoneRearmMs: Int, val deadzoneStillDp: Float,
    val inertiaX: Boolean, val inertiaY: Boolean,
    val glideMsX: Float, val glideMsY: Float,
    val curveX: InertiaCurve, val curveY: InertiaCurve,
    val moveSampleDp: Float,
    val moveLock: Boolean, val moveTurnDp: Float, val movePieMargin: Float,
    val holdStopsMove: Boolean,
) {
    /** Every Move moves freely: nothing to judge. */
    val free: Boolean get() = modeX == MoveMode.FREE && modeY == MoveMode.FREE
}

/** A swipe button's own settings: [TouchControl.surface], or what its old swipe settings were. */
val TouchControl.ownSurface: SurfaceSettings
    get() = surface ?: SurfaceSettings(
        sensitivity = swipeSensX,
        acceleration = 0f,
        inertia = false
    )

/** How a swipe button's surface works: the workspace touchpad [pad] with the button's own settings. */
fun TouchControl.surfaceSettings(pad: TouchpadSettings): TouchpadSettings =
    pad.copy(enabled = true, mapped = true).with(ownSurface)

/** Height / width as drawn: [TouchControl.aspect] for a square button, else 1. */
val TouchControl.heightRatio: Float
    get() = if (shape == TouchShape.SQUARE && (kind == TouchKind.BUTTON || kind == TouchKind.DISPLAY)) aspect else 1f

/** Resolved placement (allocates; editor use). */
fun TouchControl.placement(inPortrait: Boolean): Placement =
    Placement(placeX(inPortrait), placeY(inPortrait), placeSize(inPortrait))

/**
 * Copy with the given orientation's placement replaced. Editing landscape first pins the
 * current (possibly derived) portrait placement, so the portrait layout does not move.
 */
fun TouchControl.withPlacement(inPortrait: Boolean, x: Float, y: Float, size: Float): TouchControl =
    if (inPortrait) copy(portrait = Placement(x, y, size))
    else copy(x = x, y = y, size = size, portrait = portrait ?: placement(true))

/** Hit test in pixels of the pad area; [slop] enlarges the hit area relative to the drawn shape. */
fun TouchControl.contains(
    px: Float, py: Float, screenW: Float, screenH: Float,
    slop: Float = 1.15f, inPortrait: Boolean = false,
): Boolean {
    val dx = px - placeX(inPortrait) * screenW
    val dy = py - placeY(inPortrait) * screenH
    val half = placeSize(inPortrait) * min(screenW, screenH) / 2f
    return when (shape) {
        TouchShape.CIRCLE -> dx * dx + dy * dy <= half * half * slop * slop
        // Slop as a margin from the shorter side, so a long strip is not enlarged a lot along its length.
        TouchShape.SQUARE -> {
            val halfH = half * heightRatio
            val margin = min(half, halfH) * (slop - 1f)
            abs(dx) <= half + margin && abs(dy) <= halfH + margin
        }
    }
}

/**
 * [controls] front first, as touches see them: FRONT ones, then AUTO ones by size, then BACK
 * ones. Size is the shortest side (a circle's diameter, a rectangle's narrower side), so a long
 * thin strip counts as small; smaller is in front, then smaller area, then the one added later.
 */
fun hitOrder(controls: List<TouchControl>, inPortrait: Boolean): List<TouchControl> =
    controls.withIndex().sortedWith(
        compareBy<IndexedValue<TouchControl>> { it.value.layer.ordinal.let { l -> if (l == 0) 1 else if (l == 1) 0 else 2 } }
            .thenBy { it.value.shortSide(inPortrait) }
            .thenBy { it.value.area(inPortrait) }
            .thenByDescending { it.index },
    ).map { it.value }

/** Shortest side as drawn, in fractions of the pad area's shorter side. */
fun TouchControl.shortSide(inPortrait: Boolean): Float = placeSize(inPortrait) * min(1f, heightRatio)

/** Area as drawn, in squared fractions of the pad area's shorter side. */
fun TouchControl.area(inPortrait: Boolean): Float {
    val s = placeSize(inPortrait)
    return if (shape == TouchShape.CIRCLE) s * s * 0.7854f else s * s * heightRatio
}

/**
 * The controls a finger landing at [px], [py] works: the front one under it, plus every
 * [TouchControl.sharesTouches] one behind it. [ordered] comes from [hitOrder].
 */
fun touchedControls(
    ordered: List<TouchControl>, px: Float, py: Float, screenW: Float, screenH: Float, inPortrait: Boolean,
): List<TouchControl> {
    val under = ordered.filter { it.contains(px, py, screenW, screenH, inPortrait = inPortrait) }
    if (under.size <= 1) return under
    return listOf(under[0]) + under.drop(1).filter { it.sharesTouches }
}

/**
 * When gyro moves the mouse. ALWAYS = on, a held ratchet ([OutputTarget.GyroPause]) pauses it; OFF = off, a held ratchet engages it;
 * each gyro axis may have its own of the two ([GyroSettings.modeOf]). [OutputTarget.GyroToggle] swaps them. SENSOR_OFF = gyro off completely (the sensor is not
 * read, saving battery; ratchets and toggle do nothing). HOLD_OR_TOGGLE is only read from old
 * files and loads as OFF ([ProfileJson.normalize]).
 */
@Serializable
enum class GyroMode(val label: String) {
    OFF("Off"),
    ALWAYS("On"),
    SENSOR_OFF("Sensor off"),
    HOLD_OR_TOGGLE("Off"),
}


/**
 * A button's own gyro sensitivities in mapping set [set] ([TouchControl.gyro]), null = not its
 * own: [sensitivity] for every axis, or an axis's own. While the button is held (the last pressed
 * of several) they are the gyro's, over the workspace's, whatever the button maps.
 */
@Serializable
data class ButtonGyro(
    val set: Int = MappingSet.BASE,
    val sensitivity: Float? = null,
    val yaw: Float? = null,
    val pitch: Float? = null,
    val roll: Float? = null,
    /** Yaw's, pitch's and roll's ratchet mode while held ([GyroSettings.yawMode]); null = the workspace's. */
    val yawMode: GyroMode? = null,
    val pitchMode: GyroMode? = null,
    val rollMode: GyroMode? = null,
    /** Yaw's, pitch's and roll's inertia, glide and curve while held ([GyroSettings.ownGlide]); null = the workspace's. */
    val yawGlide: AxisGlide? = null,
    val pitchGlide: AxisGlide? = null,
    val rollGlide: AxisGlide? = null,
) {
    /** It sets something. */
    val any: Boolean get() = sensitivity != null || yaw != null || pitch != null || roll != null ||
        yawMode != null || pitchMode != null || rollMode != null || yawGlide != null || pitchGlide != null || rollGlide != null

    /** Axis [a]'s own inertia, glide and curve. */
    fun ownGlide(a: MotionAxis): AxisGlide = when (a) {
        MotionAxis.YAW -> yawGlide
        MotionAxis.PITCH -> pitchGlide
        MotionAxis.ROLL -> rollGlide
        MotionAxis.NONE -> null
    } ?: AxisGlide()

    /** Copy with axis [a]'s own inertia, glide and curve [g]. */
    fun withGlide(a: MotionAxis, g: AxisGlide): ButtonGyro {
        val v = g.takeUnless { it.isEmpty }
        return when (a) {
            MotionAxis.YAW -> copy(yawGlide = v)
            MotionAxis.PITCH -> copy(pitchGlide = v)
            MotionAxis.ROLL -> copy(rollGlide = v)
            MotionAxis.NONE -> this
        }
    }

    /** Axis [a]'s own ratchet mode. */
    fun ownMode(a: MotionAxis): GyroMode? = when (a) {
        MotionAxis.YAW -> yawMode
        MotionAxis.PITCH -> pitchMode
        MotionAxis.ROLL -> rollMode
        MotionAxis.NONE -> null
    }

    /** Copy with axis [a]'s own ratchet mode [m]. */
    fun withMode(a: MotionAxis, m: GyroMode?): ButtonGyro = when (a) {
        MotionAxis.YAW -> copy(yawMode = m)
        MotionAxis.PITCH -> copy(pitchMode = m)
        MotionAxis.ROLL -> copy(rollMode = m)
        MotionAxis.NONE -> this
    }

    /** Axis [a]'s own sensitivity. */
    fun own(a: MotionAxis): Float? = when (a) {
        MotionAxis.YAW -> yaw
        MotionAxis.PITCH -> pitch
        MotionAxis.ROLL -> roll
        MotionAxis.NONE -> null
    }

    /** Copy with axis [a]'s own sensitivity [v]. */
    fun with(a: MotionAxis, v: Float?): ButtonGyro = when (a) {
        MotionAxis.YAW -> copy(yaw = v)
        MotionAxis.PITCH -> copy(pitch = v)
        MotionAxis.ROLL -> copy(roll = v)
        MotionAxis.NONE -> this
    }

    /** [o]'s values over these (a layer over the ones below). */
    fun under(o: ButtonGyro) = ButtonGyro(
        set, o.sensitivity ?: sensitivity, o.yaw ?: yaw, o.pitch ?: pitch, o.roll ?: roll,
        o.yawMode ?: yawMode, o.pitchMode ?: pitchMode, o.rollMode ?: rollMode,
        AxisGlide.merge(yawGlide, o.yawGlide), AxisGlide.merge(pitchGlide, o.pitchGlide), AxisGlide.merge(rollGlide, o.rollGlide),
    )

    /** Axis [a]'s sensitivity with these over [g]: its own, these' shared, then [g]'s. */
    fun sensitivityOf(a: MotionAxis, g: GyroSettings): Float = own(a) ?: sensitivity ?: g.sensitivityOf(a)

    /** [g] with these over it. */
    fun over(g: GyroSettings): GyroSettings = if (!any) g else g.copy(
        yawSensitivity = sensitivityOf(MotionAxis.YAW, g), pitchSensitivity = sensitivityOf(MotionAxis.PITCH, g),
        rollSensitivity = sensitivityOf(MotionAxis.ROLL, g),
        yawMode = yawMode ?: g.yawMode, pitchMode = pitchMode ?: g.pitchMode, rollMode = rollMode ?: g.rollMode,
    ).let { r ->
        listOf(MotionAxis.YAW, MotionAxis.PITCH, MotionAxis.ROLL).fold(r) { acc, a -> acc.withGlide(a, g.ownGlide(a).under(ownGlide(a))) }
    }
}

/** One gyro axis's own inertia, glide (ms) and curve ([GyroSettings.inertia]); null = the parent's. */
@Serializable
data class AxisGlide(
    val inertia: Boolean? = null,
    val glideMs: Float? = null,
    val curve: InertiaCurve? = null,
) {
    val isEmpty: Boolean get() = inertia == null && glideMs == null && curve == null

    /** These with [o]'s own values in place of theirs. */
    fun under(o: AxisGlide?): AxisGlide = if (o == null) this else AxisGlide(o.inertia ?: inertia, o.glideMs ?: glideMs, o.curve ?: curve)

    companion object {
        /** [base] with [over] on top; null when neither sets anything. */
        fun merge(base: AxisGlide?, over: AxisGlide?): AxisGlide? =
            (base ?: AxisGlide()).under(over).takeUnless { it.isEmpty }
    }
}

/** Rotation of the phone in the player's frame. */
@Serializable
enum class MotionAxis(val label: String) {
    /** Turning left/right around the vertical axis. */
    YAW("Yaw"),

    /** Tilting the far edge up/down. */
    PITCH("Pitch"),

    /** Tilting like a steering wheel. */
    ROLL("Roll"),
    NONE("None"),
}

@Serializable
data class GyroSettings(
    val mode: GyroMode = GyroMode.OFF,
    /**
     * Mouse counts per degree of rotation, for every gyro axis without its own: also how strongly
     * gyro scrolls and moves sticks, relative to [DEFAULT_SENSITIVITY].
     */
    val sensitivity: Float = DEFAULT_SENSITIVITY,
    /** Yaw's, pitch's and roll's own sensitivity; null = [sensitivity]. */
    val yawSensitivity: Float? = null,
    val pitchSensitivity: Float? = null,
    val rollSensitivity: Float? = null,
    /** Below this speed (deg/s) motion is scaled down, killing hand jitter. 0 (default, raw gyro) disables. */
    val tighteningThreshold: Float = 0f,
    /** Below this speed (deg/s) motion is averaged over a short window. 0 (default, raw gyro) disables. */
    val smoothingThreshold: Float = 0f,
    /**
     * Yaw's, pitch's and roll's own ratchet mode ([GyroMode.ALWAYS]: a ratchet disables it,
     * [GyroMode.OFF]: enables it); null = [mode]'s. [GyroMode.SENSOR_OFF] is the whole gyro's only.
     */
    val yawMode: GyroMode? = null,
    val pitchMode: GyroMode? = null,
    val rollMode: GyroMode? = null,
    /** Tilt for a full deflection, degrees, for both tilt axes without their own. */
    val tiltRangeDeg: Float = DEFAULT_TILT_RANGE_DEG,
    /** Tilt that still counts as level, degrees, for both tilt axes without their own. */
    val tiltDeadzoneDeg: Float = 0f,
    /** Sideways ([tiltRangeOf] x) and forward and back tilt's own range and deadzone; null = the shared one. */
    val tiltXRangeDeg: Float? = null,
    val tiltYRangeDeg: Float? = null,
    val tiltXDeadzoneDeg: Float? = null,
    val tiltYDeadzoneDeg: Float? = null,
    /** How hard a jolt (m/s² off gravity) counts as a shake. */
    val shakeStrength: Float = DEFAULT_SHAKE_STRENGTH,
    /**
     * The game's mouse counts for one full camera turn ([GameSens]); 0 = unknown. Set, gyro
     * sensitivities are shown as real world sensitivity (RWS); they are stored as counts per degree either way.
     */
    val gameCountsPer360: Float = 0f,
    /** Where [gameCountsPer360] came from, shown beside it ("CS2, sens 1.5", "Measured"). */
    val gameName: String = "",
    /**
     * Inertia: when a ratchet turns a gyro axis off, its mouse and wheel motion glides on and slows
     * down over [glideMs] ([InertiaCurve]); it stops at once when the axis is on again. Sticks never glide.
     */
    val inertia: Boolean = false,
    val glideMs: Float = DEFAULT_GLIDE_MS,
    val inertiaCurve: InertiaCurve = InertiaCurve.SMOOTH,
    /** Yaw's, pitch's and roll's own inertia, glide and curve; null = the shared one. */
    val yawInertia: Boolean? = null,
    val pitchInertia: Boolean? = null,
    val rollInertia: Boolean? = null,
    val yawGlideMs: Float? = null,
    val pitchGlideMs: Float? = null,
    val rollGlideMs: Float? = null,
    val yawCurve: InertiaCurve? = null,
    val pitchCurve: InertiaCurve? = null,
    val rollCurve: InertiaCurve? = null,
) {
    /** Axis [a]'s own inertia, glide and curve ([AxisGlide]: null = the shared one). */
    fun ownGlide(a: MotionAxis): AxisGlide = when (a) {
        MotionAxis.YAW -> AxisGlide(yawInertia, yawGlideMs, yawCurve)
        MotionAxis.PITCH -> AxisGlide(pitchInertia, pitchGlideMs, pitchCurve)
        MotionAxis.ROLL -> AxisGlide(rollInertia, rollGlideMs, rollCurve)
        MotionAxis.NONE -> AxisGlide()
    }

    /** Copy with axis [a]'s own inertia, glide and curve [g]. */
    fun withGlide(a: MotionAxis, g: AxisGlide): GyroSettings = when (a) {
        MotionAxis.YAW -> copy(yawInertia = g.inertia, yawGlideMs = g.glideMs, yawCurve = g.curve)
        MotionAxis.PITCH -> copy(pitchInertia = g.inertia, pitchGlideMs = g.glideMs, pitchCurve = g.curve)
        MotionAxis.ROLL -> copy(rollInertia = g.inertia, rollGlideMs = g.glideMs, rollCurve = g.curve)
        MotionAxis.NONE -> this
    }

    fun inertiaOf(a: MotionAxis): Boolean = ownGlide(a).inertia ?: inertia
    fun glideMsOf(a: MotionAxis): Float = ownGlide(a).glideMs ?: glideMs
    fun curveOf(a: MotionAxis): InertiaCurve = ownGlide(a).curve ?: inertiaCurve

    /** Sensitivity [sens] (counts per degree) as real world sensitivity, or null with [gameCountsPer360] unknown. */
    fun rwsOf(sens: Float): Float? = if (gameCountsPer360 > 0f) GameSens.rws(sens, gameCountsPer360) else null

    /** Sideways ([x]) or forward and back tilt's range in effect. */
    fun tiltRangeOf(x: Boolean): Float = (if (x) tiltXRangeDeg else tiltYRangeDeg) ?: tiltRangeDeg

    /** Sideways ([x]) or forward and back tilt's deadzone in effect. */
    fun tiltDeadzoneOf(x: Boolean): Float = (if (x) tiltXDeadzoneDeg else tiltYDeadzoneDeg) ?: tiltDeadzoneDeg

    /** Axis [a]'s own ratchet mode, null when it follows [mode]. */
    fun ownMode(a: MotionAxis): GyroMode? = when (a) {
        MotionAxis.YAW -> yawMode
        MotionAxis.PITCH -> pitchMode
        MotionAxis.ROLL -> rollMode
        MotionAxis.NONE -> null
    }

    /** Axis [a]'s ratchet mode in effect: [GyroMode.SENSOR_OFF] whenever the whole gyro is off. */
    fun modeOf(a: MotionAxis): GyroMode = if (mode == GyroMode.SENSOR_OFF) mode else ownMode(a) ?: mode

    /** Copy with axis [a]'s own ratchet mode [m] (null: it follows [mode]). */
    fun withMode(a: MotionAxis, m: GyroMode?): GyroSettings = when (a) {
        MotionAxis.YAW -> copy(yawMode = m)
        MotionAxis.PITCH -> copy(pitchMode = m)
        MotionAxis.ROLL -> copy(rollMode = m)
        MotionAxis.NONE -> this
    }

    /** Axis [a]'s own sensitivity, null when it uses [sensitivity]. */
    fun ownSensitivity(a: MotionAxis): Float? = when (a) {
        MotionAxis.YAW -> yawSensitivity
        MotionAxis.PITCH -> pitchSensitivity
        MotionAxis.ROLL -> rollSensitivity
        MotionAxis.NONE -> null
    }

    /** Axis [a]'s sensitivity in effect. */
    fun sensitivityOf(a: MotionAxis): Float = ownSensitivity(a) ?: sensitivity

    /** Copy with axis [a]'s own sensitivity [v] (null: it uses [sensitivity]). */
    fun withSensitivity(a: MotionAxis, v: Float?): GyroSettings = when (a) {
        MotionAxis.YAW -> copy(yawSensitivity = v)
        MotionAxis.PITCH -> copy(pitchSensitivity = v)
        MotionAxis.ROLL -> copy(rollSensitivity = v)
        MotionAxis.NONE -> this
    }

    companion object {
        const val DEFAULT_SENSITIVITY = 16f
        const val DEFAULT_GLIDE_MS = 325f
        const val DEFAULT_TILT_RANGE_DEG = 30f
        const val MIN_TILT_RANGE_DEG = 5f
        const val MAX_TILT_RANGE_DEG = 90f
        const val MAX_TILT_DEADZONE_DEG = 30f
        const val DEFAULT_SHAKE_STRENGTH = 14f
        const val MIN_SHAKE_STRENGTH = 5f
        const val MAX_SHAKE_STRENGTH = 40f

        /** The properties of axis [a]'s own inertia, glide and curve ([WorkspaceDefaults.gyroKey]). */
        fun glideProps(a: MotionAxis): List<String> =
            if (a == MotionAxis.NONE) emptyList() else a.name.lowercase().let { listOf(it + "Inertia", it + "GlideMs", it + "Curve") }

        /** The property of axis [a]'s own sensitivity ([WorkspaceDefaults.gyroKey]). */
        fun sensitivityProp(a: MotionAxis): String? = when (a) {
            MotionAxis.YAW -> "yawSensitivity"
            MotionAxis.PITCH -> "pitchSensitivity"
            MotionAxis.ROLL -> "rollSensitivity"
            MotionAxis.NONE -> null
        }
    }
}

/** The pad screen's empty background as a laptop touchpad. */
@Serializable
data class TouchpadSettings(
    val enabled: Boolean = true,
    /** Horizontal mouse counts per dp of finger travel at low speed (0 = no sideways motion). */
    val sensitivity: Float = 1.5f,
    /** Extra gain for fast swipes: up to 1 + this. 0 = linear. */
    val acceleration: Float = 1f,
    val tapToClick: Boolean = true,
    /** Tap then touch and move drags with the left button held. */
    val tapDrag: Boolean = true,
    val twoFingerScroll: Boolean = true,
    /** Wheel notches per Touchpad.SCROLL_DP_PER_NOTCH dp of two-finger travel. */
    val scrollSpeed: Float = 1f,
    /** Content follows the fingers (finger down scrolls up), like a phone or Windows touchpad. */
    val naturalScroll: Boolean = true,
    /** Two-finger scrolling also moves sideways. */
    val horizontalScroll: Boolean = true,
    /**
     * A Move X / Y that lifts while moving keeps gliding and slows down ([inertiaCurve]), every
     * Move without its own; by default ([DEFAULT_MOVES]) not the one-finger cursor's.
     */
    val inertia: Boolean = true,
    /** How long a glide keeps going after the fingers lift (ms, [InertiaCurve]). */
    val inertiaGlideMs: Float = 325f,
    val inertiaCurve: InertiaCurve = InertiaCurve.SMOOTH,
    /** Two fingers spreading or closing zoom (Ctrl + wheel). */
    val pinchZoom: Boolean = true,
    /** Three- and four-finger swipes and taps run [gestures3] / [gestures4]. */
    val gestures: Boolean = true,
    val gestures3: FingerGestures = FingerGestures.WINDOWS_3,
    val gestures4: FingerGestures = FingerGestures.WINDOWS_4,
    /**
     * The touchpad's taps and gestures come from the workspace's touchpad mappings
     * ([InputSource.Surface], put in by [WorkspaceMappings.resolve]). Off (a workspace not
     * resolved): from [tapToClick], [tapDrag], [twoFingerScroll], [horizontalScroll], [pinchZoom]
     * and [gestures], which are otherwise only read for a mapping profile saved before touchpad
     * mappings ([Bindings.defaultTouchpadMappings]).
     */
    val mapped: Boolean = false,
    /**
     * Before releases fired on every lift: release mappings fired only after a touch shorter than
     * this (ms). Only read by [ButtonActivators.upgrade], which turns them into tap mappings.
     */
    val releaseTapMs: Int = 0,
    /**
     * One-finger cursor: travel from the touch-down point (along the one axis when only one is
     * mapped to the cursor) before the cursor moves, in dp; 0 = off. Past it, the cursor makes up
     * the travel it held back over [catchUpMs].
     */
    val cursorDeadzoneDp: Float = 0f,
    /** How long the cursor takes to make up the deadzone's travel, in ms; 0 = in one jump. */
    val catchUpMs: Int = 80,
    /**
     * The cursor deadzone arms again once the finger has stood still this long (ms), measured
     * from where it stopped; 0 = once per touch.
     */
    val deadzoneRearmMs: Int = 0,
    /** Movement (dp) that still counts as standing still for [deadzoneRearmMs]. */
    val deadzoneStillDp: Float = 2f,
    /** When Move X and Y move ([MoveMode]): every Move without its own. */
    val moveMode: MoveMode = MoveMode.FREE,
    /** With [MoveMode.PIE]: Move X's pie (degrees), left and right alike, and Move Y's, up and down. */
    val movePieX: Float = DEFAULT_MOVE_PIE_DEG,
    val movePieY: Float = DEFAULT_MOVE_PIE_DEG,
    /** A Move's heading is that of about the last this many dp of travel; a motion's first this many wait for it. */
    val moveSampleDp: Float = DEFAULT_MOVE_SAMPLE_DP,
    /**
     * With pies: a stroke keeps the Moves it heads for ([moveLock]) until it heads elsewhere for
     * [moveTurnDp] on end; that travel then goes to the Moves it turned to, so none is lost.
     */
    val moveLock: Boolean = true,
    val moveTurnDp: Float = DEFAULT_MOVE_TURN_DP,
    /** A Move moving now stays on until the finger heads this many degrees past its pie (0 = off). */
    val movePieMargin: Float = DEFAULT_MOVE_PIE_MARGIN,
    /** Once a mapped swipe hold presses, the stroke's Move X and Y stop until the fingers lift. */
    val holdStopsMove: Boolean = true,
    /**
     * Swipes: the pie (degrees) of Left and Right alike ([swipePieX]) and of Up and Down: a swipe
     * counts while the finger heads within its pie. Wider pies overlap (both count there),
     * narrower ones leave gaps; 90° = the way of the larger axis.
     */
    val swipePieX: Float = LARGER_AXIS_PIE_DEG,
    val swipePieY: Float = LARGER_AXIS_PIE_DEG,
    /** A swipe's heading is that of about the last this many dp of travel; there is none before it. */
    val swipeSampleDp: Float = DEFAULT_SAMPLE_DP,
    /** A swipe fires once the travel since the direction last changed reaches this (dp). */
    val swipeDp: Float = DEFAULT_SWIPE_DP,
    /** Held directions' pies, as [swipePieX]; 180° = per axis (from the start point, diagonals hold both). */
    val holdPieX: Float = PER_AXIS_PIE_DEG,
    val holdPieY: Float = PER_AXIS_PIE_DEG,
    /** A held direction's heading is that of about the last this many dp of travel. */
    val holdSampleDp: Float = DEFAULT_SAMPLE_DP,
    /** Held directions: the travel that presses one (see [holdMode]), in dp. */
    val holdDp: Float = DEFAULT_HOLD_DP,
    val holdMode: HoldMode = HoldMode.FROM_START,
    /**
     * [HoldMode.FROM_START]: the start point is dragged along so it never trails the finger by
     * more than [holdDp] + [releaseDp]. Sliding back [releaseDp] lets go and re-centres the start
     * point on the finger, so the opposite direction presses [holdDp] further on.
     */
    val dragStart: Boolean = false,
    val releaseDp: Float = DEFAULT_RELEASE_DP,
    /** Held directions stay held after the fingers lift, until a tap or another direction. */
    val latch: Boolean = false,
    /** A latch lets go by itself this long after the lift (ms); 0 = never. */
    val latchReleaseMs: Int = 0,
    /**
     * Length zones of held directions, from the first (see [holdZone]); the same for every
     * direction unless [dirHoldZones] (by [Direction.ordinal]) gives each its own.
     */
    val holdZones: List<HoldZone> = emptyList(),
    val dirHoldZones: List<List<HoldZone>>? = null,
    /** Per direction ([Direction.ordinal]), its own settings over the shared ones above ([DirSettings]). */
    val dirs: List<DirSettings>? = null,
    /** Per Move X / Y of each finger count ([MoveSlot.ordinal]), its own settings over the shared ones ([MoveSettings]). */
    val moves: List<MoveSettings>? = DEFAULT_MOVES,
) {
    /** [m]'s own settings (empty: all shared). */
    fun move(m: MoveSlot): MoveSettings = moves?.getOrNull(m.ordinal) ?: MoveSettings.NONE

    /** These with [m]'s own settings [change]d. */
    fun withMove(m: MoveSlot, change: (MoveSettings) -> MoveSettings): TouchpadSettings = copy(
        moves = List(MoveSlot.entries.size) { if (it == m.ordinal) change(move(m)) else move(MoveSlot.entries[it]) }
            .takeUnless { l -> l.all { it.isEmpty } },
    )

    /** How the motion of [fingers] fingers works: the shared settings with its Move X's and Y's own. */
    fun feel(fingers: Int): MotionFeel {
        val x = move(MoveSlot.of(fingers, true))
        val y = move(MoveSlot.of(fingers, false))
        return MotionFeel(
            x.sensitivity ?: sensitivity, y.sensitivity ?: sensitivity,
            x.acceleration ?: acceleration, y.acceleration ?: acceleration,
            x.scrollSpeed ?: scrollSpeed, y.scrollSpeed ?: scrollSpeed,
            x.naturalScroll ?: naturalScroll, y.naturalScroll ?: naturalScroll,
            x.mode ?: moveMode, y.mode ?: moveMode,
            x.pie ?: movePieX, y.pie ?: movePieY,
            x.cursorDeadzoneDp ?: cursorDeadzoneDp, x.catchUpMs ?: catchUpMs,
            x.deadzoneRearmMs ?: deadzoneRearmMs, x.deadzoneStillDp ?: deadzoneStillDp,
            x.inertia ?: inertia, y.inertia ?: inertia,
            x.glideMs ?: inertiaGlideMs, y.glideMs ?: inertiaGlideMs,
            x.curve ?: inertiaCurve, y.curve ?: inertiaCurve,
            x.sample ?: moveSampleDp,
            x.lock ?: moveLock, x.turnDp ?: moveTurnDp, x.margin ?: movePieMargin,
            x.holdStops ?: holdStopsMove,
        )
    }

    /** [d]'s own settings (empty: all shared). */
    fun dir(d: Direction): DirSettings = dirs?.getOrNull(d.ordinal) ?: DirSettings.NONE

    /** These with [d]'s own settings [change]d. */
    fun withDir(d: Direction, change: (DirSettings) -> DirSettings): TouchpadSettings =
        copy(dirs = List(4) { if (it == d.ordinal) change(dir(d)) else dir(Direction.entries[it]) }.takeUnless { l -> l.all { it.isEmpty } })

    /** How far a swipe of [d] travels before it fires (dp). */
    fun swipeDpOf(d: Direction): Float = dir(d).swipeDp ?: swipeDp

    /** How far a held [d] travels before it is held (dp). */
    fun holdDpOf(d: Direction): Float = dir(d).holdDp ?: holdDp

    /** Whether a held [d] stays held after the lift. */
    fun latchOf(d: Direction): Boolean = dir(d).latch ?: latch

    /**
     * Zone [i] (from 0) of held direction [d]. A held direction's mappings are split into up to
     * [MAX_HOLD_ZONES] zones by how far the finger travelled: the first starts at the hold
     * distance, each next one where the one before it ends ([HoldZone.lengthDp]); the last goes
     * on to the edge. Not with [dragStart] (the start point never trails far enough).
     */
    fun holdZone(d: Direction, i: Int): HoldZone =
        (dirHoldZones?.getOrNull(d.ordinal) ?: holdZones).getOrNull(i) ?: HoldZone()

    /** How long zone [i] of [d] is (dp): its own length, else the first zone's, else the hold distance. */
    fun holdZoneDp(d: Direction, i: Int): Float =
        (holdZone(d, i).lengthDp ?: holdZone(d, 0).lengthDp ?: holdDpOf(d)).coerceIn(MIN_ZONE_DP, MAX_ZONE_DP)

    /** The shared pie width of [d]'s swipes ([hold] false) or held directions: its axis's. */
    fun sharedPieDeg(d: Direction, hold: Boolean): Float =
        if (hold) (if (d.x) holdPieX else holdPieY) else if (d.x) swipePieX else swipePieY

    /** The pie width of [d]'s swipe ([hold] false) or held direction, its own or the shared one. */
    fun pieDeg(d: Direction, hold: Boolean): Float =
        (dir(d).let { if (hold) it.holdPie else it.swipePie } ?: sharedPieDeg(d, hold)).coerceIn(MIN_PIE_DEG, MAX_PIE_DEG)

    /** The sample of [d]'s swipe ([hold] false) or held direction, its own or the shared one (dp). */
    fun sampleDp(d: Direction, hold: Boolean): Float =
        (dir(d).let { if (hold) it.holdSample else it.swipeSample } ?: if (hold) holdSampleDp else swipeSampleDp)
            .coerceIn(MIN_SAMPLE_DP, MAX_SAMPLE_DP)

    fun gesturesFor(fingers: Int): FingerGestures = if (fingers >= 4) gestures4 else gestures3

    companion object {
        const val MIN_GLIDE_MS = 100f
        const val MAX_GLIDE_MS = 1000f

        /** The built-in Moves' own settings: one finger (the cursor) does not glide. */
        val DEFAULT_MOVES: List<MoveSettings> =
            MoveSlot.entries.map { if (it.fingers == 1) MoveSettings(inertia = false) else MoveSettings.NONE }
        const val MAX_RELEASE_TAP_MS = 1000
        const val MAX_CURSOR_DEADZONE_DP = 80f
        const val MAX_CATCH_UP_MS = 500
        const val MAX_REARM_MS = 1000
        const val MAX_STILL_DP = 10f
        const val DEFAULT_MOVE_PIE_DEG = 120f
        const val DEFAULT_MOVE_SAMPLE_DP = 2f
        const val DEFAULT_MOVE_TURN_DP = 6f
        const val MAX_MOVE_TURN_DP = 40f
        const val DEFAULT_MOVE_PIE_MARGIN = 15f
        const val MAX_MOVE_PIE_MARGIN = 45f
        const val DEFAULT_SAMPLE_DP = 4f
        const val MIN_SAMPLE_DP = 0.5f
        const val MAX_SAMPLE_DP = 20f
        const val DEFAULT_SWIPE_DP = 40f
        const val DEFAULT_HOLD_DP = 20f
        const val MIN_ACTIVATE_DP = 2f
        const val MAX_ACTIVATE_DP = 120f
        const val DEFAULT_RELEASE_DP = 10f
        const val MAX_RELEASE_DP = 60f
        /** Two 90° pies per axis: the way of the larger axis. */
        const val LARGER_AXIS_PIE_DEG = 90f
        /** 180° pies: each direction counts on its whole side, so diagonals count both. */
        const val PER_AXIS_PIE_DEG = 180f
        const val MIN_PIE_DEG = 10f
        const val MAX_PIE_DEG = 180f
        const val MAX_LATCH_RELEASE_MS = 10_000
        const val MAX_HOLD_ZONES = 4
        const val MIN_ZONE_DP = 4f
        const val MAX_ZONE_DP = 200f
    }
}

/**
 * One length zone of a held direction ([TouchpadSettings.holdZone]). [lengthDp]: how far it goes
 * before the next zone starts (null = the first zone's length, or for the first the hold
 * distance). On reach (default): its mappings are held while the finger is in it; [onRelease]:
 * they fire once as the finger lifts in it instead. [keep] (on reach only): whether they stay held
 * once the finger leaves it ([ZoneKeep]).
 */
@Serializable
data class HoldZone(
    val lengthDp: Float? = null,
    val onRelease: Boolean = false,
    val keep: ZoneKeep = ZoneKeep.OFF,
)

/**
 * How long a reached zone stays held ([HoldZone.keep]). OFF: while the finger is in it. GOING_ON:
 * also while it is in the zones after it (sliding back below it lets go). UNTIL_LIFT: from the
 * moment it is reached until the fingers lift, wherever they go.
 */
@Serializable
enum class ZoneKeep(val label: String) { OFF("Only in zone"), GOING_ON("Only forward"), UNTIL_LIFT("Always") }

/** A swipe direction, clockwise from up; [deg] is its centre, clockwise from straight up. */
@Serializable
enum class Direction(val deg: Float, val label: String) {
    UP(0f, "Up"), RIGHT(90f, "Right"), DOWN(180f, "Down"), LEFT(270f, "Left");

    /** Left or right: on the X axis. */
    val x: Boolean get() = this == RIGHT || this == LEFT
}

/**
 * The direction pies (of swipes, or with [hold] of held directions) vector ([x], [y]) (screen
 * axes, y down) points into, as a mask of 1 shl [Direction.ordinal].
 */
fun TouchpadSettings.directionsAt(x: Float, y: Float, hold: Boolean = false): Int {
    if (x == 0f && y == 0f) return 0
    var mask = 0
    for (d in Direction.entries) if (inPie(x, y, d, hold)) mask = mask or (1 shl d.ordinal)
    return mask
}

/** Whether vector ([x], [y]) (screen axes, y down) lies inside [d]'s pie. */
fun TouchpadSettings.inPie(x: Float, y: Float, d: Direction, hold: Boolean = false): Boolean {
    val deg = Math.toDegrees(atan2(x.toDouble(), (-y).toDouble())).toFloat()
    val off = abs(((deg - d.deg) % 360f + 540f) % 360f - 180f)
    // Never square across or behind its way: a 180° pie is its open half.
    return off <= pieDeg(d, hold) / 2f && off < 90f
}

/**
 * How held directions (Hold ←→↑↓) work. FROM_START: held while the finger is past the hold
 * distance from where the motion started (a floating stick; sliding back lets go). DIRECTION_CHANGE:
 * one is held once the finger has travelled the hold distance since turning that way, until another
 * direction takes over or the fingers lift.
 */
@Serializable
enum class HoldMode(val label: String) {
    FROM_START("From start point"), DIRECTION_CHANGE("Direction change"),
}

/** Swipe directions and tap of a three- or four-finger touchpad gesture. */
@Serializable
enum class TouchpadGesture(val label: String) {
    SWIPE_UP("Swipe up"),
    SWIPE_DOWN("Swipe down"),
    SWIPE_LEFT("Swipe left"),
    SWIPE_RIGHT("Swipe right"),
    TAP("Tap"),
}

/** What each [TouchpadGesture] does for one finger count. */
@Serializable
data class FingerGestures(
    val swipeUp: GestureAction = GestureAction.None,
    val swipeDown: GestureAction = GestureAction.None,
    val swipeLeft: GestureAction = GestureAction.None,
    val swipeRight: GestureAction = GestureAction.None,
    val tap: GestureAction = GestureAction.None,
) {
    fun action(g: TouchpadGesture): GestureAction = when (g) {
        TouchpadGesture.SWIPE_UP -> swipeUp
        TouchpadGesture.SWIPE_DOWN -> swipeDown
        TouchpadGesture.SWIPE_LEFT -> swipeLeft
        TouchpadGesture.SWIPE_RIGHT -> swipeRight
        TouchpadGesture.TAP -> tap
    }

    fun with(g: TouchpadGesture, a: GestureAction): FingerGestures = when (g) {
        TouchpadGesture.SWIPE_UP -> copy(swipeUp = a)
        TouchpadGesture.SWIPE_DOWN -> copy(swipeDown = a)
        TouchpadGesture.SWIPE_LEFT -> copy(swipeLeft = a)
        TouchpadGesture.SWIPE_RIGHT -> copy(swipeRight = a)
        TouchpadGesture.TAP -> copy(tap = a)
    }

    companion object {
        /** Windows precision touchpad defaults. */
        val WINDOWS_3 = FingerGestures(
            swipeUp = GestureActions.TASK_VIEW,
            swipeDown = GestureActions.SHOW_DESKTOP,
            swipeLeft = GestureAction.SwitchApps,
            swipeRight = GestureAction.SwitchApps,
            tap = GestureAction.Mouse(MouseButton.MIDDLE),
        )
        val WINDOWS_4 = FingerGestures(
            swipeUp = GestureActions.TASK_VIEW,
            swipeDown = GestureActions.SHOW_DESKTOP,
            swipeLeft = GestureActions.DESKTOP_LEFT,
            swipeRight = GestureActions.DESKTOP_RIGHT,
            tap = GestureActions.NOTIFICATIONS,
        )
    }
}

/** What a touchpad gesture sends to the PC. */
@Serializable
sealed interface GestureAction {
    @Serializable
    @SerialName("none")
    data object None : GestureAction

    /**
     * Presses and releases a key combo. [modifiers] uses the HID modifier byte bits (LCtrl = 1,
     * LShift = 2, LAlt = 4, LWin = 8, ...); [key] is a HID usage, or 0 for modifiers alone.
     */
    @Serializable
    @SerialName("keys")
    data class Keys(val modifiers: Int, val key: Int = 0) : GestureAction

    /** Clicks a mouse button. */
    @Serializable
    @SerialName("mouse")
    data class Mouse(val button: MouseButton) : GestureAction

    /**
     * Windows app switching: Alt is held while the fingers stay down and Tab (moving right) or
     * Shift+Tab (moving left) is sent per step of travel. On an up/down swipe it is one Alt+Tab.
     */
    @Serializable
    @SerialName("switch_apps")
    data object SwitchApps : GestureAction
}

/** Matches an Android InputDevice. Null fields match anything. */
@Serializable
data class DeviceMatch(
    val vendorId: Int? = null,
    val productId: Int? = null,
    val nameContains: String? = null,
) {
    fun matches(vendor: Int, product: Int, name: String): Boolean =
        (vendorId == null || vendorId == vendor) &&
                (productId == null || productId == product) &&
                (nameContains == null || name.contains(nameContains, ignoreCase = true))
}

/**
 * A game profile: an ordered list of [workspaces], each a complete setup (on-screen layout,
 * touchpad, gyro and the physical game pads attached to it). The [corners] are shared by all
 * workspaces: tap left = previous workspace, tap right = next, both together = fullscreen, and the
 * gyro corner switches the current workspace's gyro between always on and always off.
 * Files saved before workspaces existed are read by [ProfileJson], which migrates them.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    /** Never empty (see [ProfileJson.normalize]). */
    val workspaces: List<Workspace>,
    /** Stepping past the last workspace goes to the first (and back). */
    val wrapWorkspaces: Boolean = false,
    /** Windows executables this profile belongs to (used by the companion for auto-switching). */
    val games: List<String> = emptyList(),
    /** Auto-select this profile when a matching controller is attached to the phone. */
    val controllers: List<DeviceMatch> = emptyList(),
    /**
     * The previous / next workspace corners ([NavCorners]), kind [TouchKind.FULLSCREEN]. A gyro
     * corner from before Toggle gyro buttons may be here until [NavCorners.gyroCornerToButtons].
     */
    val corners: List<TouchControl> = NavCorners.defaults(),
    /** Whether the old gyro corner ([NavCorners.GYRO]) was shown; read once to convert it. */
    val gyroCorner: Boolean = true,
    /** Opacity of the corners in play; they ignore the workspaces' control opacity. */
    val cornerOpacity: Float = 0.5f,
    /** Resizing one corner resizes the other too. */
    val cornerSameSize: Boolean = true,
    /** What physical devices do, pinned by workspaces ([Workspace.devicePreset]). */
    val devicePresets: List<DevicePreset> = emptyList(),
    /**
     * The workspace defaults: every workspace's settings ([WorkspaceDefaults.keys]) unless it sets
     * them itself. Only those settings of it are used.
     */
    val defaults: Workspace = WorkspaceDefaults.builtin,
    /** The person's own game presets ([GameSens]), typed or measured, beside [GameSens.builtin]. */
    val gamePresets: List<GamePreset> = emptyList(),
) {
    /** The corners shown on the pad: ← and →. */
    val shownCorners: List<TouchControl>
        get() = corners.filter { it.id != NavCorners.GYRO }

    /** The workspace at [index], clamped into range. */
    fun workspace(index: Int): Workspace = workspaces[index.coerceIn(0, workspaces.lastIndex)]

    fun indexOfWorkspace(id: String): Int = workspaces.indexOfFirst { it.id == id }

    /** Copy with the workspace at [index] replaced by [change] of it. */
    fun updateWorkspace(index: Int, change: (Workspace) -> Workspace): Profile {
        val i = index.coerceIn(0, workspaces.lastIndex)
        return copy(workspaces = workspaces.mapIndexed { k, w -> if (k == i) change(w) else w })
    }

    /** Where ← / → from [index] lead; null at an end without wrap-around. */
    fun previousWorkspace(index: Int): Int? = when {
        index > 0 -> index - 1
        wrapWorkspaces && workspaces.size > 1 -> workspaces.lastIndex
        else -> null
    }

    fun nextWorkspace(index: Int): Int? = when {
        index < workspaces.lastIndex -> index + 1
        wrapWorkspaces && workspaces.size > 1 -> 0
        else -> null
    }

    companion object {
        const val MIN_CONTROL_OPACITY = 0.1f
    }
}
