package com.oleroma.gyrophone.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.ButtonLibrary
import com.oleroma.gyrophone.core.model.ControlGroups
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.GyroDisplay
import com.oleroma.gyrophone.core.model.GyroDisplayKind
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.LibraryButton
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.touchIdOf
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Placement
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchAreaKind
import com.oleroma.gyrophone.core.model.Where
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchLayer
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.WorkspaceDefaults
import com.oleroma.gyrophone.core.model.WorkspaceMappings
import com.oleroma.gyrophone.core.model.contains
import com.oleroma.gyrophone.core.model.heightRatio
import com.oleroma.gyrophone.core.model.hitOrder
import com.oleroma.gyrophone.core.model.layered
import com.oleroma.gyrophone.core.model.ownSurface
import com.oleroma.gyrophone.core.model.placeSize
import com.oleroma.gyrophone.core.model.placeX
import com.oleroma.gyrophone.core.model.placeY
import com.oleroma.gyrophone.core.model.placement
import com.oleroma.gyrophone.core.model.surfaceSettings
import com.oleroma.gyrophone.core.model.touchedControls
import com.oleroma.gyrophone.core.model.withPlacement
import com.oleroma.gyrophone.core.model.withSets
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val GRID = 0.02f
private const val NEW_BUTTON_SIZE = 0.14f
private const val NEW_STICK_SIZE = 0.3f
private const val MIN_EDIT_OPACITY = 0.5f

/** Opacity factor of a corner arrow that leads nowhere (first / last workspace, no wrap-around). */
private const val END_CORNER_ALPHA = 0.35f

/** Lowest opacity of a corner in play: the ← / → corners are the way out of fullscreen. */
private const val MIN_CORNER_ALPHA = 0.25f

/** With auto-hide on, how long the ← / → corners stay after a reveal. */
private const val CORNER_SHOW_MS = 3_000L

/** Quick taps in a row that show auto-hidden corners, each at most [REVEAL_GAP_NS] after the last. */
private const val REVEAL_TAPS = 4
private const val REVEAL_GAP_NS = 300_000_000L
private const val PILL_MS = 1_000L

/** Highlight refresh while a finger is on a button (holds and lift commits happen between events). */
private const val HIGHLIGHT_POLL_MS = 50L

/** With live mappings: refresh fast enough to catch a tap's short pulse. */
private const val LIVE_POLL_MS = 16L

/** How often the pad screen checks the mapping set in use (for its pill and live labels). */
private const val LAYER_POLL_MS = 100L

/** How long a button keeps showing what it sent after it stops. */
private const val LIVE_LINGER_MS = 700L
private val LIVE_LABEL_COLOR = Color(0xFFFFE08A)

/** Edit mode: how much of a control (all of a smaller one) must stay on screen, in dp: about a fingertip. */
private const val KEEP_ON_SCREEN_DP = 48f

/** Edit mode: [selected] value for the touchpad (empty space), whose screens are open. */
private const val TOUCHPAD_SELECTION = "\u0000touchpad"

/**
 * Edit mode's own touch timing (not the workspace's): a press held [EDIT_LONG_PRESS_MS] opens the
 * control's mapping editor. A tap selects as soon as it lifts.
 */
private const val EDIT_LONG_PRESS_MS = 450L

/** Edit mode undo: changes this close together (a slider being dragged, say) make one step. */
private const val UNDO_SETTLE_MS = 400L

/** Edit mode undo: the most steps kept. */
private const val UNDO_MAX = 100

/**
 * PRESS: on a control, not yet moved; HELD: the same after a long press (settings screen open);
 * TWO: two fingers down and still (lifted: a two-finger tap; moved: a PINCH).
 */
private enum class EditGesture { PRESS, HELD, MOVE, TWO, PINCH, EMPTY, NONE }

/** Below this spread (dp) on one axis, a pinch sizes that axis by distance rather than by ratio. */
private const val PINCH_AXIS_FLOOR_DP = 48f

/** Edit mode: the selected control, and the panel buttons that act on it. */
private val SELECTED_COLOR = Color(0xFFFFC857)

/** Edit mode's Select: the controls picked, and the panel buttons that act on them. */
private val PICKED_COLOR = Color(0xFFFF5A5A)

/** [want] cut down to [can] (the same way, no further); nothing when [can] goes the other way. */
private fun towards(want: Float, can: Float): Float =
    if (want * can <= 0f) 0f else if (abs(can) < abs(want)) can else want

/**
 * A rectangle's pinch locks to one axis, like a heading lock: once the fingers' spread has changed
 * by [PINCH_LOCK_DECIDE_DP], an axis that changed [PINCH_LOCK_RATIO] times more than the other is
 * the only one resized. The other joins in once its own spread changes by [PINCH_LOCK_RELEASE_DP].
 */
private enum class PinchLock { UNDECIDED, X, Y, FREE }

private const val PINCH_LOCK_DECIDE_DP = 16f
private const val PINCH_LOCK_RATIO = 2f
private const val PINCH_LOCK_RELEASE_DP = 40f

/** Pinch scale on one axis: the spread [now] over the spread at the start, with [floor] as the least start. */
private fun axisScale(start: Float, now: Float, floor: Float): Float {
    val base = max(start, floor)
    return ((base + now - start) / base).coerceAtLeast(0.01f)
}

/** What edit mode shows for the selected control: its mapping screen or its settings screen (a corner: its menu). */
private enum class EditMenu { NONE, MAPPING, CONTEXT }

/** What a play-mode finger was doing before an interrupting zone stopped it. */
private enum class FingerJob { NONE, PAD, BUTTON, STICK }

/**
 * A play-mode finger's place relative to the controls' areas: the zones it is in, the control it
 * took on touch-down ([origin], for Outside) and whether it is outside it, and a job an
 * interrupting zone stopped ([stopped]), resumed on leaving when [resume].
 */
private class FingerAreas(var origin: TouchControl?) {
    val zones = HashSet<String>()
    var outside = false
    var stopped = FingerJob.NONE
    var resume = false
}

/**
 * The touch event being dispatched, as [MainActivity] saw it (main thread; Compose runs the pad's
 * pointer handler synchronously inside dispatchTouchEvent). Also owns the window's unbuffered
 * input request: without one, Android hands MOVE events over once per frame (touch resampled
 * 5 ms into the past) instead of as they arrive.
 */
internal object TouchStream {
    /** A system ACTION_CANCEL: the phone took every pointer. */
    var cancelled = false

    /** An ACTION_POINTER_UP with FLAG_CANCELED: the one pointer that lifted was unintended (e.g. a palm). */
    var pointerCancelled = false

    /** MotionEvent.getEventTimeNanos: CLOCK_MONOTONIC, the same base as System.nanoTime (the engine's clock). */
    var eventNanos = 0L

    /** The pad is in play mode: touch is unbuffered too. Menus and the editor keep batched, resampled touch. */
    private var playUnbuffered = false

    /** Asks for unbuffered joystick input (always) and touch (in play mode). [decor] is the window's decor view. */
    fun requestUnbuffered(decor: View) {
        val pointer = if (playUnbuffered) InputDevice.SOURCE_CLASS_POINTER else InputDevice.SOURCE_CLASS_NONE
        decor.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK or pointer)
    }

    fun setPlayUnbuffered(decor: View, on: Boolean) {
        playUnbuffered = on
        requestUnbuffered(decor)
    }
}

/**
 * Full-screen virtual game pad for the active profile's active workspace. Multi-touch: each finger
 * that lands on a stick owns it until lifted; a finger that lands on a button goes to the engine
 * (which applies touchpad over buttons and sticky modifiers). A finger that lands on empty
 * background drives the touchpad (cursor, taps, drag, scroll). The corners ([Profile.corners])
 * are always shown: tap ← / → for the previous / next workspace, both together for fullscreen.
 * Controls have separate landscape and portrait placements; the pad box's shape picks one.
 * Edit mode works on the current workspace in a draft profile. Its menu floats and can be dragged
 * out of the way or resized; its ⚙ saves and opens the workspace's settings page. Tap a control to select it
 * as the tap lifts (taps on overlapping controls cycle through them); long-press it for its full-screen
 * mapping screen, whose Settings opens the control's settings (a corner: a menu next to it). A drag anywhere moves the selected control
 * (with none selected, the one under the finger); a pinch or the size slider resizes it. The menu's 🔒 stops
 * drags and pinches from moving or resizing what was there when edit mode opened (what is added
 * meanwhile stays free until edit mode opens again); ↶ / ↷ undo and redo. Edits change the
 * current orientation's placement. Done saves (a builtin becomes a custom copy) and switches to the workspace being edited.
 * In play mode the top row ([CapsuleRow]) switches and adds workspaces (tap the current one to edit
 * it) and opens Connection and Settings ([PadTab]) through [onOpenMenu]. With [GamepadRuntime.cornerAutoHide]
 * the ← / → corners fade out after a few seconds; four quick taps (or touching a corner) show them again.
 */
@Composable
fun PadScreen(
    runtime: GamepadRuntime,
    fullscreen: Boolean = false,
    onFullscreen: (Boolean) -> Unit = {},
    onOpenMenu: (PadMenuPage) -> Unit = {},
    onWorkspaceSettings: () -> Unit = {},
    startInEdit: Boolean = false,
    onEditResumed: () -> Unit = {},
) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val library by runtime.buttonLibrary.collectAsStateWithLifecycle()
    /** A library button being removed (asks first). */
    var removingLib by remember { mutableStateOf<LibraryButton?>(null) }

    /** The pad's top-left in the window, to turn a drop from the edit menu into a place on the pad. */
    var padOrigin by remember { mutableStateOf(Offset.Zero) }

    /** Something being dragged out of the edit menu: its label, where the finger is (window), and whether it is out on the pad. */
    var dropping by remember { mutableStateOf<Triple<String, Offset, Boolean>?>(null) }
    val isFullscreen by rememberUpdatedState(fullscreen)
    val setFullscreen by rememberUpdatedState(onFullscreen)

    /**
     * Set once the fullscreen chord fired, cleared when every finger is up. Outside the
     * touch handler because the toggle resizes the pad, which restarts the handler mid-press.
     */
    val chordFired = remember { BooleanArray(1) }
    var draft by remember { mutableStateOf<Profile?>(null) }

    /** Workspace shown while editing (index into the draft). */
    var editIndex by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(EditMenu.NONE) }

    /** The menu's Select: taps pick several controls ([picked], whole groups) and a drag moves them all. */
    var selectMode by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** The control's settings were opened from its mapping screen: closing them goes back there. */
    var settingsFromMap by remember { mutableStateOf(false) }

    /** The floating edit menu: drag offset from its top-centre home, size, folded. */
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    /** The edit panel's visible bounds in the window, for menus that open beside it. */
    var panelBounds by remember { mutableStateOf(Rect.Zero) }

    /** Width the user dragged the edit menu to (px); null = fit its rows. */
    var panelWidthPx by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    /** Cancel was tapped with unsaved changes: asking whether to throw them away. */
    var confirmingCancel by remember { mutableStateOf(false) }

    /** Leaves edit mode without saving, on the workspace being looked at if the saved profile has it. */
    fun discardEdits() {
        val d = draft ?: return
        val i = profile.indexOfWorkspace(d.workspace(editIndex).id)
        draft = null
        selected = null
        selectMode = false
        picked = emptySet()
        dragging = false
        if (i >= 0 && i != runtime.workspaceIndex.value) runtime.switchWorkspace(i)
    }
    var snap by remember { mutableStateOf(false) }
    val layoutLocked by runtime.editLayoutLocked.collectAsStateWithLifecycle()

    /**
     * The controls (and corners) there were when edit mode opened: the only ones the lock holds, so
     * what is added in this session can still be moved and sized until edit mode opens again.
     */
    val lockableIds = remember { arrayOf<Set<String>>(emptySet()) }

    /** Whether the lock keeps [id] from being moved or sized. */
    fun lockedOut(id: String?) = layoutLocked && id != null && id in lockableIds[0]

    /** Edit mode undo: earlier drafts (newest last), drafts undone, and the draft the last step ended on. */
    val undoSteps = remember { mutableStateListOf<Profile>() }
    val redoSteps = remember { mutableStateListOf<Profile>() }
    val undoBase = remember { arrayOfNulls<Profile>(1) }

    // A change becomes an undo step once the draft has been still for a moment and no finger is
    // moving or resizing a control, so one drag or pinch is one step.
    LaunchedEffect(draft, dragging) {
        val d = draft
        val base = undoBase[0]
        if (d == null) {
            undoSteps.clear()
            redoSteps.clear()
            undoBase[0] = null
            return@LaunchedEffect
        }
        if (base == null) {
            undoBase[0] = d
            return@LaunchedEffect
        }
        if (dragging || d == base) return@LaunchedEffect
        delay(UNDO_SETTLE_MS)
        undoSteps.add(base)
        if (undoSteps.size > UNDO_MAX) undoSteps.removeAt(0)
        redoSteps.clear()
        undoBase[0] = d
    }

    /** Goes back one step (first dropping a change that hasn't become a step yet). */
    fun undoEdit() {
        val d = draft ?: return
        val base = undoBase[0] ?: return
        val back = if (d != base) base else undoSteps.removeLastOrNull() ?: return
        redoSteps.add(d)
        undoBase[0] = back
        draft = back
    }

    fun redoEdit() {
        val d = draft ?: return
        val next = redoSteps.removeLastOrNull() ?: return
        undoSteps.add(undoBase[0] ?: d)
        undoBase[0] = next
        draft = next
    }

    /** Knob offsets; read only while placing the knobs, so stick motion re-places them without recomposing. */
    val stickPos = remember { mutableStateMapOf<String, Offset>() }

    /** Sticks with a finger on them (changes only on touch-down and lift). */
    var heldSticks by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** Pointers currently routed to engine.buttonDown/Move/Up. */
    val buttonFingers = remember { HashSet<Long>() }
    var fingersOnButtons by remember { mutableIntStateOf(0) }
    var pressedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var latchedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var cornersHeld by remember { mutableStateOf<Set<String>>(emptySet()) }

    /** Counts multi-finger touches that Android cancelled (a phone gesture took them). */
    var systemTookTouch by remember { mutableIntStateOf(0) }
    val editing = draft != null
    val autoHide by runtime.cornerAutoHide.collectAsStateWithLifecycle()
    val visuals by runtime.touchVisuals.collectAsStateWithLifecycle()
    val trails = remember { TouchTrails() }
    val showTouchVisuals = !editing && (visuals.showTouches || visuals.trail)
    LaunchedEffect(showTouchVisuals) { if (!showTouchVisuals) trails.clear() }
    val swipeLines = remember { SwipeLines() }
    val swipeLineOn by rememberUpdatedState(visuals.swipeLine)
    LaunchedEffect(swipeLineOn) { if (!swipeLineOn) swipeLines.clear() }

    /** Bumped to show auto-hidden ← / → corners again: four quick taps, or a corner touched. */
    var revealTick by remember { mutableIntStateOf(0) }
    var arrowsShown by remember { mutableStateOf(true) }
    LaunchedEffect(revealTick, autoHide, editing) {
        arrowsShown = true
        if (!autoHide || editing) return@LaunchedEffect
        delay(CORNER_SHOW_MS)
        arrowsShown = false
    }
    val arrowFade by animateFloatAsState(if (arrowsShown) 1f else 0f, label = "arrows")

    /** Quick-tap run for the reveal: last touch-down (event nanos) and taps so far. */
    val tapRun = remember { LongArray(2) }

    /**
     * Restarts the play touch handler on layout changes but not on gyro changes, so the gyro
     * corner switching the mode leaves held sticks and buttons alone.
     */
    val playKey =
        remember(profile) { profile.copy(workspaces = profile.workspaces.map { it.copy(gyro = GyroSettings()) }) }
    val shown = draft ?: profile
    val shownIndex = (if (editing) editIndex else wsIndex).coerceIn(0, shown.workspaces.lastIndex)
    val ws = shown.workspace(shownIndex)

    // Live mappings: per control, its mappings (not haptics), what it sends now, and until when it
    // stays shown; watched until [liveWatchUntil] after the last touch (a tap may wait out its window).
    // The mapping set the control mapping screen edits (kept while edit mode lasts).
    var mapSet by remember { mutableStateOf(MappingSet.BASE) }
    // Mapping sets: the set in use and its overrides, watched while this workspace has layer actions.
    var layers by remember { mutableStateOf(MappingEngine.Layers(MappingSet.BASE, emptyList())) }
    val hasLayers = remember(ws) {
        (ws.mappings + ws.keyboard + ws.mouse + ws.pads.flatMap { it.mappings }).any { it.target is OutputTarget.Layer }
    }
    LaunchedEffect(hasLayers, editing) {
        if (!hasLayers || editing) {
            layers = MappingEngine.Layers(MappingSet.BASE, emptyList())
            return@LaunchedEffect
        }
        while (true) {
            val l = runtime.engine.layers
            if (l != layers) layers = l
            delay(LAYER_POLL_MS)
        }
    }
    // What each control maps in the set in use (labels), and what it really presses now (live
    // labels: the engine's mappings in use, held inputs carrying their old set's included).
    val controlMappings = remember(ws, layers) {
        layered(ws.mappings, layers.set, layers.overrides).filterNot { Describe.isHaptic(it.target) }
            .groupBy { touchIdOf(it.source) }
    }
    val inUseCache = remember { arrayOfNulls<List<Mapping>>(1) }
    val inUseGroups = remember { arrayOf<Map<String?, List<Mapping>>>(emptyMap()) }
    val liveLabels = remember { mutableStateMapOf<String, String>() }
    val liveUntil = remember { HashMap<String, Long>() }
    val liveWatchUntil = remember { LongArray(1) }
    var liveWatching by remember { mutableStateOf(false) }

    fun refreshLive() {
        val now = SystemClock.uptimeMillis()
        if (!visuals.liveMappings || editing) {
            liveLabels.clear(); liveUntil.clear(); liveWatching = false
            return
        }
        val inUse = runtime.engine.touchMappingsInUse
        if (inUse !== inUseCache[0]) {
            inUseCache[0] = inUse
            inUseGroups[0] = inUse.filterNot { Describe.isHaptic(it.target) || it.target == OutputTarget.NoOutput }
                .groupBy { touchIdOf(it.source) }
        }
        val groups = inUseGroups[0]
        for (id in groups.keys + liveLabels.keys.toList()) {
            if (id == null) continue
            val ms = groups[id].orEmpty()
            val text = ms.filter { abs(runtime.sourceValue(it.source)) >= 0.5f }
                .map { Describe.shortTarget(it.target) }.distinct().joinToString(" + ")
            if (text.isNotEmpty()) {
                if (liveLabels[id] != text) liveLabels[id] = text
                liveUntil[id] = now + LIVE_LINGER_MS
            } else if ((liveUntil[id] ?: 0L) < now) {
                liveUntil.remove(id)
                if (id in liveLabels) liveLabels.remove(id)
            }
        }
        if (liveWatching && now > liveWatchUntil[0] && liveLabels.isEmpty()) liveWatching = false
    }

    fun refreshHighlights() {
        val p = runtime.engine.pressedTouches()
        if (p != pressedIds) pressedIds = p
        val l = runtime.engine.latchedTouches()
        if (l != latchedIds) latchedIds = l
        refreshLive()
    }

    /** A touch began or ended: watch the live mappings for a while (gestures fire after lifts). */
    fun watchLive() {
        if (!visuals.liveMappings) return
        liveWatchUntil[0] = SystemClock.uptimeMillis() + ws.doubleTapMs + LIVE_LINGER_MS
        if (!liveWatching) liveWatching = true
    }

    fun releaseAll() {
        for (p in buttonFingers) runtime.engine.buttonCancel(p)
        buttonFingers.clear()
        swipeLines.clear()
        fingersOnButtons = 0
        for (id in stickPos.keys.toList()) runtime.engine.setTouchStick(id, 0f, 0f)
        stickPos.clear()
        heldSticks = emptySet()
        cornersHeld = emptySet()
        runtime.engine.touchpadCancel()
        runtime.engine.touchAreasCancel()
        refreshHighlights()
    }

    DisposableEffect(Unit) { onDispose { releaseAll() } }
    // Input reaches the PC only while the pad is in play mode ([GamepadRuntime.padOnlyInput]).
    DisposableEffect(editing) {
        runtime.setPadShown(!editing)
        onDispose { runtime.setPadShown(false) }
    }
    // Play-mode touch reaches the engine as it arrives, not once per frame. Asked on the decor view:
    // ViewRootImpl reads its request, and a child's pointer request alone would not carry the joystick bit.
    val view = LocalView.current
    DisposableEffect(view, editing) {
        val decor = view.rootView
        if (!editing) TouchStream.setPlayUnbuffered(decor, true)
        onDispose { TouchStream.setPlayUnbuffered(decor, false) }
    }

    // A mouse attached to the phone is the workspace's in play while it maps one (pointer capture:
    // no phone cursor, raw moves and buttons). Edit mode, other pages and other apps get it back.
    val mouseAttached by runtime.mouseAttached.collectAsStateWithLifecycle()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val captureMouse = !editing && mouseAttached && windowFocused && ws.mouse.isNotEmpty()
    DisposableEffect(view) {
        view.setOnCapturedPointerListener { _, e -> runtime.capturedPointer?.invoke(e) ?: false }
        onDispose {
            view.setOnCapturedPointerListener(null)
            view.releasePointerCapture()
            runtime.mouseCaptured = false
        }
    }
    LaunchedEffect(view, captureMouse) {
        if (captureMouse) {
            view.isFocusable = true
            view.requestFocus()
            view.requestPointerCapture()
        } else {
            view.releasePointerCapture()
        }
        runtime.mouseCaptured = captureMouse
    }

    fun startEditing() {
        releaseAll()
        selected = null
        selectMode = false
        picked = emptySet()
        dragging = false
        editIndex = wsIndex.coerceIn(0, profile.workspaces.lastIndex)
        lockableIds[0] = profile.corners.mapTo(HashSet()) { it.id } +
            profile.workspaces.flatMap { w -> w.touchControls.map { it.id } }
        draft = profile
    }

    // Back from the workspace settings page (opened with ⚙ in edit mode): edit mode again.
    LaunchedEffect(Unit) {
        if (startInEdit) {
            startEditing()
            onEditResumed()
        }
    }

    val pollHighlights = !editing && (fingersOnButtons > 0 || pressedIds.isNotEmpty() || liveWatching)
    LaunchedEffect(pollHighlights, visuals.liveMappings) {
        if (!pollHighlights) return@LaunchedEffect
        while (true) {
            delay(if (visuals.liveMappings) LIVE_POLL_MS else HIGHLIGHT_POLL_MS)
            refreshHighlights()
        }
    }

    // "2/3 · Name" for a moment after each workspace switch (not on start or a profile change).
    var pillShown by remember { mutableStateOf(false) }
    val lastShown = remember { arrayOfNulls<String>(1) }
    val lastIndex = remember { IntArray(1) { -1 } }
    LaunchedEffect(profile.id, wsIndex) {
        val switched = lastShown[0] == profile.id && lastIndex[0] != wsIndex
        lastShown[0] = profile.id
        lastIndex[0] = wsIndex
        if (!switched) {
            pillShown = false
            return@LaunchedEffect
        }
        pillShown = true
        delay(PILL_MS)
        pillShown = false
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF101318))
            .recordTouches(trails, visuals, showTouchVisuals)
            .onGloballyPositioned { padOrigin = it.positionInWindow() },
    ) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        val pxPerDp = density.density
        val portrait = h > w
        val unit = minOf(w, h) // control size base: the pad area's shorter side

        fun center(c: TouchControl) = Offset(c.placeX(portrait) * w, c.placeY(portrait) * h)
        fun half(c: TouchControl) = c.placeSize(portrait) * unit / 2f
        fun halfH(c: TouchControl) = half(c) * c.heightRatio
        fun hit(controls: List<TouchControl>, p: Offset): TouchControl? =
            controls.lastOrNull { it.contains(p.x, p.y, w, h, inPortrait = portrait) }

        /**
         * Sets the current orientation's centre. A control may be larger than the screen and
         * hang off its edges, but a fingertip of it ([KEEP_ON_SCREEN_DP], all of a smaller one)
         * stays on screen on each axis.
         */
        fun place(c: TouchControl, x: Float, y: Float): TouchControl {
            val hw = half(c);
            val hh = halfH(c)
            val keep = KEEP_ON_SCREEN_DP * pxPerDp
            val kx = min(2f * hw, keep);
            val ky = min(2f * hh, keep)
            return c.withPlacement(
                portrait,
                x.coerceIn((kx - hw) / w, 1f - (kx - hw) / w),
                y.coerceIn((ky - hh) / h, 1f - (ky - hh) / h),
                c.placeSize(portrait),
            )
        }

        /** Applies [change] to the workspace being edited; a button switched in or out of swipe mode takes its gestures along. */
        fun editWorkspace(change: (Workspace) -> Workspace) {
            val d = draft ?: return
            // A setting changed here stops following the workspace defaults (shown at once).
            draft = d.updateWorkspace(editIndex) { WorkspaceDefaults.markOwn(it, Bindings.matchSwipeModes(change(it))) }
        }

        fun isCorner(d: Profile, id: String) = d.corners.any { it.id == id }

        /** A corner or a control of the workspace being edited. */
        fun findControl(id: String?): TouchControl? {
            val d = draft ?: return null
            if (id == null) return null
            return d.corners.firstOrNull { it.id == id }
                ?: d.workspace(editIndex).touchControls.firstOrNull { it.id == id }
        }

        fun updateControl(id: String, change: (TouchControl) -> TouchControl) {
            val d = draft ?: return
            if (isCorner(d, id)) {
                draft = d.copy(corners = d.corners.map { if (it.id == id) change(it) else it })
            } else {
                editWorkspace { wk -> wk.copy(touchControls = wk.touchControls.map { if (it.id == id) change(it) else it }) }
            }
        }

        fun resizeOne(id: String, size: Float) = updateControl(id) {
            val sized = it.withPlacement(
                portrait, it.placeX(portrait), it.placeY(portrait),
                size.coerceIn(TouchControl.MIN_SIZE, TouchControl.MAX_SIZE),
            )
            place(sized, sized.placeX(portrait), sized.placeY(portrait))
        }

        /** Resizes a control; with [Profile.cornerSameSize], all corners follow. */
        fun resize(id: String, size: Float) {
            val d = draft ?: return
            if (isCorner(d, id) && d.cornerSameSize) d.corners.map { it.id }
                .forEach { resizeOne(it, size) } else resizeOne(id, size)
        }

        /**
         * Puts a control's edges on the nearest grid lines, so its size fills whole cells: a
         * rectangle's four edges, a round one's left, right and top (it stays round). A corner
         * keeps its size (corners may share one): only its left and top edges snap.
         */
        fun snapped(c: TouchControl): TouchControl {
            val d = draft
            if (d != null && isCorner(d, c.id)) {
                val hw = half(c)
                val hh = halfH(c)
                val o = center(c)
                val l = ((o.x - hw) / (GRID * w)).roundToInt() * GRID * w
                val t = ((o.y - hh) / (GRID * h)).roundToInt() * GRID * h
                return place(c, (l + hw) / w, (t + hh) / h)
            }
            val gx = GRID * w
            val gy = GRID * h
            val o = center(c)
            val hw = half(c)
            val hh = halfH(c)
            val rect = c.kind == TouchKind.BUTTON && c.shape == TouchShape.SQUARE
            val l = ((o.x - hw) / gx).roundToInt() * gx
            val r = max(((o.x + hw) / gx).roundToInt() * gx, l + gx)
            val t = ((o.y - hh) / gy).roundToInt() * gy
            val b = if (rect) max(((o.y + hh) / gy).roundToInt() * gy, t + gy) else t + (r - l) * c.heightRatio
            val size = ((r - l) / unit).coerceIn(TouchControl.MIN_SIZE, TouchControl.MAX_SIZE)
            val sized = c.withPlacement(portrait, c.placeX(portrait), c.placeY(portrait), size)
                .let { if (rect) it.copy(aspect = (b - t) / (r - l)) else it }
            return place(sized, (l + r) / 2f / w, (t + b) / 2f / h)
        }

        /** Places a control, with [snap] on with its edges on grid lines. */
        fun placeSnapped(c: TouchControl, x: Float, y: Float): TouchControl =
            place(c, x, y).let { if (snap) snapped(it) else it }

        fun editControls(): List<TouchControl> = draft?.workspace(editIndex)?.touchControls ?: emptyList()

        /** What a drag or pinch of [id] moves: with Select on, everything picked; else its group (or itself). */
        fun unitOf(id: String): Set<String> =
            if (selectMode && picked.isNotEmpty()) picked else ControlGroups.unitOf(editControls(), id)

        /** Whether the lock keeps any of [id]'s group from being moved or sized. */
        fun unitLocked(id: String?) = id != null && unitOf(id).any { lockedOut(it) }

        /**
         * The box around [members] (px of the pad area): a group's edges, which [snap] puts on grid lines.
         */
        fun boundsOf(members: List<TouchControl>): Rect = members.map {
            val o = center(it)
            Rect(o.x - half(it), o.y - halfH(it), o.x + half(it), o.y + halfH(it))
        }.reduce { a, b ->
            Rect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
        }

        /**
         * Moves the controls [ids] together so that [anchor] lands at [x], [y] (with [snap], the
         * group's box has its left and top edges on grid lines; nothing is resized); the others keep
         * their places around it, and all stop where one would leave the screen.
         */
        fun moveUnit(ids: Set<String>, anchor: String, x: Float, y: Float) {
            val members = editControls().filter { it.id in ids }
            val a = members.firstOrNull { it.id == anchor } ?: return
            val to = place(a, x, y)
            var dx = to.placeX(portrait) - a.placeX(portrait)
            var dy = to.placeY(portrait) - a.placeY(portrait)
            if (snap) {
                val box = boundsOf(members)
                dx = (((box.left + dx * w) / (GRID * w)).roundToInt() * GRID * w - box.left) / w
                dy = (((box.top + dy * h) / (GRID * h)).roundToInt() * GRID * h - box.top) / h
            }
            for (m in members) {
                val p = place(m, m.placeX(portrait) + dx, m.placeY(portrait) + dy)
                dx = towards(dx, p.placeX(portrait) - m.placeX(portrait))
                dy = towards(dy, p.placeY(portrait) - m.placeY(portrait))
            }
            if (dx == 0f && dy == 0f) return
            editWorkspace { wk ->
                wk.copy(touchControls = wk.touchControls.map {
                    if (it.id in ids) {
                        it.withPlacement(portrait, it.placeX(portrait) + dx, it.placeY(portrait) + dy, it.placeSize(portrait))
                    } else it
                })
            }
        }

        val touchModifier = if (editing) {
            Modifier.pointerInput("edit", w, h) {
                val slop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        var event = awaitPointerEvent()
                        val first = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                        if (first == null) {
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val d0 = draft
                        val corners = d0?.shownCorners ?: emptyList()
                        val controls = d0?.workspace(editIndex)?.touchControls ?: emptyList()
                        val downPos = first.position
                        val pointer = first.id
                        var mode: EditGesture
                        var target: String? = null
                        var grab = Offset.Zero
                        var startSize = 0f
                        // Pinch anchor: the control's width and centre (px), the fingers' midpoint and spread.
                        // Taken again whenever the pair of fingers changes, so nothing jumps.
                        var startDist = 0f
                        var startW = 0f
                        var startH = 0f
                        var startDx = 0f
                        var startDy = 0f
                        var startCenter = Offset.Zero
                        var startMid = Offset.Zero
                        var pinchLock = PinchLock.UNDECIDED
                        var pinchA: PointerId? = null
                        var pinchB: PointerId? = null
                        // A group's pinch: each member's centre (px) and size when the pinch was anchored.
                        var groupStart: Map<String, Pair<Offset, Float>> = emptyMap()
                        var groupBox = Rect.Zero
                        // Two-finger tap: where each finger landed, and the control under them.
                        val tapStart = HashMap<PointerId, Offset>()
                        var tapTarget: String? = null
                        val under = hitOrder(controls, portrait).filter {
                            it.contains(
                                downPos.x,
                                downPos.y,
                                w,
                                h,
                                inPortrait = portrait
                            )
                        }
                        // Overlapping controls: a long press and a drag with nothing selected act
                        // on the selected one when it is under the finger (else the front one); a single tap
                        // picks the one after it, so taps cycle through them.
                        // With Select on, corners can't be picked and a tap picks the front control.
                        val selecting = selectMode
                        val cornerHit = if (selecting) null else hit(corners, downPos)
                        val selectedUnder = under.indexOfFirst { it.id == selected }
                        val c = cornerHit ?: under.getOrNull(max(0, selectedUnder))
                        val tapPick = cornerHit ?: if (selecting) under.firstOrNull() else {
                            under.getOrNull(if (selectedUnder >= 0) (selectedUnder + 1) % under.size else 0)
                        }
                        val pressedAt = first.uptimeMillis
                        var lastAt = pressedAt
                        // Empty space: a tap opens the touchpad's mappings, a long press its settings;
                        // with something selected, a tap there only closes it.
                        val hadSelection = selected != null
                        // A drag moves the selected control from wherever it starts, never what is under the finger
                        // (with Select on: the picked ones, held by the one under the finger if it is picked).
                        val sel = if (selecting) {
                            findControl(c?.id?.takeIf { it in picked } ?: picked.firstOrNull())
                        } else findControl(selected)
                        val selGrab = if (sel != null) downPos - center(sel) else Offset.Zero
                        if (c != null) {
                            mode = EditGesture.PRESS
                            target = c.id
                            grab = downPos - center(c)
                        } else {
                            mode = EditGesture.EMPTY
                        }
                        event.changes.forEach { it.consume() }

                        while (true) {
                            // A still press that lasts opens the settings: of the control, or on empty space the touchpad's.
                            // (Not with Select on: a press only picks.)
                            val next = if (!selecting && (mode == EditGesture.PRESS || mode == EditGesture.EMPTY)) {
                                withTimeoutOrNull(max(1L, pressedAt + EDIT_LONG_PRESS_MS - lastAt)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (next == null) {
                                if (mode == EditGesture.EMPTY) {
                                    mode = EditGesture.NONE
                                    selected = TOUCHPAD_SELECTION
                                    menu = EditMenu.MAPPING
                                } else {
                                    // A long press opens the mapping editor (a corner or a gyro display: its menu).
                                    mode = EditGesture.HELD
                                    selected = target
                                    menu = if (d0 != null && (isCorner(d0, target!!) || findControl(target)?.kind == TouchKind.DISPLAY)) {
                                        EditMenu.CONTEXT
                                    } else EditMenu.MAPPING
                                }
                                continue
                            }
                            event = next
                            lastAt = event.changes.firstOrNull()?.uptimeMillis ?: lastAt
                            val down = event.changes.filter { it.pressed }
                            if (down.isEmpty()) break
                            val main = event.changes.firstOrNull { it.id == pointer }

                            // Two fingers: lifted still, they select the control under them; moved, they
                            // pinch-resize the selected control, wherever they are (nothing when none is selected).
                            // With Select on, a second finger stops the gesture: nothing is sized there.
                            if (down.size >= 2 && selecting) {
                                mode = EditGesture.NONE
                                dragging = false
                            } else if (down.size >= 2 && tapStart.isEmpty() && mode != EditGesture.PINCH) {
                                mode = EditGesture.TWO
                                dragging = false
                                val second = down.firstOrNull { it.id != pointer }?.position
                                tapTarget = target ?: second?.let { p ->
                                    (hit(corners, p) ?: hitOrder(controls, portrait).firstOrNull {
                                        it.contains(p.x, p.y, w, h, inPortrait = portrait)
                                    })?.id
                                }
                            }
                            if (mode == EditGesture.TWO) {
                                down.forEach { tapStart.getOrPut(it.id) { it.position } }
                                if (down.any { (it.position - tapStart.getValue(it.id)).getDistance() > slop }) {
                                    val c = findControl(selected)
                                    if (c != null && !unitLocked(c.id)) {
                                        mode = EditGesture.PINCH
                                        pinchLock = PinchLock.UNDECIDED
                                        target = c.id
                                        menu = EditMenu.NONE
                                        // Holds the undo step until the pinch ends.
                                        dragging = true
                                    } else {
                                        mode = EditGesture.NONE
                                    }
                                }
                            }
                            if (mode == EditGesture.PINCH && down.size >= 2 &&
                                (down.none { it.id == pinchA } || down.none { it.id == pinchB })
                            ) {
                                val c = findControl(target)
                                val pa = down.firstOrNull { it.id == pinchA } ?: down.first { it.id != pinchB }
                                val pb = down.firstOrNull { it.id == pinchB && it.id != pa.id } ?: down.first { it.id != pa.id }
                                val dist = (pa.position - pb.position).getDistance()
                                if (c != null && dist > 1f) {
                                    pinchA = pa.id
                                    pinchB = pb.id
                                    startDist = dist
                                    startSize = c.placeSize(portrait)
                                    startW = 2f * half(c)
                                    startH = 2f * halfH(c)
                                    startDx = abs(pa.position.x - pb.position.x)
                                    startDy = abs(pa.position.y - pb.position.y)
                                    startCenter = center(c)
                                    startMid = (pa.position + pb.position) / 2f
                                    val ids = unitOf(c.id)
                                    val members = editControls().filter { it.id in ids }
                                    groupStart = if (members.size < 2) emptyMap() else {
                                        members.associate { it.id to (center(it) to it.placeSize(portrait)) }
                                    }
                                    if (members.size >= 2) groupBox = boundsOf(members)
                                }
                            }

                            when (mode) {
                                EditGesture.PRESS, EditGesture.HELD, EditGesture.EMPTY ->
                                    if (main != null && (main.position - downPos).getDistance() > slop) {
                                        if (mode != EditGesture.HELD && sel != null) {
                                            target = sel.id
                                            grab = selGrab
                                        }
                                        // Locked (and there before this session): a drag moves nothing (and isn't a tap).
                                        if (unitLocked(target) || (mode == EditGesture.EMPTY && sel == null)) {
                                            mode = EditGesture.NONE
                                        } else {
                                            mode = EditGesture.MOVE
                                            if (!selecting) selected = target
                                            menu = EditMenu.NONE
                                            dragging = true
                                        }
                                    }

                                else -> Unit
                            }

                            when (mode) {
                                EditGesture.MOVE -> {
                                    if (main == null || !main.pressed) {
                                        mode = EditGesture.NONE
                                        dragging = false
                                    } else {
                                        val id = target
                                        if (id != null) {
                                            val p = main.position - grab
                                            // A group (or what Select picked) moves as one.
                                            val ids = unitOf(id)
                                            if (ids.size > 1) moveUnit(ids, id, p.x / w, p.y / h)
                                            else updateControl(id) { placeSnapped(it, p.x / w, p.y / h) }
                                        }
                                    }
                                }

                                EditGesture.PINCH -> {
                                    // One finger up: hold still until a second one lands again.
                                    val a = down.firstOrNull { it.id == pinchA }
                                    val b = down.firstOrNull { it.id == pinchB }
                                    val id = target
                                    val d = draft
                                    if (a != null && b != null && id != null && d != null && startDist > 1f) {
                                        val dist = (a.position - b.position).getDistance()
                                        if (isCorner(d, id)) {
                                            resize(id, startSize * dist / startDist)
                                        } else if (groupStart.size > 1) {
                                            // A group zooms as one: every member's size and its place around the
                                            // fingers' midpoint scale together, as far as the smallest and largest allow.
                                            // With snap on, the group's box is as wide as whole cells, its left and top on grid lines.
                                            var lo = 0f
                                            var hi = Float.MAX_VALUE
                                            for ((_, st) in groupStart) {
                                                lo = max(lo, TouchControl.MIN_SIZE / st.second)
                                                hi = min(hi, TouchControl.MAX_SIZE / st.second)
                                            }
                                            fun limit(v: Float) = if (lo <= hi) v.coerceIn(lo, hi) else 1f
                                            var s = limit(dist / startDist)
                                            val mid = (a.position + b.position) / 2f
                                            var shift = Offset.Zero
                                            if (snap && groupBox.width > 1f) {
                                                val gx = GRID * w
                                                val gy = GRID * h
                                                val cells = max(1, (groupBox.width * s / gx).roundToInt())
                                                s = limit(cells * gx / groupBox.width)
                                                val left = mid.x + (groupBox.left - startMid.x) * s
                                                val top = mid.y + (groupBox.top - startMid.y) * s
                                                shift = Offset((left / gx).roundToInt() * gx - left, (top / gy).roundToInt() * gy - top)
                                            }
                                            editWorkspace { wk ->
                                                wk.copy(touchControls = wk.touchControls.map { c ->
                                                    val st = groupStart[c.id] ?: return@map c
                                                    val at = mid + (st.first - startMid) * s + shift
                                                    val sized = c.withPlacement(portrait, c.placeX(portrait), c.placeY(portrait), st.second * s)
                                                    place(sized, at.x / w, at.y / h)
                                                })
                                            }
                                        } else {
                                            // Pinch to zoom: the size follows the fingers' spread, and the point of the
                                            // control under the fingers' midpoint stays under it (moving both fingers
                                            // moves the control). A rectangle's width follows the spread across, its
                                            // height the spread down.
                                            val lo = TouchControl.MIN_SIZE * unit
                                            val hi = TouchControl.MAX_SIZE * unit
                                            val floor = PINCH_AXIS_FLOOR_DP * pxPerDp
                                            val mid = (a.position + b.position) / 2f
                                            updateControl(id) { c ->
                                                val rect = c.kind == TouchKind.BUTTON && c.shape == TouchShape.SQUARE
                                                val nw: Float
                                                val nh: Float
                                                if (rect) {
                                                    val dx = abs(a.position.x - b.position.x)
                                                    val dy = abs(a.position.y - b.position.y)
                                                    val ddx = abs(dx - startDx)
                                                    val ddy = abs(dy - startDy)
                                                    if (pinchLock == PinchLock.UNDECIDED && max(ddx, ddy) > PINCH_LOCK_DECIDE_DP * pxPerDp) {
                                                        pinchLock = when {
                                                            ddx > ddy * PINCH_LOCK_RATIO -> PinchLock.X
                                                            ddy > ddx * PINCH_LOCK_RATIO -> PinchLock.Y
                                                            else -> PinchLock.FREE
                                                        }
                                                    }
                                                    // The held axis lets go past the release spread; it then grows from
                                                    // there, so its size doesn't jump.
                                                    val release = PINCH_LOCK_RELEASE_DP * pxPerDp
                                                    if (pinchLock == PinchLock.X && ddy > release) {
                                                        pinchLock = PinchLock.FREE
                                                        startDy = dy
                                                    } else if (pinchLock == PinchLock.Y && ddx > release) {
                                                        pinchLock = PinchLock.FREE
                                                        startDx = dx
                                                    }
                                                    // Undecided: only the axis that changed more follows for now.
                                                    val sizeX = when (pinchLock) {
                                                        PinchLock.X, PinchLock.FREE -> true
                                                        PinchLock.Y -> false
                                                        PinchLock.UNDECIDED -> ddx >= ddy
                                                    }
                                                    val sizeY = when (pinchLock) {
                                                        PinchLock.Y, PinchLock.FREE -> true
                                                        PinchLock.X -> false
                                                        PinchLock.UNDECIDED -> ddy > ddx
                                                    }
                                                    nw = if (sizeX) (startW * axisScale(startDx, dx, floor)).coerceIn(lo, hi) else startW
                                                    nh = if (sizeY) (startH * axisScale(startDy, dy, floor)).coerceIn(lo, hi) else startH
                                                } else {
                                                    nw = (startW * dist / startDist).coerceIn(lo, hi)
                                                    nh = nw
                                                }
                                                val cx = mid.x + (startCenter.x - startMid.x) * nw / startW
                                                val cy = mid.y + (startCenter.y - startMid.y) * nh / startH
                                                val sized = c.withPlacement(portrait, c.placeX(portrait), c.placeY(portrait), nw / unit)
                                                    .let { if (rect) it.copy(aspect = nh / nw) else it }
                                                placeSnapped(sized, cx / w, cy / h)
                                            }
                                        }
                                    }
                                }

                                else -> Unit
                            }
                            event.changes.forEach { it.consume() }
                        }

                        when {
                            // With Select on, a tap picks a control (its whole group) or drops it; on empty space it drops all.
                            selecting && mode == EditGesture.PRESS -> {
                                val id = tapPick?.id
                                if (id != null) {
                                    val u = ControlGroups.unitOf(controls, id)
                                    picked = if (id in picked) picked - u else picked + u
                                }
                            }

                            selecting -> if (mode == EditGesture.EMPTY) picked = emptySet()

                            mode == EditGesture.PRESS -> {
                                // A tap selects at once.
                                selected = tapPick?.id
                                menu = EditMenu.NONE
                            }

                            mode == EditGesture.TWO -> {
                                // A two-finger tap selects without opening anything (a long press is for settings).
                                selected = tapTarget
                                menu = EditMenu.NONE
                            }

                            mode == EditGesture.EMPTY -> {
                                selected = if (hadSelection) null else TOUCHPAD_SELECTION
                                menu = if (hadSelection) EditMenu.NONE else EditMenu.MAPPING
                            }

                            else -> Unit
                        }
                        dragging = false
                    }
                }
            }
        } else {
            Modifier.pointerInput("play", playKey, wsIndex, w, h, pxPerDp) {
                releaseAll()
                val workspace = profile.workspace(wsIndex)
                // Gyro displays are only drawn: they take no touches.
                val controls = workspace.touchControls.filterNot { it.kind == TouchKind.DISPLAY }
                val corners = profile.shownCorners
                runtime.engine.touchpadPxPerDp = pxPerDp
                val stickOwner = HashMap<PointerId, String>()
                val stickCenters = HashMap<String, Offset>()
                val padOwner = HashSet<PointerId>()
                val cornerOwner = HashMap<PointerId, String>()

                // What the engine needs told of each control: a stick's press (On touch), and the
                // Area and Outside of press mappings with that Where.
                val stickIds = controls.filter { it.kind == TouchKind.STICK }.mapTo(HashSet()) { it.id }
                val areaMapped = workspace.mappings.mapNotNullTo(HashSet()) { m ->
                    val s = m.source as? InputSource.Touch ?: return@mapNotNullTo null
                    when (m.trigger.where) {
                        Where.AREA -> InputSource.TouchArea(s.id, TouchAreaKind.ZONE)
                        Where.OUTSIDE -> InputSource.TouchArea(s.id, TouchAreaKind.OUTSIDE)
                        Where.BUTTON -> if (s.id in stickIds) InputSource.TouchArea(s.id, TouchAreaKind.HELD) else null
                    }
                }
                fun mapped(id: String, kind: TouchAreaKind) = InputSource.TouchArea(id, kind) in areaMapped
                val zoneControls = controls.filter { mapped(it.id, TouchAreaKind.ZONE) }
                // A button with only Area mappings holds nothing itself: a finger landing on it acts as
                // if it landed on what is under it (the touchpad or another control).
                val zoneOnly = zoneControls.filter { c ->
                    c.kind == TouchKind.BUTTON && workspace.mappings.all { m ->
                        touchIdOf(m.source) != c.id || (m.source == InputSource.Touch(c.id) && m.trigger.where == Where.AREA)
                    }
                }.mapTo(HashSet()) { it.id }
                // Front first, as touches see them (size, layer); see hitOrder.
                val landControls = hitOrder(controls.filter { it.id !in zoneOnly }, portrait)

                /** Controls behind the front one that a finger also works (TouchControl.sharesTouches). */
                class Extra(val control: TouchControl, val pointer: Long)

                val extras = HashMap<PointerId, List<Extra>>()
                val extraSticks = HashSet<String>()
                val fingers = HashMap<PointerId, FingerAreas>()
                val zoneCount = HashMap<String, Int>()
                val outsideCount = HashMap<String, Int>()

                /** Counts fingers in a zone / outside their control; the area is held while there is one. */
                fun count(counts: HashMap<String, Int>, id: String, kind: TouchAreaKind, by: Int) {
                    val n = (counts[id] ?: 0) + by
                    if (n <= 0) counts.remove(id) else counts[id] = n
                    if (n == 1 && by > 0) runtime.engine.setTouchArea(id, kind, true)
                    if (n <= 0) runtime.engine.setTouchArea(id, kind, false)
                    // A finger sliding in or out changes no ownership, so the zone's highlight follows here.
                    if (kind == TouchAreaKind.ZONE && (n <= 0 || (n == 1 && by > 0))) refreshHighlights()
                }

                /** A corner tapped on its own: previous / next workspace (nothing at an end). */
                fun stepFrom(cornerId: String) {
                    val i = runtime.workspaceIndex.value
                    val to = when (cornerId) {
                        NavCorners.PREV -> profile.previousWorkspace(i)
                        NavCorners.NEXT -> profile.nextWorkspace(i)
                        else -> null
                    }
                    if (to != null && to != i) runtime.switchWorkspace(to)
                }

                /** Still on corner [id]: its hit area plus the touch slop, so a slight drift still counts as a tap. */
                fun onCorner(id: String, p: Offset): Boolean {
                    val c = corners.firstOrNull { it.id == id } ?: return false
                    return c.contains(
                        p.x,
                        p.y,
                        w,
                        h,
                        slop = 1.15f + viewConfiguration.touchSlop / half(c),
                        inPortrait = portrait
                    )
                }

                /** Sends and shows the deflection of a finger at [p] on stick [c] (clamped to the unit circle). */
                fun deflect(c: TouchControl, p: Offset) {
                    var d = (p - stickCenters.getValue(c.id)) / half(c)
                    val len = hypot(d.x, d.y)
                    if (len > 1f) d /= len
                    runtime.engine.setTouchStick(c.id, d.x, d.y)
                    stickPos[c.id] = d
                }

                /** Finger [pointer] takes stick [c] at [p]: its On touch is held until [dropStick]. */
                fun grabStick(pointer: PointerId, c: TouchControl, p: Offset) {
                    stickOwner[pointer] = c.id
                    stickCenters[c.id] = center(c)
                    heldSticks = heldSticks + c.id
                    if (mapped(c.id, TouchAreaKind.HELD)) runtime.engine.setTouchArea(c.id, TouchAreaKind.HELD, true)
                    // Deflect at once: a still thumb sends no MOVE.
                    deflect(c, p)
                }

                fun dropStick(pointer: PointerId) {
                    val id = stickOwner.remove(pointer) ?: return
                    runtime.engine.setTouchStick(id, 0f, 0f)
                    stickPos.remove(id)
                    heldSticks = heldSticks - id
                    if (mapped(id, TouchAreaKind.HELD)) runtime.engine.setTouchArea(id, TouchAreaKind.HELD, false)
                }

                /** Finger [pointer] landed on swipe button [c] at [p]: its swipe line, when it has swipes or holds mapped. */
                fun swipeLine(pointer: Long, c: TouchControl, p: Offset) {
                    if (swipeLineOn && c.swipes) runtime.engine.swipeGuide(c.id)?.let { swipeLines.down(pointer, it, p, pxPerDp) }
                }

                /** Stops what finger [pointer] does (an interrupting zone). False if it does nothing to stop. */
                fun stopJob(pointer: PointerId, f: FingerAreas): Boolean {
                    val pid = pointer.value
                    f.stopped = when {
                        pointer in padOwner -> {
                            padOwner.remove(pointer)
                            runtime.engine.touchpadCancel(pid)
                            FingerJob.PAD
                        }

                        pid in buttonFingers -> {
                            buttonFingers.remove(pid)
                            swipeLines.up(pid)
                            runtime.engine.buttonCancel(pid)
                            FingerJob.BUTTON
                        }

                        pointer in stickOwner -> {
                            dropStick(pointer)
                            FingerJob.STICK
                        }

                        else -> return false
                    }
                    if (f.outside) {
                        f.outside = false
                        count(outsideCount, f.origin!!.id, TouchAreaKind.OUTSIDE, -1)
                    }
                    return true
                }

                /** Restarts the job a zone stopped, at [p], as a new touch-down there. */
                fun resumeJob(pointer: PointerId, f: FingerAreas, p: Offset, at: Long) {
                    val pid = pointer.value
                    val origin = f.origin
                    when (f.stopped) {
                        FingerJob.PAD -> {
                            padOwner.add(pointer)
                            runtime.engine.touchpadDown(pid, p.x, p.y, at)
                        }

                        FingerJob.BUTTON -> if (origin != null) {
                            buttonFingers.add(pid)
                            runtime.engine.buttonDown(pid, origin.id, p.x, p.y, at)
                            swipeLine(pid, origin, p)
                        }
                        // Another finger took the stick meanwhile: this one stays stopped.
                        FingerJob.STICK -> if (origin == null || origin.id in stickOwner.values || origin.id in extraSticks) return else grabStick(
                            pointer,
                            origin,
                            p
                        )

                        FingerJob.NONE -> return
                    }
                    f.stopped = FingerJob.NONE
                }

                /**
                 * Finger [pointer] is at [p]: updates its zones, the Outside of the control it took, and
                 * interrupting zones. At touch-down ([landing]) a zone stops only a cursor finger, so a
                 * button or stick under a zone still works.
                 */
                fun track(pointer: PointerId, f: FingerAreas, p: Offset, at: Long, landing: Boolean) {
                    var interrupt = false
                    var resume = true
                    for (z in zoneControls) {
                        if (z.contains(p.x, p.y, w, h, inPortrait = portrait)) {
                            if (f.zones.add(z.id)) count(zoneCount, z.id, TouchAreaKind.ZONE, 1)
                            if (z.zoneInterrupt && z.id != f.origin?.id) {
                                interrupt = true
                                resume = resume && z.zoneResume
                            }
                        } else if (f.zones.remove(z.id)) {
                            count(zoneCount, z.id, TouchAreaKind.ZONE, -1)
                        }
                    }
                    if (interrupt) {
                        if (f.stopped != FingerJob.NONE) f.resume = f.resume && resume
                        else if ((!landing || pointer in padOwner) && stopJob(pointer, f)) f.resume = resume
                    } else if (f.stopped != FingerJob.NONE && f.resume) {
                        resumeJob(pointer, f, p, at)
                    }
                    val o = f.origin ?: return
                    if (f.stopped != FingerJob.NONE || !mapped(o.id, TouchAreaKind.OUTSIDE)) return
                    val out = !o.contains(p.x, p.y, w, h, inPortrait = portrait)
                    if (out != f.outside) {
                        f.outside = out
                        count(outsideCount, o.id, TouchAreaKind.OUTSIDE, if (out) 1 else -1)
                    }
                }

                /** Finger [pointer] lifted: it leaves its zones and its control's outside. */
                fun untrack(pointer: PointerId) {
                    val f = fingers.remove(pointer) ?: return
                    for (z in f.zones) count(zoneCount, z, TouchAreaKind.ZONE, -1)
                    if (f.outside) count(outsideCount, f.origin!!.id, TouchAreaKind.OUTSIDE, -1)
                }

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // Event time for the touchpad's speed math (processing time jitters).
                        val at = TouchStream.eventNanos
                        // The system took the touch (e.g. a three-finger screenshot gesture): Compose
                        // reports the cancel as every pointer lifting. That is not a tap.
                        val systemCancel =
                            TouchStream.cancelled && event.changes.any { !it.pressed && it.previousPressed }
                        // Android judged the one pointer that lifted unintended (e.g. a palm): not a tap either.
                        val pointerCancel = !systemCancel && TouchStream.pointerCancelled
                        if (systemCancel) {
                            if (padOwner.isNotEmpty()) {
                                if (padOwner.size >= 2) systemTookTouch++
                                padOwner.clear()
                                runtime.engine.touchpadCancel()
                            }
                            for (p in buttonFingers) runtime.engine.buttonCancel(p)
                            buttonFingers.clear()
                            swipeLines.clear()
                            cornerOwner.clear()
                        }
                        for (ch in event.changes) {
                            val pid = ch.id.value
                            if (ch.pressed && !ch.previousPressed) {
                                tapRun[1] = if (at - tapRun[0] <= REVEAL_GAP_NS) tapRun[1] + 1 else 1
                                tapRun[0] = at
                                if (tapRun[1] >= REVEAL_TAPS) {
                                    tapRun[1] = 0
                                    revealTick++
                                }
                                val corner = hit(corners, ch.position)
                                val touched = if (corner == null) touchedControls(
                                    landControls,
                                    ch.position.x,
                                    ch.position.y,
                                    w,
                                    h,
                                    portrait
                                ) else emptyList()
                                val c = touched.firstOrNull()
                                var origin: TouchControl? = null
                                // Sharing controls behind it: each with an engine finger of its own.
                                if (touched.size > 1) {
                                    val list = ArrayList<Extra>()
                                    for ((k, x) in touched.drop(1).withIndex()) {
                                        val sp = pid + ((k + 1).toLong() shl 40)
                                        if (x.kind == TouchKind.BUTTON) {
                                            buttonFingers.add(sp)
                                            runtime.engine.buttonDown(sp, x.id, ch.position.x, ch.position.y, at)
                                            list.add(Extra(x, sp))
                                        } else if (x.kind == TouchKind.STICK && x.id !in stickOwner.values && x.id !in extraSticks) {
                                            extraSticks.add(x.id)
                                            stickCenters[x.id] = center(x)
                                            heldSticks = heldSticks + x.id
                                            if (mapped(x.id, TouchAreaKind.HELD)) runtime.engine.setTouchArea(
                                                x.id,
                                                TouchAreaKind.HELD,
                                                true
                                            )
                                            deflect(x, ch.position)
                                            list.add(Extra(x, sp))
                                        }
                                    }
                                    if (list.isNotEmpty()) extras[ch.id] = list
                                }
                                if (corner != null) {
                                    // A corner belongs to the first finger on it until that finger lifts.
                                    if (corner.id !in cornerOwner.values) cornerOwner[ch.id] = corner.id
                                } else if (c == null) {
                                    // A finger that lands on empty background is a touchpad finger until it lifts
                                    // (with the touchpad off it moves nothing, but can still be a gyro ratchet).
                                    padOwner.add(ch.id)
                                    runtime.engine.touchpadDown(pid, ch.position.x, ch.position.y, at)
                                } else if (c.kind == TouchKind.STICK) {
                                    if (c.id !in stickOwner.values && c.id !in extraSticks) {
                                        origin = c
                                        grabStick(ch.id, c, ch.position)
                                    }
                                } else if (c.kind == TouchKind.BUTTON) {
                                    // Every finger on a button goes to the engine (touchpad over buttons, sticky modifiers).
                                    origin = c
                                    buttonFingers.add(pid)
                                    runtime.engine.buttonDown(pid, c.id, ch.position.x, ch.position.y, at)
                                    swipeLine(pid, c, ch.position)
                                }
                                val f = FingerAreas(origin)
                                fingers[ch.id] = f
                                track(ch.id, f, ch.position, at, landing = true)
                                ch.consume()
                                continue
                            }
                            extras[ch.id]?.let { list ->
                                for (x in list) {
                                    if (x.control.kind == TouchKind.BUTTON) {
                                        if (x.pointer !in buttonFingers) continue
                                        if (!ch.pressed) {
                                            buttonFingers.remove(x.pointer)
                                            if (pointerCancel) runtime.engine.buttonCancel(x.pointer) else runtime.engine.buttonUp(
                                                x.pointer
                                            )
                                        } else if (ch.position != ch.previousPosition) {
                                            runtime.engine.buttonMove(x.pointer, ch.position.x, ch.position.y, at)
                                        }
                                    } else if (!ch.pressed) {
                                        val id = x.control.id
                                        extraSticks.remove(id)
                                        runtime.engine.setTouchStick(id, 0f, 0f)
                                        stickPos.remove(id)
                                        heldSticks = heldSticks - id
                                        if (mapped(id, TouchAreaKind.HELD)) runtime.engine.setTouchArea(
                                            id,
                                            TouchAreaKind.HELD,
                                            false
                                        )
                                    } else if (ch.position != ch.previousPosition) {
                                        deflect(x.control, ch.position)
                                    }
                                }
                                if (!ch.pressed) extras.remove(ch.id)
                            }
                            if (ch.id in padOwner) {
                                if (!ch.pressed) {
                                    padOwner.remove(ch.id)
                                    if (pointerCancel) runtime.engine.touchpadCancel(pid) else runtime.engine.touchpadUp(
                                        pid
                                    )
                                } else if (ch.position != ch.previousPosition) {
                                    runtime.engine.touchpadMove(pid, ch.position.x, ch.position.y, at)
                                }
                            } else if (pid in buttonFingers) {
                                if (!ch.pressed) {
                                    buttonFingers.remove(pid)
                                    swipeLines.up(pid)
                                    if (pointerCancel) runtime.engine.buttonCancel(pid) else runtime.engine.buttonUp(pid)
                                } else if (ch.position != ch.previousPosition) {
                                    runtime.engine.buttonMove(pid, ch.position.x, ch.position.y, at)
                                    swipeLines.move(pid, ch.position, pxPerDp)
                                }
                            } else if (ch.id in cornerOwner) {
                                if (!ch.pressed) {
                                    val id = cornerOwner.remove(ch.id)
                                    // A tap: pressed and released on the corner without the other corner (no chord).
                                    if (id != null && !pointerCancel && !chordFired[0] && cornerOwner.isEmpty() &&
                                        onCorner(id, ch.position)
                                    ) {
                                        stepFrom(id)
                                    }
                                }
                            } else {
                                val stickId = stickOwner[ch.id]
                                if (stickId != null) {
                                    if (!ch.pressed) {
                                        dropStick(ch.id)
                                    } else if (ch.position != ch.previousPosition) {
                                        val c = controls.firstOrNull { it.id == stickId }
                                        if (c != null) deflect(c, ch.position)
                                    }
                                }
                            }
                            if (!ch.pressed) {
                                untrack(ch.id)
                            } else if (ch.position != ch.previousPosition) {
                                fingers[ch.id]?.let { track(ch.id, it, ch.position, at, landing = false) }
                            }
                            ch.consume()
                        }
                        // Only touch-downs and lifts change ownership; holds, pulses and lift commits
                        // between events are kept right by the highlight poll.
                        if (event.changes.any { it.pressed != it.previousPressed }) {
                            val held = cornerOwner.values.toSet()
                            if (held != cornersHeld) {
                                if (held.isNotEmpty()) revealTick++
                                cornersHeld = held
                            }
                            if (NavCorners.PREV in held && NavCorners.NEXT in held) {
                                if (!chordFired[0]) {
                                    chordFired[0] = true
                                    setFullscreen(!isFullscreen)
                                }
                            } else if (event.changes.none { it.pressed }) {
                                chordFired[0] = false
                            }
                            if (fingersOnButtons != buttonFingers.size) fingersOnButtons = buttonFingers.size
                            watchLive()
                            refreshHighlights()
                        }
                    }
                }
            }
        }

        // Gyro displays: under everything, only drawn. The runtime gathers data for the ones shown.
        // Lit in edit mode: what Select picked (red), else the selected control (amber) and the rest
        // of its group (red, to show they go together).
        val lit = remember(ws, selected, selectMode, picked) {
            if (selectMode) picked else selected?.let { ControlGroups.unitOf(ws.touchControls, it) } ?: emptySet()
        }
        val litColor: (String) -> Color = { id -> if (!selectMode && id == selected) SELECTED_COLOR else PICKED_COLOR }
        val displays = ws.touchControls.filter { it.kind == TouchKind.DISPLAY }
        DisposableEffect(displays) {
            runtime.setGyroDisplays(displays)
            onDispose { runtime.setGyroDisplays(emptyList()) }
        }
        if (displays.isNotEmpty()) {
            val boxes = displays.map { c ->
                val o = center(c)
                DisplayBox(c, Rect(o.x - half(c), o.y - halfH(c), o.x + half(c), o.y + halfH(c)))
            }
            GyroDisplaysLayer(runtime, boxes, editing, lit, litColor, Modifier.fillMaxSize())
        }
        val noButtons = ws.touchControls.all { it.kind == TouchKind.DISPLAY }
        if (!editing && ws.touchpad.enabled && (noButtons || portrait)) {
            Text(
                "Touchpad",
                color = Color.White.copy(alpha = 0.18f),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(
                    if (ws.touchControls.isEmpty()) Alignment.Center else BiasAlignment(0f, -0.35f),
                ),
            )
        }
        if (systemTookTouch > 0) {
            LaunchedEffect(systemTookTouch) {
                delay(5_000)
                systemTookTouch = 0
            }
            Text(
                "The phone took that touch. Turn off its three-finger screenshot gestures to use touchpad gestures.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            )
        }
        Box(Modifier.fillMaxSize().then(touchModifier)) {
            if (editing && snap) {
                Canvas(Modifier.fillMaxSize()) {
                    val n = (1f / GRID).roundToInt()
                    for (i in 1 until n) {
                        val major = i % 5 == 0
                        val color = Color.White.copy(alpha = if (major) 0.12f else 0.05f)
                        val x = i * GRID * size.width
                        val y = i * GRID * size.height
                        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f)
                        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
                    }
                }
            }
            val labels = remember(ws, editing, controlMappings) {
                ws.touchControls.associate { it.id to controlLabel(ws, it, editing, controlMappings[it.id]) }
            }
            // Back first, so the front one (as touches see them) is drawn on top.
            for (c in hitOrder(ws.touchControls, portrait).asReversed()) {
                if (c.kind == TouchKind.DISPLAY) continue
                val r = half(c)
                val rh = halfH(c)
                val ctr = center(c)
                val isSelected = editing && c.id in lit
                val isPressed = !editing && (c.id in pressedIds || c.id in heldSticks)
                val isLatched = !editing && c.id in latchedIds
                val tint = when {
                    isSelected -> litColor(c.id)
                    isPressed -> Color(0xFF7CC4FF)
                    isLatched -> Color(0xFF8FE39A)
                    else -> Color(0x66FFFFFF)
                }
                val live = if (editing) null else liveLabels[c.id]
                PadControl(
                    control = c,
                    label = live ?: labels[c.id] ?: "",
                    live = live != null,
                    widthDp = with(density) { (r * 2).toDp() },
                    heightDp = with(density) { (rh * 2).toDp() },
                    tint = tint,
                    filled = isPressed || isSelected || isLatched,
                    knob = { stickPos[c.id] ?: Offset.Zero },
                    knobRadiusPx = r * 0.6f,
                    modifier = Modifier
                        .offset { IntOffset((ctr.x - r).roundToInt(), (ctr.y - rh).roundToInt()) }
                        // A control may be larger than the screen: measured unbounded from its top left,
                        // before the opacity layer, which would otherwise cut it to the screen's size.
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        // While editing, faint controls stay findable.
                        .alpha(ws.controlOpacity.let { if (editing) max(it, MIN_EDIT_OPACITY) else it }),
                )
            }
            // The corners: shared by all workspaces, drawn over the controls, always shown.
            for (c in shown.shownCorners) {
                val r = half(c)
                val rh = halfH(c)
                val ctr = center(c)
                val isSelected = editing && !selectMode && selected == c.id
                val isPressed = !editing && c.id in cornersHeld
                val atEnd = when (c.id) {
                    NavCorners.PREV -> shown.previousWorkspace(shownIndex) == null
                    NavCorners.NEXT -> shown.nextWorkspace(shownIndex) == null
                    else -> false
                }
                val tint = when {
                    isSelected -> SELECTED_COLOR
                    isPressed -> Color(0xFF7CC4FF)
                    else -> Color(0x66FFFFFF)
                }
                val base = shown.cornerOpacity.let { if (editing) max(it, MIN_EDIT_OPACITY) else it }
                PadControl(
                    control = c,
                    label = c.label,
                    widthDp = with(density) { (r * 2).toDp() },
                    heightDp = with(density) { (rh * 2).toDp() },
                    tint = tint,
                    filled = isPressed || isSelected,
                    knob = { Offset.Zero },
                    knobRadiusPx = 0f,
                    modifier = Modifier
                        .offset { IntOffset((ctr.x - r).roundToInt(), (ctr.y - rh).roundToInt()) }
                        .alpha(
                            if (editing) {
                                if (atEnd) base * END_CORNER_ALPHA else base
                            } else {
                                // Never fully faint in play; only auto-hide takes the arrows away.
                                val a = (if (atEnd) base * END_CORNER_ALPHA else base).coerceAtLeast(MIN_CORNER_ALPHA)
                                if (c.id == NavCorners.PREV || c.id == NavCorners.NEXT) a * arrowFade else a
                            },
                        ),
                )
            }
        }

        AnimatedVisibility(
            visible = pillShown && !editing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
        ) {
            Surface(shape = RoundedCornerShape(50), color = Color(0xE01E232B)) {
                Text(
                    "${shownIndex + 1}/${shown.workspaces.size} · ${ws.name}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        val layerText = if (layers.set == MappingSet.BASE && layers.overrides.isEmpty()) null else {
            fun name(id: Int) = if (id == MappingSet.BASE) "Base" else ws.sets.firstOrNull { it.id == id }?.label ?: "Layer $id"
            (listOf(name(layers.set)) + layers.overrides.map { "+ " + name(it) }).joinToString(" ")
        }
        if (!editing && layerText != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xE01E232B),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val top = layers.overrides.lastOrNull() ?: layers.set
                    if (top != MappingSet.BASE) Box(Modifier.size(8.dp).background(setColor(top), CircleShape))
                    Text(
                        layerText,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }

        if (!editing) {
            if (!fullscreen) {
                CapsuleRow(
                    runtime,
                    onEdit = { startEditing() },
                    onDevices = {
                        releaseAll()
                        onOpenMenu(PadMenuPage.Devices)
                    },
                    onSettings = {
                        releaseAll()
                        onOpenMenu(PadMenuPage.AppSettings)
                    },
                    onConnection = {
                        releaseAll()
                        onOpenMenu(PadMenuPage.Connection)
                    },
                    // Clear of the camera cutout (left or right in landscape, top in portrait).
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                        .padding(8.dp),
                )
            }
        } else {
            val compact = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            fun clampMenu(o: Offset): Offset {
                val mx = max(0f, (w - menuSize.width) / 2f)
                return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(0f, max(0f, h - menuSize.height)))
            }

            val menuAt = clampMenu(menuOffset)

            /** Saves the draft (a builtin becomes a custom copy) and shows the workspace being edited. */
            fun finishEditing() {
                val d = draft ?: return
                val idx = editIndex.coerceIn(0, d.workspaces.lastIndex)
                runtime.editActiveProfile { d }
                if (runtime.workspaceIndex.value != idx) runtime.switchWorkspace(idx)
                draft = null
                selected = null
                selectMode = false
                picked = emptySet()
                dragging = false
            }

            val minPanel = with(density) { 120.dp.toPx() }
            val maxPanel = with(density) { (maxWidth - 16.dp).toPx() }
            val panelWidth = panelWidthPx?.coerceIn(minPanel, maxPanel)

            /** The ⠿ dots at the start of each row of the edit menu: drag to move it. */
            @Composable
            fun DragDots() {
                Text(
                    "⠿",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .pointerInput(w, h) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                menuOffset = clampMenu(clampMenu(menuOffset) + drag)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            // The edit menu floats over the pad: drag it by its dots to reach what is under it, and
            // drag its right edge to show more or fewer of its items.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(menuAt.x.roundToInt(), menuAt.y.roundToInt()) }
                    .onSizeChanged { menuSize = it }
                    .padding(8.dp)
                    .onGloballyPositioned { panelBounds = it.boundsInWindow() }
                    .then(
                        if (panelWidth != null) Modifier.width(with(density) { panelWidth.toDp() })
                        else Modifier.widthIn(max = maxWidth - 16.dp),
                    ),
                shape = RoundedCornerShape(16.dp),
                color = PANEL_COLOR,
                shadowElevation = 8.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        // A set width fills the panel, so the resize handle stays on its right edge.
                        Modifier.weight(1f, fill = panelWidth != null).padding(top = 6.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        /** A scrolling strip of the menu: framed, with a shadow at an edge that has more past it. */
                        @Composable
                        fun RowScope.ScrollStrip(content: @Composable RowScope.() -> Unit) {
                            val scroll = rememberScrollState()
                            val shape = RoundedCornerShape(12.dp)
                            Box(
                                Modifier
                                    .weight(1f, fill = false)
                                    .padding(end = 4.dp)
                                    .clip(shape)
                                    .border(1.dp, Color.White.copy(alpha = 0.25f), shape),
                            ) {
                                Row(
                                    Modifier.horizontalScroll(scroll).padding(horizontal = 6.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    content = content,
                                )
                                val back = scroll.canScrollBackward
                                val forward = scroll.canScrollForward
                                if (back || forward) {
                                    Canvas(Modifier.matchParentSize()) {
                                        val edge = 20.dp.toPx()
                                        val shade = Color.Black.copy(alpha = 0.6f)
                                        if (back) drawRect(
                                            Brush.horizontalGradient(listOf(shade, Color.Transparent), endX = edge),
                                            size = Size(edge, size.height),
                                        )
                                        if (forward) drawRect(
                                            Brush.horizontalGradient(
                                                listOf(Color.Transparent, shade),
                                                startX = size.width - edge,
                                                endX = size.width,
                                            ),
                                            topLeft = Offset(size.width - edge, 0f),
                                            size = Size(edge, size.height),
                                        )
                                    }
                                }
                            }
                        }

                        /**
                         * Something to add from the menu: tap it to add it in the middle of the pad, or drag it
                         * down onto the pad to add it there ([onAdd] gets the pad position, null for the middle).
                         * A sideways drag scrolls the strip. [onLongPress]: what a long press does, if anything.
                         */
                        @Composable
                        fun AddChip(
                            label: String,
                            outlined: Boolean,
                            onLongPress: (() -> Unit)? = null,
                            onAdd: (Offset?) -> Unit,
                        ) {
                            val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
                            val haptic = LocalHapticFeedback.current
                            val held = dropping?.first == label
                            val add by rememberUpdatedState(onAdd)
                            val longPress by rememberUpdatedState(onLongPress)
                            val shape = RoundedCornerShape(50)
                            Text(
                                label,
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                modifier = Modifier
                                    .onGloballyPositioned { coords[0] = it }
                                    .clip(shape)
                                    .then(
                                        when {
                                            // Being dragged out: lit up in the selection amber.
                                            held -> Modifier.background(SELECTED_COLOR.copy(alpha = 0.5f), shape)
                                                .border(1.dp, SELECTED_COLOR, shape)
                                            outlined -> Modifier.border(1.dp, Color.White.copy(alpha = 0.6f), shape)
                                            else -> Modifier.background(Color.White.copy(alpha = 0.18f), shape)
                                        },
                                    )
                                    .pointerInput(label, w, h) {
                                        val slop = viewConfiguration.touchSlop
                                        awaitEachGesture {
                                            val down = awaitFirstDown()
                                            val startAt = down.uptimeMillis
                                            var lastAt = startAt
                                            var dragged = false
                                            var total = Offset.Zero
                                            var lastWindow = Offset.Zero
                                            var out = false
                                            while (true) {
                                                val event = if (dragged || longPress == null) awaitPointerEvent()
                                                else withTimeoutOrNull(max(1L, startAt + EDIT_LONG_PRESS_MS - lastAt)) {
                                                    awaitPointerEvent()
                                                }
                                                if (event == null) {
                                                    longPress?.invoke()
                                                    break
                                                }
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                lastAt = change.uptimeMillis
                                                if (!dragged && change.isConsumed) break
                                                if (!change.pressed) {
                                                    if (dragged) {
                                                        // Dropped on the pad, not back on the menu: add it there.
                                                        if (out) add(lastWindow - padOrigin)
                                                        dropping = null
                                                    } else if (total.getDistance() <= slop) {
                                                        change.consume()
                                                        add(null)
                                                    }
                                                    break
                                                }
                                                total += change.positionChange()
                                                if (!dragged && total.getDistance() > slop) {
                                                    // Mostly down (or up): drag it out; mostly sideways: let the strip scroll.
                                                    if (abs(total.y) > abs(total.x)) {
                                                        dragged = true
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    } else break
                                                }
                                                if (dragged) {
                                                    change.consume()
                                                    val c = coords[0]
                                                    if (c != null) lastWindow = c.positionInWindow() + change.position
                                                    val p = lastWindow - padOrigin
                                                    val nowOut = !panelBounds.contains(lastWindow) && p.x in 0f..w && p.y in 0f..h
                                                    // Out of the menu onto the pad: a firm tick (a light one going back).
                                                    if (nowOut != out) haptic.performHapticFeedback(
                                                        if (nowOut) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove,
                                                    )
                                                    out = nowOut
                                                    dropping = Triple(label, lastWindow, out)
                                                }
                                            }
                                            if (dragged) dropping = null
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }

                        /** A pad position for something added: where it was dropped, else the middle. */
                        fun addAt(at: Offset?, c: TouchControl) =
                            if (at == null) place(c, 0.5f, 0.5f) else placeSnapped(c, at.x / w, at.y / h)

                        // Fixed: dots (move), ⚙ (this workspace's settings), undo, redo, lock, Done, Cancel, Select, Group / Ungroup.
                        // Then a scrolling strip of the layout tools.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DragDots()
                            Text(
                                "⚙",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        // The settings page edits the saved workspace: keep the layout edits first.
                                        finishEditing()
                                        releaseAll()
                                        onWorkspaceSettings()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                            // Undo, redo, and the lock that keeps drags and pinches from changing the layout.
                            @Composable
                            fun MenuIcon(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) = Icon(
                                icon,
                                contentDescription = label,
                                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable(enabled = enabled, onClick = onClick)
                                    .padding(6.dp)
                                    .size(22.dp),
                            )
                            MenuIcon(
                                EditIcons.Undo,
                                "Undo",
                                undoSteps.isNotEmpty() || (undoBase[0] != null && draft !== undoBase[0]),
                            ) { undoEdit() }
                            MenuIcon(EditIcons.Redo, "Redo", redoSteps.isNotEmpty()) { redoEdit() }
                            MenuIcon(
                                if (layoutLocked) EditIcons.Lock else EditIcons.LockOpen,
                                if (layoutLocked) "Unlock layout" else "Lock layout",
                                true,
                            ) { runtime.setEditLayoutLocked(!layoutLocked) }
                            // Snap: lit while on, dim while off (always tappable).
                            Icon(
                                EditIcons.Grid,
                                contentDescription = if (snap) "Snap: on" else "Snap: off",
                                tint = if (snap) Color.White else Color.White.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { snap = !snap }
                                    .padding(6.dp)
                                    .size(22.dp),
                            )
                            Row(
                                Modifier.padding(start = 2.dp, end = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    contentPadding = compact,
                                    onClick = { finishEditing() }) { Text("Done") }
                                OutlinedButton(contentPadding = compact, onClick = {
                                    // Nothing changed: leave at once; else ask first.
                                    if (draft == profile) discardEdits() else confirmingCancel = true
                                }) { Text("Cancel") }
                                val pickColors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = PICKED_COLOR,
                                    contentColor = Color.Black,
                                )
                                // Select: taps pick several controls to move together, and Group joins them.
                                // Turning it off keeps a group that was picked selected (ready to pinch).
                                if (selectMode) {
                                    FilledTonalButton(contentPadding = compact, colors = pickColors, onClick = {
                                        val controls = editControls()
                                        val left = picked.filterTo(LinkedHashSet()) { id -> controls.any { it.id == id } }
                                        selected = if (ControlGroups.units(controls, left) == 1) left.first() else null
                                        picked = emptySet()
                                        selectMode = false
                                    }) { Text("Select") }
                                    val controls = editControls()
                                    if (ControlGroups.units(controls, picked) >= 2) {
                                        FilledTonalButton(
                                            contentPadding = compact,
                                            colors = pickColors,
                                            onClick = { editWorkspace { ControlGroups.group(it, picked) } }) { Text("Group") }
                                    }
                                    if (ControlGroups.anyGrouped(controls, picked)) {
                                        FilledTonalButton(
                                            contentPadding = compact,
                                            colors = pickColors,
                                            onClick = { editWorkspace { ControlGroups.ungroup(it, picked) } }) { Text("Ungroup") }
                                    }
                                } else {
                                    OutlinedButton(contentPadding = compact, onClick = {
                                        val s = findControl(selected)
                                        picked = if (s != null && draft?.let { isCorner(it, s.id) } == false) {
                                            ControlGroups.unitOf(editControls(), s.id)
                                        } else emptySet()
                                        selected = null
                                        menu = EditMenu.NONE
                                        selectMode = true
                                    }) { Text("Select") }
                                }
                                // A selected control in a group: Ungroup (amber, like its other buttons).
                                val grouped = if (selectMode) null else findControl(selected)?.takeIf { it.group != null }
                                if (grouped != null) {
                                    FilledTonalButton(
                                        contentPadding = compact,
                                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = SELECTED_COLOR, contentColor = Color.Black),
                                        onClick = { editWorkspace { ControlGroups.ungroup(it, listOf(grouped.id)) } }) { Text("Ungroup") }
                                }
                            }
                            ScrollStrip {
                                // The selected control's Edit (a long press opens it too).
                                val chosen = if (selectMode) null else findControl(selected)
                                if (chosen != null) {
                                    val selColors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = SELECTED_COLOR,
                                        contentColor = Color.Black,
                                    )
                                    // Edit: a button or stick's mapping editor (its ⚙ Settings has the rest); a corner or display: its settings.
                                    val d = draft
                                    val mapped = d != null && !isCorner(d, chosen.id) && chosen.kind != TouchKind.DISPLAY
                                    FilledTonalButton(
                                        contentPadding = compact,
                                        colors = selColors,
                                        onClick = {
                                            settingsFromMap = false
                                            menu = if (mapped) EditMenu.MAPPING else EditMenu.CONTEXT
                                        }) { Text("Edit") }
                                }
                                fun addButton(at: Offset?) {
                                    val d = draft ?: return
                                    val wk = d.workspace(editIndex)
                                    val id = freshId(wk, "btn", takenIds(d))
                                    val c = newControl(wk, id, TouchKind.BUTTON, NEW_BUTTON_SIZE, portrait)
                                    editWorkspace { it.copy(touchControls = it.touchControls + addAt(at, c)) }
                                    // Only added (and selected): Edit opens its mappings.
                                    selected = id
                                }
                                AddChip("+ Button", false) { addButton(it) }
                                AddChip("+ Stick", false) addStick@{ at ->
                                    val d = draft ?: return@addStick
                                    val wk = d.workspace(editIndex)
                                    val id = freshId(wk, "stick", takenIds(d))
                                    val c = newControl(wk, id, TouchKind.STICK, NEW_STICK_SIZE, portrait)
                                    editWorkspace { it.copy(touchControls = it.touchControls + addAt(at, c)) }
                                    selected = id
                                }
                                // Gyro displays: placed and sized like buttons, drawn under everything in play.
                                Box {
                                    var picking by remember { mutableStateOf(false) }
                                    OutlinedButton(contentPadding = compact, onClick = { picking = true }) { Text("+ Gyro display ▾") }
                                    if (picking) {
                                        PanelMenu(panelBounds, onDismiss = { picking = false }) {
                                            for (kind in GyroDisplayKind.entries) {
                                                PanelMenuItem(kind.label, chosen = false) addDisplay@{
                                                    picking = false
                                                    val d = draft ?: return@addDisplay
                                                    val wk = d.workspace(editIndex)
                                                    val id = freshId(wk, "disp", takenIds(d))
                                                    val (size, aspect) = GyroDisplay.newSize(kind)
                                                    val c = newControl(wk, id, TouchKind.DISPLAY, size, portrait).copy(
                                                        shape = TouchShape.SQUARE,
                                                        aspect = aspect,
                                                        layer = TouchLayer.BACK,
                                                        display = GyroDisplay(kind),
                                                    )
                                                    editWorkspace { it.copy(touchControls = it.touchControls + addAt(null, c)) }
                                                    selected = id
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // The button library: tap one to add it, drag it onto the pad to add it there,
                        // long-press to remove it from the library.
                        if (library.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Library",
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                                )
                                ScrollStrip {
                                    for (b in library) {
                                        AddChip(ButtonLibrary.name(b), true, onLongPress = { removingLib = b }) addLib@{ at ->
                                            val d = draft ?: return@addLib
                                            val wk = d.workspace(editIndex)
                                            val prefix = if (b.control.kind == TouchKind.STICK) "stick" else "btn"
                                            val id = freshId(wk, prefix, takenIds(d))
                                            editWorkspace { ButtonLibrary.addTo(it, b, id) { c -> addAt(at, c) } }
                                            selected = id
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // The right edge: drag to resize the whole menu.
                    Box(
                        Modifier
                            .width(20.dp)
                            .height(56.dp)
                            .pointerInput(w, h) {
                                detectHorizontalDragGestures { change, dx ->
                                    change.consume()
                                    val from = panelWidthPx?.coerceIn(minPanel, maxPanel)
                                        ?: (menuSize.width - 16.dp.toPx()).coerceIn(minPanel, maxPanel)
                                    val to = (from + dx).coerceIn(minPanel, maxPanel)
                                    // Keep the left edge where it is (the menu is placed by its centre).
                                    menuOffset = clampMenu(clampMenu(menuOffset) + Offset((to - from) / 2f, 0f))
                                    panelWidthPx = to
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .height(32.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.5f)),
                        )
                    }
                }
            }

            // What is being dragged out of the edit menu, under the finger until it is dropped.
            // Out on the pad (where a drop adds it) it is lit up and larger; over the menu, faint.
            dropping?.let { (label, at, out) ->
                val p = at - padOrigin
                val r = with(density) { (if (out) 32.dp else 28.dp).toPx() }
                Box(
                    Modifier
                        .offset { IntOffset((p.x - r).roundToInt(), (p.y - r).roundToInt()) }
                        .size(if (out) 64.dp else 56.dp)
                        .background(SELECTED_COLOR.copy(alpha = if (out) 0.95f else 0.4f), CircleShape)
                        .then(if (out) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label.removePrefix("+ "),
                        color = Color.Black,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }

            val d = draft
            val sel = findControl(selected)
            if (d != null && selected == TOUCHPAD_SELECTION && !dragging && menu != EditMenu.NONE) {
                val close = {
                    selected = null
                    menu = EditMenu.NONE
                }
                run {
                    // The touchpad has no settings page of its own: its ⚙ Settings are the workspace's touchpad settings.
                    ControlMappingScreen(
                        control = TOUCHPAD_STAND_IN,
                        mappings = ws.mappings,
                        runtime = runtime,
                        onMappingsChange = { m ->
                            editWorkspace { it.copy(mappings = m) }
                        },
                        onSettings = null,
                        onClose = close,
                        sets = ws.sets,
                        editSet = if (ws.sets.any { s -> s.id == mapSet }) mapSet else MappingSet.BASE,
                        onEditSet = { mapSet = it },
                        onSetsChange = { sets, removed -> editWorkspace { it.withSets(sets, removed) } },
                        zoneSettings = ws.touchpad,
                        onZoneSettings = { a, b ->
                            editWorkspace { it.copy(touchpad = it.touchpad.copy(holdZones = a, dirHoldZones = b)) }
                        },
                        // ⚙ edits the settings of the layer picked in the set bar.
                        settings = touchpadSettings(runtime, setLevel(ws, d.defaults, mapSet) { change -> editWorkspace(change) }),
                        // Base takes the Defaults' mappings of what it does not map; a layer shows Base's greyed.
                        inherited = if (mapSet == MappingSet.BASE || ws.sets.none { s -> s.id == mapSet }) {
                            Inheritance.defaults(d.defaults)
                        } else emptyList(),
                        parentName = "Defaults",
                    )
                }
            } else if (d != null && sel != null && !dragging && menu != EditMenu.NONE) {
                if (isCorner(d, sel.id)) {
                    // A corner (no mapping): a small menu right next to it.
                    NearControl(anchor = center(sel), anchorHalfW = half(sel), anchorHalfH = halfH(sel)) {
                        CornerPanel(
                            profile = d,
                            control = sel,
                            width = (maxWidth - 16.dp).coerceAtMost(340.dp),
                            inPortrait = portrait,
                            onProfileChange = { draft = it },
                            autoHide = runtime.cornerAutoHide.collectAsStateWithLifecycle().value,
                            onAutoHide = { runtime.setCornerAutoHide(it) },
                            onSize = { resize(sel.id, it) },
                            // The control stays selected (ready to pinch); a tap on empty space clears it.
                            onClose = { menu = EditMenu.NONE },
                            modifier = Modifier.heightIn(max = maxHeight - 16.dp),
                        )
                    }
                } else if (sel.kind == TouchKind.DISPLAY) {
                    // A gyro display: what it shows, how, and its colours.
                    GyroDisplaySettingsScreen(
                        control = sel,
                        // A trackball's RWS needs the workspace's game turn (Gyro → Game).
                        gameKnown = ws.gyro.gameCountsPer360 > 0f,
                        visuals = visuals,
                        onVisuals = { runtime.setTouchVisuals(it) },
                        onChange = { changed -> updateControl(sel.id) { changed } },
                        onDuplicate = {
                            val (nw, id) = duplicate(ws, sel, takenIds(draft ?: profile))
                            val copy = nw.touchControls.first { it.id == id }
                            editWorkspace {
                                nw.copy(touchControls = nw.touchControls.map {
                                    if (it.id == id) place(copy, copy.placeX(portrait), copy.placeY(portrait)) else it
                                })
                            }
                            selected = id
                        },
                        onDelete = {
                            editWorkspace { Bindings.removeTouchControl(it, sel.id) }
                            selected = null
                            menu = EditMenu.NONE
                        },
                        onClose = { menu = EditMenu.NONE },
                    )
                } else if (menu == EditMenu.MAPPING) {
                    // A tap: the control's mapping screen.
                    ControlMappingScreen(
                        control = sel,
                        mappings = ws.mappings,
                        runtime = runtime,
                        onMappingsChange = { m -> editWorkspace { it.copy(mappings = m) } },
                        onSettings = { settingsFromMap = true; menu = EditMenu.CONTEXT },
                        // The control stays selected (ready to pinch); a tap on empty space clears it.
                        onClose = { menu = EditMenu.NONE },
                        sets = ws.sets,
                        editSet = if (ws.sets.any { s -> s.id == mapSet }) mapSet else MappingSet.BASE,
                        onEditSet = { mapSet = it },
                        onSetsChange = { sets, removed -> editWorkspace { it.withSets(sets, removed) } },
                        zoneSettings = if (sel.swipes) sel.surfaceSettings(ws.touchpad) else null,
                        onZoneSettings = { a, b ->
                            updateControl(sel.id) { it.copy(surface = it.ownSurface.copy(holdZones = a, dirHoldZones = b)) }
                        },
                        settings = touchControlSettings(sel, ws, if (ws.sets.any { s -> s.id == mapSet }) mapSet else MappingSet.BASE) { change -> updateControl(sel.id, change) },
                        inherited = if (sel.kind == TouchKind.BUTTON) {
                            Inheritance.inherited(Inheritance.workspace(ws, d.defaults), emptyList(), sel.id, if (ws.sets.any { s -> s.id == mapSet }) mapSet else MappingSet.BASE)
                        } else emptyList(),
                        parentName = "Workspace",
                    )
                } else {
                    // ⚙ Settings in its mapping editor: all of the control's settings.
                    ControlSettingsScreen(
                        workspace = ws,
                        control = sel,
                        runtime = runtime,
                        inPortrait = portrait,
                        onControlChange = { changed ->
                            updateControl(sel.id) {
                                place(
                                    changed,
                                    changed.placeX(portrait),
                                    changed.placeY(portrait)
                                )
                            }
                        },
                        onSize = { resize(sel.id, it) },
                        behaviour = touchControlSettings(sel, ws, if (ws.sets.any { s -> s.id == mapSet }) mapSet else MappingSet.BASE) { change -> updateControl(sel.id, change) }.shared,
                        onDuplicate = {
                            val (nw, id) = duplicate(ws, sel, takenIds(draft ?: profile))
                            val copy = nw.touchControls.first { it.id == id }
                            editWorkspace {
                                nw.copy(touchControls = nw.touchControls.map {
                                    if (it.id == id) place(
                                        copy,
                                        copy.placeX(portrait),
                                        copy.placeY(portrait)
                                    ) else it
                                })
                            }
                            selected = id
                        },
                        onDelete = {
                            editWorkspace { Bindings.removeTouchControl(it, sel.id) }
                            selected = null
                            menu = EditMenu.NONE
                        },
                        // Back to the mapping screen it was opened from; else the control stays selected
                        // (ready to pinch) and a tap on empty space clears it.
                        onClose = {
                            menu = if (settingsFromMap) EditMenu.MAPPING else EditMenu.NONE
                            settingsFromMap = false
                        },
                    )
                }
            }
        }
        if (confirmingCancel) {
            AlertDialog(
                onDismissRequest = { confirmingCancel = false },
                title = { Text("Discard your changes?") },
                text = { Text("Everything changed since edit mode opened is undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingCancel = false
                        discardEdits()
                    }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmingCancel = false }) { Text("Keep editing") } },
            )
        }
        removingLib?.let { b ->
            AlertDialog(
                onDismissRequest = { removingLib = null },
                title = { Text("Remove \"${ButtonLibrary.name(b)}\" from the library?") },
                text = { Text("Buttons already added from it stay where they are.") },
                confirmButton = {
                    TextButton(onClick = {
                        removingLib = null
                        runtime.removeFromLibrary(b.id)
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { removingLib = null }) { Text("Cancel") } },
            )
        }
        // Touches and trails: over everything, never taking a touch.
        if (showTouchVisuals) TouchOverlay(trails, visuals, Modifier.fillMaxSize())
        if (!editing && visuals.swipeLine) SwipeLineOverlay(swipeLines, Modifier.fillMaxSize())
    }
}

/**
 * Label shown on a control: its own, else its first mapping's short name: in play mode from
 * [inUse] (its mappings in the set in use), while editing from Base.
 */
private fun controlLabel(workspace: Workspace, c: TouchControl, editing: Boolean, inUse: List<Mapping>?): String {
    if (c.label.isNotBlank()) return c.label
    val first = if (editing) {
        Bindings.touchInputs(c).firstNotNullOfOrNull { Bindings.targets(workspace.mappings, it).firstOrNull() }
    } else {
        val ms = inUse.orEmpty().filter { it.target != OutputTarget.NoOutput }
        Bindings.touchInputs(c).firstNotNullOfOrNull { input ->
            ms.firstOrNull { m -> input.sources.any { Bindings.sameSource(it, m.source) } }?.target
        }
    }
    return first?.let { Describe.shortTarget(it) } ?: if (editing) "?" else ""
}

private fun shapeOf(c: TouchControl): Shape =
    if (c.shape == TouchShape.SQUARE) RoundedCornerShape(percent = 12) else CircleShape

/**
 * Ids a new control must not take: any control's or touch mapping's in [set], so it never picks
 * up the mappings of a deleted button.
 */
private fun takenIds(set: Profile): Set<String> = WorkspaceMappings.takenTouchIds(set)

/** Id not used by any control of the workspace, by its leftover mappings, nor in [taken]. */
private fun freshId(workspace: Workspace, prefix: String, taken: Set<String>): String {
    val orphans = workspace.mappings.mapNotNull { touchIdOf(it.source) }.plus(taken).distinct()
        .map { TouchControl(it, TouchKind.BUTTON, "", 0f, 0f) }
    return Bindings.newTouchId(workspace.touchControls + orphans, prefix)
}

/** Candidate centres, best first, for a new control in the layout that is not being edited. */
private val NEW_SPOTS = listOf(
    0.5f to 0.5f, 0.5f to 0.3f, 0.5f to 0.75f, 0.3f to 0.3f, 0.7f to 0.3f,
    0.3f to 0.5f, 0.7f to 0.5f, 0.3f to 0.75f, 0.7f to 0.75f, 0.5f to 0.2f, 0.5f to 0.8f,
)

/**
 * A new control of [size]. The caller centres it in the orientation being edited; in the other
 * orientation (which the user does not see while adding) it goes to the first candidate spot no
 * existing control covers, so it never silently sits on top of, say, the Gyro button there.
 */
private fun newControl(
    workspace: Workspace,
    id: String,
    kind: TouchKind,
    size: Float,
    inPortrait: Boolean
): TouchControl {
    val other = !inPortrait
    // Nominal pad box of the other orientation, short side 1000.
    val bw = if (other) 1000f else 2000f
    val bh = if (other) 2000f else 1000f
    val half = size * 1000f / 2f
    val spot = NEW_SPOTS.firstOrNull { (x, y) ->
        workspace.touchControls.none {
            val r = it.placeSize(other) * 1000f / 2f
            hypot(it.placeX(other) * bw - x * bw, it.placeY(other) * bh - y * bh) < r + half
        }
    } ?: NEW_SPOTS[0]
    val c = TouchControl(id, kind, "", 0.5f, 0.5f, size, TouchShape.CIRCLE)
    return if (other) c.copy(portrait = Placement(spot.first, spot.second, size))
    else c.copy(x = spot.first, y = spot.second, portrait = Placement(0.5f, 0.5f, size))
}

/** Copies a control and its mappings next to the original; returns the workspace and the new id. */
private fun duplicate(workspace: Workspace, c: TouchControl, taken: Set<String>): Pair<Workspace, String> {
    val id = freshId(workspace, when (c.kind) {
        TouchKind.STICK -> "stick"
        TouchKind.DISPLAY -> "disp"
        else -> "btn"
    }, taken)
    val copies = WorkspaceMappings.rekeyAll(workspace.mappings, c.id, id)

    // Offset towards the centre in both orientations, so the copy never hides under the original
    // and stays on screen in the layout not being edited (the edited one is clamped by place()).
    fun toCentre(v: Float, d: Float) = if (v < 0.5f) v + d else v - d
    val p = c.placement(true)
    val lHalf = (c.size * c.heightRatio / 2f).coerceAtMost(0.5f) // landscape: size is a fraction of the height
    val pHalf = (p.size / 2f).coerceAtMost(0.5f) // portrait: size is a fraction of the width
    val copy = c.copy(
        id = id,
        group = null,
        x = toCentre(c.x, 0.05f),
        y = toCentre(c.y, 0.05f).coerceIn(lHalf, 1f - lHalf),
        portrait = Placement(toCentre(p.x, 0.05f).coerceIn(pHalf, 1f - pHalf), toCentre(p.y, 0.03f), p.size),
    )
    return workspace.copy(touchControls = workspace.touchControls + copy, mappings = workspace.mappings + copies) to id
}

/**
 * Places [content] beside a control centred at [anchor] (px of the pad area, [anchorHalfW] and
 * [anchorHalfH] its half width and height): right of it if it fits, else left, else below or above; always kept on screen.
 * Takes no touches itself, so the pad around the menu stays editable.
 */
@Composable
private fun NearControl(anchor: Offset, anchorHalfW: Float, anchorHalfH: Float, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val box = constraints.copy(minWidth = 0, minHeight = 0)
        val p = measurables.first().measure(box)
        val gap = 8.dp.roundToPx()
        val bw = constraints.maxWidth
        val bh = constraints.maxHeight
        val right = (anchor.x + anchorHalfW).roundToInt() + gap
        val left = (anchor.x - anchorHalfW).roundToInt() - gap - p.width
        val below = (anchor.y + anchorHalfH).roundToInt() + gap
        val above = (anchor.y - anchorHalfH).roundToInt() - gap - p.height
        val beside = right + p.width <= bw || left >= 0
        val x = when {
            right + p.width <= bw -> right
            left >= 0 -> left
            else -> anchor.x.roundToInt() - p.width / 2
        }.coerceIn(0, max(0, bw - p.width))
        val y = when {
            beside -> anchor.y.roundToInt() - p.height / 2
            below + p.height <= bh -> below
            else -> above
        }.coerceIn(0, max(0, bh - p.height))
        layout(bw, bh) { p.place(x, y) }
    }
}

@Composable
private fun PadControl(
    control: TouchControl,
    label: String,
    widthDp: Dp,
    heightDp: Dp,
    tint: Color,
    filled: Boolean,
    /** [label] is what the control sends right now (live mappings): shown highlighted. */
    live: Boolean = false,
    /** Read while placing the knob only, so stick motion does not recompose the pad. */
    knob: () -> Offset,
    knobRadiusPx: Float,
    modifier: Modifier = Modifier,
) {
    val shape = shapeOf(control)
    Box(
        modifier
            .size(widthDp, heightDp)
            .border(2.dp, tint, shape)
            .background(if (filled) tint.copy(alpha = 0.25f) else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (control.kind == TouchKind.STICK) {
            Box(
                Modifier
                    .offset {
                        val k = knob()
                        IntOffset((k.x * knobRadiusPx).roundToInt(), (k.y * knobRadiusPx).roundToInt())
                    }
                    .size(widthDp / 2.5f)
                    .background(tint.copy(alpha = 0.6f), CircleShape),
            )
        }
        Text(
            label,
            color = if (live) LIVE_LABEL_COLOR else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (live) FontWeight.Bold else null,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Settings of a workspace corner, shown next to it. Corner settings change [profile]. */
@Composable
private fun CornerPanel(
    profile: Profile,
    control: TouchControl,
    width: Dp,
    inPortrait: Boolean,
    onProfileChange: (Profile) -> Unit,
    autoHide: Boolean,
    onAutoHide: (Boolean) -> Unit,
    onSize: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(width),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CornerSettings(
                control = control,
                inPortrait = inPortrait,
                sameSize = profile.cornerSameSize,
                onSameSize = { on ->
                    onProfileChange(profile.copy(cornerSameSize = on))
                    if (on) onSize(control.placeSize(inPortrait))
                },
                opacity = profile.cornerOpacity,
                onOpacity = { onProfileChange(profile.copy(cornerOpacity = it)) },
                wrap = profile.wrapWorkspaces,
                onWrap = { onProfileChange(profile.copy(wrapWorkspaces = it)) },
                autoHide = autoHide,
                onAutoHide = onAutoHide,
                onSize = onSize,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun CornerSettings(
    control: TouchControl,
    inPortrait: Boolean,
    sameSize: Boolean,
    onSameSize: (Boolean) -> Unit,
    opacity: Float,
    onOpacity: (Float) -> Unit,
    wrap: Boolean,
    onWrap: (Boolean) -> Unit,
    autoHide: Boolean,
    onAutoHide: (Boolean) -> Unit,
    onSize: (Float) -> Unit,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Workspace corner " + control.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClose) { Text("✕") }
    }
    Text(
        "Tap ← for the previous workspace and → for the next. Press both at the same time for " +
                "fullscreen (the buttons at the top hide); press both again (or Back) to leave it. The corners are " +
                "the same in every workspace; drag each one where you like.",
        style = MaterialTheme.typography.bodyMedium,
    )
    SizeSlider("Size", control.placeSize(inPortrait), onSize)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Same size for all corners", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = sameSize, onCheckedChange = onSameSize)
    }
    // The corners' other settings, shared by both: how visible, wrap-around and auto-hide.
    var shown by remember(opacity) { mutableStateOf(opacity) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Opacity %.0f%%".format(shown * 100), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
        Slider(
            value = shown,
            onValueChange = { shown = it },
            valueRange = Profile.MIN_CONTROL_OPACITY..1f,
            onValueChangeFinished = { onOpacity(shown) },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Wrap around (→ on the last workspace goes to the first)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = wrap, onCheckedChange = onWrap)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hide after a few seconds", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = autoHide, onCheckedChange = onAutoHide)
    }
    Text(
        "Hidden corners still work; tap the screen 4 times quickly to show them again.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The edit panel's colour, shared by the menus that open from it. */
private val PANEL_COLOR = Color(0xE01E232B)

/** The gap between the edit panel and a menu it opens, and around and between the menu's items. */
private val PANEL_MENU_GAP = 6.dp

/**
 * A menu opened from the edit panel: a rounded card of the panel's colour that opens below [panel]
 * (its window bounds), or above it when there is no room below, never over it.
 */
@Composable
private fun PanelMenu(panel: Rect, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val gap = with(LocalDensity.current) { PANEL_MENU_GAP.roundToPx() }
    val position = remember(panel, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val below = panel.bottom.roundToInt() + gap
                val y = if (below + popupContentSize.height <= windowSize.height) below
                else (panel.top.roundToInt() - gap - popupContentSize.height).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(shape = RoundedCornerShape(24.dp), color = PANEL_COLOR, shadowElevation = 8.dp) {
            Column(
                Modifier.width(IntrinsicSize.Max).padding(PANEL_MENU_GAP),
                verticalArrangement = Arrangement.spacedBy(PANEL_MENU_GAP),
                content = content,
            )
        }
    }
}

/** A capsule in a [PanelMenu]; the [chosen] one is filled. */
@Composable
private fun PanelMenuItem(text: String, chosen: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (chosen) colors.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().widthIn(min = 96.dp),
    ) {
        Text(
            text,
            color = if (chosen) colors.onPrimaryContainer else Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
