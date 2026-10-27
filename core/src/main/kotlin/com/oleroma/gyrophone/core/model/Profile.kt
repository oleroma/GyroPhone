package com.oleroma.gyrophone.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.min

@Serializable
/** FULLSCREEN: a workspace corner (see [NavCorners]); it lives in [Profile.corners], never in a workspace. */
enum class TouchKind { BUTTON, STICK, FULLSCREEN }

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
    /**
     * Click-through (BUTTON kind): the finger presses at once and also moves the cursor like a
     * touchpad finger until it lifts, like a fire button you aim with in mobile shooters.
     */
    val clickThrough: Boolean = false,
    /** Height / width of a SQUARE button (circles and sticks stay even), in both orientations. */
    val aspect: Float = 1f,
    /** STICK: deflection (0..1 of the radius) that counts as centred; the rest is rescaled to 0..1. */
    val deadzone: Float = 0f,
    /** STICK: how deflection becomes directions. */
    val zones: StickZones = StickZones.OVERLAP,
    /**
     * BUTTON without click-through: a small touchpad of its own. A still tap presses it; a swipe
     * drives its swipe mappings ([InputSource.TouchSwipe]) instead.
     */
    val swipe: Boolean = false,
    /**
     * Before swipe buttons were touch surfaces: swipe sensitivity across and down (mouse counts
     * per dp). Only read for a button with no [surface] settings yet ([surfaceSettings]).
     */
    val swipeSensX: Float = 1.5f,
    val swipeSensY: Float = 1.5f,
    /** Swipe mode: press only on a still tap's release (else the press starts at touch-down). */
    val swipeTapOnRelease: Boolean = false,
    /**
     * With a double tap mapping: the press waits to see if a second tap follows (a double tap
     * then never presses). Off: the press is instant, and a double tap also presses twice.
     */
    val doubleTapWaits: Boolean = false,
    /**
     * Release mapping: fires only after a press shorter than this (ms), a quick tap; 0 = after
     * every press.
     */
    val releaseTapMs: Int = 0,
    /**
     * Zone mapping ([TouchAreaKind.ZONE]): a finger that slides in from elsewhere stops what it
     * was doing (cursor, stick, another button) while it is in the zone, and one that lands on
     * the zone but on no other control starts no cursor.
     */
    val zoneInterrupt: Boolean = false,
    /** With [zoneInterrupt]: the stopped job resumes when the finger leaves (off: stopped until it lifts). */
    val zoneResume: Boolean = false,
    /**
     * Swipe mode: the touch surface settings this button changes from the workspace touchpad's;
     * null (a button from before) = its old swipe sensitivities, no acceleration, no inertia.
     */
    val surface: SurfaceSettings? = null,
    /** Among overlapping controls ([hitOrder]). */
    val layer: TouchLayer = TouchLayer.AUTO,
    /** A finger landing here while another control is in front also works this control. */
    val sharesTouches: Boolean = false,
) {
    /** In swipe mode: a button with [swipe] on and click-through off. */
    val swipes: Boolean get() = kind == TouchKind.BUTTON && swipe && !clickThrough

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
    val sensitivityY: Float? = null,
    val acceleration: Float? = null,
    val scrollSpeed: Float? = null,
    val naturalScroll: Boolean? = null,
    val inertia: Boolean? = null,
    val releaseTapMs: Int? = null,
    val cursorDeadzoneDp: Float? = null,
    val catchUpMs: Int? = null,
    val deadzoneRearmMs: Int? = null,
    val deadzoneStillDp: Float? = null,
)

/** These settings with [o]'s own values in place of theirs. */
fun TouchpadSettings.with(o: SurfaceSettings?): TouchpadSettings = if (o == null) this else copy(
    sensitivity = o.sensitivity ?: sensitivity,
    sensitivityY = o.sensitivityY ?: sensitivityY,
    acceleration = o.acceleration ?: acceleration,
    scrollSpeed = o.scrollSpeed ?: scrollSpeed,
    naturalScroll = o.naturalScroll ?: naturalScroll,
    inertia = o.inertia ?: inertia,
    releaseTapMs = o.releaseTapMs ?: releaseTapMs,
    cursorDeadzoneDp = o.cursorDeadzoneDp ?: cursorDeadzoneDp,
    catchUpMs = o.catchUpMs ?: catchUpMs,
    deadzoneRearmMs = o.deadzoneRearmMs ?: deadzoneRearmMs,
    deadzoneStillDp = o.deadzoneStillDp ?: deadzoneStillDp,
)

/** A swipe button's own settings: [TouchControl.surface], or what its old swipe settings were. */
val TouchControl.ownSurface: SurfaceSettings
    get() = surface ?: SurfaceSettings(sensitivity = swipeSensX, sensitivityY = swipeSensY, acceleration = 0f, inertia = false)

/** How a swipe button's surface works: the workspace touchpad [pad] with the button's own settings. */
fun TouchControl.surfaceSettings(pad: TouchpadSettings): TouchpadSettings =
    pad.copy(enabled = true, mapped = true).with(ownSurface)

/** Height / width as drawn: [TouchControl.aspect] for a square button, else 1. */
val TouchControl.heightRatio: Float
    get() = if (shape == TouchShape.SQUARE && kind == TouchKind.BUTTON) aspect else 1f

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
 * When gyro moves the mouse. ALWAYS = on, a held ratchet ([OutputTarget.GyroPause], or a touchpad
 * finger with [GyroSettings.touchpadRatchet]) pauses it; OFF = off, a held ratchet engages it;
 * [OutputTarget.GyroToggle] swaps the two. SENSOR_OFF = gyro off completely (the sensor is not
 * read, saving battery; ratchets and toggle do nothing). HOLD_OR_TOGGLE is only read from old
 * files and loads as OFF ([ProfileJson.normalize]).
 */
@Serializable
enum class GyroMode(val label: String) {
    OFF("Ratchet enables"),
    ALWAYS("Ratchet disables"),
    SENSOR_OFF("Gyro off"),
    HOLD_OR_TOGGLE("Ratchet enables"),
}

/** The cursor axes a gyro ratchet acts on. */
@Serializable
enum class RatchetAxes(val label: String) { BOTH("Both"), X("Only X"), Y("Only Y") }

/** How the phone is held. AUTO follows the screen rotation. */
@Serializable
enum class GyroOrientation(val label: String) {
    AUTO("Auto (screen rotation)"),
    LANDSCAPE_LEFT("Landscape, top on the left"),
    LANDSCAPE_RIGHT("Landscape, top on the right"),
    PORTRAIT("Portrait"),
    FLAT("Flat, screen up"),
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
    /** Horizontal mouse counts per degree of rotation (0 = the cursor never moves sideways). */
    val sensitivity: Float = 16f,
    /** Vertical mouse counts per degree; null (older files) = [sensitivity] × [verticalRatio]. */
    val sensitivityY: Float? = null,
    val orientation: GyroOrientation = GyroOrientation.AUTO,
    /** Motion that moves the cursor horizontally: yaw right / pitch up / roll clockwise = right. */
    val mouseX: MotionAxis = MotionAxis.YAW,
    val invertX: Boolean = false,
    val scaleX: Float = 1f,
    /** Motion that moves the cursor vertically: pitch up / yaw right / roll clockwise = up. */
    val mouseY: MotionAxis = MotionAxis.PITCH,
    val invertY: Boolean = false,
    val scaleY: Float = 1f,
    /** Only read from older files, through [verticalSensitivity]. */
    val verticalRatio: Float = 1f,
    /** Below this speed (deg/s) motion is scaled down, killing hand jitter. 0 (default, raw gyro) disables. */
    val tighteningThreshold: Float = 0f,
    /** Below this speed (deg/s) motion is averaged over a short window. 0 (default, raw gyro) disables. */
    val smoothingThreshold: Float = 0f,
    /**
     * Axis mapping used while the phone is held in portrait. Null = same as the fields above,
     * which are the landscape mapping (and the only one in profiles saved before this existed).
     */
    val portrait: GyroAxes? = null,
    /**
     * A finger on the empty touchpad is a ratchet: gyro pauses (ALWAYS) or engages (OFF) meanwhile.
     * A click-through button's finger counts only through the button's own ratchet mapping.
     */
    val touchpadRatchet: Boolean = false,
    /** Which cursor axes a ratchet flips; the other one stays as the mode has it without a ratchet. */
    val ratchetAxes: RatchetAxes = RatchetAxes.BOTH,
) {
    val verticalSensitivity: Float get() = sensitivityY ?: (sensitivity * verticalRatio)

    val landscapeAxes: GyroAxes get() = GyroAxes(mouseX, invertX, scaleX, mouseY, invertY, scaleY)

    fun axesFor(portrait: Boolean): GyroAxes = (if (portrait) this.portrait else null) ?: landscapeAxes

    /** Returns a copy with [axes] as the mapping for that orientation. */
    fun withAxes(portrait: Boolean, axes: GyroAxes): GyroSettings =
        if (portrait) copy(portrait = axes)
        else copy(mouseX = axes.mouseX, invertX = axes.invertX, scaleX = axes.scaleX,
            mouseY = axes.mouseY, invertY = axes.invertY, scaleY = axes.scaleY)

    /** Copy with the portrait axes spelled out (the landscape ones when unset), so each orientation has its own. */
    fun withBothOrientations(): GyroSettings = if (portrait != null) this else copy(portrait = landscapeAxes)
}

/** Which motion drives each mouse axis; see [GyroSettings.mouseX] and [GyroSettings.mouseY]. */
@Serializable
data class GyroAxes(
    val mouseX: MotionAxis = MotionAxis.YAW,
    val invertX: Boolean = false,
    val scaleX: Float = 1f,
    val mouseY: MotionAxis = MotionAxis.PITCH,
    val invertY: Boolean = false,
    val scaleY: Float = 1f,
)

/** The pad screen's empty background as a laptop touchpad. */
@Serializable
data class TouchpadSettings(
    val enabled: Boolean = true,
    /** Horizontal mouse counts per dp of finger travel at low speed (0 = no sideways motion). */
    val sensitivity: Float = 1.5f,
    /** Vertical counts per dp; null (older files) = [sensitivity]. */
    val sensitivityY: Float? = null,
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
    /** A two-finger scroll that lifts while moving keeps gliding and slows down. */
    val inertia: Boolean = true,
    /** Glide time constant in ms: how long the scroll keeps going after the fingers lift. */
    val inertiaGlideMs: Float = 325f,
    /** Two fingers spreading or closing zoom (Ctrl + wheel). */
    val pinchZoom: Boolean = true,
    /** Three- and four-finger swipes and taps run [gestures3] / [gestures4]. */
    val gestures: Boolean = true,
    val gestures3: FingerGestures = FingerGestures.WINDOWS_3,
    val gestures4: FingerGestures = FingerGestures.WINDOWS_4,
    /**
     * The touchpad's taps and gestures come from the workspace's touchpad mappings
     * ([InputSource.Surface], put in by [MappingProfiles.resolve]). Off (a workspace not
     * resolved): from [tapToClick], [tapDrag], [twoFingerScroll], [horizontalScroll], [pinchZoom]
     * and [gestures], which are otherwise only read for a mapping profile saved before touchpad
     * mappings ([Bindings.defaultTouchpadMappings]).
     */
    val mapped: Boolean = false,
    /** Release mappings fire only after a touch shorter than this (ms); 0 = after every touch. */
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
) {
    fun gesturesFor(fingers: Int): FingerGestures = if (fingers >= 4) gestures4 else gestures3

    val verticalSensitivity: Float get() = sensitivityY ?: sensitivity

    companion object {
        const val MIN_GLIDE_MS = 100f
        const val MAX_GLIDE_MS = 1000f
        const val MAX_RELEASE_TAP_MS = 1000
        const val MAX_CURSOR_DEADZONE_DP = 80f
        const val MAX_CATCH_UP_MS = 500
        const val MAX_REARM_MS = 1000
        const val MAX_STILL_DP = 10f
    }
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
    @Serializable @SerialName("none")
    data object None : GestureAction

    /**
     * Presses and releases a key combo. [modifiers] uses the HID modifier byte bits (LCtrl = 1,
     * LShift = 2, LAlt = 4, LWin = 8, ...); [key] is a HID usage, or 0 for modifiers alone.
     */
    @Serializable @SerialName("keys")
    data class Keys(val modifiers: Int, val key: Int = 0) : GestureAction

    /** Clicks a mouse button. */
    @Serializable @SerialName("mouse")
    data class Mouse(val button: MouseButton) : GestureAction

    /**
     * Windows app switching: Alt is held while the fingers stay down and Tab (moving right) or
     * Shift+Tab (moving left) is sent per step of travel. On an up/down swipe it is one Alt+Tab.
     */
    @Serializable @SerialName("switch_apps")
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
