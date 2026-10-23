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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.Placement
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.StickZones
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.contains
import com.oleroma.gyrophone.core.model.heightRatio
import com.oleroma.gyrophone.core.model.placeSize
import com.oleroma.gyrophone.core.model.placeX
import com.oleroma.gyrophone.core.model.placeY
import com.oleroma.gyrophone.core.model.placement
import com.oleroma.gyrophone.core.model.withPlacement
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
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

/** PRESS: on a control, not yet moved; HELD: the same after a long press (context menu open). */
private enum class EditGesture { PRESS, HELD, MOVE, PINCH, EMPTY, NONE }

/** What edit mode shows for the selected control: its mapping picker or its context menu. */
private enum class EditMenu { NONE, MAPPING, CONTEXT }

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
 * out of the way or resized; its ⚙ saves and opens the workspace's settings page. Tap a control to change its mapping,
 * long-press it (or tap a corner) for a menu next to it with all its settings, drag to move,
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
         * Sets the current orientation's centre, keeping the whole control on screen and
         * optionally snapping the centre to the grid.
         */
        fun place(c: TouchControl, x: Float, y: Float, snapToGrid: Boolean): TouchControl {
            val sx = if (snapToGrid) (x / GRID).roundToInt() * GRID else x
            val sy = if (snapToGrid) (y / GRID).roundToInt() * GRID else y
            val hx = (half(c) / w).coerceAtMost(0.5f)
            val hy = (halfH(c) / h).coerceAtMost(0.5f)
            return c.withPlacement(portrait, sx.coerceIn(hx, 1f - hx), sy.coerceIn(hy, 1f - hy), c.placeSize(portrait))
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
                        var pinchA: PointerId? = null
                        var pinchB: PointerId? = null
                        val c = hit(corners, downPos) ?: hit(controls, downPos)
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
                                        if (id != null) resize(id, startSize * dist / pinchStart)
                                    }
                                }
                                else -> Unit
                            }
                            event.changes.forEach { it.consume() }
                        }

                        when (mode) {
                            EditGesture.PRESS -> {
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
                val padEnabled = workspace.touchpad.enabled
                runtime.engine.touchpadPxPerDp = pxPerDp
                val stickOwner = HashMap<PointerId, String>()
                val stickCenters = HashMap<String, Offset>()
                val padOwner = HashSet<PointerId>()
                val cornerOwner = HashMap<PointerId, String>()

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
                                val c = if (corner == null) hit(controls, ch.position) else null
                                if (corner != null) {
                                    // A corner belongs to the first finger on it until that finger lifts.
                                    if (corner.id !in cornerOwner.values) cornerOwner[ch.id] = corner.id
                                } else if (c == null) {
                                    // A finger that lands on empty background is a touchpad finger until it lifts.
                                    if (padEnabled) {
                                        padOwner.add(ch.id)
                                        runtime.engine.touchpadDown(pid, ch.position.x, ch.position.y, at)
                                    }
                                } else if (c.kind == TouchKind.STICK) {
                                    if (c.id !in stickOwner.values) {
                                        stickOwner[ch.id] = c.id
                                        stickCenters[c.id] = center(c)
                                        heldSticks = heldSticks + c.id
                                        // Deflect at once: a still thumb sends no MOVE.
                                        deflect(c, ch.position)
                                    }
                                } else if (c.kind == TouchKind.BUTTON) {
                                    // Every finger on a button goes to the engine (touchpad over buttons, sticky modifiers).
                                    buttonFingers.add(pid)
                                    runtime.engine.buttonDown(pid, c.id, ch.position.x, ch.position.y, at)
                                }
                                ch.consume()
                                continue
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
                                        stickOwner.remove(ch.id)
                                        runtime.engine.setTouchStick(stickId, 0f, 0f)
                                        stickPos.remove(stickId)
                                        heldSticks = heldSticks - stickId
                                    } else if (ch.position != ch.previousPosition) {
                                        val c = controls.firstOrNull { it.id == stickId }
                                        if (c != null) deflect(c, ch.position)
                                    }
                                }
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
            for (c in ws.touchControls) {
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
            val tapPick = d != null && sel != null && !dragging && menu == EditMenu.MAPPING && sel.kind == TouchKind.BUTTON
            if (tapPick && sel != null) {
                // Tap on a button: straight to its mapping. The pick replaces the button's (first) action.
                val input = Bindings.touchInputs(sel).first()
                TargetPickerDialog(
                    analog = false,
                    runtime = runtime,
                    current = Bindings.targets(ws.mappings, input),
                    onPick = { t: OutputTarget ->
                        editWorkspace {
                            // The pick replaces the main action; a ratchet stays.
                            val current = Bindings.targets(it.mappings, input)
                            val main = current.filterNot(::isRatchet)
                            val next = (listOf(t) + main.drop(1) + current.filter(::isRatchet)).distinct()
                            it.copy(mappings = Bindings.setTargets(it.mappings, input, next))
                        }
                    },
                    onDismiss = { menu = EditMenu.NONE },
                )
            } else if (d != null && sel != null && !dragging && menu != EditMenu.NONE) {
                // Long press (or a tap on a stick or a corner): a menu right next to the control.
                NearControl(anchor = center(sel), anchorHalfW = half(sel), anchorHalfH = halfH(sel)) {
                    ControlSettingsPanel(
                        profile = d,
                        workspace = ws,
                        control = sel,
                        runtime = runtime,
                        width = (maxWidth - 16.dp).coerceAtMost(340.dp),
                        inPortrait = portrait,
                        mappingOnly = menu == EditMenu.MAPPING,
                        onMore = { menu = EditMenu.CONTEXT },
                        onProfileChange = { draft = it },
                        onMappingsChange = { m -> editWorkspace { it.copy(mappings = m) } },
                        onControlChange = { changed ->
                            updateControl(sel.id) { place(changed, changed.placeX(portrait), changed.placeY(portrait), false) }
                        },
                        onSize = { resize(sel.id, it) },
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
                        modifier = Modifier.heightIn(max = maxHeight - 16.dp),
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

/**
 * Settings and mappings of the selected control (or corner), shown next to it. Corner settings
 * change [profile]; a control's mappings live in [workspace].
 */
@Composable
private fun ControlSettingsPanel(
    profile: Profile,
    workspace: Workspace,
    control: TouchControl,
    runtime: GamepadRuntime,
    width: Dp,
    inPortrait: Boolean,
    /** Only the mapping (a tap on a stick); [onMore] opens the full menu. */
    mappingOnly: Boolean = false,
    onMore: () -> Unit = {},
    onProfileChange: (Profile) -> Unit,
    onMappingsChange: (List<Mapping>) -> Unit,
    onControlChange: (TouchControl) -> Unit,
    onSize: (Float) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(control)
    val inputs = Bindings.touchInputs(control)
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
            if (control.kind == TouchKind.FULLSCREEN) {
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
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (control.kind == TouchKind.STICK) "Stick" else "Button") + " · " + control.id,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClose) { Text("✕") }
                }
                if (!mappingOnly) {
                    OutlinedTextField(
                        value = control.label,
                        onValueChange = { onControlChange(current.copy(label = it)) },
                        label = { Text("Label") },
                        placeholder = { Text("Shows the mapping") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Shape", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(52.dp))
                        SelectChip("Circle", control.shape == TouchShape.CIRCLE) { onControlChange(current.copy(shape = TouchShape.CIRCLE)) }
                        SelectChip("Square", control.shape == TouchShape.SQUARE) { onControlChange(current.copy(shape = TouchShape.SQUARE)) }
                    }
                    val size = control.placeSize(inPortrait)
                    if (control.kind == TouchKind.BUTTON && control.shape == TouchShape.SQUARE) {
                        // Width and height apart: each slider keeps the other side as it is.
                        val height = size * control.aspect
                        fun sized(w: Float, h: Float) = current.withPlacement(
                            inPortrait, current.placeX(inPortrait), current.placeY(inPortrait), w,
                        ).copy(aspect = h / w)
                        SizeSlider("Width", size) { onControlChange(sized(it, height)) }
                        SizeSlider("Height", height) { onControlChange(sized(size, it)) }
                    } else {
                        SizeSlider("Size", size, onSize)
                    }
                    if (control.kind == TouchKind.STICK) {
                        Column {
                            Text("Deadzone ${(control.deadzone * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = control.deadzone,
                                onValueChange = { onControlChange(current.copy(deadzone = it)) },
                                valueRange = 0f..TouchControl.MAX_DEADZONE,
                            )
                            Text(
                                "How far from the centre the stick stays at rest. Past it, the stick still reaches full.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (control.kind == TouchKind.BUTTON) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Click-through", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "Presses at once, and the same finger moves the cursor until it lifts.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = control.clickThrough,
                                onCheckedChange = { onControlChange(current.copy(clickThrough = it)) },
                            )
                        }
                        val input = inputs.first()
                        val targets = Bindings.targets(workspace.mappings, input)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Ratchet", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "While held it also flips gyro, besides its action: pauses it (Ratchet disables) " +
                                        "or turns it on (Ratchet enables). With click-through, only this switch " +
                                        "makes its finger a ratchet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = targets.any(::isRatchet),
                                onCheckedChange = { on ->
                                    val main = targets.filterNot(::isRatchet)
                                    onMappingsChange(
                                        Bindings.setTargets(workspace.mappings, input, if (on) main + OutputTarget.GyroPause else main),
                                    )
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                }
                Text("Mapping", style = MaterialTheme.typography.titleSmall)
                if (control.kind == TouchKind.STICK) {
                    Text("Directions", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (z in StickZones.entries) {
                            SelectChip(z.label, control.zones == z) { onControlChange(current.copy(zones = z)) }
                        }
                    }
                    Text(
                        when (control.zones) {
                            StickZones.OVERLAP -> "Left, Right, Up and Down each on their own: a diagonal presses two."
                            StickZones.FOUR -> "One of Left, Right, Up and Down at a time, never two."
                            StickZones.EIGHT -> "8 zones. A diagonal does its own mapping, or presses both neighbours when it has none."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (preset in Bindings.StickPreset.entries) {
                            SelectChip(preset.label, presetMatches(workspace.mappings, inputs, preset)) {
                                onMappingsChange(Bindings.applyStickPreset(workspace.mappings, inputs, preset))
                            }
                        }
                    }
                }
                TouchMappingList(mappings = workspace.mappings, inputs = inputs, onChange = onMappingsChange, runtime = runtime)
                HorizontalDivider()
                if (mappingOnly) {
                    TextButton(onClick = onMore) { Text("All settings…") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDuplicate) { Text("Duplicate") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
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

/**
 * A control size slider, in % of the pad's shorter side. The track is squared, so the usual
 * sizes get most of it while the far end still fills the screen.
 */
@Composable
private fun SizeSlider(title: String, value: Float, onChange: (Float) -> Unit) {
    val span = TouchControl.MAX_SIZE - TouchControl.MIN_SIZE
    Column {
        Text("$title ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = sqrt(((value - TouchControl.MIN_SIZE) / span).coerceIn(0f, 1f)),
            onValueChange = { t -> onChange(TouchControl.MIN_SIZE + span * t * t) },
        )
    }
}

/** A ratchet action (the old gyro hold works as one). */
private fun isRatchet(t: OutputTarget): Boolean = t == OutputTarget.GyroPause || t == OutputTarget.GyroHold

/** True when the stick's mappings equal what [preset] would write. */
private fun presetMatches(
    mappings: List<Mapping>,
    inputs: List<BindableInput>,
    preset: Bindings.StickPreset,
): Boolean {
    val applied = Bindings.applyStickPreset(mappings, inputs, preset)
    return inputs.all { Bindings.targets(applied, it).toSet() == Bindings.targets(mappings, it).toSet() }
}

@Composable
private fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else Color.Transparent, shape)
            .border(1.dp, if (selected) colors.secondaryContainer else colors.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            maxLines = 1,
        )
    }
}
