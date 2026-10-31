package com.oleroma.gyrophone.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.PhysicalDevice
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.Triggers
import com.oleroma.gyrophone.core.model.holdDirection
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlin.math.roundToInt
import kotlin.math.abs
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
/** The card order last arranged by hand, per control kind: Sort > Custom brings it back. */
private const val PREF_CUSTOM = "map.custom."
private val recentSerializer = ListSerializer(OutputTarget.serializer())
private val prefJson = Json { ignoreUnknownKeys = true }

/** Something being dragged on the mapping screen. */
private sealed interface Dragged {
    /** A target from the library ([from] null) or a chip of action [from]. */
    data class Target(val target: OutputTarget, val from: BindableInput?) : Dragged
    data class Preset(val preset: MapPreset) : Dragged

    /** An action card moved within its [group] (the one list: the control's actions, a swipe button's with the chosen fingers). */
    data class Card(val input: BindableInput, val group: Int) : Dragged

    /** Zone divider [k] of held-direction card [card] (zone 0's input), moved within its row. */
    data class Divider(val card: BindableInput, val k: Int) : Dragged
}

/**
 * One item in an action card's row, in reading order: a [Chip] of the edited set in its length
 * [Chip.zone], a [Divider] ending zone k - 1 (before zone k), the [End] divider after the last
 * zone (a held direction with mappings), and while dragging the [Landing] spot where the drag goes.
 */
private sealed interface RowItem {
    /** A mapping: [target] in length zone [zone], fired on [trigger] (zone 0 only; Hold elsewhere). */
    data class Chip(val zone: Int, val target: OutputTarget, val trigger: Trigger = Trigger.HOLD) : RowItem
    data class Divider(val k: Int) : RowItem
    data object End : RowItem
    data object Landing : RowItem
}

/** A ready-made mapping of one or more actions; [apply] rewrites the mapping list. */
private class MapPreset(val label: String, val apply: (List<Mapping>) -> List<Mapping>)

private enum class LibraryTab(val label: String) {
    RECENT("Recent"), PRESETS("Presets"), KEYBOARD("Keyboard"), MOUSE("Mouse"), GAMEPAD("Gamepad"),
    GYRO("Gyro"), PHONE("Phone"), TOUCHPAD("Touchpad"), LAYERS("Layers"), OTHER("Other"),
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
    LibraryTab.LAYERS -> Describe.layerTargets
    LibraryTab.OTHER -> Describe.otherTargets
    LibraryTab.RECENT, LibraryTab.PRESETS -> emptyList()
}

/** The targets [input]'s picker offers ([Describe.pickerGroups]). */
private fun allowedTargets(input: BindableInput): Set<OutputTarget> =
    Describe.pickerGroups(input.analog, input.swipe, input.extra, input.mouseMotion).flatMapTo(HashSet()) { it.second }

/** Whether [t] may be mapped to an input offering [allowed]: haptics always, variants of an offered kind too. */
private fun allows(allowed: Set<OutputTarget>, t: OutputTarget): Boolean {
    if (Describe.isHaptic(t) || t in allowed) return true
    return when (t) {
        is OutputTarget.PadAxis -> allowed.any { it is OutputTarget.PadAxis && it.axis == t.axis }
        is OutputTarget.MouseAxis -> allowed.any { it is OutputTarget.MouseAxis && it.motion == t.motion }
        is OutputTarget.Key -> allowed.any { it is OutputTarget.Key }
        OutputTarget.GyroHold -> OutputTarget.GyroPause in allowed
        // Layer actions, (Let go) and Nothing go where button outputs do.
        is OutputTarget.Layer, OutputTarget.LayerBack, OutputTarget.LetGo, OutputTarget.NoOutput -> allowed.any { it is OutputTarget.Key }
        else -> false
    }
}

private fun isSurface(input: BindableInput) = input.sources.firstOrNull() is InputSource.Surface

/**
 * A chip's text: its trigger's badge unless On touch ("Tap · Q"), its target, then marks for its
 * How and options: ¹ once, ⟲ toggle, ↻ cycle, ⏱ delays.
 */
private fun chipText(badge: String, target: OutputTarget, o: MapOptions?): String = buildString {
    if (badge.isNotEmpty()) append(badge).append(" · ")
    append(Describe.shortTarget(target))
    if (o != null) {
        if (o.mode.mark.isNotEmpty()) append(" ").append(o.mode.mark)
        if (o.cycle) append(" ↻")
        if (o.startDelayMs > 0 || o.endDelayMs > 0 || o.minHoldMs > 0) append(" ⏱")
    }
}

/** Card title in messages: surface actions carry their finger count. */
private fun actionTitle(control: TouchControl, input: BindableInput): String {
    val s = input.sources.firstOrNull()
    return if (s is InputSource.Surface) s.fingers.toString() + "f " + input.label else actionLabel(control, input)
}

/** Remembered card orders are per control kind. */
private fun kindKey(c: TouchControl) = when {
    c.isTouchpadStandIn -> "touchpad"
    c.physicalDevice != null -> "device." + if (c.kind == TouchKind.STICK) "stick" else "button"
    c.kind == TouchKind.STICK -> "stick"
    c.swipes -> "swipe"
    else -> "button"
}

/** A card's key in the remembered order: its label, surface ones (and a swipe button's hold) without the finger count. */
private fun orderKey(control: TouchControl, input: BindableInput): String =
    when {
        isSurface(input) -> "f:" + input.label.replace(Regex("^[0-9]f "), "")
        control.swipes && input.sources.first() is InputSource.Touch -> "f:" + SurfaceInput.HOLD.label
        else -> actionLabel(control, input)
    }

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
    if (press != null && !press.mouseMotion) {
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
    // Sets roll and pitch only; the other gyro axes stay.
    add(MapPreset("Gyro mouse") { m ->
        Bindings.gyroMouseMappings(control.id).fold(m) { r, g ->
            val input = Bindings.gyroInputs(control.id).first { it.sources.single() == g.source }
            Bindings.setTargets(r, input, listOf(g.target))
        }
    })
}

/**
 * Starts a drag on this element, after a long press or, for a handle ([longPress] false), at once;
 * [onStart] gets the root position. The screen follows the finger from there, so the drag goes on
 * when this element scrolls out of its list or moves under the finger.
 */
private fun Modifier.dragSource(
    key: Any?,
    coords: Array<LayoutCoordinates?>,
    longPress: Boolean = true,
    onStart: (Offset) -> Unit,
): Modifier = this
    .onGloballyPositioned { coords[0] = it }
    .pointerInput(key, longPress) {
        val begin = { p: Offset -> coords[0]?.let { onStart(it.localToRoot(p)) }; Unit }
        if (longPress) {
            detectDragGesturesAfterLongPress(onDragStart = begin) { change, _ -> change.consume() }
        } else {
            detectDragGestures(onDragStart = begin) { change, _ -> change.consume() }
        }
    }

/**
 * Stands for the workspace touchpad in the control screens: a tap or long press on empty space
 * in edit mode opens them with it. Its surface inputs have the touchpad's id ("").
 */
internal val TOUCHPAD_STAND_IN = TouchControl("", TouchKind.BUTTON, "Workspace", 0f, 0f, swipe = true)

internal val TouchControl.isTouchpadStandIn: Boolean get() = id.isEmpty()

private const val PHYSICAL_PREFIX = "dev:"

/**
 * Stands for control [id] of a physical [device] in the control screens (a tap on it in the device
 * screens), named [label]; its inputs are given to [ControlMappingScreen] as they are.
 */
internal fun physicalStandIn(device: PhysicalDevice, id: String, label: String, stick: Boolean) =
    TouchControl(PHYSICAL_PREFIX + device.name + ":" + id, if (stick) TouchKind.STICK else TouchKind.BUTTON, label, 0f, 0f)

/** The physical device a [physicalStandIn] stands in for; null for touch controls. */
internal val TouchControl.physicalDevice: PhysicalDevice?
    get() = if (!id.startsWith(PHYSICAL_PREFIX)) null
    else PhysicalDevice.entries.firstOrNull { it.name == id.removePrefix(PHYSICAL_PREFIX).substringBefore(':') }

/** "Stick", "Touchpad button", "Button", "Workspace" (the touchpad), or a physical device's "Controller", "Keyboard", "Mouse". */
internal fun controlKindName(c: TouchControl): String = when {
    c.isTouchpadStandIn -> "Workspace"
    c.physicalDevice != null -> c.physicalDevice!!.label
    c.kind == TouchKind.STICK -> "Stick"
    c.swipes -> "Touchpad button"
    else -> "Button"
}

/**
 * Full-screen mapping editor of a touch control (a tap on it in edit mode), or of the touchpad
 * ([TOUCHPAD_STAND_IN], a tap on empty space). Its actions (cards
 * with their targets as chips) and a library of targets share the screen, split by a draggable
 * divider: tap a card to select it and a target to add it there, or long-press and drag a target
 * onto a card, or a chip to another card (or back onto the library to remove it). A card is
 * reordered by its ⠿ handle. Near the pane's top or bottom edge a drag scrolls the cards.
 * Every change goes to [onMappingsChange] and can be undone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ControlMappingScreen(
    control: TouchControl,
    mappings: List<Mapping>,
    runtime: GamepadRuntime,
    onMappingsChange: (List<Mapping>) -> Unit,
    /** The control's settings; null = none (a physical control). */
    onSettings: (() -> Unit)?,
    onClose: () -> Unit,
    /** The workspace's mapping sets, shown in the top bar. */
    sets: List<MappingSet> = emptyList(),
    /** The set being edited (0 = Base): new mappings go into it, other sets' show greyed. */
    editSet: Int = MappingSet.BASE,
    onEditSet: (Int) -> Unit = {},
    /** The sets changed: added, renamed, or [removed] (with its mappings and the actions using it). */
    onSetsChange: ((sets: List<MappingSet>, removed: Int?) -> Unit)? = null,
    /** The surface's settings in effect, for the length zones of its held directions; null = no zones. */
    zoneSettings: TouchpadSettings? = null,
    /** Its zones changed (shared, per direction or null), from a zone divider's settings. */
    onZoneSettings: ((List<HoldZone>, List<List<HoldZone>>?) -> Unit)? = null,
    /** A physical control's inputs ([physicalStandIn]): its cards, instead of the touch control's. */
    physicalInputs: List<BindableInput>? = null,
    /** The actions' own settings (their ⚙) and the shared ones (Settings); null = none. */
    settings: MappingSettings? = null,
    /**
     * What the level above maps each of the control's actions to in the edited set (in the control's
     * own sources): an action mapped in neither the edited set nor Base does that, shown faded.
     */
    inherited: List<Mapping> = emptyList(),
    /** The level above, as the faded chips name it ("Workspace", "Defaults"). */
    parentName: String = "",
    /** An action's long-press time and double-tap window (ms), for the timing line under its card; null = none shown. */
    timing: (BindableInput) -> Pair<Int, Int>? = { null },
) {
    // The edited set seen as Base, so the Base-only Bindings helpers edit it.
    val view = remember(mappings, editSet) { Bindings.viewSet(mappings, editSet) }
    val parentView = remember(inherited) { inherited.map { it.copy(set = MappingSet.BASE) }.distinct() }

    /** [input] firing on [t] (itself for Hold). */
    fun trig(input: BindableInput, t: Trigger): BindableInput = Bindings.triggerInput(input, t) ?: input

    /** [input]'s inputs: one per trigger it offers, Hold first. */
    fun trigInputs(input: BindableInput): List<Pair<Trigger, BindableInput>> =
        Bindings.triggersOf(input).map { it to trig(input, it) }

    /** [input]'s zone [z] (itself for 0). */
    fun zin(input: BindableInput, z: Int): BindableInput = if (z == 0) input else Bindings.zoneInput(input, z)

    /** What [input]'s chips call trigger [t] ("Quick swipe"...). */
    fun trigLabel(input: BindableInput, t: Trigger): String = Triggers.label(input.sources.first(), t)

    /** What the level above maps [input] to (cleared ones left out), with their triggers. */
    fun parentOf(input: BindableInput): List<Pair<Trigger, OutputTarget>> = trigInputs(input).flatMap { (t, ti) ->
        Bindings.targets(parentView, ti).filter { it != OutputTarget.NoOutput }.map { t to it }
    }
    // Drag callbacks outlive a composition: everything they read goes through state.
    val currentMappings by rememberUpdatedState(view)
    val allMappings by rememberUpdatedState(mappings)
    val currentSet by rememberUpdatedState(editSet)
    val currentControl by rememberUpdatedState(control)
    val emit by rememberUpdatedState(onMappingsChange)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val inputs = remember(control, physicalInputs) {
        when {
            physicalInputs != null -> physicalInputs
            control.isTouchpadStandIn -> (1..4).flatMap { Bindings.surfaceInputs(it) } + Bindings.gyroInputs() + Bindings.motionInputs()
            else -> Bindings.touchInputs(control)
        }
    }
    val presets = remember(control, inputs) { presetsFor(control, inputs) }
    val extras = remember(inputs) { inputs.flatMap { it.extra }.distinct() }

    var fingers by remember(control.id) { mutableIntStateOf(1) }
    var order by remember(control.id) {
        mutableStateOf(runtime.uiPref(PREF_ORDER + kindKey(control))?.split("\n").orEmpty())
    }
    var custom by remember(control.id) {
        mutableStateOf(runtime.uiPref(PREF_CUSTOM + kindKey(control))?.split("\n").orEmpty())
    }
    var sortMenu by remember { mutableStateOf(false) }
    var confirmDefault by remember { mutableStateOf(false) }

    /** Saves [next] as the card order of this kind of control (and, [byHand], as its custom order). */
    fun saveOrder(next: List<String>, byHand: Boolean = false) {
        order = next
        runtime.setUiPref(PREF_ORDER + kindKey(control), next.joinToString("\n"))
        if (byHand) {
            custom = next
            runtime.setUiPref(PREF_CUSTOM + kindKey(control), next.joinToString("\n"))
        }
    }
    // A swipe button: one list per finger count (hold, taps, moves, swipes...); the button's own
    // outside and area (any fingers) go with one finger's, beside its hold.
    val groups = listOf(
        sorted(
            control,
            (if (!control.swipes) inputs
            else if (fingers == 0) Bindings.gyroInputs(control.id) + Bindings.motionInputs(control.id)
            else Bindings.swipeInputs(control, fingers) +
                    if (fingers == 1) inputs.filter { it.sources.first() is InputSource.TouchArea } else emptyList())
                .filterNot(Bindings::isTriggerOf),
            order,
        ),
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
    var layerDraft by remember { mutableStateOf<Pair<BindableInput, OutputTarget.Layer>?>(null) }
    var setMenu by remember { mutableStateOf<MappingSet?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var split by remember {
        mutableFloatStateOf(runtime.uiPref(PREF_SPLIT)?.toFloatOrNull()?.coerceIn(MIN_SPLIT, MAX_SPLIT) ?: 0.5f)
    }

    fun undoLast() {
        val prev = undo.removeLastOrNull() ?: return
        emit(prev)
    }

    /** [next] is the edited set's view; undo keeps every set as it was. */
    fun change(next: List<Mapping>, message: String) {
        val prev = allMappings
        // A zone left empty closes up: the ones after it move down.
        // Mapping something to a cleared action replaces the clear.
        val all = Inheritance.tidy(Bindings.compactZones(Bindings.viewSet(next, currentSet)))
        if (all == prev) return
        undo.add(prev)
        if (undo.size > MAX_UNDO) undo.removeAt(0)
        emit(all)
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

    /** Adds [t] to [input]; a layer action is set up first (a buzz is added as it is, its chip opens its settings). */
    fun pick(input: BindableInput, t: OutputTarget) {
        when {
            t is OutputTarget.Layer && t.set < 0 -> layerDraft = input to t
            else -> assign(input, t)
        }
    }

    fun remove(input: BindableInput, t: OutputTarget) = change(
        Bindings.removeTarget(currentMappings, input, t),
        "Removed " + Describe.shortTarget(t) + " from " + actionTitle(currentControl, input),
    )

    fun applyPreset(p: MapPreset) = change(p.apply(currentMappings), "Applied " + p.label)

    /** [input] maps nothing here, so it does not do what the level above maps it to. */
    fun clear(input: BindableInput) = change(
        trigInputs(input).fold(currentMappings) { m, (t, ti) ->
            val blocks = t == Trigger.HOLD || Bindings.targets(parentView, ti).isNotEmpty()
            Bindings.setTargets(m, ti, if (blocks) listOf(OutputTarget.NoOutput) else emptyList())
        },
        "Cleared " + actionTitle(currentControl, input),
    )

    /** [input] follows the level above again: its own mappings here go. */
    fun follow(input: BindableInput) = change(
        trigInputs(input).fold(currentMappings) { m, (_, ti) -> Bindings.setTargets(m, ti, emptyList()) },
        actionTitle(currentControl, input) + " follows " + parentName,
    )

    /** Every action the level above maps follows it again. */
    fun followAll() {
        val overridden = inputs.filter { Bindings.targets(parentView, it).isNotEmpty() && Bindings.targets(currentMappings, it).isNotEmpty() }
        change(
            overridden.fold(currentMappings) { m, input -> Bindings.setTargets(m, input, emptyList()) },
            "Every action follows " + parentName,
        )
    }

    /** Every action does nothing: cleared where the level above maps it, its own mappings gone. */
    fun clearAll() = change(
        inputs.fold(currentMappings) { m, input ->
            Bindings.setTargets(m, input, if (Bindings.targets(parentView, input).isNotEmpty()) listOf(OutputTarget.NoOutput) else emptyList())
        },
        "Cleared every action",
    )

    // Drag & drop geometry, in root coordinates.
    val cardBounds = remember { mutableStateMapOf<InputSource, Rect>() }
    // Each card's row items (chips and dividers), by the card's source; read only by drag code.
    val itemBounds = remember { HashMap<Pair<InputSource, RowItem>, Rect>() }
    // Each card's sideways scrolling row and where it is, for scrolling it while dragging.
    val rowScrolls = remember { HashMap<InputSource, ScrollState>() }
    val rowBounds = remember { HashMap<InputSource, Rect>() }
    val zonesWork = zoneSettings != null && !(zoneSettings.dragStart && zoneSettings.holdMode == HoldMode.FROM_START)
    val zonesNow by rememberUpdatedState(zonesWork)
    var actionsBounds by remember { mutableStateOf(Rect.Zero) }
    var libraryBounds by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    fun cardAt(p: Offset): BindableInput? {
        if (!actionsBounds.contains(p)) return null
        return shownNow.firstOrNull { cardBounds[it.sources.first()]?.contains(p) == true }
    }

    /** How many zones [input] (a held direction) has, in any set (1 for anything else). */
    fun zonesOf(input: BindableInput): Int {
        val src = input.sources.first() as? InputSource.Surface ?: return 1
        if (src.input.holdDirection == null) return 1
        return 1 + (allMappings.maxOfOrNull { m ->
            val s = m.source as? InputSource.Surface
            if (s != null && s.copy(zone = 0, on = Trigger.HOLD) == src) s.zone else 0
        } ?: 0)
    }

    /** A held direction offers a next zone: something is mapped and there is room. */
    fun addsZone(input: BindableInput): Boolean {
        val src = input.sources.first() as? InputSource.Surface ?: return false
        if (!zonesNow || src.input.holdDirection == null) return false
        return zonesOf(input) < TouchpadSettings.MAX_HOLD_ZONES &&
                allMappings.any { (it.source as? InputSource.Surface)?.copy(zone = 0, on = Trigger.HOLD) == src }
    }

    /** A held direction with something mapped (any set): its row ends in a divider that opens the last zone's settings. */
    fun hasEnd(input: BindableInput): Boolean {
        val src = input.sources.first() as? InputSource.Surface ?: return false
        if (zoneSettings == null || src.input.holdDirection == null) return false
        return allMappings.any { (it.source as? InputSource.Surface)?.copy(zone = 0, on = Trigger.HOLD) == src }
    }

    /** [input]'s row as mapped: each zone's chips of the edited set, divided, then the end divider. */
    fun rowOf(input: BindableInput): List<RowItem> = buildList {
        for (z in 0 until zonesOf(input)) {
            if (z > 0) add(RowItem.Divider(z))
            for ((tr, ti) in trigInputs(zin(input, z))) for (t in Bindings.targets(currentMappings, ti)) add(RowItem.Chip(z, t, tr))
        }
        if (hasEnd(input)) add(RowItem.End)
    }

    /** The dragged chip or divider, if it is in [input]'s row: left out of it while carried. */
    fun carried(input: BindableInput): RowItem? = when (val d = dragged) {
        is Dragged.Target -> d.from?.let { f ->
            val s0 = f.sources.first()
            val s = s0 as? InputSource.Surface
            val zone = s?.zone ?: 0
            val tr = Triggers.of(s0)
            val base = Triggers.base(s0).let { b -> if (b is InputSource.Surface) b.copy(zone = 0) else b }
            if (base == input.sources.first()) RowItem.Chip(zone, d.target, tr) else null
        }

        is Dragged.Divider -> if (d.card.sources.first() == input.sources.first()) RowItem.Divider(d.k) else null
        else -> null
    }

    /** Where a chip or divider being dragged lands: a card and the index in its row (without the carried item). */
    var landing by remember { mutableStateOf<Pair<BindableInput, Int>?>(null) }

    /** Zone of index [at] in [row]: how many dividers come before it. */
    fun zoneIn(row: List<RowItem>, at: Int) = row.take(at).count { it is RowItem.Divider || it is RowItem.End }

    /**
     * Updates [landing] from the finger: in the card under it, after every row item whose centre
     * is left of it (scrolled away ones too). Past
     * the end divider is a new zone, while there is room for one.
     */
    fun place() {
        val d = dragged
        val card = when (d) {
            is Dragged.Target -> cardAt(dragPos)?.takeIf { allows(allowedTargets(it), d.target) }
            is Dragged.Divider -> cardAt(dragPos)?.takeIf { it.sources.first() == d.card.sources.first() }
            else -> null
        }
        if (card == null) {
            landing = null
            return
        }
        val src = card.sources.first()
        val c = carried(card)
        val row = rowOf(card).filter { it != c }
        val p = dragPos
        var at = row.count { item ->
            val r = itemBounds[src to item] ?: return@count false
            r.center.x < p.x
        }
        val end = row.indexOf(RowItem.End)
        if (end >= 0) at = at.coerceAtMost(if (d is Dragged.Target && addsZone(card)) end + 1 else end)
        val next = card to at
        if (next != landing) {
            if (landing != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            landing = next
        }
    }

    /** [card]'s row as it shows now: without the carried item, with the landing spot. */
    fun liveRow(card: BindableInput): List<RowItem> {
        val c = carried(card)
        val row = rowOf(card).filter { it != c }
        val l = landing
        if (l == null || l.first.sources.first() != card.sources.first()) return row
        return row.toMutableList().apply { add(l.second.coerceIn(0, size), RowItem.Landing) }
    }

    /**
     * [m] (the edited set's view) with [card]'s chips as [row] has them: each in the zone of the
     * dividers before it, in its order. Zones left empty close up in [change].
     */
    fun withRow(m: List<Mapping>, card: BindableInput, row: List<RowItem>): List<Mapping> {
        val lists = mutableListOf(mutableListOf<RowItem.Chip>())
        for (item in row) when (item) {
            is RowItem.Chip -> lists.last().add(item)
            is RowItem.Divider, RowItem.End -> lists.add(mutableListOf())
            RowItem.Landing -> {}
        }
        var out = m
        for (z in 0 until maxOf(lists.size, zonesOf(card)).coerceAtMost(TouchpadSettings.MAX_HOLD_ZONES)) {
            val chips = lists.getOrNull(z).orEmpty()
            // Each trigger's chips keep their options; a chip moved within the card keeps its trigger.
            for ((tr, ti) in trigInputs(zin(card, z))) {
                val was = out
                out = Bindings.setTargets(out, ti, chips.filter { it.trigger == tr }.map { it.target }) { t ->
                    Bindings.options(was, ti, t)
                }
            }
        }
        return out
    }

    /** The chip whose settings are open, with its card. */
    var chipDialog by remember { mutableStateOf<Pair<BindableInput, RowItem.Chip>?>(null) }

    /**
     * When [input]'s triggers fire, for those it maps (Hold left out): "Tap: at lift before 400 ms ·
     * Long press: from 400 ms"; null when it maps none or has no timings.
     */
    fun timingLine(input: BindableInput): String? {
        val tis = trigInputs(input).filter { (t, ti) ->
            t != Trigger.HOLD && Bindings.targets(view, ti).any { it != OutputTarget.NoOutput }
        }
        if (tis.isEmpty()) return null
        val (long, double) = timing(input) ?: return null
        val doubleTargets = Bindings.targets(view, trig(input, Trigger.DOUBLE_TAP)).toSet()
        val waits = !Triggers.isSwipe(input.sources.first()) && doubleTargets.isNotEmpty() && doubleTargets != Bindings.targets(view, trig(input, Trigger.TAP)).toSet()
        val swipe = Triggers.isSwipe(input.sources.first())
        return tis.joinToString(" · ") { (t, _) ->
            trigLabel(input, t) + ": " + when (t) {
                Trigger.HOLD, Trigger.PRESS -> ""
                Trigger.TAP -> if (swipe) "as you swipe" else "at lift before $long ms" + if (waits) ", after a $double ms wait" else ""
                Trigger.LONG_PRESS -> "from $long ms"
                Trigger.RELEASE -> "every lift"
                Trigger.DOUBLE_TAP -> (if (swipe) "2nd swipe" else "2nd touch") + " within $double ms"
            }
        }
    }

    val hovered by remember { derivedStateOf { if (dragged is Dragged.Target) cardAt(dragPos) else null } }
    var zoneDialog by remember { mutableStateOf<Pair<BindableInput, Int?>?>(null) }
    var actionDialog by remember { mutableStateOf<BindableInput?>(null) }
    var sharedOpen by remember { mutableStateOf(false) }

    /** What [input]'s ⚙ opens: its own settings, or a held direction's zones; null = no ⚙. */
    fun gearOf(input: BindableInput): (() -> Unit)? {
        if (settings?.action?.invoke(input) != null) return { actionDialog = input }
        if (trigInputs(input).any { (t, ti) ->
                Bindings.targets(view, ti).any { it is OutputTarget.Key } ||
                    (t != Trigger.HOLD && Bindings.targets(view, ti).isNotEmpty() && settings?.action?.invoke(ti) != null)
            }) return { actionDialog = input }
        val src = input.sources.first() as? InputSource.Surface
        if (src?.input?.holdDirection != null && onZoneSettings != null && zonesNow && zonesOf(input) > 1) {
            return { zoneDialog = input to null }
        }
        return null
    }

    fun startDrag(d: Dragged, at: Offset) {
        dragged = d
        dragPos = at
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** The ⠿ handle of a card: drags the card within its group. */
    fun startCardDrag(input: BindableInput, at: Offset) {
        val src = input.sources.first()
        val g = groupsNow.indexOfFirst { list -> list.any { it.sources.first() == src } }
        if (g < 0) return
        liveOrder = groupsNow[g]
        // The card being moved is the selected one.
        selectedSrc = src
        startDrag(Dragged.Card(input, g), at)
    }

    /**
     * Puts the dragged card after every card of its group whose centre is above the finger. Only
     * cards on screen count: one scrolled away stays on its side until auto-scroll brings it back.
     */
    fun reorder() {
        val d = dragged as? Dragged.Card ?: return
        val at = liveOrder.indexOf(d.input)
        if (at < 0) return
        val others = liveOrder.filter { it != d.input }
        val to = others.withIndex().count { (i, o) ->
            val c = cardBounds[o.sources.first()]?.takeIf { b -> b.overlaps(actionsBounds) }?.center?.y
            if (c == null) i < at else c < dragPos.y
        }
        if (to == at) return
        // Keeps the list where it is: by default it follows a moved first visible card.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        liveOrder = others.toMutableList().apply { add(to, d.input) }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun moveDrag(to: Offset) {
        dragPos = to
        reorder()
        place()
    }

    fun cancelDrag() {
        dragged = null
        landing = null
        liveOrder = emptyList()
    }

    fun endDrag() {
        val d = dragged ?: return
        place()
        val l = landing
        var done = false
        when (d) {
            is Dragged.Target -> {
                val from = d.from
                if (l != null) {
                    val (card, at) = l
                    // Where something lands is the selected card from then on.
                    selectedSrc = card.sources.first()
                    val c = carried(card)
                    val row = rowOf(card).filter { it != c }.toMutableList()
                    val i = at.coerceIn(0, row.size)
                    val zone = zoneIn(row, i)
                    val zi = if (zone == 0) card else Bindings.zoneInput(card, zone)
                    if (from == null && (d.target as? OutputTarget.Layer)?.set?.let { it < 0 } == true) {
                        pick(zi, d.target)
                    } else {
                        if (from == null) addRecent(d.target)
                        // A chip keeps its trigger where the card offers it; a new one is Hold.
                        val tr = when {
                            c != null -> (c as? RowItem.Chip)?.trigger ?: Trigger.HOLD
                            from != null -> Triggers.of(from.sources.first())
                            else -> Trigger.HOLD
                        }.takeIf { it in Bindings.triggersOf(zi) } ?: Trigger.HOLD
                        row.add(i, RowItem.Chip(zone, d.target, tr))
                        val base = if (from == null || c != null) currentMappings else Bindings.removeTarget(currentMappings, from, d.target)
                        // Already in the zone it lands in: just moved there, not twice.
                        val cleaned = if (c == null) row.filterIndexed { j, it ->
                            j == i || !(it is RowItem.Chip && it.target == d.target && it.trigger == tr && zoneIn(row, j) == zone)
                        } else row
                        change(
                            withRow(base, card, cleaned),
                            (if (from == null) "Mapped " else "Moved ") + Describe.shortTarget(d.target) + " to " + actionTitle(currentControl, zi),
                        )
                    }
                    done = true
                } else if (from != null && libraryBounds.contains(dragPos)) {
                    // Dropped back on the library it goes; anywhere else it stays.
                    remove(from, d.target)
                    done = true
                }
            }

            is Dragged.Divider -> if (l != null) {
                val (card, at) = l
                val row = rowOf(card).filter { it != carried(card) }.toMutableList()
                row.add(at.coerceIn(0, row.size), RowItem.Divider(d.k))
                change(withRow(currentMappings, card, row), "Moved a zone divider of " + actionTitle(currentControl, card))
                done = true
            }

            is Dragged.Preset -> if (actionsBounds.contains(dragPos)) {
                applyPreset(d.preset)
                done = true
            }

            is Dragged.Card -> {
                val keys = liveOrder.map { orderKey(currentControl, it) }
                saveOrder(keys + order.filter { it !in keys }, byHand = true)
            }
        }
        if (done) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        cancelDrag()
    }

    val trackMove by rememberUpdatedState<(Offset) -> Unit> { moveDrag(it) }
    val trackEnd by rememberUpdatedState<() -> Unit> { endDrag() }

    // While dragging near the top or bottom of the actions pane, scroll it; faster closer to the edge.
    // A card may be pulled past the edge (full speed); targets and chips scroll only inside the pane.
    val dragging = dragged != null
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val maxStep = with(density) { 18.dp.toPx() }
        val rowEdge = with(density) { 48.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val b = actionsBounds
            val p = dragPos
            val card = dragged is Dragged.Card
            if (p.x !in b.left..b.right || (!card && !b.contains(p))) continue
            val f = when {
                p.y < b.top + edge -> -((b.top + edge - p.y) / edge).coerceAtMost(1f)
                p.y > b.bottom - edge -> ((p.y - (b.bottom - edge)) / edge).coerceAtMost(1f)
                else -> 0f
            }
            if (f != 0f) listState.scrollBy(f * abs(f) * maxStep)
            // Near the left or right end of the row it lands in, scroll that row.
            landing?.first?.sources?.first()?.let { src ->
                val rb = rowBounds[src] ?: return@let
                val sc = rowScrolls[src] ?: return@let
                val h = when {
                    p.x < rb.left + rowEdge -> -((rb.left + rowEdge - p.x) / rowEdge).coerceAtMost(1f)
                    p.x > rb.right - rowEdge -> ((p.x - (rb.right - rowEdge)) / rowEdge).coerceAtMost(1f)
                    else -> 0f
                }
                if (h != 0f) sc.scrollBy(h * abs(h) * maxStep)
            }
            reorder()
            place()
        }
    }

    val colors = MaterialTheme.colorScheme
    val actionsPane: @Composable () -> Unit = {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().onGloballyPositioned { c ->
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
                                selected = presetMatches(view, inputs, p),
                                onClick = { presets.getOrNull(p.ordinal)?.let { applyPreset(it) } },
                                label = { Text(p.label) },
                            )
                        }
                    }
                }
            }
            shownGroups.forEachIndexed { g, list ->
                if (control.swipes) {
                    item(key = "header$g") {
                        Column(
                            Modifier.animateItem().padding(top = if (g == 0) 0.dp else 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
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
                                if (physicalInputs == null) {
                                    FilterChip(selected = fingers == 0, onClick = { fingers = 0 }, label = { Text("Motion") })
                                }
                            }
                            if (fingers == 0) {
                                Text(
                                    if (control.isTouchpadStandIn) {
                                        "Gyro: what turning the phone does while gyro is on (roll and pitch move the mouse unless " +
                                                "mapped otherwise; ⚙ sets an axis's own sensitivity). Tilt: how far it leans " +
                                                "(sideways from upright, forward and back from where it was when tilt started); full at 30°."
                                    } else {
                                        "While this is pressed: a gyro axis it maps does that instead of what the workspace maps it to; " +
                                                "its tilt and shake work only meanwhile."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                items(list, key = { it.sources.first().toString() }) { input ->
                    val src = input.sources.first()
                    val d = dragged
                    val dragTarget = (d as? Dragged.Target)?.target
                    val fits = dragTarget == null || allows(allowedTargets(input), dragTarget)
                    val zoneCount = zonesOf(input)
                    val zoneInputs = List(zoneCount) { z -> if (z == 0) input else Bindings.zoneInput(input, z) }
                    val row = liveRow(input)
                    val l = landing
                    val landsHere = l != null && l.first.sources.first() == src
                    val rowScroll = rememberScrollState()
                    rowScrolls[src] = rowScroll
                    DisposableEffect(src) {
                        onDispose {
                            cardBounds.remove(src)
                            itemBounds.keys.removeAll { it.first == src }
                            rowScrolls.remove(src)
                            rowBounds.remove(src)
                        }
                    }
                    val tis = trigInputs(input)
                    val own = tis.flatMap { (_, ti) -> Bindings.targets(view, ti) }
                    val parent = parentOf(input)
                    // Mapped in Base, a layer's action does that: the level above shows only where Base maps nothing.
                    val ownBase = editSet != MappingSet.BASE && tis.any { (_, ti) -> Bindings.targets(mappings, ti).isNotEmpty() }
                    ActionCard(
                        title = when {
                            isSurface(input) && (src as InputSource.Surface).input == SurfaceInput.HOLD -> "Touch"
                            isSurface(input) -> input.label
                            else -> actionLabel(control, input)
                        },
                        row = row,
                        inherited = if (own.isEmpty() && !ownBase) parent else emptyList(),
                        parentName = parentName,
                        onClear = if (parent.isNotEmpty() && !(own.isNotEmpty() && own.all { it == OutputTarget.NoOutput })) ({ clear(input) }) else null,
                        onFollow = if (parent.isNotEmpty() && own.isNotEmpty()) ({ follow(input) }) else null,
                        others = zoneInputs.mapIndexed { z, zi ->
                            (listOf(MappingSet.BASE) + sets.map { it.id }).filter { it != editSet }.flatMap { n ->
                                val inSet = Bindings.viewSet(mappings, n)
                                trigInputs(zi).flatMap { (tr, ti) -> Bindings.targets(inSet, ti).map { Triple(n, it, tr) } }
                            }
                        },
                        optionsOf = { chip -> Bindings.options(view, trig(zin(input, chip.zone), chip.trigger), chip.target) },
                        badge = { tr -> Triggers.badge(src, tr) },
                        timing = timingLine(input),
                        setOrder = sets.map { it.id },
                        setId = editSet,
                        onOther = onEditSet,
                        // Only one card stands out: while a target is over another card, that one does.
                        selected = input == selected && hovered.let { h ->
                            h == null || h == input || dragTarget == null || !allows(allowedTargets(h), dragTarget)
                        },
                        hover = dragTarget != null && fits && hovered == input,
                        landingLabel = when (d) {
                            is Dragged.Target -> Describe.shortTarget(d.target)
                            else -> null
                        },
                        landingZone = if (landsHere && (zoneCount > 1 || row.any { it == RowItem.End })) zoneIn(row, row.indexOf(RowItem.Landing)) else -1,
                        blocked = !fits,
                        lifted = cardDrag?.input == input,
                        addZone = addsZone(input),
                        note = if (zoneCount > 1 && !zonesWork) {
                            if (zoneSettings == null) null else "Drag start point is on, so only zone 1 works."
                        } else null,
                        onBounds = { b -> if (cardBounds[src] != b) cardBounds[src] = b },
                        rowScroll = rowScroll,
                        onRowBounds = { b -> rowBounds[src] = b },
                        onItemBounds = { item, b -> itemBounds[src to item] = b },
                        onDivider = { k -> if (onZoneSettings != null) zoneDialog = input to k - 1 },
                        onGear = gearOf(input),
                        onDividerDrag = { k, at -> selectedSrc = src; startDrag(Dragged.Divider(input, k), at) },
                        onClick = { selectedSrc = src },
                        onChipDrag = { chip, at ->
                            selectedSrc = src
                            val from = trig(zoneInputs[chip.zone], chip.trigger)
                            startDrag(Dragged.Target(chip.target, from), at)
                        },
                        onChipClick = { chip -> selectedSrc = src; chipDialog = input to chip },
                        onHandleDrag = { at -> startCardDrag(input, at) },
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
    val chipCarried = (dragged as? Dragged.Target)?.from != null
    val overLibrary = chipCarried && libraryBounds.contains(dragPos)
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
        val current = selected?.let { Bindings.targets(view, it) }.orEmpty()
        Box(Modifier.fillMaxSize().onGloballyPositioned { c ->
            val b = c.boundsInRoot()
            if (b != libraryBounds) libraryBounds = b
        }) {
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
                                            ?.let { presetMatches(view, inputs, it) } == true,
                                preset = true,
                                dragging = (dragged as? Dragged.Preset)?.preset == p,
                                dragKey = p.label,
                                onClick = { applyPreset(p) },
                                onDragStart = { at -> startDrag(Dragged.Preset(p), at) },
                            )
                        }
                        for (t in shownTargets) {
                            LibraryChip(
                                text = Describe.shortTarget(t),
                                enabled = selected != null && allows(selectedAllowed, t),
                                mapped = t in current,
                                preset = false,
                                dragging = (dragged as? Dragged.Target)?.let { it.from == null && it.target == t } == true,
                                dragKey = t,
                                onClick = { selected?.let { pick(it, t) } },
                                onDragStart = { at -> startDrag(Dragged.Target(t, null), at) },
                            )
                        }
                    }
                }
            }
            // A chip carried from an action: dropped here, it is removed.
            if (chipCarried) {
                Box(
                    Modifier.fillMaxSize().background(
                        if (overLibrary) colors.errorContainer.copy(alpha = 0.92f) else colors.surfaceContainerLow.copy(alpha = 0.85f),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "🗑  🗑  🗑",
                            style = MaterialTheme.typography.displaySmall,
                            color = if (overLibrary) colors.onErrorContainer else colors.outline,
                        )
                        Text(
                            if (overLibrary) "Release to remove" else "Drop here to remove",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (overLibrary) colors.onErrorContainer else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = colors.surface) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .pointerInput(Unit) {
                        // Once a source starts a drag, the finger is followed here, before the panes see it.
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                if (dragged == null) continue
                                val c = e.changes.firstOrNull { it.pressed }
                                if (c == null) {
                                    trackEnd()
                                    continue
                                }
                                e.changes.forEach { it.consume() }
                                trackMove(origin + c.position)
                            }
                        }
                    },
            ) {
                Column(Modifier.fillMaxSize()) {
                    val compact = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Column(Modifier.widthIn(max = 180.dp).padding(end = 8.dp)) {
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
                        if (onSetsChange != null) {
                            SetChip("Base", MappingSet.BASE, editSet == MappingSet.BASE, { onEditSet(MappingSet.BASE) }, null)
                            for (st in sets) {
                                SetChip(st.label, st.id, editSet == st.id, { onEditSet(st.id) }, { setMenu = st })
                            }
                            if (sets.size < MappingSet.MAX) {
                                TextButton(onClick = {
                                    val id = (1..MappingSet.MAX).first { n -> sets.none { it.id == n } }
                                    onSetsChange((sets + MappingSet(id)).sortedBy { it.id }, null)
                                    onEditSet(id)
                                }, contentPadding = compact) { Text("+ Layer") }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Box {
                            TextButton(onClick = { sortMenu = true }, contentPadding = compact) { Text("Sort ▾") }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Default") },
                                    onClick = { sortMenu = false; confirmDefault = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Custom") },
                                    enabled = custom.isNotEmpty() && custom != order,
                                    onClick = { sortMenu = false; saveOrder(custom) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Mapped first") },
                                    onClick = {
                                        sortMenu = false
                                        // Every action of the control in the order shown, the ones mapped in any layer first.
                                        val all = sorted(control, inputs, order)
                                        val keys = all.map { orderKey(control, it) }.distinct()
                                        val mapped = all.filter { input ->
                                            val zones = (0 until zonesOf(input)).map { z -> if (z == 0) input else Bindings.zoneInput(input, z) }
                                            zones.any { zi -> mappings.any { m -> zi.sources.any { it == m.source } } }
                                        }.mapTo(HashSet()) { orderKey(control, it) }
                                        saveOrder(keys.filter { it in mapped } + keys.filter { it !in mapped } + order.filter { it !in keys })
                                    },
                                )
                            }
                        }
                        if (inputs.any { parentOf(it).isNotEmpty() && Bindings.targets(view, it).isNotEmpty() }) {
                            var asking by remember { mutableStateOf(false) }
                            TextButton(onClick = { asking = true }, contentPadding = compact) { Text("↺ Reset all") }
                            if (asking) ConfirmResetAll(
                                "Every action follows $parentName again; what it maps here goes.",
                                { asking = false },
                            ) { asking = false; followAll() }
                        }
                        if (inputs.any { (Bindings.targets(view, it).ifEmpty { parentOf(it) }).any { t -> t != OutputTarget.NoOutput } }) {
                            TextButton(onClick = { clearAll() }, contentPadding = compact) { Text("✕ Clear all") }
                        }
                        // A touch control's shared action settings are its "Button defaults"; its own
                        // settings (size, shape, look...) sit next to them.
                        val shared = settings?.shared
                        val ownSettings = onSettings != null && !control.isTouchpadStandIn && physicalInputs == null
                        if (shared != null) TextButton(onClick = { sharedOpen = true }, contentPadding = compact) {
                            Text(if (ownSettings) "⚙ Button defaults" else "⚙ Settings")
                        }
                        if (onSettings != null && (shared == null || ownSettings)) {
                            TextButton(onClick = onSettings, contentPadding = compact) { Text("Settings") }
                        }
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
                    val removing = overLibrary
                    val label = when (d) {
                        is Dragged.Target -> Describe.shortTarget(d.target)
                        is Dragged.Preset -> d.preset.label
                        is Dragged.Card -> actionTitle(control, d.input)
                        is Dragged.Divider -> "┃ Zone divider"
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
                            if (removing) "🗑 Remove " + label else label,
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

    if (confirmDefault) {
        AlertDialog(
            onDismissRequest = { confirmDefault = false },
            title = { Text("Default order?") },
            text = {
                Text(
                    "Every action of every " + controlKindName(control).lowercase() +
                            " goes back to its default place. The order you arranged by hand is lost.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDefault = false
                    saveOrder(emptyList(), byHand = true)
                }) { Text("Reorder", color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDefault = false }) { Text("Cancel") } },
        )
    }
    zoneDialog?.let { (input, zone) ->
        val src = input.sources.first() as? InputSource.Surface
        val dir = src?.input?.holdDirection
        val zs = zoneSettings
        if (src != null && dir != null && zs != null && onZoneSettings != null) {
            AlertDialog(
                onDismissRequest = { zoneDialog = null },
                title = { Text(actionTitle(control, input) + if (zone == null) " · zones" else " · zone " + (zone + 1)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        HoldZoneSettings(zs, Bindings.holdZoneCounts(mappings, src.id), control.id + "dlg", only = dir, zone = zone, onChange = onZoneSettings)
                    }
                },
                confirmButton = { TextButton(onClick = { zoneDialog = null }) { Text("Done") } },
            )
        }
    }
    actionDialog?.let { input ->
        val content = settings?.action?.invoke(input)
        // A card's triggers that map something: their timing settings (long press time, double tap window), once each.
        val mappedTriggers = trigInputs(input).filter { (t, ti) -> t != Trigger.HOLD && Bindings.targets(view, ti).isNotEmpty() }
        val timingContents = listOfNotNull(
            mappedTriggers.firstOrNull { it.first == Trigger.TAP || it.first == Trigger.LONG_PRESS }?.second,
            // A swipe's double has its quick swipe's settings.
            mappedTriggers.firstOrNull { it.first == Trigger.DOUBLE_TAP && !Triggers.isSwipe(input.sources.first()) }?.second,
        ).mapNotNull { settings?.action?.invoke(it) }
        if (content != null || timingContents.isNotEmpty()) AlertDialog(
            onDismissRequest = { actionDialog = null },
            title = { Text("⚙ " + actionTitle(control, input)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    content?.invoke()
                    for (c in timingContents) c()
                }
            },
            confirmButton = { TextButton(onClick = { actionDialog = null }) { Text("Done") } },
        )
    }
    chipDialog?.let { (input, chip) ->
        val zi = zin(input, chip.zone)
        val ti = trig(zi, chip.trigger)
        MappingChipDialog(
            title = Describe.shortTarget(chip.target) + " · " + actionTitle(control, input) +
                if (chip.zone > 0) " · zone " + (chip.zone + 1) else "",
            triggers = Bindings.triggersOf(zi),
            trigger = chip.trigger,
            triggerLabel = { trigLabel(input, it) },
            swipe = Triggers.isSwipe(input.sources.first()),
            analog = chip.target is OutputTarget.PadAxis || chip.target is OutputTarget.MouseAxis,
            options = Bindings.options(view, ti, chip.target),
            onApply = { tr, o ->
                chipDialog = null
                val dest = trig(zi, tr)
                // Another trigger: it leaves this one's chips and joins that one's (keeping their order).
                val base = if (dest == ti) currentMappings else Bindings.removeTarget(currentMappings, ti, chip.target)
                val targets = Bindings.targets(base, dest).let { if (chip.target in it) it else it + chip.target }
                change(
                    Bindings.setTargets(base, dest, targets) { t -> if (t == chip.target) o else Bindings.options(base, dest, t) },
                    Describe.shortTarget(chip.target) + ": " + trigLabel(input, tr) + if (o == null) "" else " with options",
                )
            },
            onRemove = {
                chipDialog = null
                remove(ti, chip.target)
            },
            onDismiss = { chipDialog = null },
            onTargetSettings = if (Describe.isHaptic(chip.target)) {
                {
                    chipDialog = null
                    hapticDraft = ti to chip.target
                }
            } else null,
        )
    }
    val shared = settings?.shared
    if (sharedOpen && shared != null) {
        Dialog(onDismissRequest = { sharedOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = colors.surface) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(control.label.ifBlank { control.id }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (control.isTouchpadStandIn) "The empty pad area · this workspace's settings"
                                else controlKindName(control) + " · settings its actions share",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = { sharedOpen = false }) { Text("Done") }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(Modifier.widthIn(max = 720.dp)) { shared() }
                    }
                }
            }
        }
    }
    layerDraft?.let { (input, t) ->
        LayerTargetDialog(
            start = t,
            sets = sets,
            onAddSet = onSetsChange?.let { setsChange ->
                {
                    val id = (1..MappingSet.MAX).first { n -> sets.none { it.id == n } }
                    MappingSet(id).also { setsChange((sets + it).sortedBy { s -> s.id }, null) }
                }
            },
            onOk = { configured ->
                layerDraft = null
                assign(input, configured)
            },
            onCancel = { layerDraft = null },
        )
    }
    setMenu?.let { st ->
        var setName by remember(st.id) { mutableStateOf(st.name) }
        var deleting by remember(st.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { setMenu = null },
            title = { Text(if (deleting) "Delete \"" + st.label + "\"?" else "Layer " + st.id) },
            text = {
                if (deleting) {
                    Text("Its mappings go too (every control, the touchpad and the controller), and so do the layer actions that switch to it.")
                } else {
                    OutlinedTextField(
                        value = setName,
                        onValueChange = { setName = it },
                        singleLine = true,
                        label = { Text("Name") },
                        placeholder = { Text("Layer " + st.id) },
                    )
                }
            },
            confirmButton = {
                if (deleting) {
                    TextButton(onClick = {
                        setMenu = null
                        if (editSet == st.id) onEditSet(MappingSet.BASE)
                        onSetsChange?.invoke(sets.filterNot { it.id == st.id }, st.id)
                    }) { Text("Delete", color = colors.error) }
                } else {
                    TextButton(onClick = {
                        setMenu = null
                        onSetsChange?.invoke(sets.map { if (it.id == st.id) it.copy(name = setName.trim()) else it }, null)
                    }) { Text("Rename") }
                }
            },
            dismissButton = {
                Row {
                    if (!deleting) TextButton(onClick = { deleting = true }) { Text("Delete layer", color = colors.error) }
                    TextButton(onClick = { setMenu = null }) { Text("Cancel") }
                }
            },
        )
    }
    hapticDraft?.let { (input, t) ->
        HapticTargetDialog(
            start = t,
            runtime = runtime,
            onOk = { configured ->
                hapticDraft = null
                // The chip's buzz changes in place, keeping its place, When and How.
                val old = Bindings.targets(currentMappings, input)
                if (configured != t && t in old) {
                    val opts = Bindings.options(currentMappings, input, t)
                    change(
                        Bindings.setTargets(currentMappings, input, old.map { if (it == t) configured else it }.distinct()) { x ->
                            if (x == configured) opts else Bindings.options(currentMappings, input, x)
                        },
                        Describe.shortTarget(configured) + " set up",
                    )
                }
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

/**
 * One action of the control: its title and mapped targets; [hover] a fitting drag over it, [blocked] one it can't take.
 * Its [row] lays out the edited set's chips; a held direction's is split into length zones by ┃
 * dividers, each closing the zone before it, and ends in a divider while anything is mapped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionCard(
    title: String,
    row: List<RowItem>,
    /** What the level above maps this action to (with their triggers), while it maps nothing itself: faded chips. */
    inherited: List<Pair<Trigger, OutputTarget>>,
    parentName: String,
    /** ✕: it maps nothing, overriding the level above; null = no ✕. */
    onClear: (() -> Unit)?,
    /** ↺: it follows the level above again; null = no ↺. */
    onFollow: (() -> Unit)?,
    /** Per zone, other sets' targets (set, target, trigger): greyed at the end of that zone. */
    others: List<List<Triple<Int, OutputTarget, Trigger>>>,
    /** A chip's options (How, cycle...), marked on it. */
    optionsOf: (RowItem.Chip) -> MapOptions?,
    /** A trigger's badge on this card's chips. */
    badge: (Trigger) -> String,
    /** When its triggers fire (a line under the chips); null = none. */
    timing: String?,
    /** The layers in their order (Base comes first): the row keeps every set's mappings in it. */
    setOrder: List<Int>,
    /** The set the targets are in (0 = Base: plain chips, else its colour and number). */
    setId: Int,
    onOther: (Int) -> Unit,
    selected: Boolean,
    hover: Boolean,
    /** What the drag landing in [row] ([RowItem.Landing]) shows: a target, else a divider. */
    landingLabel: String?,
    /** The zone the drag lands in, shown by the title (-1 = none, or no zones). */
    landingZone: Int,
    blocked: Boolean,
    /** This card is being moved by its handle: it is raised and outlined where it will land. */
    lifted: Boolean,
    /** A held direction with room for another zone: its end divider takes drops after it. */
    addZone: Boolean,
    /** A line under the chips (zones that don't work now). */
    note: String?,
    onBounds: (Rect) -> Unit,
    /** Where each row item is, for placing drags. */
    onItemBounds: (RowItem, Rect) -> Unit,
    /** The row scrolls sideways instead of wrapping. */
    rowScroll: ScrollState,
    onRowBounds: (Rect) -> Unit,
    /** A tap on the divider ending zone k - 1 (k from 1): its settings. */
    onDivider: (Int) -> Unit,
    /** The action's own settings, opened by a ⚙ before its title; null = none. */
    onGear: (() -> Unit)?,
    /** A long press on a divider (not the end one) starts moving it. */
    onDividerDrag: (Int, Offset) -> Unit,
    onClick: () -> Unit,
    /** A long press on a chip starts dragging it (back onto the library to remove it). */
    onChipDrag: (RowItem.Chip, Offset) -> Unit,
    /** A tap on a chip opens its settings (when it fires, its options). */
    onChipClick: (RowItem.Chip) -> Unit,
    /** The ⠿ handle starts moving the card. */
    onHandleDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val handleCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val chipDrag by rememberUpdatedState(onChipDrag)
    val chipClick by rememberUpdatedState(onChipClick)
    val handleDrag by rememberUpdatedState(onHandleDrag)
    val dividerDrag by rememberUpdatedState(onDividerDrag)
    val empty = row.none { it is RowItem.Chip }
    val nothing = row.isEmpty() && others.all { it.isEmpty() } && inherited.isEmpty()
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .alpha(
                when {
                    blocked -> 0.45f
                    empty && !selected && !hover -> 0.7f
                    else -> 1f
                },
            )
            .onGloballyPositioned { onBounds(it.boundsInRoot()) },
        shape = RoundedCornerShape(16.dp),
        color = when {
            hover -> colors.tertiaryContainer
            lifted || selected -> colors.primaryContainer
            else -> colors.surfaceContainerHigh
        },
        border = when {
            hover -> BorderStroke(2.dp, colors.tertiary)
            lifted -> BorderStroke(2.dp, colors.primary)
            selected -> BorderStroke(1.dp, colors.primary)
            else -> null
        },
        shadowElevation = if (lifted) 8.dp else 0.dp,
    ) {
        Column(
            Modifier.animateContentSize().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onGear != null) {
                    Box(
                        Modifier.padding(end = 6.dp).size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onGear),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⚙", style = MaterialTheme.typography.titleMedium, color = colors.primary)
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected && !hover) colors.onPrimaryContainer else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                for ((label, action) in listOf("✕" to onClear, "↺" to onFollow)) if (action != null) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = action),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                    }
                }
                if (landingZone >= 0) {
                    Text(
                        "→ Zone " + (landingZone + 1) + if (landingZone >= others.size) " (new)" else "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.tertiary,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .dragSource("handle", handleCoords, longPress = false) { handleDrag(it) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "⠿",
                        color = if (lifted) colors.primary else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
            if (nothing) {
                Text(
                    if (hover) "Release to map" else "Drop here",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                // A framed row; a shadow at an end that has more to scroll to.
                val shade = colors.scrim.copy(alpha = 0.28f)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { onRowBounds(it.boundsInRoot()) }
                        .clip(RoundedCornerShape(12.dp))
                        // Plain inside, whatever the card's highlight, so the chips stay easy to read.
                        .background(colors.surface)
                        .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
                        .drawWithContent {
                            drawContent()
                            val w = ROW_SHADE_DP.dp.toPx()
                            if (rowScroll.canScrollBackward) {
                                drawRect(Brush.horizontalGradient(listOf(shade, Color.Transparent), 0f, w), size = Size(w, size.height))
                            }
                            if (rowScroll.canScrollForward) {
                                drawRect(
                                    Brush.horizontalGradient(listOf(Color.Transparent, shade), size.width - w, size.width),
                                    topLeft = Offset(size.width - w, 0f), size = Size(w, size.height),
                                )
                            }
                        }
                        .horizontalScroll(rowScroll)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Every set keeps its place in the row (Base, then the layers in order), whichever is edited.
                    val rank = (listOf(MappingSet.BASE) + setOrder).let { o -> { n: Int -> o.indexOf(n) } }

                    /** Zone [z]'s mappings in the other sets ranked before the one edited ([before]) or after it. */
                    @Composable
                    fun othersOf(z: Int, before: Boolean) {
                        for ((n, t, tr) in others.getOrNull(z).orEmpty()) if ((rank(n) < rank(setId)) == before) key("o", z, n, t, tr) {
                            Row(
                                Modifier
                                    .alpha(0.5f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceVariant)
                                    .clickable { onOther(n) }
                                    .heightIn(min = 32.dp)
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (n == MappingSet.BASE) {
                                    Text("Base", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                } else SetNumber(n)
                                Text(
                                    chipText(badge(tr), t, null),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                    // Faded: what the level above does here.
                    for ((tr, t) in inherited) key("p", tr, t) {
                        Row(
                            Modifier
                                .alpha(0.5f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                                .heightIn(min = 32.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(parentName, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(
                                chipText(badge(tr), t, null),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    var zone = 0
                    othersOf(0, true)
                    for (item in row) when (item) {
                        is RowItem.Chip -> key(item) {
                            val z = item.zone
                            val t = item.target
                            val chipCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
                            val fg = colors.onSecondaryContainer
                            Row(
                                Modifier
                                    .onGloballyPositioned { onItemBounds(item, Rect(it.positionInRoot(), it.size.toSize())) }
                                    .dragSource(item, chipCoords) { chipDrag(item, it) }
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { chipClick(item) }
                                    .background(
                                        if (setId == MappingSet.BASE) colors.secondaryContainer else setColor(setId).copy(alpha = 0.35f),
                                    )
                                    .heightIn(min = 32.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (setId != MappingSet.BASE) SetNumber(setId, Modifier.padding(start = 6.dp))
                                Text(
                                    chipText(badge(item.trigger), t, optionsOf(item)),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = fg,
                                    maxLines = 1,
                                    modifier = Modifier.padding(start = if (setId == MappingSet.BASE) 10.dp else 6.dp, end = 10.dp),
                                )
                            }
                        }

                        is RowItem.Divider -> {
                            othersOf(zone, false)
                            zone++
                            val k = zone
                            key(item) {
                                val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
                                ZoneDivider(
                                    k, end = false, addZone = false,
                                    Modifier
                                        .onGloballyPositioned { onItemBounds(item, Rect(it.positionInRoot(), it.size.toSize())) }
                                        .dragSource(item, coords) { dividerDrag(item.k, it) },
                                ) { onDivider(k) }
                            }
                            othersOf(zone, true)
                        }

                        RowItem.End -> {
                            othersOf(zone, false)
                            zone++
                            val k = zone
                            key(item) {
                                ZoneDivider(
                                    k, end = true, addZone = addZone,
                                    Modifier.onGloballyPositioned { onItemBounds(item, Rect(it.positionInRoot(), it.size.toSize())) },
                                ) { onDivider(k) }
                            }
                            othersOf(zone, true)
                        }

                        RowItem.Landing -> key(item) {
                            if (landingLabel != null) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.tertiary.copy(alpha = 0.18f))
                                        .border(2.dp, colors.tertiary, RoundedCornerShape(8.dp))
                                        .heightIn(min = 32.dp)
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(landingLabel, style = MaterialTheme.typography.labelLarge, color = colors.tertiary, maxLines = 1)
                                }
                            } else {
                                ZoneDivider(0, end = false, addZone = false, landing = true) {}
                            }
                        }
                    }
                    othersOf(zone, false)
                    for (z in zone + 1 until others.size) {
                        othersOf(z, true); othersOf(z, false)
                    }
                }
            }
            if (timing != null) Text(timing, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }
}

/**
 * A ┃ divider ending length zone [k] (from 1) of a held direction: chips after it fire further
 * along. [end]: the one after the last zone ([addZone]: a drop after it opens a new zone).
 * [landing]: where a dragged divider goes. A tap opens zone [k]'s settings.
 */
@Composable
private fun ZoneDivider(
    k: Int,
    end: Boolean,
    addZone: Boolean,
    modifier: Modifier = Modifier,
    landing: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val c = if (landing) colors.tertiary else colors.primary
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (landing) Modifier.background(colors.tertiary.copy(alpha = 0.18f)) else Modifier.clickable(onClick = onClick))
            .heightIn(min = 32.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!landing) {
            Text(
                k.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = c,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Box(Modifier.width(if (landing) 4.dp else 3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(c))
        if (end && addZone) {
            Text("+", style = MaterialTheme.typography.labelLarge, color = c, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** How wide the shadow at a mapping row's scrollable end is. */
private const val ROW_SHADE_DP = 20

/** A mapping set's number in a circle of its colour. */
/** A mapping set's colour (sets have no colour of their own: picked from a palette by id). */
internal fun setColor(id: Int): Color = SET_COLORS[(id - 1).mod(SET_COLORS.size)]

private val SET_COLORS = listOf(
    Color(0xFF42A5F5), Color(0xFFFFA726), Color(0xFF66BB6A), Color(0xFFAB47BC), Color(0xFFEF5350),
    Color(0xFF26C6DA), Color(0xFFD4E157), Color(0xFFEC407A), Color(0xFF8D6E63),
)

@Composable
private fun SetNumber(id: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(18.dp).background(setColor(id), CircleShape), contentAlignment = Alignment.Center) {
        Text(id.toString(), style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

/** A set in the top bar: tap to edit it, long-press ([onLongClick]) to rename or delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetChip(label: String, id: Int, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .padding(horizontal = 2.dp)
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surface)
            .border(1.dp, if (selected) colors.secondary else colors.outlineVariant, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (id != MappingSet.BASE) SetNumber(id, Modifier.padding(end = 6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A target or preset of the library: tap to use it, long-press to drag it. */
@Composable
private fun LibraryChip(
    text: String,
    enabled: Boolean,
    mapped: Boolean,
    preset: Boolean,
    /** Being dragged: highlighted. */
    dragging: Boolean,
    dragKey: Any,
    onClick: () -> Unit,
    onDragStart: (Offset) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val start by rememberUpdatedState(onDragStart)
    val (bg, fg) = when {
        dragging -> colors.primary to colors.onPrimary
        mapped -> colors.primaryContainer to colors.onPrimaryContainer
        preset -> colors.tertiaryContainer to colors.onTertiaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurface
    }
    Box(
        Modifier
            .alpha(if (enabled || dragging) 1f else 0.38f)
            .dragSource(dragKey, coords) { start(it) }
            .shadow(if (dragging) 6.dp else 0.dp, RoundedCornerShape(10.dp))
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
