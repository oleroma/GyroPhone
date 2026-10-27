package com.oleroma.gyrophone.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val MAX_RECENT = 12
private const val MAX_UNDO = 50
private const val MIN_SPLIT = 0.25f
private const val MAX_SPLIT = 0.75f
private const val PREF_SPLIT = "map.split"
private const val PREF_RECENT = "map.recent"
private const val PREF_ORDER = "map.order."
private val recentSerializer = ListSerializer(OutputTarget.serializer())
private val prefJson = Json { ignoreUnknownKeys = true }

/** Something being dragged on the mapping screen. */
private sealed interface Dragged {
    /** A target from the library ([from] null) or a chip of action [from]. */
    data class Target(val target: OutputTarget, val from: BindableInput?) : Dragged
    data class Preset(val preset: MapPreset) : Dragged

    /** An action card moved within its [group] (0: the control's own actions, 1: its surface ones). */
    data class Card(val input: BindableInput, val group: Int) : Dragged
}

/** A ready-made mapping of one or more actions; [apply] rewrites the mapping list. */
private class MapPreset(val label: String, val apply: (List<Mapping>) -> List<Mapping>)

private enum class LibraryTab(val label: String) {
    RECENT("Recent"), PRESETS("Presets"), KEYBOARD("Keyboard"), MOUSE("Mouse"), GAMEPAD("Gamepad"),
    GYRO("Gyro"), PHONE("Phone"), TOUCHPAD("Touchpad"),
}

private fun group(name: String): List<OutputTarget> =
    Describe.targetGroups.firstOrNull { it.first == name }?.second.orEmpty()

private fun tabTargets(tab: LibraryTab, extras: List<OutputTarget>): List<OutputTarget> = when (tab) {
    LibraryTab.KEYBOARD -> group("Keyboard")
    LibraryTab.MOUSE -> group("Mouse") + group("Mouse motion")
    LibraryTab.GAMEPAD -> group("Gamepad buttons") + group("Gamepad sticks & triggers")
    LibraryTab.GYRO -> group("Gyro")
    LibraryTab.PHONE -> group("Phone")
    LibraryTab.TOUCHPAD -> extras
    LibraryTab.RECENT, LibraryTab.PRESETS -> emptyList()
}

/** The targets [input]'s picker offers ([Describe.pickerGroups]). */
private fun allowedTargets(input: BindableInput): Set<OutputTarget> =
    Describe.pickerGroups(input.analog, input.swipe, input.extra).flatMapTo(HashSet()) { it.second }

/** Whether [t] may be mapped to an input offering [allowed]: haptics always, variants of an offered kind too. */
private fun allows(allowed: Set<OutputTarget>, t: OutputTarget): Boolean {
    if (Describe.isHaptic(t) || t in allowed) return true
    return when (t) {
        is OutputTarget.PadAxis -> allowed.any { it is OutputTarget.PadAxis && it.axis == t.axis }
        is OutputTarget.MouseAxis -> allowed.any { it is OutputTarget.MouseAxis && it.motion == t.motion }
        is OutputTarget.Key -> allowed.any { it is OutputTarget.Key }
        OutputTarget.GyroHold -> OutputTarget.GyroPause in allowed
        else -> false
    }
}

private fun isSurface(input: BindableInput) = input.sources.firstOrNull() is InputSource.Surface

/** Card title in messages: surface actions carry their finger count. */
private fun actionTitle(control: TouchControl, input: BindableInput): String {
    val s = input.sources.firstOrNull()
    return if (s is InputSource.Surface) s.fingers.toString() + "f " + input.label else actionLabel(control, input)
}

/** Remembered card orders are per control kind. */
private fun kindKey(c: TouchControl) = when {
    c.isTouchpadStandIn -> "touchpad"
    c.kind == TouchKind.STICK -> "stick"
    c.swipes -> "swipe"
    else -> "button"
}

/** A card's key in the remembered order: its label, surface ones without the finger count. */
private fun orderKey(control: TouchControl, input: BindableInput): String =
    if (isSurface(input)) "f:" + input.label.replace(Regex("^[0-9]f "), "") else actionLabel(control, input)

/** [inputs] in the remembered [order]; unknown ones last, in their own order. */
private fun sorted(control: TouchControl, inputs: List<BindableInput>, order: List<String>): List<BindableInput> =
    inputs.sortedBy { order.indexOf(orderKey(control, it)).let { i -> if (i < 0) Int.MAX_VALUE else i } }

private fun loadRecent(runtime: GamepadRuntime): List<OutputTarget> =
    runtime.uiPref(PREF_RECENT)?.let { runCatching { prefJson.decodeFromString(recentSerializer, it) }.getOrNull() }
        .orEmpty()

/** Presets of [control]: stick presets, or common button actions (and touchpad set-ups for swipe buttons). */
private fun presetsFor(control: TouchControl, inputs: List<BindableInput>): List<MapPreset> = buildList {
    if (control.kind == TouchKind.STICK) {
        for (p in Bindings.StickPreset.entries) add(MapPreset(p.label) { m -> Bindings.applyStickPreset(m, inputs, p) })
        return@buildList
    }
    val press = inputs.firstOrNull { !isSurface(it) }
    if (press != null) {
        // A preset replaces the press; a ratchet stays.
        fun main(label: String, t: OutputTarget) = add(
            MapPreset(label) { m ->
                Bindings.setTargets(
                    m,
                    press,
                    listOf(t) + Bindings.targets(m, press).filter(::isRatchet)
                )
            },
        )
        main("Mouse left", OutputTarget.Mouse(MouseButton.LEFT))
        main("Mouse right", OutputTarget.Mouse(MouseButton.RIGHT))
        main("Space", OutputTarget.Key(0x2C))
        main("Shift", OutputTarget.Key(HidUsage.LEFT_SHIFT))
        main("Ctrl", OutputTarget.Key(HidUsage.LEFT_CTRL))
        main("Esc", OutputTarget.Key(0x29))
        main("Tab", OutputTarget.Key(0x2B))
        main("Enter", OutputTarget.Key(0x28))
        add(MapPreset("Ratchet") { m ->
            Bindings.setTargets(
                m,
                press,
                Bindings.targets(m, press).filterNot(::isRatchet) + OutputTarget.GyroPause
            )
        })
    }
    if (control.swipes) {
        add(MapPreset("Windows touchpad") { m ->
            m.filterNot { (it.source as? InputSource.Surface)?.id == control.id } +
                    Bindings.defaultTouchpadMappings(TouchpadSettings()).map { d ->
                        val s = d.source
                        if (s is InputSource.Surface) d.copy(source = s.copy(id = control.id)) else d
                    }
        })
        add(MapPreset("Mouse cursor") { m ->
            fun move(i: SurfaceInput) =
                BindableInput(i.label, listOf(InputSource.Surface(1, i, control.id)), analog = true, swipe = true)

            val x =
                Bindings.setTargets(m, move(SurfaceInput.MOTION_X), listOf(OutputTarget.MouseAxis(MouseMotion.MOVE_X)))
            Bindings.setTargets(x, move(SurfaceInput.MOTION_Y), listOf(OutputTarget.MouseAxis(MouseMotion.MOVE_Y)))
        })
    }
}

/** Starts a long-press drag on this element; positions are in root coordinates, [onMove] gets deltas. */
private fun Modifier.dragSource(
    key: Any?,
    coords: Array<LayoutCoordinates?>,
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier = this
    .onGloballyPositioned { coords[0] = it }
    .pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { p -> coords[0]?.let { onStart(it.localToRoot(p)) } },
            onDrag = { change, amount ->
                change.consume()
                onMove(amount)
            },
            onDragEnd = onEnd,
            onDragCancel = onCancel,
        )
    }

/**
 * Stands for the workspace touchpad in the control screens: a tap or long press on empty space
 * in edit mode opens them with it. Its surface inputs have the touchpad's id ("").
 */
internal val TOUCHPAD_STAND_IN = TouchControl("", TouchKind.BUTTON, "Touchpad", 0f, 0f, swipe = true)

internal val TouchControl.isTouchpadStandIn: Boolean get() = id.isEmpty()

/** "Stick", "Swipe button", "Button" or "Touchpad". */
internal fun controlKindName(c: TouchControl): String = when {
    c.isTouchpadStandIn -> "Touchpad"
    c.kind == TouchKind.STICK -> "Stick"
    c.swipes -> "Swipe button"
    else -> "Button"
}

/**
 * Full-screen mapping editor of a touch control (a tap on it in edit mode), or of the touchpad
 * ([TOUCHPAD_STAND_IN], a tap on empty space). Its actions (cards
 * with their targets as chips) and a library of targets share the screen, split by a draggable
 * divider: tap a card to select it and a target to add it there, or long-press and drag a target
 * onto a card, a chip to another card (or off the cards to remove it), or a card to reorder it.
 * Every change goes to [onMappingsChange] and can be undone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ControlMappingScreen(
    control: TouchControl,
    mappings: List<Mapping>,
    runtime: GamepadRuntime,
    onMappingsChange: (List<Mapping>) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    // Drag callbacks outlive a composition: everything they read goes through state.
    val currentMappings by rememberUpdatedState(mappings)
    val currentControl by rememberUpdatedState(control)
    val emit by rememberUpdatedState(onMappingsChange)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val inputs = remember(control) {
        if (control.isTouchpadStandIn) (1..4).flatMap { Bindings.surfaceInputs(it) } else Bindings.touchInputs(control)
    }
    val presets = remember(control) { presetsFor(control, inputs) }
    val extras = remember(inputs) { inputs.flatMap { it.extra }.distinct() }

    var fingers by remember(control.id) { mutableIntStateOf(1) }
    var order by remember(control.id) {
        mutableStateOf(runtime.uiPref(PREF_ORDER + kindKey(control))?.split("\n").orEmpty())
    }
    val groups = listOf(
        sorted(control, inputs.filterNot(::isSurface), order),
        if (control.swipes) sorted(control, Bindings.surfaceInputs(fingers, control.id), order) else emptyList(),
    )
    var dragged by remember { mutableStateOf<Dragged?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var liveOrder by remember { mutableStateOf<List<BindableInput>>(emptyList()) }
    val cardDrag = dragged as? Dragged.Card
    val shownGroups = groups.mapIndexed { g, list -> if (cardDrag?.group == g) liveOrder else list }
    val shown = shownGroups.flatten()
    val groupsNow by rememberUpdatedState(shownGroups)
    val shownNow by rememberUpdatedState(shown)
    var selectedSrc by remember(control.id) { mutableStateOf<InputSource?>(null) }
    val selected = shown.firstOrNull { it.sources.first() == selectedSrc } ?: shown.firstOrNull()
    val selectedAllowed = remember(selected) { selected?.let(::allowedTargets).orEmpty() }

    val undo = remember(control.id) { mutableStateListOf<List<Mapping>>() }
    var recent by remember { mutableStateOf(loadRecent(runtime)) }
    var hapticDraft by remember { mutableStateOf<Pair<BindableInput, OutputTarget>?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var split by remember {
        mutableFloatStateOf(runtime.uiPref(PREF_SPLIT)?.toFloatOrNull()?.coerceIn(MIN_SPLIT, MAX_SPLIT) ?: 0.5f)
    }

    fun undoLast() {
        val prev = undo.removeLastOrNull() ?: return
        emit(prev)
    }

    fun change(next: List<Mapping>, message: String) {
        val prev = currentMappings
        if (next == prev) return
        undo.add(prev)
        if (undo.size > MAX_UNDO) undo.removeAt(0)
        emit(next)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undoLast()
        }
    }

    fun addRecent(t: OutputTarget) {
        recent = (listOf(t) + recent.filter { it != t }).take(MAX_RECENT)
        runtime.setUiPref(PREF_RECENT, prefJson.encodeToString(recentSerializer, recent))
    }

    fun assign(input: BindableInput, t: OutputTarget) {
        if (!allows(allowedTargets(input), t)) return
        addRecent(t)
        change(
            Bindings.addTarget(currentMappings, input, t),
            "Mapped " + Describe.shortTarget(t) + " to " + actionTitle(currentControl, input),
        )
    }

    /** Adds [t] to [input]; a haptic target is set up first. */
    fun pick(input: BindableInput, t: OutputTarget) {
        if (Describe.isHaptic(t)) hapticDraft = input to t else assign(input, t)
    }

    fun remove(input: BindableInput, t: OutputTarget) = change(
        Bindings.removeTarget(currentMappings, input, t),
        "Removed " + Describe.shortTarget(t) + " from " + actionTitle(currentControl, input),
    )

    fun move(from: BindableInput, to: BindableInput, t: OutputTarget) = change(
        Bindings.addTarget(Bindings.removeTarget(currentMappings, from, t), to, t),
        "Moved " + Describe.shortTarget(t) + " to " + actionTitle(currentControl, to),
    )

    fun applyPreset(p: MapPreset) = change(p.apply(currentMappings), "Applied " + p.label)

    // Drag & drop geometry, in root coordinates.
    val cardBounds = remember { mutableStateMapOf<InputSource, Rect>() }
    val chipBounds = remember { HashMap<Pair<InputSource, OutputTarget>, Rect>() }
    var actionsBounds by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    /** Card centres (y) when a card drag started: where the others sit while it moves. */
    val startCenters = remember { HashMap<InputSource, Float>() }

    fun cardAt(p: Offset): BindableInput? {
        if (!actionsBounds.contains(p)) return null
        return shownNow.firstOrNull { cardBounds[it.sources.first()]?.contains(p) == true }
    }

    val hovered by remember { derivedStateOf { if (dragged is Dragged.Target) cardAt(dragPos) else null } }

    fun startDrag(d: Dragged, at: Offset) {
        dragged = d
        dragPos = at
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** A long press on a card: drags the chip under the finger, else the card. */
    fun startOnCard(input: BindableInput, at: Offset) {
        val src = input.sources.first()
        val chip = Bindings.targets(currentMappings, input).firstOrNull { chipBounds[src to it]?.contains(at) == true }
        if (chip != null) {
            startDrag(Dragged.Target(chip, input), at)
            return
        }
        val g = groupsNow.indexOfFirst { list -> list.any { it.sources.first() == src } }
        if (g < 0) return
        startCenters.clear()
        for (i in groupsNow[g]) cardBounds[i.sources.first()]?.let { startCenters[i.sources.first()] = it.center.y }
        liveOrder = groupsNow[g]
        startDrag(Dragged.Card(input, g), at)
    }

    fun moveDrag(delta: Offset) {
        dragPos += delta
        val d = dragged as? Dragged.Card ?: return
        val others = liveOrder.filter { it != d.input }
        val at = others.count { (startCenters[it.sources.first()] ?: Float.MAX_VALUE) < dragPos.y }
        val next = others.toMutableList().apply { add(at, d.input) }
        if (next != liveOrder) {
            liveOrder = next
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun cancelDrag() {
        dragged = null
        liveOrder = emptyList()
    }

    fun endDrag() {
        val d = dragged ?: return
        val over = cardAt(dragPos)
        var done = false
        when (d) {
            is Dragged.Target -> {
                val from = d.from
                val fits = over != null && allows(allowedTargets(over), d.target)
                if (from == null) {
                    if (over != null && fits) {
                        pick(over, d.target)
                        done = true
                    }
                } else if (over == null) {
                    remove(from, d.target)
                    done = true
                } else if (over.sources.first() != from.sources.first() && fits) {
                    move(from, over, d.target)
                    done = true
                }
            }

            is Dragged.Preset -> if (actionsBounds.contains(dragPos)) {
                applyPreset(d.preset)
                done = true
            }

            is Dragged.Card -> {
                val keys = liveOrder.map { orderKey(currentControl, it) }
                order = keys + order.filter { it !in keys }
                runtime.setUiPref(PREF_ORDER + kindKey(currentControl), order.joinToString("\n"))
            }
        }
        if (done) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        cancelDrag()
    }

    val colors = MaterialTheme.colorScheme
    val actionsPane: @Composable () -> Unit = {
        LazyColumn(
            Modifier.fillMaxSize().onGloballyPositioned { c ->
                val b = c.boundsInRoot()
                if (b != actionsBounds) actionsBounds = b
            },
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (control.kind == TouchKind.STICK) {
                item(key = "presets") {
                    Row(
                        Modifier.animateItem().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (p in Bindings.StickPreset.entries) {
                            FilterChip(
                                selected = presetMatches(mappings, inputs, p),
                                onClick = { presets.getOrNull(p.ordinal)?.let { applyPreset(it) } },
                                label = { Text(p.label) },
                            )
                        }
                    }
                }
            }
            shownGroups.forEachIndexed { g, list ->
                // The touchpad has no button actions: only its surface ones.
                if (control.swipes && !(g == 0 && control.isTouchpadStandIn)) {
                    item(key = "header$g") {
                        Column(
                            Modifier.animateItem().padding(top = if (g == 0) 0.dp else 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                if (g == 0) "Button" else if (control.isTouchpadStandIn) "Touchpad" else "Touch surface",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.primary,
                            )
                            if (g == 1) {
                                Row(
                                    Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    for (n in 1..4) {
                                        FilterChip(
                                            selected = fingers == n,
                                            onClick = { fingers = n },
                                            label = { Text(if (n == 1) "1 finger" else n.toString() + " fingers") },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                items(list, key = { it.sources.first().toString() }) { input ->
                    val src = input.sources.first()
                    val d = dragged
                    val dragTarget = (d as? Dragged.Target)?.target
                    val fits = dragTarget == null || allows(allowedTargets(input), dragTarget)
                    DisposableEffect(src) { onDispose { cardBounds.remove(src) } }
                    ActionCard(
                        title = if (isSurface(input)) input.label else actionLabel(control, input),
                        targets = Bindings.targets(mappings, input),
                        selected = input == selected,
                        hover = dragTarget != null && fits && hovered == input,
                        blocked = !fits,
                        lifted = cardDrag?.input == input,
                        dragKey = src,
                        onBounds = { b -> if (cardBounds[src] != b) cardBounds[src] = b },
                        onChipBounds = { t, b -> chipBounds[src to t] = b },
                        onClick = { selectedSrc = src },
                        onRemove = { t -> remove(input, t) },
                        onDragStart = { at -> startOnCard(input, at) },
                        onDragMove = { moveDrag(it) },
                        onDragEnd = { endDrag() },
                        onDragCancel = { cancelDrag() },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    var query by remember { mutableStateOf("") }
    val tabs = remember(extras) { LibraryTab.entries.filter { it != LibraryTab.TOUCHPAD || extras.isNotEmpty() } }
    var tab by remember(control.id) {
        mutableStateOf(
            when {
                recent.isNotEmpty() -> LibraryTab.RECENT
                control.kind == TouchKind.STICK -> LibraryTab.PRESETS
                else -> LibraryTab.KEYBOARD
            },
        )
    }
    val libraryPane: @Composable () -> Unit = {
        val q = query.trim()
        val shownPresets = when {
            q.isNotEmpty() -> presets.filter { it.label.contains(q, ignoreCase = true) }
            tab == LibraryTab.PRESETS -> presets
            else -> emptyList()
        }
        val shownTargets = when {
            q.isNotEmpty() -> (Describe.allTargets + extras).distinct().filter {
                Describe.shortTarget(it).contains(q, ignoreCase = true) || Describe.target(it)
                    .contains(q, ignoreCase = true)
            }

            tab == LibraryTab.RECENT -> recent
            else -> tabTargets(tab, extras)
        }
        val current = selected?.let { Bindings.targets(mappings, it) }.orEmpty()
        Column(Modifier.fillMaxSize().background(colors.surfaceContainerLow).imePadding().padding(top = 8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search, e.g. W, LT, wheel") },
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("✕") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = false,
                    enabled = selected != null && allows(selectedAllowed, OutputTarget.Key(0x04)),
                    onClick = { capturing = true },
                    label = { Text("⌨ Press a key") },
                )
                if (q.isEmpty()) {
                    for (t in tabs) FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                }
            }
            Text(
                if (selected == null) "Long-press a target to drag it onto an action."
                else "Tap to add to " + actionTitle(control, selected) + " · long-press to drag",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            ) {
                if (shownPresets.isEmpty() && shownTargets.isEmpty()) {
                    Text(
                        when {
                            q.isNotEmpty() -> "Nothing matches \"" + q + "\""
                            tab == LibraryTab.RECENT -> "Targets you map show here."
                            else -> "Nothing here for this control."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.outline,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (p in shownPresets) {
                        LibraryChip(
                            text = "✦ " + p.label,
                            enabled = true,
                            mapped = control.kind == TouchKind.STICK &&
                                    Bindings.StickPreset.entries.getOrNull(presets.indexOf(p))
                                        ?.let { presetMatches(mappings, inputs, it) } == true,
                            preset = true,
                            dragKey = p.label,
                            onClick = { applyPreset(p) },
                            onDragStart = { at -> startDrag(Dragged.Preset(p), at) },
                            onDragMove = { moveDrag(it) },
                            onDragEnd = { endDrag() },
                            onDragCancel = { cancelDrag() },
                        )
                    }
                    for (t in shownTargets) {
                        LibraryChip(
                            text = Describe.shortTarget(t),
                            enabled = selected != null && allows(selectedAllowed, t),
                            mapped = t in current,
                            preset = false,
                            dragKey = t,
                            onClick = { selected?.let { pick(it, t) } },
                            onDragStart = { at -> startDrag(Dragged.Target(t, null), at) },
                            onDragMove = { moveDrag(it) },
                            onDragEnd = { endDrag() },
                            onDragCancel = { cancelDrag() },
                        )
                    }
                }
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = colors.surface) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
                Column(Modifier.fillMaxSize()) {
                    val compact = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                control.label.ifBlank { control.id },
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                controlKindName(control) + " · mapping",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        TextButton(onClick = onSettings, contentPadding = compact) { Text("Settings") }
                        TextButton(
                            onClick = { undoLast() },
                            enabled = undo.isNotEmpty(),
                            contentPadding = compact
                        ) { Text("↶ Undo") }
                        FilledTonalButton(onClick = onClose, contentPadding = compact) { Text("Done") }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val landscape = maxWidth > maxHeight
                        val total =
                            (if (landscape) constraints.maxWidth else constraints.maxHeight).toFloat().coerceAtLeast(1f)
                        val handle: @Composable () -> Unit = {
                            SplitHandle(
                                landscape = landscape,
                                onMove = { delta -> split = (split + delta / total).coerceIn(MIN_SPLIT, MAX_SPLIT) },
                                onEnd = { runtime.setUiPref(PREF_SPLIT, split.toString()) },
                            )
                        }
                        if (landscape) {
                            Row(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(split).fillMaxHeight()) { actionsPane() }
                                handle()
                                Box(Modifier.weight(1f - split).fillMaxHeight()) { libraryPane() }
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(split).fillMaxWidth()) { actionsPane() }
                                handle()
                                Box(Modifier.weight(1f - split).fillMaxWidth()) { libraryPane() }
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                val d = dragged
                if (d != null) {
                    val removing = d is Dragged.Target && d.from != null && hovered == null
                    val label = when (d) {
                        is Dragged.Target -> Describe.shortTarget(d.target)
                        is Dragged.Preset -> d.preset.label
                        is Dragged.Card -> actionTitle(control, d.input)
                    }
                    Surface(
                        Modifier.offset {
                            IntOffset(
                                (dragPos.x - origin.x).roundToInt() - 40.dp.roundToPx(),
                                (dragPos.y - origin.y).roundToInt() - 64.dp.roundToPx(),
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (removing) colors.errorContainer else colors.inverseSurface,
                        shadowElevation = 8.dp,
                    ) {
                        Text(
                            if (removing) "✕ Remove " + label else label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (removing) colors.onErrorContainer else colors.inverseOnSurface,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    hapticDraft?.let { (input, t) ->
        HapticTargetDialog(
            start = t,
            runtime = runtime,
            onOk = { configured ->
                hapticDraft = null
                assign(input, configured)
            },
            onCancel = { hapticDraft = null },
        )
    }
    if (capturing) {
        Dialog(onDismissRequest = { capturing = false }) {
            Surface(shape = RoundedCornerShape(20.dp), color = colors.surface, tonalElevation = 6.dp) {
                KeyCapturePanel(
                    runtime = runtime,
                    onKey = { t ->
                        capturing = false
                        selected?.let { assign(it, t) }
                    },
                    onCancel = { capturing = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(16.dp),
                )
            }
        }
    }
}

/** One action of the control: its title and mapped targets; [hover] a fitting drag over it, [blocked] one it can't take. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionCard(
    title: String,
    targets: List<OutputTarget>,
    selected: Boolean,
    hover: Boolean,
    blocked: Boolean,
    lifted: Boolean,
    dragKey: Any,
    onBounds: (Rect) -> Unit,
    onChipBounds: (OutputTarget, Rect) -> Unit,
    onClick: () -> Unit,
    onRemove: (OutputTarget) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDragMove)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    val empty = targets.isEmpty()
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .alpha(
                when {
                    lifted -> 0.4f
                    blocked -> 0.45f
                    empty && !selected && !hover -> 0.7f
                    else -> 1f
                },
            )
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .dragSource(dragKey, coords, { start(it) }, { move(it) }, { end() }, { cancel() }),
        shape = RoundedCornerShape(16.dp),
        color = when {
            hover -> colors.tertiaryContainer
            selected -> colors.primaryContainer
            else -> colors.surfaceContainerHigh
        },
        border = when {
            hover -> BorderStroke(2.dp, colors.tertiary)
            selected -> BorderStroke(1.dp, colors.primary)
            else -> null
        },
    ) {
        Column(
            Modifier.animateContentSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected && !hover) colors.onPrimaryContainer else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("⠿", color = colors.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
            }
            if (empty) {
                Text(
                    if (hover) "Release to map" else "Drop here",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (t in targets) {
                        Row(
                            Modifier
                                .onGloballyPositioned { onChipBounds(t, it.boundsInRoot()) }
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.secondaryContainer)
                                .heightIn(min = 32.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                Describe.shortTarget(t),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSecondaryContainer,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 10.dp, end = 2.dp),
                            )
                            Box(Modifier.size(32.dp).clickable { onRemove(t) }, contentAlignment = Alignment.Center) {
                                Text(
                                    "✕",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A target or preset of the library: tap to use it, long-press to drag it. */
@Composable
private fun LibraryChip(
    text: String,
    enabled: Boolean,
    mapped: Boolean,
    preset: Boolean,
    dragKey: Any,
    onClick: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDragMove)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    val (bg, fg) = when {
        mapped -> colors.primaryContainer to colors.onPrimaryContainer
        preset -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurface
    }
    Box(
        Modifier
            .alpha(if (enabled) 1f else 0.38f)
            .dragSource(dragKey, coords, { start(it) }, { move(it) }, { end() }, { cancel() })
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (mapped) "✓ " + text else text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
        )
    }
}

/** The bar between the two panes: drag it to resize them ([onMove] gets px along the split). */
@Composable
private fun SplitHandle(landscape: Boolean, onMove: (Float) -> Unit, onEnd: () -> Unit) {
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    Box(
        (if (landscape) Modifier.fillMaxHeight().width(20.dp) else Modifier.fillMaxWidth().height(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(landscape) {
                detectDragGestures(onDragEnd = { end() }, onDragCancel = { end() }) { change, amount ->
                    change.consume()
                    move(if (landscape) amount.x else amount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (landscape) 4.dp else 40.dp, if (landscape) 40.dp else 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}
