package com.oleroma.gyrophone.ui

import android.content.res.Configuration
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import com.oleroma.gyrophone.core.model.AxisRange
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.runtime
import com.oleroma.gyrophone.core.model.GameSens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
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
import com.oleroma.gyrophone.core.hid.HidUsage
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.ButtonLibrary
import com.oleroma.gyrophone.core.model.LibraryButton
import com.oleroma.gyrophone.core.model.MappingPreset
import com.oleroma.gyrophone.core.model.MappingPresets
import com.oleroma.gyrophone.core.model.PresetChip
import com.oleroma.gyrophone.core.model.PresetLevel
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.HoldMode
import com.oleroma.gyrophone.core.model.HoldZone
import com.oleroma.gyrophone.core.model.Inheritance
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.nothing
import com.oleroma.gyrophone.core.model.PhysicalDevice
import com.oleroma.gyrophone.core.model.SurfaceInput
import com.oleroma.gyrophone.core.model.TouchAreaKind
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchpadSettings
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.Triggers
import com.oleroma.gyrophone.core.model.ANY_SLOT
import com.oleroma.gyrophone.core.model.heldSwipe
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
    /**
     * A target from the library ([from] null) or a chip of action [from]; [inherited]: one of the
     * level above's faded chips on card [from] (taken away, the card overrides it).
     */
    data class Target(
        val target: OutputTarget,
        val from: BindableInput?,
        val inherited: Boolean = false,
        val n: Int = 0,
        /** A chip preset's How and options, which the new chip takes. */
        val options: MapOptions? = null,
    ) : Dragged
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
    /**
     * A mapping: [target] in length zone [zone], fired on [trigger] (zone 0 only; Hold elsewhere).
     * On a button's touch card, [where] says where the finger is: on it (0), in its Area (1) or
     * Outside it (2). [n] tells chips of the same target on one When apart (another How, as
     * Double tap Left Repeat ×2 and Left Held): its place among them ([Bindings.entry]); -1 = a
     * chip just dropped there.
     */
    data class Chip(val zone: Int, val target: OutputTarget, val trigger: Trigger = Trigger.HOLD, val where: Int = 0, val n: Int = 0) : RowItem
    data class Divider(val k: Int) : RowItem
    data object End : RowItem
    data object Landing : RowItem
}

/** A faded chip: what the level above maps a card's Where [where] to on [trigger]. */
private data class Faded(val trigger: Trigger, val target: OutputTarget, val where: Int = 0)

/**
 * A preset of the library, built in or the user's ([user]: its [MappingPreset] or [LibraryButton]).
 * A CHIP adds its one chip to any card that takes it; a CARD fits cards of [kind]; a BUTTON fills
 * the whole control. [chips] on their slots ([MappingPresets.slot]; a button's keep their finger
 * counts and physical places). [control]: the touch control whose settings it can bring.
 * [stick]: a built-in stick preset (lit while the stick does just that).
 */
private class MapPreset(
    val label: String,
    val level: PresetLevel,
    val chips: List<PresetChip>,
    val kind: InputSource? = null,
    val control: TouchControl? = null,
    val user: Any? = null,
    val stick: Bindings.StickPreset? = null,
)

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

/** What [Describe.pickerGroups] reads of an input: inputs alike in these are offered the same targets. */
private data class PickerKind(val analog: Boolean, val swipe: Boolean, val extra: List<OutputTarget>, val mouseMotion: Boolean)

/**
 * [allowedTargets] by [PickerKind] (main thread): a drag asks for it on every move, frame and
 * card, and the targets offered never change, so each kind's set is built once.
 */
private val allowedByKind = HashMap<PickerKind, Set<OutputTarget>>()

/** The targets [input]'s picker offers ([Describe.pickerGroups]). */
private fun allowedTargets(input: BindableInput): Set<OutputTarget> =
    allowedByKind.getOrPut(PickerKind(input.analog, input.swipe, input.extra, input.mouseMotion)) {
        Describe.pickerGroups(input.analog, input.swipe, input.extra, input.mouseMotion).flatMapTo(HashSet()) { it.second }
    }

/** Whether [t] may be mapped to an input offering [allowed]: haptics always, variants of an offered kind too. */
private fun allows(allowed: Set<OutputTarget>, t: OutputTarget): Boolean {
    if (Describe.isHaptic(t) || t in allowed) return true
    return when (t) {
        is OutputTarget.PadAxis -> allowed.any { it is OutputTarget.PadAxis && it.axis == t.axis }
        is OutputTarget.MouseAxis -> allowed.any { it is OutputTarget.MouseAxis && it.motion == t.motion }
        is OutputTarget.Key -> allowed.any { it is OutputTarget.Key }
        OutputTarget.GyroHold -> OutputTarget.GyroPause in allowed
        // Layer actions, (Let go), Black screen, Set orientation, Clear displays and Nothing go where button outputs do.
        is OutputTarget.Layer, OutputTarget.LayerBack, OutputTarget.LetGo, OutputTarget.BlackScreen,
        is OutputTarget.SetOrientation, OutputTarget.ClearDisplays, OutputTarget.NoOutput ->
            allowed.any { it is OutputTarget.Key }
        else -> false
    }
}

private fun isSurface(input: BindableInput) = input.sources.firstOrNull() is InputSource.Surface

/**
 * A chip's text: its trigger's badge unless On touch ("Tap · Q"), its target, then marks for its
 * How and options: ¹ once, ⟲ toggle, ↻ cycle, ⏱ delays.
 */
private fun chipText(badge: String, target: OutputTarget, o: MapOptions?, range: String? = null): String = buildString {
    if (badge.isNotEmpty()) append(badge).append(" · ")
    append(Describe.shortTarget(target))
    if (range != null) append(" ").append(range)
    if (o != null) {
        if (o.mode.mark.isNotEmpty()) append(" ").append(o.mode.mark)
        if (o.mode == How.REPEAT) append(o.repeat.coerceIn(MapOptions.MIN_REPEAT, MapOptions.MAX_REPEAT))
        if (o.cycle) append(" ↻")
        if (o.startDelayMs > 0 || o.endDelayMs > 0 || o.minHoldMs != MapOptions.DEFAULT_MIN_HOLD_MS) append(" ⏱")
    }
}

/** Card title in messages: surface actions carry their finger count. */
private fun actionTitle(control: TouchControl, input: BindableInput): String {
    val s = input.sources.firstOrNull()
    if (s !is InputSource.Surface) return actionLabel(control, input)
    // A direction of the Swipe card ("↑"): "Swipe ↑".
    val d = s.input.holdDirection
    return s.fingers.toString() + "f " + if (d != null) "Swipe " + Bindings.slotLabel(d.ordinal) else input.label
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

/**
 * The built-in presets of [control]: whole-stick set-ups for a stick; common outputs as chips for
 * anything with a press; touchpad and gyro set-ups for a touch button or the touchpad. [toChips]
 * turns mappings the control would have (from nothing) into a button preset's chips.
 */
private fun presetsFor(
    control: TouchControl,
    inputs: List<BindableInput>,
    physical: Boolean,
    toChips: (List<Mapping>) -> List<PresetChip>,
): List<MapPreset> = buildList {
    if (control.kind == TouchKind.STICK) {
        for (p in Bindings.StickPreset.entries) {
            add(MapPreset(p.label, PresetLevel.BUTTON, toChips(Bindings.applyStickPreset(emptyList(), inputs, p)), stick = p))
        }
        return@buildList
    }
    // A press to put chips on: the touchpad has none (its first other input is a finger's position).
    val press = inputs.firstOrNull { !isSurface(it) && !it.analog && !it.swipe && !it.mouseMotion }
    if (press != null && !control.isTouchpadStandIn) {
        fun chip(label: String, t: OutputTarget) = add(MapPreset(label, PresetLevel.CHIP, listOf(PresetChip(press.sources.first(), t))))
        chip("Mouse left", OutputTarget.Mouse(MouseButton.LEFT))
        chip("Mouse right", OutputTarget.Mouse(MouseButton.RIGHT))
        chip("Space", OutputTarget.Key(0x2C))
        chip("Shift", OutputTarget.Key(HidUsage.LEFT_SHIFT))
        chip("Ctrl", OutputTarget.Key(HidUsage.LEFT_CTRL))
        chip("Esc", OutputTarget.Key(0x29))
        chip("Tab", OutputTarget.Key(0x2B))
        chip("Enter", OutputTarget.Key(0x28))
        chip("Ratchet", OutputTarget.GyroPause)
    }
    if (control.swipes && !physical) {
        add(MapPreset("Windows touchpad", PresetLevel.BUTTON, toChips(
            Bindings.defaultTouchpadMappings(TouchpadSettings()).map { d ->
                val s = d.source
                if (s is InputSource.Surface) d.copy(source = s.copy(id = control.id)) else d
            },
        )))
        fun move(i: SurfaceInput) =
            BindableInput(i.label, listOf(InputSource.Surface(1, i, control.id)), analog = true, swipe = true)
        val x = Bindings.setTargets(emptyList(), move(SurfaceInput.MOTION_X), listOf(OutputTarget.MouseAxis(MouseMotion.MOVE_X)))
        add(MapPreset("Mouse cursor", PresetLevel.BUTTON, toChips(
            Bindings.setTargets(x, move(SurfaceInput.MOTION_Y), listOf(OutputTarget.MouseAxis(MouseMotion.MOVE_Y))),
        )))
    }
    // Roll and pitch move the mouse. A physical control has no gyro of its own here.
    if (!physical) add(MapPreset("Gyro mouse", PresetLevel.BUTTON, toChips(Bindings.gyroMouseMappings(control.id))))
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
    /**
     * A physical key or button that can be held: its gyro owner id ([HeldButton.GYRO_ID] + its preset
     * id). Its Motion tab then maps gyro axes while it is held; null = no Motion tab.
     */
    physicalMotionId: String? = null,
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
    /** The workspace a touch control is in: presets bring only the settings that change what it does there. */
    workspace: Workspace? = null,
    /** Changes the touch control (a preset's settings); null = presets bring mappings only. */
    onControlChange: (((TouchControl) -> TouchControl) -> Unit)? = null,
    /** Its label, edited in the title ([LabelField]); null = the title is not editable. */
    label: String? = null,
    onLabel: ((String) -> Unit)? = null,
    /** The touchpad: its label and presses show on the pad ([Workspace.touchpadLabelShown]); null = no such switch. */
    labelShown: Boolean? = null,
    onLabelShown: ((Boolean) -> Unit)? = null,
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

    // Drag callbacks outlive a composition: everything they read goes through state.
    val currentMappings by rememberUpdatedState(view)

    /**
     * What the level above maps [input] itself to, with their triggers, that [over] (the edited
     * mappings) still takes: chip by chip, unless it maps that chip itself, stops it
     * ([OutputTarget.Off]) or clears the whole action ([OutputTarget.NoOutput]) ([Inheritance]).
     */
    fun parentOfInput(input: BindableInput, over: List<Mapping> = currentMappings): List<Pair<Trigger, OutputTarget>> {
        if (OutputTarget.NoOutput in Bindings.targets(over, input)) return emptyList()
        // A held control's gyro axis takes the whole axis over while held: none of the level above's then.
        val g = input.sources.first() as? InputSource.Gyro
        if (g != null && g.id.isNotEmpty() && trigInputs(input).any { (_, ti) -> Bindings.targets(over, ti).isNotEmpty() }) return emptyList()
        return trigInputs(input).flatMap { (t, ti) ->
            val own = Bindings.targets(over, ti)
            Bindings.targets(parentView, ti).filter { x -> !x.nothing && x !in own && OutputTarget.Off(x) !in own }.map { t to it }
        }.distinct()
    }

    /** [m] with each of [chips] (faded ones) stopped: an [OutputTarget.Off] of it on its input (Override). */
    fun withOffs(m: List<Mapping>, chips: List<Pair<BindableInput, OutputTarget>>): List<Mapping> = chips.fold(m) { acc, (ti, x) ->
        Bindings.setEntries(acc, ti, Bindings.entries(acc, ti) + Bindings.Entry(OutputTarget.Off(x), null))
    }

    /** [m] with faded chip [x] on [ti] made its own: the same output, with the level above's options (Replace). */
    fun withCopy(m: List<Mapping>, ti: BindableInput, x: OutputTarget): List<Mapping> {
        val e = Bindings.entries(parentView, ti).firstOrNull { it.target == x } ?: Bindings.Entry(x, null)
        return Bindings.setEntries(m, ti, Bindings.entries(m, ti).filter { it.target != OutputTarget.Off(x) } + e)
    }
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
            control.isTouchpadStandIn -> (1..4).flatMap { Bindings.surfaceInputs(it) } + Bindings.fingerInputs("", round = false) + Bindings.gyroInputs() + Bindings.motionInputs()
            else -> Bindings.touchInputs(control)
        }
    }
    // A physical button or stick: only its own inputs, none of a touch button's (touch, moves, swipes).
    val physical = physicalInputs != null
    val surfaceCards = control.swipes && !physical

    /** [input]'s place among the control's inputs (its own input's, for a When of it): a physical slot ([MappingPresets.slot]). */
    fun placeOf(input: BindableInput): Int {
        val b = Triggers.base(input.sources.first()).let { if (it is InputSource.Surface) it.copy(zone = 0) else it }
        return inputs.indexOfFirst { top -> top.sources.any { Bindings.sameSource(it, b) } }.coerceAtLeast(0)
    }
    /** Every input of the whole control a button preset may map (a physical button's Motion gyro too). */
    val controlInputs = remember(inputs, physicalMotionId) {
        MappingPresets.allInputs(inputs + physicalMotionId?.let { Bindings.gyroInputs(it) }.orEmpty())
    }
    fun buttonChips(m: List<Mapping>) = MappingPresets.chipsOf(m, controlInputs, ::placeOf, keepFingers = true)
    val presetKind = remember(control) { ButtonLibrary.kindOf(control) }
    val userPresets by runtime.mappingPresets.collectAsStateWithLifecycle()
    val library by runtime.buttonLibrary.collectAsStateWithLifecycle()
    val builtins = remember(control, inputs) { presetsFor(control, inputs, physical, ::buttonChips) }
    // Button presets: library buttons of this kind (a touch one's Base mappings as chips).
    val libraryPresets = remember(library, presetKind) {
        library.filter { !ButtonLibrary.isGroup(it) && ButtonLibrary.kindOf(it.control) == presetKind }.map { b ->
            val chips = b.chips.ifEmpty {
                MappingPresets.chipsOf(
                    b.mappings.filter { it.set == MappingSet.BASE },
                    MappingPresets.allInputs(Bindings.touchInputs(b.control)), keepFingers = true,
                )
            }
            MapPreset(ButtonLibrary.name(b), PresetLevel.BUTTON, chips, control = b.control.takeUnless { ButtonLibrary.presetOnly(b) }, user = b)
        }
    }
    val ownPresets = remember(userPresets) { userPresets.map { MapPreset(it.name, it.level, it.chips, it.kind, it.control, it) } }
    val presets = builtins + ownPresets + libraryPresets
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
    /** The card of touching the button (or stick) itself: its Area and Outside chips show on it. */
    fun isTouchCard(s: InputSource) = s == InputSource.Touch(control.id) ||
            s == InputSource.TouchArea(control.id, TouchAreaKind.HELD) ||
            (s is InputSource.Surface && s.id == control.id && s.fingers == 1 && s.input == SurfaceInput.HOLD && s.zone == 0)
    // The button's Area and Outside (null where it has none): chips of its touch card, not cards.
    val areaInputs = remember(inputs, control.id) {
        listOf(TouchAreaKind.ZONE, TouchAreaKind.OUTSIDE).map { k ->
            inputs.firstOrNull { it.sources.first() == InputSource.TouchArea(control.id, k) }
        }
    }
    fun isAreaCard(s: InputSource) = s is InputSource.TouchArea && s.kind != TouchAreaKind.HELD

    /** The one Swipe card (any way, zone 0): every swipe direction's chips are on it, each with its own ([wheresOf]). */
    fun isSwipeCard(s: InputSource) = s is InputSource.Surface && s.input == SurfaceInput.HOLD_ANY && s.zone == 0 && s.on == Trigger.HOLD

    /** A swipe direction's own card (↑ → ↓ ←): not shown, its chips are the Swipe card's. */
    fun isDirectionCard(s: InputSource) = s is InputSource.Surface && s.input.holdDirection != null && s.zone == 0 && s.on == Trigger.HOLD

    // A swipe button: one list per finger count (hold, taps, moves, swipes...); the button's own
    // outside and area (any fingers) go with one finger's hold, as its chips.
    val groups = listOf(
        sorted(
            control,
            (if (!surfaceCards) (if (physicalMotionId != null && fingers == 0) Bindings.gyroInputs(physicalMotionId) else inputs)
            else if (fingers == 0) Bindings.gyroInputs(control.id) + Bindings.motionInputs(control.id)
            else Bindings.swipeInputs(control, fingers))
                .filterNot(Bindings::isTriggerOf)
                .let { l -> if (l.any { isTouchCard(it.sources.first()) }) l.filterNot { isAreaCard(it.sources.first()) } else l }
                .let { l -> if (l.any { isSwipeCard(it.sources.first()) }) l.filterNot { isDirectionCard(it.sources.first()) } else l },
            order,
        ),
    )

    /** [card]'s inputs by Where (see [RowItem.Chip.where]): itself, then for its touch card its Area and Outside. */
    fun wheresOf(card: BindableInput): List<BindableInput?> {
        val s = card.sources.first()
        return if (isTouchCard(s) && areaInputs.any { it != null }) listOf(card) + areaInputs else listOf(card)
    }

    /**
     * The lines of [card]: the Swipe card has one per direction (Any, ↑ → ↓ ←), each a row of its
     * own with its own zones, whose chips map that direction; any other card is its one line.
     */
    fun linesOf(card: BindableInput): List<BindableInput> {
        val s = card.sources.first()
        if (!isSwipeCard(s)) return listOf(card)
        return listOf(card) + Bindings.swipeSlots((s as InputSource.Surface).fingers, s.id).drop(1)
    }

    /** The lines that are one action with [line] for inheritance: the Swipe card's all (see [Inheritance.cardOf]), else itself. */
    fun groupOf(line: BindableInput): List<BindableInput> {
        val s = line.sources.first() as? InputSource.Surface ?: return listOf(line)
        if (!s.input.heldSwipe || s.zone != 0 || s.on != Trigger.HOLD) return listOf(line)
        val card = inputs.firstOrNull { isSwipeCard(it.sources.first()) && (it.sources.first() as InputSource.Surface).let { c -> c.fingers == s.fingers && c.id == s.id } }
            ?: BindableInput(SurfaceInput.HOLD_ANY.label, listOf(s.copy(input = SurfaceInput.HOLD_ANY)))
        return linesOf(card)
    }

    /** A Where of [card] that has length zones (a swipe direction): its chips go in any zone; the others only in the first. */
    fun zoned(wi: BindableInput?): Boolean = (wi?.sources?.first() as? InputSource.Surface)?.input?.heldSwipe == true

    /** [card]'s Wheres in zone [z]: each as that zone's input, null where it has none. */
    fun wheresIn(card: BindableInput, z: Int): List<BindableInput?> =
        if (z == 0) wheresOf(card) else wheresOf(card).map { wi -> if (zoned(wi)) zin(wi!!, z) else null }

    /** The input [chip] of [card]'s row maps (its trigger aside): its Where in its zone. */
    fun chipInput(card: BindableInput, chip: RowItem.Chip): BindableInput {
        val wi = wheresOf(card).getOrNull(chip.where) ?: card
        return if (chip.zone > 0) zin(wi, chip.zone) else wi
    }

    /** What the level above maps line [line] to (cleared ones left out), with their triggers. */
    fun parentOf(line: BindableInput): List<Faded> = parentOfInput(line).map { (t, x) -> Faded(t, x) }

    /** Every chip the level above maps on [card]'s lines that [over] still takes, by the input it fires on. */
    fun fadedOf(card: BindableInput, over: List<Mapping> = currentMappings): List<Pair<BindableInput, OutputTarget>> =
        groupOf(card).flatMap { line -> parentOfInput(line, over).map { (t, x) -> trig(line, t) to x } }

    /** Where [from] (a mapped input, any trigger) is on [card]'s row, or null when it is not on it. */
    fun whereOn(card: BindableInput, from: InputSource): Int? {
        val base = Triggers.base(from).let { b -> if (b is InputSource.Surface) b.copy(zone = 0) else b }
        return wheresOf(card).indexOfFirst { it?.sources?.first() == base }.takeIf { it >= 0 }
    }
    var dragged by remember { mutableStateOf<Dragged?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var liveOrder by remember { mutableStateOf<List<BindableInput>>(emptyList()) }
    val cardDrag = dragged as? Dragged.Card
    val shownGroups = groups.mapIndexed { g, list -> if (cardDrag?.group == g) liveOrder else list }
    val shown = shownGroups.flatten()
    val groupsNow by rememberUpdatedState(shownGroups)
    val shownNow by rememberUpdatedState(shown)
    var selectedSrc by remember(control.id) { mutableStateOf<InputSource?>(null) }
    // A line of a card can be selected (the Swipe card's ↑...): taps on the library add to it.
    val selected = shown.flatMap(::linesOf).firstOrNull { it.sources.first() == selectedSrc } ?: shown.firstOrNull()
    val selectedAllowed = remember(selected) { selected?.let(::allowedTargets).orEmpty() }

    // Each step: the mappings before, and the control before when a preset changed its settings too.
    val undo = remember(control.id) { mutableStateListOf<Pair<List<Mapping>, TouchControl?>>() }
    var recent by remember { mutableStateOf(loadRecent(runtime)) }
    var hapticDraft by remember { mutableStateOf<Pair<BindableInput, OutputTarget>?>(null) }
    var layerDraft by remember { mutableStateOf<Pair<BindableInput, OutputTarget.Layer>?>(null) }
    var setMenu by remember { mutableStateOf<MappingSet?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var split by remember {
        mutableFloatStateOf(runtime.uiPref(PREF_SPLIT)?.toFloatOrNull()?.coerceIn(MIN_SPLIT, MAX_SPLIT) ?: 0.5f)
    }

    fun undoLast() {
        val (prev, was) = undo.removeLastOrNull() ?: return
        // Its snackbar's Undo would undo the change before it.
        snackbar.currentSnackbarData?.dismiss()
        emit(prev)
        if (was != null) onControlChange?.invoke { was }
    }

    /**
     * [next] is the edited set's view; undo keeps every set as it was. [controlBefore]: the control
     * before a preset changed its settings too (undo brings them back).
     */
    fun change(next: List<Mapping>, message: String, controlBefore: TouchControl? = null) {
        val prev = allMappings
        // A zone left empty closes up: the ones after it move down.
        // Mapping something to a cleared action replaces the clear.
        val all = Inheritance.tidy(Bindings.compactZones(Bindings.viewSet(next, currentSet)))
        if (all == prev && controlBefore == null) return
        undo.add(prev to controlBefore)
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
        var next = Bindings.addTarget(currentMappings, input, t)
        if (t is OutputTarget.GyroSens) {
            // It needs a range to do anything: around the sensitivity it would change.
            val g = runtime.engine.workspace.gyro
            val base = g.sensitivityOf(if (t.axis == MotionAxis.NONE) MotionAxis.ROLL else t.axis)
            next = next.map { if (Bindings.belongs(it, input) && it.target == t && it.range == null) it.copy(range = AxisRange(base * 0.8f, base * 1.2f)) else it }
        }
        change(
            next,
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

    fun remove(input: BindableInput, t: OutputTarget, n: Int = 0) = change(
        Bindings.removeEntry(currentMappings, input, t, n),
        "Removed " + Describe.shortTarget(t) + " from " + actionTitle(currentControl, input),
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

    // The Swipe card's lines, by source: a drag lands on the line under the finger.
    val lineBounds = remember { mutableStateMapOf<InputSource, Rect>() }


    /** The held swipe sources of [input]'s zoned Wheres (zone 0, Hold): their mappings make its zones. */
    fun zoneKeys(input: BindableInput): List<InputSource> =
        wheresOf(input).filter(::zoned).map { it!!.sources.first() }

    /** Something of [keys] is mapped, in any set and zone, with any trigger. */
    fun mapsAny(keys: List<InputSource>): Boolean =
        keys.isNotEmpty() && allMappings.any { m ->
            val b = (m.source as? InputSource.Surface)?.let { Triggers.base(it) } as? InputSource.Surface
            b != null && keys.contains(b.copy(zone = 0))
        }

    /** How many zones [input] (a held direction, or the Swipe card's directions) has, in any set (1 for anything else). */
    fun zonesOf(input: BindableInput): Int {
        val keys = zoneKeys(input)
        if (keys.isEmpty()) return 1
        return 1 + (allMappings.maxOfOrNull { m ->
            val s = m.source as? InputSource.Surface
            if (s != null && s.copy(zone = 0, on = Trigger.HOLD) in keys) s.zone else 0
        } ?: 0)
    }

    /** A held direction offers a next zone: something is mapped and there is room. */
    fun addsZone(input: BindableInput): Boolean =
        zonesNow && zonesOf(input) < TouchpadSettings.MAX_HOLD_ZONES && mapsAny(zoneKeys(input))

    /** A held direction with something mapped (any set): its row ends in a divider that opens the last zone's settings. */
    fun hasEnd(input: BindableInput): Boolean = zoneSettings != null && mapsAny(zoneKeys(input))

    /** Every input [card] shows chips of: its Wheres and zones, on each trigger (mapping any of them overrides the level above). */
    fun lineInputs(line: BindableInput): List<BindableInput> =
        (0 until zonesOf(line)).flatMap { z -> wheresIn(line, z).filterNotNull() }
            .flatMap { trigInputs(it).map { (_, ti) -> ti } }.distinctBy { it.sources }

    /** Every input [card] shows chips of, on all its lines (the Swipe card's directions are one action). */
    fun cardInputs(card: BindableInput): List<BindableInput> =
        groupOf(card).flatMap(::lineInputs).distinctBy { it.sources }

    /** Every input the cards show chips of: each card's Wheres and zones, on each trigger. */
    fun bulkInputs(): List<BindableInput> = inputs.filterNot(Bindings::isTriggerOf).flatMap(::lineInputs).distinctBy { it.sources }

    /** [input] maps nothing here, so it does not do what the level above maps it to (a clear on its Hold overrides it all). */
    fun clear(input: BindableInput) {
        // Its own chips go, and each of the level above's gets an Off (Override): it does nothing,
        // and what the level above adds later still shows.
        val bare = cardInputs(input).fold(currentMappings) { m, ti -> Bindings.setTargets(m, ti, emptyList()) }
        change(withOffs(bare, fadedOf(input, bare)), "Cleared " + actionTitle(currentControl, input))
    }

    /** [input] follows the level above again: its own mappings here go. */
    fun follow(input: BindableInput) = change(
        cardInputs(input).fold(currentMappings) { m, ti -> Bindings.setTargets(m, ti, emptyList()) },
        actionTitle(currentControl, input) + " follows " + parentName,
    )

    /** The card [line] belongs to: the Swipe card for one of its lines, else itself. */
    fun cardOf(line: BindableInput): BindableInput =
        shownNow.firstOrNull { c -> linesOf(c).any { it.sources == line.sources } } ?: line

    /** Every input [card] may map: its lines', Wheres', zones' and Whens'. */
    fun cardSlots(card: BindableInput): List<BindableInput> =
        MappingPresets.allInputs(groupOf(card).flatMap { wheresOf(it).filterNotNull() })

    /** [p] may go onto [card] (a button preset onto the control, whatever card). */
    fun presetFits(p: MapPreset, card: BindableInput?): Boolean = when (p.level) {
        PresetLevel.CHIP -> card != null && allows(allowedTargets(card), p.chips.single().target)
        PresetLevel.CARD -> card != null && MappingPresets.kindOf(cardOf(card)) == p.kind
        PresetLevel.BUTTON -> true
    }

    /**
     * [m] with the own chips of [cards] that only repeat what the level above maps there taken
     * away: those cards follow it again instead of showing an override that changes nothing.
     */
    fun followSame(m: List<Mapping>, cards: List<BindableInput>): List<Mapping> {
        if (currentSet != MappingSet.BASE) return m
        return cards.fold(m) { acc, card ->
            val all = cardInputs(card)
            val own = all.map { Bindings.entries(acc, it) }
            if (own.all { it.isEmpty() } || own != all.map { Bindings.entries(parentView, it) }) acc
            else all.fold(acc) { a, ti -> Bindings.setTargets(a, ti, emptyList()) }
        }
    }

    /** The settings [p] can bring to this control (its card's, or all for a button preset); empty = none. */
    fun presetSettings(p: MapPreset): Set<String> {
        val from = p.control ?: return emptySet()
        if (physical || control.isTouchpadStandIn || onControlChange == null || workspace == null) return emptySet()
        val keys = when (p.level) {
            PresetLevel.CARD -> p.kind?.let(MappingPresets::cardSettings).orEmpty()
            PresetLevel.BUTTON -> MappingPresets.BUTTON_SETTINGS
            PresetLevel.CHIP -> emptySet()
        }
        return if (from.kind == control.kind) keys else emptySet()
    }

    /**
     * Applies [p] to [card] (a chip or card preset) or the whole control (a button preset): added
     * beside what is there, or [replace]-ing it; [withSettings]: with the settings it brings,
     * written only where they change something. Chips that just repeat the level above follow it.
     */
    fun applyPreset(p: MapPreset, card: BindableInput?, replace: Boolean, withSettings: Boolean) {
        val next = when (p.level) {
            PresetLevel.CHIP -> {
                val c = card ?: return
                val ch = p.chips.single()
                val base = if (!replace) currentMappings
                else cardInputs(cardOf(c)).fold(currentMappings) { m, ti -> Bindings.setTargets(m, ti, emptyList()) }
                val now = Bindings.entries(base, c)
                val e = Bindings.Entry(ch.target, ch.options, ch.range)
                if (now.any { it.target == e.target && it.shape == e.shape }) base else Bindings.setEntries(base, c, now + e)
            }
            PresetLevel.CARD -> MappingPresets.place(currentMappings, p.chips, cardSlots(cardOf(card ?: return)), replace)
            PresetLevel.BUTTON -> MappingPresets.place(currentMappings, p.chips, controlInputs, replace, ::placeOf, keepFingers = true)
        }
        val cards = if (p.level == PresetLevel.BUTTON || card == null) inputs.filterNot(Bindings::isTriggerOf) else listOf(cardOf(card))
        val keys = if (withSettings) presetSettings(p) else emptySet()
        val from = p.control
        val ws = workspace
        val before = currentControl
        val changed = from != null && ws != null && keys.isNotEmpty() && MappingPresets.applySettings(before, from, keys, ws) != before
        if (changed) onControlChange?.invoke { MappingPresets.applySettings(it, from!!, keys, ws!!) }
        change(
            followSame(next, cards),
            (if (replace) "Replaced with " else "Added ") + p.label + if (changed) " and its settings" else "",
            controlBefore = if (changed) before else null,
        )
    }

    /** A preset asking Add or Replace, for a card (null: the whole control). */
    var presetAsk by remember { mutableStateOf<Pair<MapPreset, BindableInput?>?>(null) }

    /** A preset being named before it is saved: its title, the name offered, and what saves it. */
    var naming by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }

    /** Asks for a preset's name ([title], [initial] offered), then [save]s it. */
    fun askName(title: String, initial: String, save: (String) -> Unit) {
        naming = Triple(title, initial, save)
    }

    /** The card whose faded chip maps [from] (one of a card's Wheres on one of its triggers). */
    fun inheritedCard(from: BindableInput): BindableInput? =
        shownNow.flatMap(::linesOf).firstOrNull { line -> trigInputs(line).any { (_, ti) -> ti.sources == from.sources } }

    /** Every action the level above maps follows it again. */
    fun followAll() {
        val overridden = bulkInputs().filter { Bindings.targets(parentView, it).isNotEmpty() && Bindings.targets(currentMappings, it).isNotEmpty() }
        change(
            overridden.fold(currentMappings) { m, input -> Bindings.setTargets(m, input, emptyList()) },
            "Every action follows " + parentName,
        )
    }

    /** Every action does nothing: its own mappings gone, each of the level above's chips stopped (Off). */
    fun clearAll() = change(
        bulkInputs().fold(currentMappings) { m, input ->
            val bare = Bindings.setTargets(m, input, emptyList())
            withOffs(bare, parentOfInput(input, bare).map { (t, x) -> trig(input, t) to x })
        },
        "Cleared every action",
    )

    /** Each of [ti]'s chips in the edited set: its target and its place among those of that target. */
    fun chipsOf(ti: BindableInput, each: (OutputTarget, Int) -> Unit) {
        val seen = HashMap<OutputTarget, Int>()
        for (e in Bindings.entries(currentMappings, ti)) {
            val n = seen[e.target] ?: 0
            seen[e.target] = n + 1
            each(e.target, n)
        }
    }

    /** The options of [chip] of [card] in [m]; null = none, or not there. */
    fun chipOptions(m: List<Mapping>, card: BindableInput, chip: RowItem.Chip): MapOptions? =
        Bindings.entry(m, trig(chipInput(card, chip), chip.trigger), chip.target, chip.n)?.options

    /** A chip's range as its text shows it ("10–85 %"), or null. */
    fun chipRangeText(m: List<Mapping>, card: BindableInput, chip: RowItem.Chip): String? {
        val r = Bindings.entry(m, trig(chipInput(card, chip), chip.trigger), chip.target, chip.n)?.range ?: return null
        return when (chip.target) {
            is OutputTarget.PadAxis -> "%.0f–%.0f %%".format(r.from * 100, r.to * 100)
            is OutputTarget.GyroSens -> {
                val c = runtime.engine.workspace.gyro.gameCountsPer360
                if (c > 0f) "%.2f–%.2f".format(GameSens.rws(r.from, c), GameSens.rws(r.to, c)) else "%.1f–%.1f".format(r.from, r.to)
            }
            else -> null
        }
    }

    /** [input]'s row as mapped: each zone's chips of the edited set, divided, then the end divider. */
    fun rowOf(input: BindableInput): List<RowItem> = buildList {
        for (z in 0 until zonesOf(input)) {
            if (z > 0) add(RowItem.Divider(z))
            // Its own chips, then its other Wheres' (Area and Outside in zone 1; the Swipe card's directions in every zone).
            wheresIn(input, z).forEachIndexed { w, wi ->
                if (wi != null) for ((tr, ti) in trigInputs(wi)) chipsOf(ti) { t, n -> add(RowItem.Chip(z, t, tr, w, n)) }
            }
        }
        if (hasEnd(input)) add(RowItem.End)
    }

    /** The dragged chip or divider, if it is in [input]'s row: left out of it while carried. */
    fun carried(input: BindableInput): RowItem? = when (val d = dragged) {
        is Dragged.Target -> d.from?.takeIf { !d.inherited }?.let { f ->
            val s0 = f.sources.first()
            val s = s0 as? InputSource.Surface
            val zone = s?.zone ?: 0
            val tr = Triggers.of(s0)
            whereOn(input, s0)?.let { w -> RowItem.Chip(if (w == 0 || zoned(wheresOf(input).getOrNull(w))) zone else 0, d.target, tr, w, d.n) }
        }

        is Dragged.Divider -> if (d.card.sources.first() == input.sources.first()) RowItem.Divider(d.k) else null
        else -> null
    }

    /** [card]'s lines map something themselves, here or (in a layer) in Base: the level above's are then not shown. */
    fun groupOwns(card: BindableInput): Boolean {
        val all = groupOf(card).flatMap(::lineInputs)
        return all.any { ti -> Bindings.targets(view, ti).isNotEmpty() } ||
            (editSet != MappingSet.BASE && all.any { ti -> Bindings.targets(mappings, ti).isNotEmpty() })
    }

    /** Line [line] of the Swipe card shows: it has chips or dividers, or the level above's faded chips. */
    fun lineShown(line: BindableInput, @Suppress("UNUSED_PARAMETER") owns: Boolean): Boolean =
        rowOf(line).isNotEmpty() || (editSet == MappingSet.BASE && parentOf(line).isNotEmpty())

    /**
     * The Swipe card's new line, shown under its lines while a target is dragged: the first direction
     * that shows no line yet (↑ → ↓ ←, then Any); a target dropped there opens its settings to pick the
     * direction. Null for any other card, or when every direction already has its line.
     */
    fun newLineOf(card: BindableInput): BindableInput? {
        val lines = linesOf(card)
        if (lines.size == 1) return null
        val owns = groupOwns(card)
        return (lines.drop(1) + lines.first()).firstOrNull { !lineShown(it, owns) }
    }

    /** The card (or the line of the Swipe card) under [p]: the line it is on, else the Swipe card's new line. */
    fun cardAt(p: Offset): BindableInput? {
        if (!actionsBounds.contains(p)) return null
        val card = shownNow.firstOrNull { cardBounds[it.sources.first()]?.contains(p) == true } ?: return null
        val lines = linesOf(card)
        if (lines.size == 1) return card
        val placed = lines.filter { lineBounds[it.sources.first()] != null }
        return placed.firstOrNull { lineBounds[it.sources.first()]!!.contains(p) }
            ?: newLineOf(card)?.takeIf { dragged is Dragged.Target }
            ?: placed.minByOrNull { abs(lineBounds[it.sources.first()]!!.center.y - p.y) } ?: card
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
     * dividers before it, in its order. Zones left empty close up in [change]. A chip keeps the
     * options it had where [m] maps it ([RowItem.Chip.zone] is where it came from); [carry] gives
     * a chip just dropped (n -1) its options. Exact duplicates (same target and How on one When)
     * are kept once.
     */
    fun withRow(
        m: List<Mapping>,
        card: BindableInput,
        row: List<RowItem>,
        carry: Pair<OutputTarget, MapOptions?>? = null,
    ): List<Mapping> {
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
            val wheres = wheresIn(card, z)
            for ((w, wi) in wheres.withIndex()) for ((tr, ti) in trigInputs(wi ?: continue)) {
                val here = chips.filter { it.trigger == tr && it.where == w }
                // Moved here from another zone or Where: the options it had there.
                out = Bindings.setEntries(out, ti, here.map { c ->
                    Bindings.Entry(
                        c.target, if (c.n < 0) carry?.takeIf { it.first == c.target }?.second else chipOptions(m, card, c),
                        if (c.n < 0) null else Bindings.entry(m, trig(chipInput(card, c), c.trigger), c.target, c.n)?.range,
                    )
                })
            }
        }
        return out
    }

    /** The chip whose settings are open, with its card. */
    var chipDialog by remember { mutableStateOf<Pair<BindableInput, RowItem.Chip>?>(null) }

    /** A faded chip tapped or dragged out (the input it fires on, its output): Override or Replace? */
    var fadedAsk by remember { mutableStateOf<Pair<BindableInput, OutputTarget>?>(null) }

    /**
     * When [input]'s triggers fire, for those it maps (Hold left out): "Tap: at lift before 400 ms ·
     * Long press: from 400 ms"; null when it maps none or has no timings.
     */
    fun timingLine(input: BindableInput): String? {
        // The Swipe card: its directions' too, each trigger once.
        val all = groupOf(input).flatMap { trigInputs(it) }
        val tis = all.filter { (t, ti) ->
            t != Trigger.HOLD && Bindings.targets(view, ti).any { it != OutputTarget.NoOutput }
        }.distinctBy { it.first }
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

    val hovered by remember { derivedStateOf { if (dragged is Dragged.Target || dragged is Dragged.Preset) cardAt(dragPos) else null } }
    var zoneDialog by remember { mutableStateOf<Pair<BindableInput, Int?>?>(null) }
    var actionDialog by remember { mutableStateOf<BindableInput?>(null) }

    /** The settings of [card]'s Area and Outside while they map something: on its ⚙. */
    fun areaGears(card: BindableInput): List<@Composable () -> Unit> =
        wheresOf(card).drop(1).filterNotNull().filter { wi ->
            trigInputs(wi).any { (_, ti) -> Bindings.targets(view, ti).isNotEmpty() }
        }.mapNotNull { settings?.action?.invoke(it) }
    var sharedOpen by remember { mutableStateOf(false) }

    /** What [input]'s ⚙ opens: its own settings, or a held direction's zones; null = no ⚙. */
    fun gearOf(input: BindableInput): (() -> Unit)? {
        if (settings?.action?.invoke(input) != null) return { actionDialog = input }
        if (areaGears(input).isNotEmpty()) return { actionDialog = input }
        if (wheresOf(input).filterNotNull().flatMap { trigInputs(it) }.any { (t, ti) ->
                Bindings.targets(view, ti).any { it is OutputTarget.Key } ||
                    (t != Trigger.HOLD && Bindings.targets(view, ti).isNotEmpty() && settings?.action?.invoke(ti) != null)
            }) return { actionDialog = input }
        if (onZoneSettings != null && zonesNow && mapsAny(zoneKeys(input))) {
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
                    // Dropped on the Swipe card's new line: its settings open, to pick its direction.
                    val onNewLine = groupOf(card).size > 1 && newLineOf(groupOf(card).first())?.sources?.first() == card.sources.first()
                    // Where something lands is the selected card from then on.
                    selectedSrc = card.sources.first()
                    val c = carried(card)
                    val row = rowOf(card).filter { it != c }.toMutableList()
                    val i = at.coerceIn(0, row.size)
                    val zone = zoneIn(row, i)
                    // A chip keeps its Where on its own card (Area and Outside in zone 1 only); anything else
                    // lands on the line itself.
                    val w0 = (c as? RowItem.Chip)?.where ?: from?.let { whereOn(card, it.sources.first()) } ?: 0
                    val w = if (zone > 0 && !zoned(wheresOf(card).getOrNull(w0))) 0 else w0
                    val zi = wheresIn(card, zone).getOrNull(w) ?: zin(card, zone)
                    if (from == null && (d.target as? OutputTarget.Layer)?.set?.let { it < 0 } == true) {
                        pick(zi, d.target)
                    } else {
                        if (from == null) addRecent(d.target)
                        // A chip keeps its trigger where the card offers it; a new one is Hold.
                        val tr0 = when {
                            c != null -> (c as? RowItem.Chip)?.trigger ?: Trigger.HOLD
                            from != null -> Triggers.of(from.sources.first())
                            else -> Trigger.HOLD
                        }.takeIf { it in Bindings.triggersOf(zi) } ?: Trigger.HOLD
                        // A new one already mapped there goes to the next When that does not map it yet
                        // (On touch Crouch, then Long press Crouch), its settings opened to pick When and How.
                        // A moved chip keeps its options (Toggle, Once, delays...).
                        // A chip preset's new chip takes its How and options.
                        val kept = from?.let {
                            (if (d.inherited) null else Bindings.entry(currentMappings, it, d.target, d.n)?.options)
                                ?: Bindings.options(parentView, it, d.target)
                        } ?: d.options
                        val shape = (kept ?: MapOptions()).shape
                        fun maps(t: Trigger) = Bindings.entries(currentMappings, trig(zi, t)).any { it.target == d.target && it.shape == shape }
                        val fresh = if (from == null && maps(tr0)) Bindings.triggersOf(zi).firstOrNull { !maps(it) } else null
                        val tr = fresh ?: tr0
                        val base = if (from == null || c != null || d.inherited) currentMappings else Bindings.removeEntry(currentMappings, from, d.target, d.n)
                        // Already there alike (same When and How): just moved there, not twice.
                        val twin = row.indexOfFirst {
                            it is RowItem.Chip && it.target == d.target && it.trigger == tr && it.where == w &&
                                (chipOptions(base, card, it) ?: MapOptions()).shape == shape
                        }
                        var to = i
                        if (c == null && twin >= 0 && zoneIn(row, twin) == zone) {
                            row.removeAt(twin)
                            if (twin < i) to--
                        }
                        row.add(to.coerceIn(0, row.size), RowItem.Chip(zone, d.target, tr, w, -1))
                        // A faded chip dropped on a card is mapped there as its own (on its own card, that replaces it).
                        val placed = withRow(base, card, row, if (from != null || d.options != null) d.target to kept else null)
                        change(
                            Inheritance.tidy(placed),
                            (if (from == null || d.inherited) "Mapped " else "Moved ") + Describe.shortTarget(d.target) + " to " + actionTitle(currentControl, zi),
                        )
                        if (fresh != null || onNewLine) chipDialog = card to RowItem.Chip(zone, d.target, tr, w,
                            Bindings.entries(placed, trig(zi, tr)).filter { it.target == d.target }.indexOfFirst { it.shape == shape }.coerceAtLeast(0))
                    }
                    done = true
                } else if (from != null && libraryBounds.contains(dragPos)) {
                    // Dropped back on the library it goes; anywhere else it stays. A faded chip asks
                    // whether to stop it (Override) or make it its own (Replace).
                    if (d.inherited) fadedAsk = from to d.target else remove(from, d.target, d.n)
                    done = true
                }
            }

            is Dragged.Divider -> if (l != null) {
                val (card, at) = l
                val row = rowOf(card).filter { it != carried(card) }.toMutableList()
                row.add(at.coerceIn(0, row.size), RowItem.Divider(d.k))
                // A chip passed into a later zone fires on Hold there when that zone doesn't offer its trigger
                // (a Quick or Double swipe), as a dropped chip does.
                val moved = row.mapIndexed { j, item ->
                    val z = zoneIn(row, j)
                    if (item is RowItem.Chip && z > 0 && !zoned(wheresOf(card).getOrNull(item.where))) item.copy(where = 0, trigger = Trigger.HOLD)
                    else if (item is RowItem.Chip && z > 0 && item.trigger !in Bindings.triggersOf(zin(card, z))) item.copy(trigger = Trigger.HOLD)
                    else item
                }
                change(withRow(currentMappings, card, moved), "Moved a zone divider of " + actionTitle(currentControl, card))
                done = true
            }

            // A card preset onto a card of its kind, a button preset anywhere on the cards: Add or Replace?
            is Dragged.Preset -> if (d.preset.level == PresetLevel.BUTTON) {
                if (actionsBounds.contains(dragPos)) {
                    presetAsk = d.preset to null
                    done = true
                }
            } else {
                cardAt(dragPos)?.let(::cardOf)?.takeIf { presetFits(d.preset, it) }?.let { c ->
                    selectedSrc = c.sources.first()
                    presetAsk = d.preset to c
                    done = true
                }
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
            if (control.kind == TouchKind.STICK && !physical) {
                item(key = "presets") {
                    Row(
                        Modifier.animateItem().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (p in Bindings.StickPreset.entries) {
                            FilterChip(
                                selected = presetMatches(view, inputs, p),
                                onClick = { presets.firstOrNull { it.stick == p }?.let { presetAsk = it to null } },
                                label = { Text(p.label) },
                            )
                        }
                    }
                }
            }
            shownGroups.forEachIndexed { g, list ->
                if (!surfaceCards && physicalMotionId != null) {
                    item(key = "header$g") {
                        Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = fingers != 0, onClick = { fingers = 1 }, label = { Text("Button") })
                                FilterChip(selected = fingers == 0, onClick = { fingers = 0 }, label = { Text("Motion") })
                            }
                            if (fingers == 0) {
                                Text(
                                    "While this is held: a gyro axis it maps does that instead of what the workspace maps it to " +
                                        "(faded until it maps its own); ⚙ sets the axis's sensitivity while held.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (surfaceCards) {
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
                    // A card preset fits only cards of its kind: the others grey out.
                    val dragCard = (d as? Dragged.Preset)?.preset?.takeIf { it.level == PresetLevel.CARD }
                    val fits = (dragTarget == null || allows(allowedTargets(input), dragTarget)) && (dragCard == null || presetFits(dragCard, input))
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
                    val all = cardInputs(input)
                    val own = all.flatMap { ti -> Bindings.targets(view, ti) }
                    val cardLines = linesOf(input)
                    val swipe = cardLines.size > 1
                    // A layer shows only its own mappings: nothing faded from the level above there.
                    val parent = if (editSet == MappingSet.BASE) cardLines.flatMap(::parentOf) else emptyList()
                    // The level above passes down the whole card (Inheritance.cardOf) while it maps nothing
                    // itself, here or, in a layer, in Base: Base's mapping is what the layer does there.
                    val owns = own.isNotEmpty() || (editSet != MappingSet.BASE && all.any { ti -> Bindings.targets(mappings, ti).isNotEmpty() })
                    val hoverHere = hovered != null && cardLines.any { it.sources.first() == hovered?.sources?.first() }

                    // While a target is dragged, one new line under the others takes it (see newLineOf).
                    val newLine = if (swipe && d is Dragged.Target && fits) newLineOf(input) else null

                    /**
                     * Line [i] of the Swipe card ([line]: Any, then ↑ → ↓ ←): shown while it maps something
                     * (or shows the level above's); the new line while a target is dragged.
                     */
                    @Composable
                    fun swipeLine(i: Int, line: BindableInput): CardLine? {
                        val ls = line.sources.first()
                        val lrow = liveRow(line)
                        val inh = if (editSet != MappingSet.BASE) emptyList() else parentOf(line)
                        val rs = rememberScrollState()
                        DisposableEffect(ls) {
                            onDispose {
                                lineBounds.remove(ls)
                                itemBounds.keys.removeAll { it.first == ls }
                                rowScrolls.remove(ls)
                                rowBounds.remove(ls)
                            }
                        }
                        val isNew = newLine?.sources?.first() == ls
                        val show = lrow.isNotEmpty() || inh.isNotEmpty() || isNew ||
                            (d is Dragged.Divider && d.card.sources.first() == ls)
                        if (!show) {
                            SideEffect { lineBounds.remove(ls) }
                            return null
                        }
                        rowScrolls[ls] = rs
                        val lz = zonesOf(line)
                        val lands = landing?.first?.sources?.first() == ls
                        return CardLine(
                            key = ls,
                            label = if (isNew) "" else Bindings.slotLabel(if (i == 0) ANY_SLOT else i - 1),
                            selected = selected?.sources?.first() == ls,
                            row = lrow,
                            inherited = inh,
                            others = List(lz) { emptyList() },
                            optionsOf = { chip -> chipOptions(view, line, chip) },
                            rangeOf = { chip -> chipRangeText(view, line, chip) },
                            badge = { tr, _ -> Triggers.badge(ls, tr) },
                            landingZone = if (lands && (lz > 1 || lrow.any { it == RowItem.End })) zoneIn(lrow, lrow.indexOf(RowItem.Landing)) else -1,
                            addZone = addsZone(line),
                            rowScroll = rs,
                            onBounds = { b -> if (lineBounds[ls] != b) lineBounds[ls] = b },
                            onRowBounds = { b -> rowBounds[ls] = b },
                            onItemBounds = { item, b -> itemBounds[ls to item] = b },
                            onDivider = { k -> if (onZoneSettings != null) zoneDialog = line to k - 1 },
                            onDividerDrag = { k, at -> selectedSrc = ls; startDrag(Dragged.Divider(line, k), at) },
                            onChipDrag = { chip, at ->
                                selectedSrc = ls
                                startDrag(Dragged.Target(chip.target, trig(chipInput(line, chip), chip.trigger), n = chip.n), at)
                            },
                            onChipClick = { chip -> selectedSrc = ls; chipDialog = line to chip },
                            onInheritedDrag = { f, at ->
                                selectedSrc = ls
                                startDrag(Dragged.Target(f.target, trig(line, f.trigger), inherited = true), at)
                            },
                            onInheritedClick = { f -> selectedSrc = ls; fadedAsk = trig(line, f.trigger) to f.target },
                            onClick = { selectedSrc = ls },
                        )
                    }
                    // The new line goes last, under the mapped ones.
                    val lines = if (!swipe) null else cardLines.mapIndexedNotNull { i, line -> key(line.sources.first()) { swipeLine(i, line) } }
                        .sortedBy { it.key == newLine?.sources?.first() }
                    ActionCard(
                        lines = lines,
                        title = when {
                            isSurface(input) && (src as InputSource.Surface).input == SurfaceInput.HOLD -> "Touch"
                            isSurface(input) -> input.label
                            else -> actionLabel(control, input)
                        },
                        row = row,
                        inherited = if (swipe) emptyList() else parent,
                        parentName = parentName,
                        onClear = if (parent.isNotEmpty()) ({ clear(input) }) else null,
                        onFollow = if (own.isNotEmpty() && editSet == MappingSet.BASE && cardLines.any { parentOfInput(it, emptyList()).isNotEmpty() }) ({ follow(input) }) else null,
                        // Each set shows only its own chips: other sets' would clutter it.
                        others = zoneInputs.map { emptyList() },
                        optionsOf = { chip -> chipOptions(view, input, chip) },
                        rangeOf = { chip -> chipRangeText(view, input, chip) },
                        badge = { tr, w ->
                            val wi = wheresOf(input).getOrNull(w)
                            if (w == 0 || wi == null) Triggers.badge(src, tr)
                            else listOf(wi.label, Triggers.badge(wi.sources.first(), tr)).filter { it.isNotEmpty() }.joinToString(" · ")
                        },
                        timing = timingLine(input),
                        setOrder = sets.map { it.id },
                        setId = editSet,
                        onOther = onEditSet,
                        // Only one card stands out: while a target is over another card, that one does.
                        selected = cardLines.any { it == selected } && hovered.let { h ->
                            h == null || h == input || hoverHere || dragTarget == null || !allows(allowedTargets(h), dragTarget)
                        },
                        hover = (dragTarget != null || dragCard != null) && fits && (hovered == input || hoverHere),
                        landingLabel = when (d) {
                            is Dragged.Target -> Describe.shortTarget(d.target)
                            else -> null
                        },
                        landingZone = if (!swipe && landsHere && (zoneCount > 1 || row.any { it == RowItem.End })) zoneIn(row, row.indexOf(RowItem.Landing)) else -1,
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
                        // The Swipe card: a tap picks its ↑ line, where library taps then add.
                        onClick = { selectedSrc = cardLines.getOrNull(1)?.sources?.first() ?: src },
                        onChipDrag = { chip, at ->
                            selectedSrc = src
                            val from = trig(chipInput(input, chip), chip.trigger)
                            startDrag(Dragged.Target(chip.target, from, n = chip.n), at)
                        },
                        onChipClick = { chip -> selectedSrc = src; chipDialog = input to chip },
                        onInheritedDrag = { f, at ->
                            selectedSrc = src
                            startDrag(Dragged.Target(f.target, trig(wheresOf(input).getOrNull(f.where) ?: input, f.trigger), inherited = true), at)
                        },
                        onInheritedClick = { f ->
                            selectedSrc = src
                            fadedAsk = trig(wheresOf(input).getOrNull(f.where) ?: input, f.trigger) to f.target
                        },
                        onHandleDrag = { at -> startCardDrag(input, at) },
                        onSavePreset = if (own.any { it != OutputTarget.NoOutput }) ({
                            val title = actionTitle(control, input)
                            askName("Save card preset", title) { name ->
                                runtime.saveMappingPreset(
                                    MappingPreset(
                                        MappingPresets.newId(userPresets.map { it.id }), name, PresetLevel.CARD,
                                        MappingPresets.kindOf(input), MappingPresets.chipsOf(view, cardSlots(input)),
                                        control.takeIf { !physical && !control.isTouchpadStandIn },
                                    ),
                                )
                                scope.launch { snackbar.showSnackbar("Saved card preset " + name + " (Presets)") }
                            }
                        }) else null,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    var query by remember { mutableStateOf("") }
    val tabs = remember(extras, physical) {
        LibraryTab.entries.filter { (it != LibraryTab.TOUCHPAD || extras.isNotEmpty()) && !(physical && it == LibraryTab.PHONE) }
    }
    var tab by remember(control.id) {
        mutableStateOf(
            when {
                recent.isNotEmpty() -> LibraryTab.RECENT
                control.kind == TouchKind.STICK && !physical -> LibraryTab.PRESETS
                else -> LibraryTab.KEYBOARD
            },
        )
    }
    val chipCarried = (dragged as? Dragged.Target)?.from != null
    // Read through derived state, so a moving finger doesn't recompose the panes every frame.
    val overLibrary by remember { derivedStateOf { (dragged as? Dragged.Target)?.from != null && libraryBounds.contains(dragPos) } }
    val libraryPane: @Composable () -> Unit = {
        val q = query.trim()
        val shownPresets = when {
            q.isNotEmpty() -> presets.filter { it.label.contains(q, ignoreCase = true) }
            tab == LibraryTab.PRESETS -> presets
            else -> emptyList()
        }
        val presetsTab = q.isEmpty() && tab == LibraryTab.PRESETS
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
                SettingsSearchField(
                    query,
                    { query = it },
                    Modifier.padding(horizontal = 12.dp),
                    placeholder = "Search, e.g. W, LT, wheel",
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
                if (presetsTab) PresetLegend(Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                Text(
                    when {
                        presetsTab -> "Tap to add or replace · long-press to drag onto a card"
                        selected == null -> "Long-press a target to drag it onto an action."
                        else -> "Tap to add to " + actionTitle(control, selected) + " · long-press to drag"
                    },
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
                            val chip = p.chips.singleOrNull()?.takeIf { p.level == PresetLevel.CHIP }
                            LibraryChip(
                                text = p.label,
                                // A chip or card preset goes onto the selected card: greyed where it doesn't fit.
                                enabled = p.level == PresetLevel.BUTTON || presetFits(p, selected),
                                mapped = p.stick?.let { presetMatches(view, inputs, it) } == true,
                                preset = true,
                                level = p.level,
                                dragging = (dragged as? Dragged.Preset)?.preset == p ||
                                    (chip != null && (dragged as? Dragged.Target)?.let { it.from == null && it.target == chip.target && it.options == chip.options } == true),
                                dragKey = "preset:" + p.level + ":" + p.label + ":" +
                                    ((p.user as? MappingPreset)?.id ?: (p.user as? LibraryButton)?.id ?: ""),
                                onClick = { presetAsk = p to (if (p.level == PresetLevel.BUTTON) null else selected) },
                                // A chip preset drags as its chip; a card or button preset asks where it lands.
                                onDragStart = { at ->
                                    if (chip != null) startDrag(Dragged.Target(chip.target, null, options = chip.options), at)
                                    else startDrag(Dragged.Preset(p), at)
                                },
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

    FullScreenDialog(onDismiss = onClose) {
        Surface(Modifier.fillMaxSize(), color = colors.surface) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
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
                    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
                    // The bar has two rows. The title (the label field) leads the first row in portrait;
                    // the layer chips always lead their row; the actions follow them.
                    val titleBar: @Composable () -> Unit = {
                            Column(Modifier.widthIn(max = 220.dp).padding(end = 8.dp)) {
                                if (label != null && onLabel != null) {
                                    // The title is the label: type, add emoji or pick a symbol (☺).
                                    LabelField(label, if (control.isTouchpadStandIn) "Touchpad" else "Label", onLabel)
                                } else Text(
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
                            if (labelShown != null && onLabelShown != null) {
                                FilterChip(
                                    selected = labelShown,
                                    onClick = { onLabelShown(!labelShown) },
                                    label = { Text("Show on pad") },
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                    }
                    val layerChips: @Composable () -> Unit = {
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
                    }
                    val actionButtons: @Composable () -> Unit = {
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
                            val bulk = bulkInputs()
                            if (bulk.any { Bindings.targets(parentView, it).isNotEmpty() && Bindings.targets(view, it).isNotEmpty() }) {
                                var asking by remember { mutableStateOf(false) }
                                TextButton(onClick = { asking = true }, contentPadding = compact) { Text("↺ Reset all") }
                                if (asking) ConfirmResetAll(
                                    "Every action follows $parentName again; what it maps here goes.",
                                    { asking = false },
                                ) { asking = false; followAll() }
                            }
                            if (bulk.any { (Bindings.targets(view, it).ifEmpty { Bindings.targets(parentView, it) }).any { t -> t != OutputTarget.NoOutput } }) {
                                TextButton(onClick = { clearAll() }, contentPadding = compact) { Text("✕ Clear all") }
                            }
                            // The whole control as a button preset (its mappings in the edited layer, its settings).
                            if (bulk.any { Bindings.targets(view, it).any { t -> t != OutputTarget.NoOutput } }) {
                                TextButton(onClick = {
                                    askName("Save button preset", control.label.ifBlank { controlKindName(control) }) { name ->
                                        val own = view.filter { m -> m.set == MappingSet.BASE && controlInputs.any { Bindings.belongs(m, it) } }
                                        val touch = !physical && !control.isTouchpadStandIn
                                        runtime.addToLibrary(
                                            LibraryButton(
                                                ButtonLibrary.newId(library.map { it.id }), control.copy(group = null),
                                                mappings = if (touch) own else emptyList(), name = name,
                                                chips = if (touch) emptyList() else buttonChips(view),
                                            ),
                                        )
                                        scope.launch {
                                            snackbar.showSnackbar("Saved button preset " + name + if (touch) " (Presets, and the Add menu's library)" else " (Presets)")
                                        }
                                    }
                                }, contentPadding = compact) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("+")
                                        PresetIcon(PresetLevel.BUTTON, colors.primary)
                                        Text("Preset")
                                    }
                                }
                            }
                            // One Settings: a touch control's own page (its look and the settings its actions
                            // share), else the shared settings (the touchpad's: the workspace's).
                            val shared = settings?.shared
                            val ownSettings = onSettings != null && !control.isTouchpadStandIn && physicalInputs == null
                            val openSettings: (() -> Unit)? = when {
                                ownSettings -> onSettings
                                shared != null -> ({ sharedOpen = true })
                                else -> onSettings
                            }
                            if (openSettings != null) TextButton(onClick = openSettings, contentPadding = compact) { Text("⚙ Settings") }
                            TextButton(
                                onClick = { undoLast() },
                                enabled = undo.isNotEmpty(),
                                contentPadding = compact
                            ) { Text("↶ Undo") }
                    }
                    val doneButton: @Composable () -> Unit = {
                            FilledTonalButton(onClick = onClose, contentPadding = compact) { Text("Done") }
                    }
                    val barRow = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                    // The bar sits on its own background, divided from the editor under it.
                    Column(Modifier.fillMaxWidth().background(colors.surfaceContainer)) {
                        if (portrait) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { titleBar() }
                                doneButton()
                            }
                            Row(barRow, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                layerChips(); actionButtons()
                            }
                        } else {
                            Row(barRow, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                layerChips(); titleBar()
                            }
                            Row(barRow, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                actionButtons(); doneButton()
                            }
                    }
                    HorizontalDivider(color = colors.outlineVariant)
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
        // The Swipe card's zones are the shared ones every direction follows (an arrow's own are in its ⚙ settings).
        val dir = src?.input?.holdDirection
        val zs = zoneSettings
        if (src != null && src.input.heldSwipe && zs != null && onZoneSettings != null) {
            ContextCard(actionTitle(control, input) + if (zone == null) " · zones" else " · zone " + (zone + 1), onDismiss = { zoneDialog = null }) {
                HoldZoneSettings(zs, Bindings.holdZoneCounts(mappings, src.id), control.id + "dlg", only = dir, zone = zone, onChange = onZoneSettings)
            }
        }
    }
    actionDialog?.let { input ->
        val content = settings?.action?.invoke(input)
        // A card's triggers that map something: their timing settings (long press time, double tap window), once each.
        val mappedTriggers = wheresOf(input).filterNotNull().flatMap { trigInputs(it) }
            .filter { (t, ti) -> t != Trigger.HOLD && Bindings.targets(view, ti).isNotEmpty() }
        fun mapped(vararg ts: Trigger) = mappedTriggers.firstOrNull { it.first in ts }?.second
        val timingContents = (
            if (Triggers.isSwipe(input.sources.first())) {
                // A swipe: its Long hold and Double swipe times (each arrow's Quick swipe settings are in its own ⚙).
                listOfNotNull(mapped(Trigger.LONG_PRESS, Trigger.DOUBLE_TAP))
            } else {
                listOfNotNull(mapped(Trigger.TAP, Trigger.LONG_PRESS), mapped(Trigger.DOUBLE_TAP))
            }
        ).mapNotNull { settings?.action?.invoke(it) } + areaGears(input)
        if (content != null || timingContents.isNotEmpty()) ContextCard("⚙ " + actionTitle(control, input), onDismiss = { actionDialog = null }) {
            content?.invoke()
            for (c in timingContents) c()
        }
    }
    presetAsk?.let { (p, card) ->
        val user = p.user
        PresetApplyDialog(
            name = p.label,
            level = p.level,
            where = if (card == null) "this " + controlKindName(control).lowercase() else actionTitle(control, cardOf(card)),
            canAdd = p.chips.isNotEmpty(),
            withSettings = presetSettings(p).isNotEmpty(),
            onApply = { replace, withSettings ->
                presetAsk = null
                if (p.level != PresetLevel.BUTTON && (card == null || !presetFits(p, card))) {
                    scope.launch { snackbar.showSnackbar("Select a card it fits first") }
                } else applyPreset(p, card, replace, withSettings)
            },
            onDismiss = { presetAsk = null },
            onRename = when (user) {
                is MappingPreset -> ({ presetAsk = null; askName("Rename preset", user.name) { n -> runtime.saveMappingPreset(user.copy(name = n)) } })
                is LibraryButton -> ({ presetAsk = null; askName("Rename preset", ButtonLibrary.name(user)) { n -> runtime.updateLibrary(user.copy(name = n)) } })
                else -> null
            },
            onDelete = when (user) {
                is MappingPreset -> ({ presetAsk = null; runtime.removeMappingPreset(user.id) })
                is LibraryButton -> ({ presetAsk = null; runtime.removeFromLibrary(user.id) })
                else -> null
            },
        )
    }
    naming?.let { (title, initial, save) ->
        PresetNameDialog(title, initial, onSave = { n -> naming = null; save(n) }, onDismiss = { naming = null })
    }
    fadedAsk?.let { (ti, x) ->
        AlertDialog(
            onDismissRequest = { fadedAsk = null },
            title = { Text(Describe.shortTarget(x) + " from " + parentName) },
            text = {
                Text(
                    "Override: it does nothing here (a Nothing takes its place); " + parentName + "'s other chips still come.\n" +
                        "Replace: it becomes a chip of its own, the same, to change as you like; if you change it, " +
                        parentName + "'s comes back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    fadedAsk = null
                    change(withCopy(currentMappings, ti, x), "Replaced " + Describe.shortTarget(x) + " with its own")
                }) { Text("Replace") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { fadedAsk = null }) { Text("Cancel") }
                    TextButton(onClick = {
                        fadedAsk = null
                        change(withOffs(currentMappings, listOf(ti to x)), "Overrode " + Describe.shortTarget(x) + " (does nothing here)")
                    }) { Text("Override") }
                }
            },
        )
    }
    chipDialog?.let { (input, chip) ->
        val zi = chipInput(input, chip)
        val ti = trig(zi, chip.trigger)
        // Set orientation's Granular switch, applied with the rest.
        var granular by remember(chip) { mutableStateOf((chip.target as? OutputTarget.SetOrientation)?.granular == true) }
        // A button's touch card: a chip can move between its Touch, Area and Outside (Where); the Swipe
        // card's between its directions (Any, ↑ → ↓ ←), in its zone.
        val group = groupOf(input)
        val swipeCard = group.size > 1
        val names = if (swipeCard) List(group.size) { Bindings.slotLabel(if (it == 0) ANY_SLOT else it - 1) }
        else listOf(WHERE_TOUCH, WHERE_AREA, WHERE_OUTSIDE)
        val choices = if (swipeCard) group.map { zin(it, chip.zone) } else wheresIn(input, chip.zone)
        val whereInputs = choices.mapIndexedNotNull { w, wi -> wi?.let { names[w] to it } }
            .takeIf { (chip.zone == 0 || swipeCard) && it.size > 1 }.orEmpty()
        val whereNow = whereInputs.indexOfFirst { it.second.sources == zi.sources }.coerceAtLeast(0)
        fun inputAt(w: Int) = whereInputs.getOrNull(w)?.second ?: input
        MappingChipDialog(
            wheres = whereInputs.map { it.first },
            where = whereNow,
            whereTitle = if (swipeCard) "Direction" else null,
            title = Describe.shortTarget(chip.target) + " · " + actionTitle(control, input) +
                if (chip.zone > 0) " · zone " + (chip.zone + 1) else "",
            triggers = Bindings.triggersOf(zi),
            trigger = chip.trigger,
            triggerLabel = { w, t -> trigLabel(inputAt(w), t) },
            swipe = Triggers.isSwipe(input.sources.first()),
            analog = chip.target is OutputTarget.PadAxis || chip.target is OutputTarget.MouseAxis,
            options = Bindings.entry(view, ti, chip.target, chip.n)?.options,
            range = chipRange(chip.target, Bindings.entry(view, ti, chip.target, chip.n)?.range, runtime.engine.workspace.gyro),
            onApply = { tr, o, w, r ->
                chipDialog = null
                val target = (chip.target as? OutputTarget.SetOrientation)?.copy(granular = granular) ?: chip.target
                val destInput = if (whereInputs.isEmpty()) zi else inputAt(w)
                val dest = trig(destInput, if (tr in Bindings.triggersOf(destInput)) tr else Trigger.HOLD)
                // Another trigger: it leaves this one's chips and joins that one's (keeping their order); one
                // the same there (same output and How) it merges with.
                val es = Bindings.entries(currentMappings, ti)
                var k = -1
                val at = es.indexOfFirst { it.target == chip.target && ++k == chip.n }
                val now = Bindings.Entry(target, o, r)
                change(
                    if (dest == ti && at >= 0) Bindings.setEntries(currentMappings, ti, es.toMutableList().apply { set(at, now) })
                    else Bindings.removeEntry(currentMappings, ti, chip.target, chip.n).let { base ->
                        Bindings.setEntries(base, dest, Bindings.entries(base, dest) + now)
                    },
                    Describe.shortTarget(chip.target) + ": " + trigLabel(if (whereInputs.isEmpty()) input else inputAt(w), tr) +
                            if (o == null) "" else " with options",
                )
            },
            onRemove = {
                chipDialog = null
                remove(ti, chip.target, chip.n)
            },
            onSavePreset = {
                chipDialog = null
                val e = Bindings.entry(view, ti, chip.target, chip.n)
                askName("Save chip preset", chipText("", chip.target, e?.options)) { name ->
                    runtime.saveMappingPreset(
                        MappingPreset(
                            MappingPresets.newId(userPresets.map { it.id }), name, PresetLevel.CHIP, MappingPresets.kindOf(input),
                            listOf(PresetChip(MappingPresets.slot(ti.sources.first()), chip.target, e?.options, e?.range)),
                        ),
                    )
                    scope.launch { snackbar.showSnackbar("Saved chip preset " + name + " (Presets)") }
                }
            },
            onDismiss = { chipDialog = null },
            onTargetSettings = if (Describe.isHaptic(chip.target)) {
                {
                    chipDialog = null
                    hapticDraft = ti to chip.target
                }
            } else null,
            targetOptions = if (chip.target is OutputTarget.SetOrientation) {
                {
                    OverrideSwitch(
                        "Granular",
                        if (granular) "Takes the exact angle you tilt the phone back as the new hold position (leaning sideways is left out)."
                        else "Snaps to the screen axis pointing most nearly up, either way up.",
                        granular, overridden = false, onReset = null,
                    ) { granular = it }
                }
            } else null,
        )
    }
    val shared = settings?.shared
    if (sharedOpen && shared != null) {
        SettingsDialog(
            title = control.label.ifBlank { control.id },
            subtitle = if (control.isTouchpadStandIn) "The empty pad area · this workspace's settings"
            else controlKindName(control) + " · settings its actions share",
            onClose = { sharedOpen = false },
            top = settings?.sharedTop,
        ) { shared() }
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
                        // The undo steps hold the deleted layer's mappings: undoing one would bring them back without it.
                        undo.clear()
                        snackbar.currentSnackbarData?.dismiss()
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
    inherited: List<Faded>,
    parentName: String,
    /** ✕: it maps nothing, overriding the level above; null = no ✕. */
    onClear: (() -> Unit)?,
    /** ↺: it follows the level above again; null = no ↺. */
    onFollow: (() -> Unit)?,
    /** Per zone, other sets' targets (set, target, trigger): greyed at the end of that zone. */
    others: List<List<Triple<Int, OutputTarget, Trigger>>>,
    /** A chip's options (How, cycle...), marked on it. */
    optionsOf: (RowItem.Chip) -> MapOptions?,
    /** An axis chip's range as text, marked on it; null = none. */
    rangeOf: (RowItem.Chip) -> String? = { null },
    /** A trigger's badge on this card's chips, by Where ([RowItem.Chip.where]). */
    badge: (Trigger, Int) -> String,
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
    /** A long press on a faded chip starts dragging it: onto a card it is mapped there; onto the library it asks (Override or Replace). */
    onInheritedDrag: (Faded, Offset) -> Unit,
    /** A tap on a faded chip asks: Override (stop it here) or Replace (make it its own). */
    onInheritedClick: (Faded) -> Unit,
    /** The ⠿ handle starts moving the card. */
    onHandleDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    /** Saves the card as a card preset (its icon with a +, before ✕ and ↺); null = nothing to save. */
    onSavePreset: (() -> Unit)? = null,
    /** A card of several labelled lines (the Swipe card's directions), each its own row; null = the one [row]. */
    lines: List<CardLine>? = null,
) {
    val colors = MaterialTheme.colorScheme
    val handleCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val handleDrag by rememberUpdatedState(onHandleDrag)
    val empty = if (lines != null) lines.none { l -> l.row.any { it is RowItem.Chip } } else row.none { it is RowItem.Chip }
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
                if (onSavePreset != null) {
                    Row(
                        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onSavePreset).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("+", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                        PresetIcon(PresetLevel.CARD, colors.onSurfaceVariant)
                    }
                }
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
            if (lines != null) {
                if (lines.isEmpty()) Text(
                    if (hover) "Release to map" else "Drop here",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                for (ln in lines) key(ln.key) {
                    Column(
                        Modifier.onGloballyPositioned { ln.onBounds(it.boundsInRoot()) },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = ln.onClick).padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (ln.label.isEmpty()) Text(
                                "New",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.tertiary,
                            ) else DirectionLabel(
                                ln.label,
                                color = if (ln.selected) colors.primary else colors.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                bold = ln.selected,
                            )
                            if (ln.landingZone >= 0) Text(
                                "  → Zone " + (ln.landingZone + 1) + if (ln.landingZone >= ln.others.size) " (new)" else "",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.tertiary,
                            )
                        }
                        if (ln.row.isEmpty() && ln.inherited.isEmpty()) Text(
                            "Drop here",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                        ) else ChipRow(ln, parentName, setOrder, setId, onOther, landingLabel)
                    }
                }
            } else if (nothing) {
                Text(
                    if (hover) "Release to map" else "Drop here",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            } else {
                ChipRow(
                    CardLine(
                        key = "", label = "", selected = selected, row = row, inherited = inherited, others = others,
                        optionsOf = optionsOf, rangeOf = rangeOf, badge = badge, landingZone = landingZone, addZone = addZone,
                        rowScroll = rowScroll, onBounds = {}, onRowBounds = onRowBounds, onItemBounds = onItemBounds,
                        onDivider = onDivider, onDividerDrag = onDividerDrag, onChipDrag = onChipDrag, onChipClick = onChipClick,
                        onInheritedDrag = onInheritedDrag, onInheritedClick = onInheritedClick, onClick = onClick,
                    ),
                    parentName, setOrder, setId, onOther, landingLabel,
                )
            }
            if (timing != null) Text(timing, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }
}

/** One line of a card: its [label] (none on a one-line card), its chips and dividers, and what they do. */
private class CardLine(
    val key: Any,
    val label: String,
    val selected: Boolean,
    val row: List<RowItem>,
    val inherited: List<Faded>,
    val others: List<List<Triple<Int, OutputTarget, Trigger>>>,
    val optionsOf: (RowItem.Chip) -> MapOptions?,
    val rangeOf: (RowItem.Chip) -> String?,
    val badge: (Trigger, Int) -> String,
    val landingZone: Int,
    val addZone: Boolean,
    val rowScroll: ScrollState,
    val onBounds: (Rect) -> Unit,
    val onRowBounds: (Rect) -> Unit,
    val onItemBounds: (RowItem, Rect) -> Unit,
    val onDivider: (Int) -> Unit,
    val onDividerDrag: (Int, Offset) -> Unit,
    val onChipDrag: (RowItem.Chip, Offset) -> Unit,
    val onChipClick: (RowItem.Chip) -> Unit,
    val onInheritedDrag: (Faded, Offset) -> Unit,
    val onInheritedClick: (Faded) -> Unit,
    val onClick: () -> Unit,
)

/** [line]'s framed, sideways scrolling row of chips and zone dividers (see [ActionCard]). */
@Composable
private fun ChipRow(
    line: CardLine,
    parentName: String,
    setOrder: List<Int>,
    setId: Int,
    onOther: (Int) -> Unit,
    landingLabel: String?,
) {
    val colors = MaterialTheme.colorScheme
    val chipDrag by rememberUpdatedState(line.onChipDrag)
    val chipClick by rememberUpdatedState(line.onChipClick)
    val inheritedDrag by rememberUpdatedState(line.onInheritedDrag)
    val inheritedClick by rememberUpdatedState(line.onInheritedClick)
    val dividerDrag by rememberUpdatedState(line.onDividerDrag)
    // A framed row; a shadow at an end that has more to scroll to.
    val shade = colors.scrim.copy(alpha = 0.28f)
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { line.onRowBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(12.dp))
            // Plain inside, whatever the card's highlight, so the chips stay easy to read.
            .background(colors.surface)
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            .drawWithContent {
                drawContent()
                val w = ROW_SHADE_DP.dp.toPx()
                if (line.rowScroll.canScrollBackward) {
                    drawRect(Brush.horizontalGradient(listOf(shade, Color.Transparent), 0f, w), size = Size(w, size.height))
                }
                if (line.rowScroll.canScrollForward) {
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, shade), size.width - w, size.width),
                        topLeft = Offset(size.width - w, 0f), size = Size(w, size.height),
                    )
                }
            }
            .horizontalScroll(line.rowScroll)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Every set keeps its place in the row (Base, then the layers in order), whichever is edited.
        val rank = (listOf(MappingSet.BASE) + setOrder).let { o -> { n: Int -> o.indexOf(n) } }

        /** Zone [z]'s mappings in the other sets ranked before the one edited ([before]) or after it. */
        @Composable
        fun othersOf(z: Int, before: Boolean) {
            for ((n, t, tr) in line.others.getOrNull(z).orEmpty()) if ((rank(n) < rank(setId)) == before) key("o", z, n, t, tr) {
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
                        chipText(line.badge(tr, 0), t, null),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
        // Faded: what the level above does here.
        for (f in line.inherited) key("p", f) {
            val (tr, t) = f
            val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
            Row(
                Modifier
                    .dragSource("p" to f, coords) { inheritedDrag(f, it) }
                    .alpha(0.5f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { inheritedClick(f) }
                    .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(parentName, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(
                    chipText(line.badge(tr, f.where), t, null),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        var zone = 0
        othersOf(0, true)
        for (item in line.row) when (item) {
            is RowItem.Chip -> key(item) {
                val z = item.zone
                val t = item.target
                val chipCoords = remember { arrayOfNulls<LayoutCoordinates>(1) }
                val fg = colors.onSecondaryContainer
                Row(
                    Modifier
                        .onGloballyPositioned { line.onItemBounds(item, Rect(it.positionInRoot(), it.size.toSize())) }
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
                        chipText(line.badge(item.trigger, item.where), t, line.optionsOf(item), line.rangeOf(item)),
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
                            .onGloballyPositioned { line.onItemBounds(item, Rect(it.positionInRoot(), it.size.toSize())) }
                            .dragSource(item, coords) { dividerDrag(item.k, it) },
                    ) { line.onDivider(k) }
                }
                othersOf(zone, true)
            }

            RowItem.End -> {
                othersOf(zone, false)
                zone++
                val k = zone
                key(item) {
                    ZoneDivider(
                        k, end = true, addZone = line.addZone,
                        Modifier.onGloballyPositioned { line.onItemBounds(item, Rect(it.positionInRoot(), it.size.toSize())) },
                    ) { line.onDivider(k) }
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
        for (z in zone + 1 until line.others.size) {
            othersOf(z, true); othersOf(z, false)
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
    /** A preset's level, shown by its icon before the text; null = none (a target). */
    level: PresetLevel? = null,
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (level != null) PresetIcon(level, fg)
            Text(
                if (mapped) "✓ " + text else text,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                maxLines = 1,
            )
        }
    }
}

/**
 * A preset level's icon: a chip (a small pill), a card (a card holding a pill), a whole button (a
 * round button with a card in it).
 */
@Composable
internal fun PresetIcon(level: PresetLevel, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.11f)
        fun pill(x: Float, y: Float, pw: Float, ph: Float, fill: Boolean) {
            val r = CornerRadius(ph / 2f, ph / 2f)
            if (fill) drawRoundRect(color, Offset(x, y), Size(pw, ph), r)
            else drawRoundRect(color, Offset(x, y), Size(pw, ph), r, style = stroke)
        }
        when (level) {
            PresetLevel.CHIP -> pill(w * 0.08f, h * 0.3f, w * 0.84f, h * 0.4f, fill = true)
            PresetLevel.CARD -> {
                drawRoundRect(color, Offset(w * 0.06f, h * 0.14f), Size(w * 0.88f, h * 0.72f), CornerRadius(w * 0.14f), style = stroke)
                pill(w * 0.24f, h * 0.4f, w * 0.52f, h * 0.2f, fill = true)
            }
            PresetLevel.BUTTON -> {
                drawCircle(color, radius = w * 0.44f, style = stroke)
                drawRoundRect(color, Offset(w * 0.3f, h * 0.36f), Size(w * 0.4f, h * 0.28f), CornerRadius(w * 0.06f))
            }
        }
    }
}

/** What the presets' icons mean, above the Presets tab. */
@Composable
private fun PresetLegend(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((level, name) in listOf(PresetLevel.CHIP to "chip", PresetLevel.CARD to "card", PresetLevel.BUTTON to "whole button")) {
            PresetIcon(level, c)
            Text(name, style = MaterialTheme.typography.bodySmall, color = c, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/**
 * Applying preset [name] ([level]) to [where] (a card's name, or "this button"): Add (beside what
 * is there), or Replace (asks first). [withSettings]: the "Apply with settings" switch (on by
 * default), when the preset brings settings. A preset of the user's can be renamed or deleted here.
 */
@Composable
private fun PresetApplyDialog(
    name: String,
    level: PresetLevel,
    where: String,
    canAdd: Boolean,
    withSettings: Boolean,
    onApply: (replace: Boolean, settings: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var settings by remember { mutableStateOf(true) }
    var confirmReplace by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace?") },
            text = { Text("What $where maps now goes, and $name takes its place.") },
            confirmButton = { TextButton(onClick = { confirmReplace = false; onApply(true, withSettings && settings) }) { Text("Replace", color = colors.error) } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
        return
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete preset \"$name\"?") },
            text = { Text("Mappings it already filled stay.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = colors.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetIcon(level, colors.onSurface, Modifier.size(20.dp))
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Add: onto $where, beside what it maps. Replace: instead of what it maps.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (withSettings) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Apply with settings", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (settings) "Its " + (if (level == PresetLevel.BUTTON) "button" else "card") +
                                    " settings too, only those that change something here."
                                else "Only its mappings, with their own options.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings, onCheckedChange = { settings = it })
                    }
                }
                if (onRename != null || onDelete != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (onRename != null) TextButton(onClick = onRename) { Text("Rename") }
                        if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete preset", color = colors.error) }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { confirmReplace = true }) { Text("Replace") }
                TextButton(onClick = { onApply(false, withSettings && settings) }, enabled = canAdd) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Names a preset before saving it ([title]), [initial] offered. */
@Composable
internal fun PresetNameDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifEmpty { initial }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

/**
 * The Range of a chip of [target] ([ChipRange]), null for a target that takes none: a game pad
 * axis or trigger in %, Gyro sensitivity in RWS when [g]'s game is known, else counts per
 * degree, its slider up to the Slider cap (App › Gyro).
 */
@Composable
private fun chipRange(target: OutputTarget, current: AxisRange?, g: GyroSettings): ChipRange? {
    val runtime = LocalContext.current.runtime
    val capRws by runtime.sensCapRws.collectAsStateWithLifecycle()
    val capCounts by runtime.sensCapCounts.collectAsStateWithLifecycle()
    return when (target) {
        is OutputTarget.PadAxis -> ChipRange(
            current, AxisRange(0f, 1f), AxisRange(0f, 1f), 0f..100f, 0f..100f, 1f, { "%.0f %%".format(it) },
            "What the input's 0 and full send: the stick or trigger moves from the first to the second as the input " +
                    "goes from 0 to full (past its deadzones). Drag one handle past the other to reverse it.",
            shown = { it * 100f }, stored = { it / 100f },
        )

        is OutputTarget.GyroSens -> {
            val c = g.gameCountsPer360
            val base = g.sensitivityOf(if (target.axis == MotionAxis.NONE) MotionAxis.ROLL else target.axis)
            val initial = AxisRange(base * 0.8f, base * 1.2f)
            val hint = "The gyro sensitivity at the input's 0 and at full; it changes evenly between them while the input " +
                    "is active (a finger on the button, a trigger off its rest) and goes back when it lets go. " +
                    "Drag one handle past the other to reverse it. Tap near a handle to type a value above the Slider cap."
            if (c > 0f) ChipRange(
                current, initial, null, 0f..capRws.coerceAtMost(GameSens.rws(MAX_GYRO_SENS, c)), 0f..GameSens.rws(MAX_GYRO_SENS, c),
                0.01f, { "%.2f RWS".format(it) }, hint,
                shown = { GameSens.rws(it, c) }, stored = { GameSens.sensOf(it, c) },
            ) else ChipRange(
                current, initial, null, 0f..capCounts.coerceAtMost(MAX_GYRO_SENS), 0f..MAX_GYRO_SENS,
                0.5f, { "%.1f".format(it) }, hint,
            )
        }

        else -> null
    }
}
