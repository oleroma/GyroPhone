package com.oleroma.gyrophone.ui

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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.Placement
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchAreaKind
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.contains
import com.oleroma.gyrophone.core.model.hitOrder
import com.oleroma.gyrophone.core.model.touchedControls
import com.oleroma.gyrophone.core.model.heightRatio
import com.oleroma.gyrophone.core.model.placeSize
import com.oleroma.gyrophone.core.model.placeX
import com.oleroma.gyrophone.core.model.placeY
import com.oleroma.gyrophone.core.model.placement
import com.oleroma.gyrophone.core.model.withPlacement
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
/** Edit mode: how much of a control (all of a smaller one) must stay on screen, in dp. */
private const val KEEP_ON_SCREEN_DP = 24f
/** Edit mode: a second tap on overlapping controls within this picks the next one behind. */
private const val EDIT_CYCLE_MS = 1_000L

/** PRESS: on a control, not yet moved; HELD: the same after a long press (settings screen open). */
private enum class EditGesture { PRESS, HELD, MOVE, PINCH, EMPTY, NONE }

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
 * out of the way or resized; its ⚙ saves and opens the workspace's settings page. Tap a control for its full-screen
 * mapping screen, long-press it for its full-screen settings (tap a corner for a menu next to it), drag to move,
 * pinch or use the size slider to resize. Edits change the current orientation's placement. Done
 * saves (a builtin becomes a custom copy) and switches to the workspace being edited.
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
    /** The floating edit menu: drag offset from its top-centre home, size, folded. */
    var menuOffset by remember { mutableStateOf(Offset.Zero) }
    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    /** Width the user dragged the edit menu to (px); null = fit its rows. */
    var panelWidthPx by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var snap by remember { mutableStateOf(false) }
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
    /** Edit mode: the last tap on overlapping controls (x, y, which one) and its time, to cycle through them. */
    val editCycle = remember { FloatArray(3) }
    val editCycleAt = remember { LongArray(1) { Long.MIN_VALUE / 4 } }
    /**
     * Restarts the play touch handler on layout changes but not on gyro changes, so the gyro
     * corner switching the mode leaves held sticks and buttons alone.
     */
    val playKey = remember(profile) { profile.copy(workspaces = profile.workspaces.map { it.copy(gyro = GyroSettings()) }) }
    val shown = draft ?: profile
    val shownIndex = (if (editing) editIndex else wsIndex).coerceIn(0, shown.workspaces.lastIndex)
    val ws = shown.workspace(shownIndex)

    fun refreshHighlights() {
        val p = runtime.engine.pressedTouches()
        if (p != pressedIds) pressedIds = p
        val l = runtime.engine.latchedTouches()
        if (l != latchedIds) latchedIds = l
    }

    fun releaseAll() {
        for (p in buttonFingers) runtime.engine.buttonCancel(p)
        buttonFingers.clear()
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

    fun startEditing() {
        releaseAll()
        selected = null
        dragging = false
        editIndex = wsIndex.coerceIn(0, profile.workspaces.lastIndex)
        draft = profile
    }

    // Back from the workspace settings page (opened with ⚙ in edit mode): edit mode again.
    LaunchedEffect(Unit) {
        if (startInEdit) {
            startEditing()
            onEditResumed()
        }
    }

    val pollHighlights = !editing && (fingersOnButtons > 0 || pressedIds.isNotEmpty())
    LaunchedEffect(pollHighlights) {
        if (!pollHighlights) return@LaunchedEffect
        while (true) {
            delay(HIGHLIGHT_POLL_MS)
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

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101318))) {
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
         * A control larger than the screen on one axis only: when it would be on both, the axis
         * it overflows less is cut to the screen, so two of its edges always show.
         */
        fun fitOneAxis(c: TouchControl): TouchControl {
            val cw = 2f * half(c); val ch = 2f * halfH(c)
            if (cw <= w || ch <= h) return c
            val x = c.placeX(portrait); val y = c.placeY(portrait)
            return when {
                c.shape == TouchShape.CIRCLE || c.kind != TouchKind.BUTTON -> c.withPlacement(portrait, x, y, max(w, h) / unit)
                cw / w >= ch / h -> c.copy(aspect = h / cw)
                else -> c.withPlacement(portrait, x, y, w / unit).copy(aspect = ch / w)
            }
        }

        /**
         * Sets the current orientation's centre, optionally snapped to the grid. A control may
         * hang off the screen's edge, but at least [KEEP_ON_SCREEN_DP] of it (all of a smaller
         * one) stays on screen. One larger than the screen on an axis stays whole on the other,
         * so its outline shows.
         */
        fun place(control: TouchControl, x: Float, y: Float, snapToGrid: Boolean): TouchControl {
            val c = fitOneAxis(control)
            val sx = if (snapToGrid) (x / GRID).roundToInt() * GRID else x
            val sy = if (snapToGrid) (y / GRID).roundToInt() * GRID else y
            val keep = KEEP_ON_SCREEN_DP * pxPerDp
            val hw = half(c); val hh = halfH(c)
            var kx = min(2f * hw, keep); var ky = min(2f * hh, keep)
            if (2f * hw > w) ky = 2f * hh
            if (2f * hh > h) kx = 2f * hw
            return c.withPlacement(
                portrait,
                sx.coerceIn((kx - hw) / w, 1f - (kx - hw) / w),
                sy.coerceIn((ky - hh) / h, 1f - (ky - hh) / h),
                c.placeSize(portrait),
            )
        }

        /** Applies [change] to the workspace being edited. */
        fun editWorkspace(change: (Workspace) -> Workspace) {
            val d = draft ?: return
            draft = d.updateWorkspace(editIndex, change)
        }

        fun isCorner(d: Profile, id: String) = d.corners.any { it.id == id }

        /** A corner or a control of the workspace being edited. */
        fun findControl(id: String?): TouchControl? {
            val d = draft ?: return null
            if (id == null) return null
            return d.corners.firstOrNull { it.id == id } ?: d.workspace(editIndex).touchControls.firstOrNull { it.id == id }
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
            place(sized, sized.placeX(portrait), sized.placeY(portrait), false)
        }

        /** Resizes a control; with [Profile.cornerSameSize], all corners follow. */
        fun resize(id: String, size: Float) {
            val d = draft ?: return
            if (isCorner(d, id) && d.cornerSameSize) d.corners.map { it.id }.forEach { resizeOne(it, size) } else resizeOne(id, size)
        }

        val touchModifier = if (editing) {
            Modifier.pointerInput("edit", w, h) {
                val slop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis
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
                        var pinchStart = 0f
                        // Pinch start: the control's width, height and centre (px), the fingers' midpoint and spread on each axis.
                        var startW = 0f; var startH = 0f
                        var startCenter = Offset.Zero
                        var startMid = Offset.Zero
                        var startDx = 0f; var startDy = 0f
                        var pinchA: PointerId? = null
                        var pinchB: PointerId? = null
                        // Overlapping controls: the front one; a tap on the same spot soon after picks the next one behind.
                        val under = hitOrder(controls, portrait).filter { it.contains(downPos.x, downPos.y, w, h, inPortrait = portrait) }
                        val cycleAgain = under.size > 1 && first.uptimeMillis - editCycleAt[0] <= EDIT_CYCLE_MS &&
                            (downPos - Offset(editCycle[0], editCycle[1])).getDistance() <= slop * 2
                        val underIndex = if (cycleAgain) (editCycle[2].toInt() + 1) % under.size else 0
                        val c = hit(corners, downPos) ?: under.getOrNull(underIndex)
                        val pressedAt = first.uptimeMillis
                        var lastAt = pressedAt
                        if (c != null) {
                            mode = EditGesture.PRESS
                            target = c.id
                            grab = downPos - center(c)
                        } else {
                            mode = EditGesture.EMPTY
                        }
                        event.changes.forEach { it.consume() }

                        while (true) {
                            // A still press on a control that lasts opens its context menu.
                            val next = if (mode == EditGesture.PRESS) {
                                withTimeoutOrNull(max(1L, pressedAt + longPressMs - lastAt)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (next == null) {
                                mode = EditGesture.HELD
                                selected = target
                                menu = EditMenu.CONTEXT
                                continue
                            }
                            event = next
                            lastAt = event.changes.firstOrNull()?.uptimeMillis ?: lastAt
                            val down = event.changes.filter { it.pressed }
                            if (down.isEmpty()) break
                            val main = event.changes.firstOrNull { it.id == pointer }

                            if (down.size >= 2 && mode != EditGesture.PINCH && mode != EditGesture.NONE) {
                                val c = findControl(target ?: selected)
                                val dist = (down[0].position - down[1].position).getDistance()
                                if (c != null && dist > 1f) {
                                    mode = EditGesture.PINCH
                                    pinchA = down[0].id
                                    pinchB = down[1].id
                                    target = c.id
                                    selected = c.id
                                    menu = EditMenu.NONE
                                    startSize = c.placeSize(portrait)
                                    pinchStart = dist
                                    startW = 2f * half(c); startH = 2f * halfH(c)
                                    startCenter = center(c)
                                    startMid = (down[0].position + down[1].position) / 2f
                                    startDx = abs(down[0].position.x - down[1].position.x)
                                    startDy = abs(down[0].position.y - down[1].position.y)
                                    dragging = false
                                } else {
                                    mode = EditGesture.NONE
                                }
                            }

                            when (mode) {
                                EditGesture.PRESS, EditGesture.HELD -> if (main != null && (main.position - downPos).getDistance() > slop) {
                                    mode = EditGesture.MOVE
                                    selected = target
                                    menu = EditMenu.NONE
                                    dragging = true
                                }
                                EditGesture.EMPTY -> if (main != null && (main.position - downPos).getDistance() > slop) {
                                    mode = EditGesture.NONE
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
                                            updateControl(id) { place(it, p.x / w, p.y / h, snap) }
                                        }
                                    }
                                }
                                EditGesture.PINCH -> {
                                    val a = down.firstOrNull { it.id == pinchA }
                                    val b = down.firstOrNull { it.id == pinchB }
                                    if (a == null || b == null) {
                                        mode = EditGesture.NONE
                                    } else {
                                        val id = target
                                        val dist = (a.position - b.position).getDistance()
                                        val d = draft
                                        if (id != null && d != null && isCorner(d, id)) {
                                            resize(id, startSize * dist / pinchStart)
                                        } else if (id != null) {
                                            // Linear: each edge follows its finger, and the point under the
                                            // fingers' midpoint stays under it. A rectangle sizes on each axis apart.
                                            val lo = TouchControl.MIN_SIZE * unit
                                            val hi = TouchControl.MAX_SIZE * unit
                                            updateControl(id) { c ->
                                                val rect = c.kind == TouchKind.BUTTON && c.shape == TouchShape.SQUARE
                                                val nw: Float
                                                val nh: Float
                                                if (rect) {
                                                    nw = (startW + abs(a.position.x - b.position.x) - startDx).coerceIn(lo, hi)
                                                    nh = (startH + abs(a.position.y - b.position.y) - startDy).coerceIn(lo, hi)
                                                } else {
                                                    nw = (startW + dist - pinchStart).coerceIn(lo, hi)
                                                    nh = nw
                                                }
                                                val mid = (a.position + b.position) / 2f
                                                val cx = mid.x + (startCenter.x - startMid.x) * nw / startW
                                                val cy = mid.y + (startCenter.y - startMid.y) * nh / startH
                                                val sized = c.withPlacement(portrait, c.placeX(portrait), c.placeY(portrait), nw / unit)
                                                    .let { if (rect) it.copy(aspect = nh / nw) else it }
                                                place(sized, cx / w, cy / h, false)
                                            }
                                        }
                                    }
                                }
                                else -> Unit
                            }
                            event.changes.forEach { it.consume() }
                        }

                        when (mode) {
                            EditGesture.PRESS -> {
                                editCycle[0] = downPos.x; editCycle[1] = downPos.y; editCycle[2] = underIndex.toFloat()
                                editCycleAt[0] = lastAt
                                // A tap: a control's mapping; a corner (no mapping) its menu.
                                selected = target
                                menu = if (target != null && d0 != null && isCorner(d0, target)) EditMenu.CONTEXT else EditMenu.MAPPING
                            }
                            EditGesture.EMPTY -> {
                                selected = null
                                menu = EditMenu.NONE
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
                val controls = workspace.touchControls
                val corners = profile.shownCorners
                runtime.engine.touchpadPxPerDp = pxPerDp
                val stickOwner = HashMap<PointerId, String>()
                val stickCenters = HashMap<String, Offset>()
                val padOwner = HashSet<PointerId>()
                val cornerOwner = HashMap<PointerId, String>()

                // On touch, Outside and Zone mappings (TouchArea sources).
                val areaMapped = workspace.mappings.mapNotNullTo(HashSet()) { it.source as? InputSource.TouchArea }
                fun mapped(id: String, kind: TouchAreaKind) = InputSource.TouchArea(id, kind) in areaMapped
                val zoneControls = controls.filter { mapped(it.id, TouchAreaKind.ZONE) }
                // A button with only a Zone mapping holds nothing itself: a finger landing on it acts as
                // if it landed on what is under it (the touchpad or another control).
                val zoneOnly = zoneControls.filter { c ->
                    c.kind == TouchKind.BUTTON && workspace.mappings.all { m ->
                        MappingProfiles.touchId(m.source) != c.id || m.source == InputSource.TouchArea(c.id, TouchAreaKind.ZONE)
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
                    return c.contains(p.x, p.y, w, h, slop = 1.15f + viewConfiguration.touchSlop / half(c), inPortrait = portrait)
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
                        }
                        // Another finger took the stick meanwhile: this one stays stopped.
                        FingerJob.STICK -> if (origin == null || origin.id in stickOwner.values || origin.id in extraSticks) return else grabStick(pointer, origin, p)
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
                        val systemCancel = TouchStream.cancelled && event.changes.any { !it.pressed && it.previousPressed }
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
                                val touched = if (corner == null) touchedControls(landControls, ch.position.x, ch.position.y, w, h, portrait) else emptyList()
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
                                            if (mapped(x.id, TouchAreaKind.HELD)) runtime.engine.setTouchArea(x.id, TouchAreaKind.HELD, true)
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
                                            if (pointerCancel) runtime.engine.buttonCancel(x.pointer) else runtime.engine.buttonUp(x.pointer)
                                        } else if (ch.position != ch.previousPosition) {
                                            runtime.engine.buttonMove(x.pointer, ch.position.x, ch.position.y, at)
                                        }
                                    } else if (!ch.pressed) {
                                        val id = x.control.id
                                        extraSticks.remove(id)
                                        runtime.engine.setTouchStick(id, 0f, 0f)
                                        stickPos.remove(id)
                                        heldSticks = heldSticks - id
                                        if (mapped(id, TouchAreaKind.HELD)) runtime.engine.setTouchArea(id, TouchAreaKind.HELD, false)
                                    } else if (ch.position != ch.previousPosition) {
                                        deflect(x.control, ch.position)
                                    }
                                }
                                if (!ch.pressed) extras.remove(ch.id)
                            }
                            if (ch.id in padOwner) {
                                if (!ch.pressed) {
                                    padOwner.remove(ch.id)
                                    if (pointerCancel) runtime.engine.touchpadCancel(pid) else runtime.engine.touchpadUp(pid)
                                } else if (ch.position != ch.previousPosition) {
                                    runtime.engine.touchpadMove(pid, ch.position.x, ch.position.y, at)
                                }
                            } else if (pid in buttonFingers) {
                                if (!ch.pressed) {
                                    buttonFingers.remove(pid)
                                    if (pointerCancel) runtime.engine.buttonCancel(pid) else runtime.engine.buttonUp(pid)
                                } else if (ch.position != ch.previousPosition) {
                                    runtime.engine.buttonMove(pid, ch.position.x, ch.position.y, at)
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
                            refreshHighlights()
                        }
                    }
                }
            }
        }

        if (!editing && ws.touchpad.enabled && (ws.touchControls.isEmpty() || portrait)) {
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
            val labels = remember(ws, editing) { ws.touchControls.associate { it.id to controlLabel(ws, it, editing) } }
            // Back first, so the front one (as touches see them) is drawn on top.
            for (c in hitOrder(ws.touchControls, portrait).asReversed()) {
                val r = half(c)
                val rh = halfH(c)
                val ctr = center(c)
                val isSelected = editing && selected == c.id
                val isPressed = !editing && (c.id in pressedIds || c.id in heldSticks)
                val isLatched = !editing && c.id in latchedIds
                val tint = when {
                    isSelected -> Color(0xFFFFC857)
                    isPressed -> Color(0xFF7CC4FF)
                    isLatched -> Color(0xFF8FE39A)
                    else -> Color(0x66FFFFFF)
                }
                PadControl(
                    control = c,
                    label = labels[c.id] ?: "",
                    widthDp = with(density) { (r * 2).toDp() },
                    heightDp = with(density) { (rh * 2).toDp() },
                    tint = tint,
                    filled = isPressed || isSelected || isLatched,
                    knob = { stickPos[c.id] ?: Offset.Zero },
                    knobRadiusPx = r * 0.6f,
                    modifier = Modifier
                        .offset { IntOffset((ctr.x - r).roundToInt(), (ctr.y - rh).roundToInt()) }
                        // While editing, faint controls stay findable.
                        .alpha(ws.controlOpacity.let { if (editing) max(it, MIN_EDIT_OPACITY) else it }),
                )
            }
            // The corners: shared by all workspaces, drawn over the controls, always shown.
            for (c in shown.shownCorners) {
                val r = half(c)
                val rh = halfH(c)
                val ctr = center(c)
                val isSelected = editing && selected == c.id
                val isPressed = !editing && c.id in cornersHeld
                val atEnd = when (c.id) {
                    NavCorners.PREV -> shown.previousWorkspace(shownIndex) == null
                    NavCorners.NEXT -> shown.nextWorkspace(shownIndex) == null
                    else -> false
                }
                val tint = when {
                    isSelected -> Color(0xFFFFC857)
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

        if (!editing) {
            if (!fullscreen) {
                CapsuleRow(
                    runtime,
                    onEdit = { startEditing() },
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
                    .then(
                        if (panelWidth != null) Modifier.width(with(density) { panelWidth.toDp() })
                        else Modifier.widthIn(max = maxWidth - 16.dp),
                    ),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xE01E232B),
                shadowElevation = 8.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f, fill = false).padding(top = 6.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // Dots (move), ⚙ (this workspace's settings), Done, then the layout tools.
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
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(contentPadding = compact, onClick = { finishEditing() }) { Text("Done") }
                                OutlinedButton(contentPadding = compact, onClick = cancel@{
                                    val d = draft ?: return@cancel
                                    // Stay on the workspace being looked at if the saved profile has it.
                                    val i = profile.indexOfWorkspace(d.workspace(editIndex).id)
                                    draft = null
                                    selected = null
                                    dragging = false
                                    if (i >= 0 && i != runtime.workspaceIndex.value) runtime.switchWorkspace(i)
                                }) { Text("Cancel") }
                                FilledTonalButton(contentPadding = compact, onClick = addButton@{
                                    val d = draft ?: return@addButton
                                    val wk = d.workspace(editIndex)
                                    val id = freshId(wk, "btn", takenIds(d, runtime))
                                    val c = newControl(wk, id, TouchKind.BUTTON, NEW_BUTTON_SIZE, portrait)
                                    editWorkspace { it.copy(touchControls = it.touchControls + place(c, 0.5f, 0.5f, false)) }
                                    selected = id
                                    menu = EditMenu.MAPPING
                                }) { Text("+ Button") }
                                FilledTonalButton(contentPadding = compact, onClick = addStick@{
                                    val d = draft ?: return@addStick
                                    val wk = d.workspace(editIndex)
                                    val id = freshId(wk, "stick", takenIds(d, runtime))
                                    val c = newControl(wk, id, TouchKind.STICK, NEW_STICK_SIZE, portrait)
                                    editWorkspace { it.copy(touchControls = it.touchControls + place(c, 0.5f, 0.5f, false)) }
                                    selected = id
                                    menu = EditMenu.MAPPING
                                }) { Text("+ Stick") }
                                if (snap) {
                                    FilledTonalButton(contentPadding = compact, onClick = { snap = false }) { Text("Snap: on") }
                                } else {
                                    OutlinedButton(contentPadding = compact, onClick = { snap = true }) { Text("Snap: off") }
                                }
                                Text(
                                    ws.name + " · " + if (portrait) "portrait layout" else "landscape layout",
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
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

            val d = draft
            val sel = findControl(selected)
            if (d != null && sel != null && !dragging && menu != EditMenu.NONE) {
                if (isCorner(d, sel.id)) {
                    // A corner (no mapping): a small menu right next to it.
                    NearControl(anchor = center(sel), anchorHalfW = half(sel), anchorHalfH = halfH(sel)) {
                        CornerPanel(
                            profile = d,
                            control = sel,
                            width = (maxWidth - 16.dp).coerceAtMost(340.dp),
                            inPortrait = portrait,
                            onProfileChange = { draft = it },
                            onSize = { resize(sel.id, it) },
                            onClose = {
                                selected = null
                                menu = EditMenu.NONE
                            },
                            modifier = Modifier.heightIn(max = maxHeight - 16.dp),
                        )
                    }
                } else if (menu == EditMenu.MAPPING) {
                    // A tap: the control's mapping screen.
                    ControlMappingScreen(
                        control = sel,
                        mappings = ws.mappings,
                        runtime = runtime,
                        onMappingsChange = { m -> editWorkspace { it.copy(mappings = m) } },
                        onSettings = { menu = EditMenu.CONTEXT },
                        onClose = {
                            selected = null
                            menu = EditMenu.NONE
                        },
                    )
                } else {
                    // A long press: all of the control's settings.
                    ControlSettingsScreen(
                        workspace = ws,
                        control = sel,
                        runtime = runtime,
                        inPortrait = portrait,
                        onControlChange = { changed ->
                            updateControl(sel.id) { place(changed, changed.placeX(portrait), changed.placeY(portrait), false) }
                        },
                        onSize = { resize(sel.id, it) },
                        onMappingsChange = { m -> editWorkspace { it.copy(mappings = m) } },
                        onEditMappings = { menu = EditMenu.MAPPING },
                        onDuplicate = {
                            val (nw, id) = duplicate(ws, sel, takenIds(draft ?: profile, runtime))
                            val copy = nw.touchControls.first { it.id == id }
                            editWorkspace {
                                nw.copy(touchControls = nw.touchControls.map {
                                    if (it.id == id) place(copy, copy.placeX(portrait), copy.placeY(portrait), false) else it
                                })
                            }
                            selected = id
                        },
                        onDelete = {
                            editWorkspace { Bindings.removeTouchControl(it, sel.id) }
                            selected = null
                            menu = EditMenu.NONE
                        },
                        onClose = {
                            selected = null
                            menu = EditMenu.NONE
                        },
                    )
                }
            }
        }
    }
}

/** Label shown on a control: its own, else its first mapping's short name. */
private fun controlLabel(workspace: Workspace, c: TouchControl, editing: Boolean): String {
    if (c.label.isNotBlank()) return c.label
    val first = Bindings.touchInputs(c).firstNotNullOfOrNull { Bindings.targets(workspace.mappings, it).firstOrNull() }
    return first?.let { Describe.shortTarget(it) } ?: if (editing) "?" else ""
}

private fun shapeOf(c: TouchControl): Shape =
    if (c.shape == TouchShape.SQUARE) RoundedCornerShape(percent = 12) else CircleShape

/**
 * Ids a new control must not take: any control's in [set] or any mapping profile's, so it never
 * picks up the mappings of a button in another workspace or of a deleted one.
 */
private fun takenIds(set: Profile, runtime: GamepadRuntime): Set<String> =
    MappingProfiles.takenTouchIds(set, runtime.mappingProfiles.value)

/** Id not used by any control of the workspace, by its leftover mappings, nor in [taken]. */
private fun freshId(workspace: Workspace, prefix: String, taken: Set<String>): String {
    val orphans = workspace.mappings.mapNotNull { MappingProfiles.touchId(it.source) }.plus(taken).distinct().map { TouchControl(it, TouchKind.BUTTON, "", 0f, 0f) }
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
private fun newControl(workspace: Workspace, id: String, kind: TouchKind, size: Float, inPortrait: Boolean): TouchControl {
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
    val id = freshId(workspace, if (c.kind == TouchKind.STICK) "stick" else "btn", taken)
    val copies = workspace.mappings.mapNotNull { m ->
        when (val s = m.source) {
            is InputSource.Touch -> if (s.id == c.id) m.copy(source = InputSource.Touch(id)) else null
            is InputSource.TouchStick -> if (s.id == c.id) m.copy(source = s.copy(id = id)) else null
            is InputSource.TouchStickDiagonal -> if (s.id == c.id) m.copy(source = s.copy(id = id)) else null
            is InputSource.TouchSwipe -> if (s.id == c.id) m.copy(source = s.copy(id = id)) else null
            is InputSource.TouchRelease -> if (s.id == c.id) m.copy(source = s.copy(id = id)) else null
            is InputSource.TouchDoubleTap -> if (s.id == c.id) m.copy(source = s.copy(id = id)) else null
            is InputSource.TouchArea -> if (s.id == c.id) m.copy(source = s.copy(id = id)) else null
            is InputSource.Surface -> if (s.id == c.id) m.copy(source = s.copy(id = id)) else null
            else -> null
        }
    }
    // Offset towards the centre in both orientations, so the copy never hides under the original
    // and stays on screen in the layout not being edited (the edited one is clamped by place()).
    fun toCentre(v: Float, d: Float) = if (v < 0.5f) v + d else v - d
    val p = c.placement(true)
    val lHalf = (c.size * c.heightRatio / 2f).coerceAtMost(0.5f) // landscape: size is a fraction of the height
    val pHalf = (p.size / 2f).coerceAtMost(0.5f) // portrait: size is a fraction of the width
    val copy = c.copy(
        id = id,
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
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
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
    Text(
        "Corner opacity and wrap-around are in Settings (on the pad).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
