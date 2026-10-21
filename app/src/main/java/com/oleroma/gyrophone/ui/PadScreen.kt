package com.oleroma.gyrophone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.Placement
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.contains
import com.oleroma.gyrophone.core.model.placeSize
import com.oleroma.gyrophone.core.model.placeX
import com.oleroma.gyrophone.core.model.placeY
import com.oleroma.gyrophone.core.model.placement
import com.oleroma.gyrophone.core.model.withPlacement
import com.oleroma.gyrophone.runtime.GamepadRuntime
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val GRID = 0.02f
private const val NEW_BUTTON_SIZE = 0.14f
private const val NEW_STICK_SIZE = 0.3f
private const val MIN_EDIT_OPACITY = 0.5f
/** Opacity factor of a corner arrow that leads nowhere (first / last workspace, no wrap-around). */
private const val END_CORNER_ALPHA = 0.35f
private const val PILL_MS = 1_000L
/** Highlight refresh while a finger is on a button (holds and lift commits happen between events). */
private const val HIGHLIGHT_POLL_MS = 50L

/** PRESS: on a control, not yet moved; HELD: the same after a long press (context menu open). */
private enum class EditGesture { PRESS, HELD, MOVE, PINCH, EMPTY, NONE }

/** What edit mode shows for the selected control: its mapping picker or its context menu. */
private enum class EditMenu { NONE, MAPPING, CONTEXT }

/** Whether the touch event being dispatched is a cancel; set by [MainActivity] (main thread). */
internal object TouchStream {
    var cancelled = false
}

/**
 * Full-screen virtual game pad for the active profile's active workspace. Multi-touch: each finger
 * that lands on a stick owns it until lifted; a finger that lands on a button goes to the engine
 * (which applies touchpad over buttons and sticky modifiers). A finger that lands on empty
 * background drives the touchpad (cursor, taps, drag, scroll). The two corners ([Profile.corners])
 * are always shown: tap ← / → for the previous / next workspace, both together for fullscreen.
 * Controls have separate landscape and portrait placements; the pad box's shape picks one.
 * Edit mode works on a draft profile. Its menu floats and can be dragged (or folded) out of the
 * way; its workspace bar picks, adds and arranges workspaces. Tap a control to change its mapping,
 * long-press it (or tap a corner) for a menu next to it with all its settings, drag to move,
 * pinch or use the size slider to resize. Edits change the current orientation's placement. Done
 * saves (a builtin becomes a custom copy) and switches to the workspace being edited.
 */
@Composable
fun PadScreen(
    runtime: GamepadRuntime,
    fullscreen: Boolean = false,
    onFullscreen: (Boolean) -> Unit = {},
    onEditPad: (String) -> Unit = {},
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
    var menuFolded by remember { mutableStateOf(false) }
    var showWsSettings by remember { mutableStateOf(false) }
    /** The attached-devices dialog (play mode only). */
    var showDevices by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var snap by remember { mutableStateOf(false) }
    val stickPos = remember { mutableStateMapOf<String, Offset>() }
    /** Pointers currently routed to engine.buttonDown/Move/Up. */
    val buttonFingers = remember { HashSet<Long>() }
    var fingersOnButtons by remember { mutableIntStateOf(0) }
    var pressedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var latchedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var cornersHeld by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** Counts multi-finger touches that Android cancelled (a phone gesture took them). */
    var systemTookTouch by remember { mutableIntStateOf(0) }
    val editing = draft != null
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
        for (p in buttonFingers) runtime.engine.buttonUp(p)
        buttonFingers.clear()
        fingersOnButtons = 0
        for (id in stickPos.keys.toList()) runtime.engine.setTouchStick(id, 0f, 0f)
        stickPos.clear()
        cornersHeld = emptySet()
        runtime.engine.touchpadCancel()
        refreshHighlights()
    }

    DisposableEffect(Unit) { onDispose { releaseAll() } }

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
            val hy = (half(c) / h).coerceAtMost(0.5f)
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

        /** Resizes a control; with [Profile.cornerSameSize], both corners follow. */
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
                        val corners = d0?.corners ?: emptyList()
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
                                showWsSettings = false
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
                                    showWsSettings = false
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
                                    showWsSettings = false
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
                                showWsSettings = false
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
            Modifier.pointerInput("play", profile, wsIndex, w, h, pxPerDp) {
                releaseAll()
                val workspace = profile.workspace(wsIndex)
                val controls = workspace.touchControls
                val corners = profile.corners
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

                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        // The system took the touch (e.g. a three-finger screenshot gesture): Compose
                        // reports the cancel as every pointer lifting. That is not a tap.
                        val systemCancel = TouchStream.cancelled && event.changes.any { !it.pressed && it.previousPressed }
                        if (systemCancel) {
                            if (padOwner.isNotEmpty()) {
                                if (padOwner.size >= 2) systemTookTouch++
                                padOwner.clear()
                                runtime.engine.touchpadCancel()
                            }
                            cornerOwner.clear()
                        }
                        for (ch in event.changes) {
                            val pid = ch.id.value
                            if (ch.pressed && !ch.previousPressed) {
                                val corner = hit(corners, ch.position)
                                val c = if (corner == null) hit(controls, ch.position) else null
                                if (corner != null) {
                                    // A corner belongs to the first finger on it until that finger lifts.
                                    if (corner.id !in cornerOwner.values) cornerOwner[ch.id] = corner.id
                                } else if (c == null) {
                                    // A finger that lands on empty background is a touchpad finger until it lifts.
                                    if (padEnabled) {
                                        padOwner.add(ch.id)
                                        runtime.engine.touchpadDown(pid, ch.position.x, ch.position.y)
                                    }
                                } else if (c.kind == TouchKind.STICK) {
                                    if (c.id !in stickOwner.values) {
                                        stickOwner[ch.id] = c.id
                                        stickCenters[c.id] = center(c)
                                    }
                                } else if (c.kind == TouchKind.BUTTON) {
                                    // Every finger on a button goes to the engine (touchpad over buttons, sticky modifiers).
                                    buttonFingers.add(pid)
                                    runtime.engine.buttonDown(pid, c.id, ch.position.x, ch.position.y)
                                }
                                ch.consume()
                                continue
                            }
                            if (ch.id in padOwner) {
                                if (!ch.pressed) {
                                    padOwner.remove(ch.id)
                                    runtime.engine.touchpadUp(pid)
                                } else if (ch.position != ch.previousPosition) {
                                    runtime.engine.touchpadMove(pid, ch.position.x, ch.position.y)
                                }
                            } else if (pid in buttonFingers) {
                                if (!ch.pressed) {
                                    buttonFingers.remove(pid)
                                    runtime.engine.buttonUp(pid)
                                } else if (ch.position != ch.previousPosition) {
                                    runtime.engine.buttonMove(pid, ch.position.x, ch.position.y)
                                }
                            } else if (ch.id in cornerOwner) {
                                if (!ch.pressed) {
                                    val id = cornerOwner.remove(ch.id)
                                    // A tap: pressed and released without the other corner (no chord).
                                    if (id != null && !chordFired[0] && cornerOwner.isEmpty()) stepFrom(id)
                                }
                            } else {
                                val stickId = stickOwner[ch.id]
                                if (stickId != null) {
                                    if (!ch.pressed) {
                                        stickOwner.remove(ch.id)
                                        runtime.engine.setTouchStick(stickId, 0f, 0f)
                                        stickPos.remove(stickId)
                                    } else {
                                        val c = controls.firstOrNull { it.id == stickId }
                                        if (c != null) {
                                            var d = (ch.position - stickCenters.getValue(stickId)) / half(c)
                                            val len = hypot(d.x, d.y)
                                            if (len > 1f) d /= len
                                            runtime.engine.setTouchStick(stickId, d.x, d.y)
                                            stickPos[stickId] = d
                                        }
                                    }
                                }
                            }
                            ch.consume()
                        }
                        val held = cornerOwner.values.toSet()
                        if (held != cornersHeld) cornersHeld = held
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
            for (c in ws.touchControls) {
                val r = half(c)
                val ctr = center(c)
                val isSelected = editing && selected == c.id
                val isPressed = !editing && (c.id in pressedIds || stickPos.containsKey(c.id))
                val isLatched = !editing && c.id in latchedIds
                val tint = when {
                    isSelected -> Color(0xFFFFC857)
                    isPressed -> Color(0xFF7CC4FF)
                    isLatched -> Color(0xFF8FE39A)
                    else -> Color(0x66FFFFFF)
                }
                PadControl(
                    control = c,
                    label = controlLabel(ws, c, editing),
                    sizeDp = with(density) { (r * 2).toDp() },
                    tint = tint,
                    filled = isPressed || isSelected || isLatched,
                    knob = stickPos[c.id] ?: Offset.Zero,
                    knobRadiusPx = r * 0.6f,
                    modifier = Modifier
                        .offset { IntOffset((ctr.x - r).roundToInt(), (ctr.y - r).roundToInt()) }
                        // While editing, faint controls stay findable.
                        .alpha(ws.controlOpacity.let { if (editing) max(it, MIN_EDIT_OPACITY) else it }),
                )
            }
            // The corners: shared by all workspaces, drawn over the controls, always shown.
            for (c in shown.corners) {
                val r = half(c)
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
                    sizeDp = with(density) { (r * 2).toDp() },
                    tint = tint,
                    filled = isPressed || isSelected,
                    knob = Offset.Zero,
                    knobRadiusPx = 0f,
                    modifier = Modifier
                        .offset { IntOffset((ctr.x - r).roundToInt(), (ctr.y - r).roundToInt()) }
                        .alpha(if (atEnd) base * END_CORNER_ALPHA else base),
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
                Row(
                    Modifier.align(Alignment.TopCenter).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            releaseAll()
                            selected = null
                            showWsSettings = false
                            dragging = false
                            editIndex = wsIndex.coerceIn(0, profile.workspaces.lastIndex)
                            draft = profile
                        },
                    ) { Text("Edit layout") }
                    FilledTonalButton(
                        onClick = {
                            releaseAll()
                            showDevices = true
                        },
                    ) { Text("Devices (${ws.pads.size})") }
                }
            }
            if (showDevices && !fullscreen) {
                AttachedDevicesPanel(
                    runtime = runtime,
                    onDismiss = { showDevices = false },
                    onEditPad = { id ->
                        showDevices = false
                        onEditPad(id)
                    },
                )
            }
        } else {
            val compact = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            fun clampMenu(o: Offset): Offset {
                val mx = max(0f, (w - menuSize.width) / 2f)
                return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(0f, max(0f, h - menuSize.height)))
            }
            val menuAt = clampMenu(menuOffset)
            // The edit menu floats over the pad: drag it by its handle, or fold it, to reach what is under it.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(menuAt.x.roundToInt(), menuAt.y.roundToInt()) }
                    .onSizeChanged { menuSize = it }
                    .padding(8.dp)
                    .widthIn(max = maxWidth - 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xE01E232B),
                shadowElevation = 8.dp,
            ) {
                Column(
                    Modifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        if (!menuFolded) {
                            Text(
                                if (portrait) "Portrait layout" else "Landscape layout",
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            FilledTonalButton(contentPadding = compact, onClick = addButton@{
                                val d = draft ?: return@addButton
                                val wk = d.workspace(editIndex)
                                val id = freshId(wk, "btn")
                                val c = newControl(wk, id, TouchKind.BUTTON, NEW_BUTTON_SIZE, portrait)
                                editWorkspace { it.copy(touchControls = it.touchControls + place(c, 0.5f, 0.5f, false)) }
                                selected = id
                                menu = EditMenu.MAPPING
                                showWsSettings = false
                            }) { Text("+ Button") }
                            FilledTonalButton(contentPadding = compact, onClick = addStick@{
                                val d = draft ?: return@addStick
                                val wk = d.workspace(editIndex)
                                val id = freshId(wk, "stick")
                                val c = newControl(wk, id, TouchKind.STICK, NEW_STICK_SIZE, portrait)
                                editWorkspace { it.copy(touchControls = it.touchControls + place(c, 0.5f, 0.5f, false)) }
                                selected = id
                                menu = EditMenu.MAPPING
                                showWsSettings = false
                            }) { Text("+ Stick") }
                            if (snap) {
                                FilledTonalButton(contentPadding = compact, onClick = { snap = false }) { Text("Snap: on") }
                            } else {
                                OutlinedButton(contentPadding = compact, onClick = { snap = true }) { Text("Snap: off") }
                            }
                        }
                        OutlinedButton(contentPadding = compact, onClick = { menuFolded = !menuFolded }) {
                            Text(if (menuFolded) "More ▾" else "Less ▴")
                        }
                        OutlinedButton(contentPadding = compact, onClick = cancel@{
                            val d = draft ?: return@cancel
                            // Stay on the workspace being looked at if the saved profile has it.
                            val i = profile.indexOfWorkspace(d.workspace(editIndex).id)
                            draft = null
                            selected = null
                            showWsSettings = false
                            dragging = false
                            if (i >= 0 && i != runtime.workspaceIndex.value) runtime.switchWorkspace(i)
                        }) { Text("Cancel") }
                        FilledTonalButton(contentPadding = compact, onClick = done@{
                            val d = draft ?: return@done
                            val idx = editIndex.coerceIn(0, d.workspaces.lastIndex)
                            val active = runtime.activeProfile.value
                            if (d == active) {
                                Unit
                            } else if (d.id == active.id) {
                                runtime.editActiveProfile { d }
                            } else {
                                val saved = if (runtime.store.isBuiltin(d)) {
                                    d.copy(id = "user." + UUID.randomUUID(), name = d.name + " (custom)")
                                } else d
                                runtime.saveProfile(saved)
                                runtime.activate(saved)
                            }
                            if (runtime.workspaceIndex.value != idx) runtime.switchWorkspace(idx)
                            draft = null
                            selected = null
                            showWsSettings = false
                            dragging = false
                        }) { Text("Done") }
                    }
                    val d = draft
                    if (d != null && !menuFolded) {
                        AttachHints(
                            unattached = unattachedControllers(ws, controllers),
                            onAttach = { c -> editWorkspace { attachController(it, c) } },
                        )
                        WorkspaceBar(
                            profile = d,
                            index = shownIndex,
                            onSelect = { k ->
                                editIndex = k
                                selected = null
                            },
                            onProfileChange = { p, k ->
                                draft = p
                                editIndex = k.coerceIn(0, p.workspaces.lastIndex)
                                selected = null
                            },
                            onSettings = {
                                selected = null
                                showWsSettings = !showWsSettings
                            },
                        )
                    }
                }
            }

            val d = draft
            val sel = findControl(selected)
            // Workspace settings: docked at the right (landscape) or the top half (portrait).
            val panelWidth = if (portrait) maxWidth - 16.dp else (maxWidth * 0.42f).coerceAtMost(360.dp)
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
                            val current = Bindings.targets(it.mappings, input)
                            val next = (listOf(t) + current.drop(1)).distinct()
                            it.copy(mappings = Bindings.setTargets(it.mappings, input, next))
                        }
                    },
                    onDismiss = { menu = EditMenu.NONE },
                )
            } else if (d != null && sel != null && !dragging && menu != EditMenu.NONE) {
                // Long press (or a tap on a stick or a corner): a menu right next to the control.
                NearControl(anchor = center(sel), anchorHalf = half(sel)) {
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
                            val (nw, id) = duplicate(ws, sel)
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
            } else if (d != null && showWsSettings && sel == null) {
                val panelModifier = if (portrait) {
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 8.dp, end = 8.dp, top = 56.dp, bottom = 8.dp)
                        .fillMaxHeight(0.5f)
                } else {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(start = 8.dp, end = 8.dp, top = 56.dp, bottom = 8.dp)
                        .fillMaxHeight()
                }
                WorkspaceSettingsPanel(
                    workspace = ws,
                    width = panelWidth,
                    onChange = { changed -> editWorkspace { changed } },
                    onClose = { showWsSettings = false },
                    modifier = panelModifier,
                )
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

/** Id not used by any control of the workspace nor by its leftover mappings. */
private fun freshId(workspace: Workspace, prefix: String): String {
    val orphans = workspace.mappings.mapNotNull {
        when (val s = it.source) {
            is InputSource.Touch -> s.id
            is InputSource.TouchStick -> s.id
            else -> null
        }
    }.distinct().map { TouchControl(it, TouchKind.BUTTON, "", 0f, 0f) }
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
private fun duplicate(workspace: Workspace, c: TouchControl): Pair<Workspace, String> {
    val id = freshId(workspace, if (c.kind == TouchKind.STICK) "stick" else "btn")
    val copies = workspace.mappings.mapNotNull { m ->
        when (val s = m.source) {
            is InputSource.Touch -> if (s.id == c.id) m.copy(source = InputSource.Touch(id)) else null
            is InputSource.TouchStick -> if (s.id == c.id) m.copy(source = s.copy(id = id)) else null
            else -> null
        }
    }
    // Offset towards the centre in both orientations, so the copy never hides under the original
    // and stays on screen in the layout not being edited (the edited one is clamped by place()).
    fun toCentre(v: Float, d: Float) = if (v < 0.5f) v + d else v - d
    val p = c.placement(true)
    val lHalf = (c.size / 2f).coerceAtMost(0.5f) // landscape: size is a fraction of the height
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
 * Places [content] beside a control centred at [anchor] (px of the pad area, [anchorHalf] its
 * half size): right of it if it fits, else left, else below or above; always kept on screen.
 * Takes no touches itself, so the pad around the menu stays editable.
 */
@Composable
private fun NearControl(anchor: Offset, anchorHalf: Float, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val box = constraints.copy(minWidth = 0, minHeight = 0)
        val p = measurables.first().measure(box)
        val gap = 8.dp.roundToPx()
        val bw = constraints.maxWidth
        val bh = constraints.maxHeight
        val right = (anchor.x + anchorHalf).roundToInt() + gap
        val left = (anchor.x - anchorHalf).roundToInt() - gap - p.width
        val below = (anchor.y + anchorHalf).roundToInt() + gap
        val above = (anchor.y - anchorHalf).roundToInt() - gap - p.height
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
    sizeDp: Dp,
    tint: Color,
    filled: Boolean,
    knob: Offset,
    knobRadiusPx: Float,
    modifier: Modifier = Modifier,
) {
    val shape = shapeOf(control)
    Box(
        modifier
            .size(sizeDp)
            .border(2.dp, tint, shape)
            .background(if (filled) tint.copy(alpha = 0.25f) else Color.Transparent, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (control.kind == TouchKind.STICK) {
            Box(
                Modifier
                    .offset { IntOffset((knob.x * knobRadiusPx).roundToInt(), (knob.y * knobRadiusPx).roundToInt()) }
                    .size(sizeDp / 2.5f)
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
                    opacity = profile.cornerOpacity,
                    onOpacity = { onProfileChange(profile.copy(cornerOpacity = it)) },
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
                    Column {
                        Text("Size ${(control.placeSize(inPortrait) * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = control.placeSize(inPortrait),
                            onValueChange = onSize,
                            valueRange = TouchControl.MIN_SIZE..TouchControl.MAX_SIZE,
                        )
                    }
                    HorizontalDivider()
                }
                Text("Mapping", style = MaterialTheme.typography.titleSmall)
                if (control.kind == TouchKind.STICK) {
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
    opacity: Float,
    onOpacity: (Float) -> Unit,
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
        "Tap ← for the previous workspace and → for the next. Press both at the same time to hide " +
            "the tabs and the Edit button; press both again (or Back) to show them. The corners are " +
            "the same in every workspace; drag each one where you like.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Column {
        Text("Size ${(control.placeSize(inPortrait) * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = control.placeSize(inPortrait),
            onValueChange = onSize,
            valueRange = TouchControl.MIN_SIZE..TouchControl.MAX_SIZE,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Same size for both corners", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = sameSize, onCheckedChange = onSameSize)
    }
    Column {
        Text("Corner opacity ${(opacity * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(value = opacity, onValueChange = onOpacity, valueRange = Profile.MIN_CONTROL_OPACITY..1f)
    }
}

/** True when the stick's mappings equal what [preset] would write. */
private fun presetMatches(
    mappings: List<Mapping>,
    inputs: List<BindableInput>,
    preset: Bindings.StickPreset,
): Boolean {
    val applied = Bindings.applyStickPreset(mappings, inputs, preset)
    return inputs.take(6).all { Bindings.targets(applied, it).toSet() == Bindings.targets(mappings, it).toSet() }
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
