package com.oleroma.gyrophone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.DevicePresets
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** What a control shows inside: its name, nothing (A B X Y and the D-pad: their place says which), or an icon. */
internal enum class Mark { NAME, NONE, VIEW, MENU, HOME }

/** A control of a device drawing: what its mapping screen edits, and how it is drawn. */
internal data class Element(
    val id: String,
    val label: String,
    val stick: Boolean,
    val inputs: List<BindableInput>,
    val round: Boolean = false,
    val mark: Mark = Mark.NAME,
)

/**
 * The controls of the device on screen and what is being done to them: a tap selects one (and
 * shows what it does), a hold buzzes and lifts it, letting go in place opens its editor, letting go
 * on another control swaps what the two do.
 */
internal class ControlBoard {
    /** Each control's bounds in the root, as last laid out. */
    val bounds = mutableStateMapOf<String, Rect>()
    /** The controls drawn now, by id (filled while composing). */
    val elements = HashMap<String, Element>()
    /** Controls that can't be picked (a back button the attached pads lack). */
    val disabled = HashSet<String>()
    /** Where the board sits in the root. */
    var origin by mutableStateOf(Offset.Zero)
    var selected by mutableStateOf<String?>(null)
    var dragging by mutableStateOf<String?>(null)
    var dragBy by mutableStateOf(Offset.Zero)
    var target by mutableStateOf<String?>(null)
    private var moved = false

    // Set each composition by the screen.
    var haptic: HapticFeedback? = null
    var slop = 0f
    var count: (Element) -> Int = { 0 }
    var onOpen: (Element) -> Unit = {}
    var onSwap: (Element, Element) -> Unit = { _, _ -> }

    fun tap(e: Element) {
        selected = if (selected == e.id) null else e.id
    }

    fun holdStart(e: Element) {
        haptic?.performHapticFeedback(HapticFeedbackType.LongPress)
        selected = null
        dragging = e.id
        dragBy = Offset.Zero
        target = null
        moved = false
    }

    fun moveBy(d: Offset) {
        val from = elements[dragging ?: return] ?: return
        dragBy += d
        if (dragBy.getDistance() > slop) moved = true
        val at = (bounds[from.id] ?: return).center + dragBy
        val next = elements.values.firstOrNull { e ->
            e.id != from.id && e.id !in disabled && bounds[e.id]?.contains(at) == true && DevicePresets.canSwap(from.inputs, e.inputs)
        }?.id
        if (next != target && next != null) haptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        target = next
    }

    fun holdEnd(done: Boolean) {
        val from = dragging?.let { elements[it] }
        val to = target?.let { elements[it] }
        dragging = null
        target = null
        dragBy = Offset.Zero
        if (!done || from == null) return
        if (to != null) {
            haptic?.performHapticFeedback(HapticFeedbackType.LongPress)
            onSwap(from, to)
        } else if (!moved) {
            onOpen(from)
        }
    }

    /** Whether [e] can't be dropped on now: something is dragged that can't swap with it. */
    fun faded(e: Element): Boolean {
        val from = elements[dragging ?: return false] ?: return false
        return from.id != e.id && !DevicePresets.canSwap(from.inputs, e.inputs)
    }
}

/** A board's [ControlBoard] wired to this composition: haptics, slop and what a control's remap count is. */
@Composable
internal fun ControlBoard.wire(count: (Element) -> Int, onOpen: (Element) -> Unit, onSwap: (Element, Element) -> Unit): ControlBoard {
    haptic = LocalHapticFeedback.current
    slop = with(LocalDensity.current) { 12.dp.toPx() }
    this.count = count
    this.onOpen = onOpen
    this.onSwap = onSwap
    return this
}

/**
 * The area a board's controls are drawn in: a tap on nothing closes the selection. That tap is
 * caught by a layer behind the controls, not by the area itself: around them it would also see
 * (and take) the tap that selects a control.
 */
@Composable
internal fun BoardArea(board: ControlBoard, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.onGloballyPositioned { board.origin = it.positionInRoot() }) {
        Box(Modifier.matchParentSize().pointerInput(board) { detectTapGestures { board.selected = null } })
        content()
    }
}

/** Control [e] of [board] at [modifier]'s place and size: tap, hold and drag as [ControlBoard] says. */
@Composable
internal fun DeviceControl(board: ControlBoard, e: Element, enabled: Boolean, modifier: Modifier) {
    board.elements[e.id] = e
    if (enabled) board.disabled.remove(e.id) else board.disabled.add(e.id)
    val lifted = board.dragging == e.id
    Box(
        modifier
            .onGloballyPositioned { board.bounds[e.id] = it.boundsInRoot() }
            .alpha(
                when {
                    !enabled -> 0.3f
                    lifted -> 0.25f
                    board.faded(e) -> 0.3f
                    else -> 1f
                },
            )
            .pointerInput(e.id, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val long = awaitLongPressOrCancellation(down.id)
                    if (long == null) {
                        // Let go before the hold time without sliding off: a tap.
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed &&
                            (up.position - down.position).getDistance() <= viewConfiguration.touchSlop
                        ) {
                            up.consume()
                            board.tap(e)
                        }
                        return@awaitEachGesture
                    }
                    board.holdStart(e)
                    val done = drag(long.id) { c ->
                        board.moveBy(c.positionChange())
                        c.consume()
                    }
                    board.holdEnd(done)
                }
            },
    ) {
        ControlFace(e, board.count(e), selected = board.selected == e.id, target = board.target == e.id)
    }
}

/**
 * How a control looks: purple when it does something other than itself, with a ↪ and how many
 * things it does; grey with nothing more when it doesn't. Its name (or icon) inside shrinks to fit,
 * and turns sideways in a tall narrow control.
 */
@Composable
internal fun ControlFace(e: Element, count: Int, selected: Boolean = false, target: Boolean = false, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = if (e.round) CircleShape else RoundedCornerShape(6.dp)
    val mapped = count > 0
    val content = if (mapped) colors.onPrimaryContainer else colors.onSurfaceVariant
    val ring = when {
        target -> BorderStroke(3.dp, colors.tertiary)
        selected -> BorderStroke(2.5.dp, colors.onSurface)
        mapped -> BorderStroke(1.dp, colors.primary)
        else -> BorderStroke(1.dp, colors.outline)
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .scale(if (target) 1.1f else 1f)
            .background(if (mapped) colors.primaryContainer else colors.surfaceContainerHighest, shape)
            .border(ring, shape),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        // A circle's content has its inner square.
        val room = if (e.round) 0.70f else 0.86f
        val tall = h > 1.6f * w && e.mark == Mark.NAME && e.label.length > 2
        val wide = w > 2.4f * h
        @Composable
        fun MarkView() {
            when (e.mark) {
                Mark.NONE -> Unit
                Mark.NAME -> Text(
                    e.label,
                    fontSize = 13.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    color = content,
                    modifier = if (tall) Modifier.sideways() else Modifier,
                )
                else -> SystemIcon(e.mark, content, 18.dp)
            }
        }
        @Composable
        fun BadgeView() {
            if (!mapped) return
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemapArrow(content, 11.dp)
                Text(count.toString(), fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, color = content)
            }
        }
        // Laid out at full size, then shrunk as a whole to fit the control: nothing is cut off.
        Box(Modifier.fitInside(w * room, h * room)) {
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { MarkView(); BadgeView() }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { MarkView(); BadgeView() }
            }
        }
    }
}

/** Content measured at its own size and drawn scaled down (never up) to fit [maxW] x [maxH] pixels, centred. */
private fun Modifier.fitInside(maxW: Float, maxH: Float) = layout { m, _ ->
    val p = m.measure(Constraints())
    val k = minOf(1f, maxW / p.width.coerceAtLeast(1), maxH / p.height.coerceAtLeast(1))
    val w = (p.width * k).roundToInt()
    val h = (p.height * k).roundToInt()
    // Scaled about its centre, so placed centred in the scaled size.
    layout(w, h) {
        p.placeWithLayer((w - p.width) / 2, (h - p.height) / 2) {
            scaleX = k
            scaleY = k
        }
    }
}

/** Text turned a quarter left, laid out in its turned size. */
private fun Modifier.sideways() = layout { m, c ->
    val p = m.measure(Constraints(maxWidth = c.maxHeight, maxHeight = c.maxWidth))
    layout(p.height, p.width) {
        p.placeWithLayer((p.height - p.width) / 2, (p.width - p.height) / 2) { rotationZ = -90f }
    }
}

/** The ↪ before a remap count. */
@Composable
private fun RemapArrow(color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width
        val line = (u * 0.13f).coerceAtLeast(1f)
        val p = Path().apply {
            moveTo(u * 0.25f, u * 0.15f)
            lineTo(u * 0.25f, u * 0.62f)
            lineTo(u * 0.85f, u * 0.62f)
        }
        val head = Path().apply {
            moveTo(u * 0.62f, u * 0.40f)
            lineTo(u * 0.85f, u * 0.62f)
            lineTo(u * 0.62f, u * 0.84f)
        }
        val s = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(p, color, style = s)
        drawPath(head, color, style = s)
    }
}

/** The View (two windows), Menu (three lines) and Home (a house) icons. */
@Composable
private fun SystemIcon(mark: Mark, color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width
        val line = (u * 0.1f).coerceAtLeast(1.2f)
        val s = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (mark) {
            Mark.VIEW -> {
                drawRoundRect(color, Offset(u * 0.1f, u * 0.1f), Size(u * 0.52f, u * 0.52f), CornerRadius(u * 0.08f), style = s)
                val back = Path().apply {
                    moveTo(u * 0.38f, u * 0.9f)
                    lineTo(u * 0.82f, u * 0.9f)
                    quadraticTo(u * 0.9f, u * 0.9f, u * 0.9f, u * 0.82f)
                    lineTo(u * 0.9f, u * 0.38f)
                }
                drawPath(back, color, style = s)
            }
            Mark.MENU -> for (y in listOf(0.25f, 0.5f, 0.75f)) {
                drawLine(color, Offset(u * 0.15f, u * y), Offset(u * 0.85f, u * y), line, StrokeCap.Round)
            }
            Mark.HOME -> {
                val house = Path().apply {
                    moveTo(u * 0.5f, u * 0.1f)
                    lineTo(u * 0.92f, u * 0.48f)
                    lineTo(u * 0.78f, u * 0.48f)
                    lineTo(u * 0.78f, u * 0.9f)
                    lineTo(u * 0.58f, u * 0.9f)
                    lineTo(u * 0.58f, u * 0.64f)
                    lineTo(u * 0.42f, u * 0.64f)
                    lineTo(u * 0.42f, u * 0.9f)
                    lineTo(u * 0.22f, u * 0.9f)
                    lineTo(u * 0.22f, u * 0.48f)
                    lineTo(u * 0.08f, u * 0.48f)
                    close()
                }
                drawPath(house, color)
            }
            else -> Unit
        }
    }
}

/**
 * Over a board: the lifted control following the finger, the selected control's list of what it
 * does ([lines]: what fires, what it sends), and after a swap a bar to undo it.
 */
@Composable
internal fun BoxScope.BoardOverlay(
    board: ControlBoard,
    lines: (Element) -> List<Pair<String, String>>,
    undo: String?,
    onUndo: () -> Unit,
    onUndoGone: () -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    // The lifted control.
    val from = board.dragging?.let { board.elements[it] }
    val rect = from?.let { board.bounds[it.id] }
    if (from != null && rect != null) {
        val at = rect.topLeft + board.dragBy - board.origin
        with(density) {
            Surface(
                shape = if (from.round) CircleShape else RoundedCornerShape(6.dp),
                shadowElevation = 10.dp,
                color = Color.Transparent,
                modifier = Modifier
                    .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                    .size(rect.width.toDp(), rect.height.toDp())
                    .scale(1.2f),
            ) { ControlFace(from, board.count(from), selected = true) }
        }
    }
    // What the selected control does.
    val sel = board.selected?.let { board.elements[it] }
    val selRect = sel?.let { board.bounds[it.id] }
    if (sel != null && selRect != null) {
        val gap = with(density) { 8.dp.roundToPx() }
        Layout(
            content = {
                Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerHigh, shadowElevation = 6.dp) {
                    Column(Modifier.widthIn(min = 150.dp, max = 260.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        val rows = lines(sel)
                        if (rows.isEmpty()) {
                            Text("Not remapped", style = MaterialTheme.typography.bodyMedium)
                        }
                        for ((what, to) in rows) {
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    what,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.width(78.dp),
                                )
                                Text(to, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(
                            "Hold to edit, or drag onto another to swap",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            modifier = Modifier.matchParentSize(),
        ) { ms, c ->
            val p = ms.first().measure(c.copy(minWidth = 0, minHeight = 0))
            val r = selRect.translate(-board.origin)
            val right = r.right.roundToInt() + gap
            val x = if (right + p.width <= c.maxWidth) right else (r.left.roundToInt() - gap - p.width).coerceAtLeast(0)
            val y = r.top.roundToInt().coerceIn(0, (c.maxHeight - p.height).coerceAtLeast(0))
            layout(c.maxWidth, c.maxHeight) { p.place(x, y) }
        }
    }
    // Undo a swap for a few seconds.
    if (undo != null) {
        LaunchedEffect(undo) {
            delay(UNDO_MS)
            onUndoGone()
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.inverseSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(undo, color = colors.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onUndo) { Text("Undo", color = colors.inversePrimary) }
            }
        }
    }
}

private const val UNDO_MS = 5000L

/** The switch between a preset's devices: one bar, an icon and a name each. */
@Composable
internal fun <T> DeviceSwitch(items: List<T>, selected: T, label: (T) -> String, icon: (T) -> DeviceGlyph, onPick: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(Modifier.border(1.dp, colors.outline, shape).background(colors.surface, shape)) {
        for ((i, it) in items.withIndex()) {
            val on = it == selected
            val segment = when (i) {
                0 -> RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                items.lastIndex -> RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                else -> RoundedCornerShape(0)
            }
            Row(
                Modifier
                    .background(if (on) colors.secondaryContainer else Color.Transparent, segment)
                    .clickable { onPick(it) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tint = if (on) colors.onSecondaryContainer else colors.onSurfaceVariant
                Glyph(icon(it), tint)
                Text(label(it), style = MaterialTheme.typography.labelLarge, color = tint)
            }
        }
    }
}

internal enum class DeviceGlyph { PAD, KEYBOARD, MOUSE, PHONE }

/** A small line drawing of a game pad, a keyboard, a mouse or a phone. */
@Composable
private fun Glyph(g: DeviceGlyph, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val u = size.width / 20f
        val line = 1.6.dp.toPx()
        val s = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (g) {
            DeviceGlyph.PAD -> {
                val pad = Path().apply {
                    moveTo(5 * u, 5 * u)
                    lineTo(15 * u, 5 * u)
                    cubicTo(18.5f * u, 5 * u, 20 * u, 11 * u, 19.6f * u, 14 * u)
                    cubicTo(19.2f * u, 16.5f * u, 16.5f * u, 16.5f * u, 15.3f * u, 14.2f * u)
                    lineTo(14 * u, 12.5f * u)
                    lineTo(6 * u, 12.5f * u)
                    lineTo(4.7f * u, 14.2f * u)
                    cubicTo(3.5f * u, 16.5f * u, 0.8f * u, 16.5f * u, 0.4f * u, 14 * u)
                    cubicTo(0f, 11 * u, 1.5f * u, 5 * u, 5 * u, 5 * u)
                    close()
                }
                drawPath(pad, color, style = s)
                drawLine(color, Offset(4 * u, 8.7f * u), Offset(7.4f * u, 8.7f * u), line, StrokeCap.Round)
                drawLine(color, Offset(5.7f * u, 7 * u), Offset(5.7f * u, 10.4f * u), line, StrokeCap.Round)
                drawCircle(color, 1f * u, Offset(13.5f * u, 7.8f * u))
                drawCircle(color, 1f * u, Offset(15.6f * u, 9.8f * u))
            }
            DeviceGlyph.KEYBOARD -> {
                drawRoundRect(color, Offset(1 * u, 4.5f * u), Size(18 * u, 11 * u), CornerRadius(2 * u), style = s)
                for (row in 0..1) for (k in 0..3) drawCircle(color, 0.85f * u, Offset(4.5f * u + k * 3.7f * u, 7.8f * u + row * 2.8f * u))
                drawLine(color, Offset(6 * u, 13.2f * u), Offset(14 * u, 13.2f * u), line, StrokeCap.Round)
            }
            DeviceGlyph.MOUSE -> {
                drawRoundRect(color, Offset(5 * u, 1.5f * u), Size(10 * u, 17 * u), CornerRadius(5 * u), style = s)
                drawLine(color, Offset(10 * u, 2 * u), Offset(10 * u, 7.5f * u), line, StrokeCap.Round)
                drawLine(color, Offset(5.5f * u, 7.5f * u), Offset(14.5f * u, 7.5f * u), line, StrokeCap.Round)
            }
            DeviceGlyph.PHONE -> {
                drawRoundRect(color, Offset(1 * u, 5 * u), Size(18 * u, 10.5f * u), CornerRadius(2.2f * u), style = s)
                drawLine(color, Offset(6 * u, 3 * u), Offset(10 * u, 3 * u), line, StrokeCap.Round)
            }
        }
    }
}
