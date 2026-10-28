package com.oleroma.gyrophone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.hid.AndroidKeyToHid
import com.oleroma.gyrophone.core.model.BindableInput
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.HapticEdge
import com.oleroma.gyrophone.core.model.HapticFeel
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.runtime.GamepadRuntime
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Lists inputs with their mapped targets as removable chips plus an Add button per input. Tapping
 * a chip replaces it. [mappings] is the list being edited (a workspace's touch mappings or an
 * attached pad's); [onChange] receives the rewritten list.
 */
@Composable
fun BindingList(
    mappings: List<Mapping>,
    inputs: List<BindableInput>,
    onChange: (List<Mapping>) -> Unit,
    runtime: GamepadRuntime,
    defaults: ((BindableInput) -> List<OutputTarget>)? = null,
) {
    // (input, target being replaced or null to add)
    var picking by remember { mutableStateOf<Pair<BindableInput, OutputTarget?>?>(null) }
    // Layer actions pick from the workspace profile's sets (new sets are made in the profile editor).
    val active by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (input in inputs) {
            val targets = Bindings.targets(mappings, input)
            val standard = defaults?.invoke(input)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inputs.size > 1) {
                    Text(
                        input.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(64.dp),
                    )
                }
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (targets.isEmpty()) {
                        Text(
                            "Unmapped",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    for (t in targets) {
                        TargetChip(
                            text = Describe.shortTarget(t),
                            onClick = { picking = input to t },
                            onRemove = { onChange(Bindings.removeTarget(mappings, input, t)) },
                        )
                    }
                    AddChip(onClick = { picking = input to null })
                }
                if (standard != null && standard.toSet() != targets.toSet()) {
                    TextButton(onClick = { onChange(Bindings.setTargets(mappings, input, standard)) }) {
                        Text("Reset", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    picking?.let { (input, replacing) ->
        TargetPickerDialog(
            analog = input.analog,
            swipe = input.swipe,
            extra = input.extra,
            runtime = runtime,
            current = Bindings.targets(mappings, input),
            sets = active.workspace(wsIndex).sets,
            editing = replacing as? OutputTarget.Layer,
            onPick = { picked ->
                val current = Bindings.targets(mappings, input)
                val next = if (replacing != null && replacing in current) {
                    current.map { if (it == replacing) picked else it }.distinct()
                } else {
                    (current + picked).distinct()
                }
                onChange(Bindings.setTargets(mappings, input, next))
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
internal fun TargetChip(text: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.clickable(onClick = onClick)
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        )
        Box(
            Modifier.size(32.dp).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "✕",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
internal fun AddChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("+ Add", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

private sealed interface PickerEntry {
    data class Header(val title: String) : PickerEntry
    data class Item(val target: OutputTarget) : PickerEntry
}

/**
 * Grouped target picker dialog with a search field, a section list per group, and "Press a key on
 * a keyboard" capture (uses runtime.keyLearnListener + AndroidKeyToHid). analog=true -> only analog
 * targets; swipe=true -> analog and mouse motion targets. Picking calls [onPick] and then [onDismiss]; [current] targets are shown as mapped.
 * A haptic target first opens [HapticTargetDialog] to set its feel (and step).
 */
@Composable
fun TargetPickerDialog(
    analog: Boolean,
    runtime: GamepadRuntime,
    onPick: (OutputTarget) -> Unit,
    onDismiss: () -> Unit,
    current: List<OutputTarget> = emptyList(),
    swipe: Boolean = false,
    extra: List<OutputTarget> = emptyList(),
    /** The profile's mapping sets: offers the "Layers" group (null = not offered). */
    sets: List<MappingSet>? = null,
    /** Adds a set to the profile and gives it ("+ New set" in the layer dialog). */
    onAddSet: (() -> MappingSet)? = null,
    /** A layer target being changed: its dialog opens at once. */
    editing: OutputTarget? = null,
) {
    var query by remember { mutableStateOf("") }
    var capturing by remember { mutableStateOf(false) }
    // A picked haptic target being configured before it is handed to onPick.
    var hapticDraft by remember { mutableStateOf<OutputTarget?>(null) }
    // A picked layer action, its set and option being chosen.
    var layerDraft by remember { mutableStateOf(editing as? OutputTarget.Layer) }

    val groups = remember(analog, swipe, extra, sets != null) {
        val g = Describe.pickerGroups(analog, swipe, extra)
        if (sets == null || analog) g else g + ("Layers" to Describe.layerTargets)
    }
    val entries = remember(groups, query) {
        val q = query.trim()
        buildList {
            for ((name, list) in groups) {
                val shown = if (q.isEmpty()) list else list.filter {
                    Describe.shortTarget(it).contains(q, ignoreCase = true) || Describe.target(it)
                        .contains(q, ignoreCase = true)
                }
                if (shown.isEmpty()) continue
                add(PickerEntry.Header(name))
                shown.forEach { add(PickerEntry.Item(it)) }
            }
        }
    }
    val pick: (OutputTarget) -> Unit = { t ->
        if (Describe.isHaptic(t)) {
            hapticDraft = t
        } else if (t is OutputTarget.Layer) {
            layerDraft = t
        } else {
            onPick(t)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (analog) "Map analog axis to" else "Map to",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
                if (capturing) {
                    KeyCapturePanel(
                        runtime = runtime,
                        onKey = pick,
                        onCancel = { capturing = false },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                } else {
                    PickerBody(
                        entries = entries,
                        query = query,
                        onQuery = { query = it },
                        showCapture = !analog,
                        onCapture = { capturing = true },
                        current = current.toSet(),
                        onPick = pick,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }

    hapticDraft?.let { draft ->
        HapticTargetDialog(
            start = draft,
            runtime = runtime,
            onOk = { t ->
                onPick(t)
                onDismiss()
            },
            onCancel = { hapticDraft = null },
        )
    }

    layerDraft?.let { draft ->
        LayerTargetDialog(
            start = draft,
            sets = sets.orEmpty(),
            onAddSet = onAddSet,
            onOk = { t ->
                onPick(t)
                onDismiss()
            },
            onCancel = { if (editing != null) onDismiss() else layerDraft = null },
        )
    }
}

/**
 * Sets up a picked [OutputTarget.Layer]: which action, which set (Base, the profile's [sets], or a
 * new one through [onAddSet]) and what held buttons do. [onOk] gets the target.
 */
@Composable
internal fun LayerTargetDialog(
    start: OutputTarget.Layer,
    sets: List<MappingSet>,
    onAddSet: (() -> MappingSet)?,
    onOk: (OutputTarget) -> Unit,
    onCancel: () -> Unit,
) {
    var t by remember {
        mutableStateOf((if (start.set < 0) start.copy(set = sets.firstOrNull()?.id ?: MappingSet.BASE) else start).copy(switchHeld = false))
    }
    var known by remember { mutableStateOf(sets) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Layer action") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (kind in Describe.layerTargets.filterIsInstance<OutputTarget.Layer>()) {
                        val k = t.copy(override = kind.override, whileHeld = kind.whileHeld)
                        FilterChip(selected = k == t, onClick = { t = k }, label = { Text(Describe.layer(kind), maxLines = 1) })
                    }
                }
                Text(
                    when {
                        t.override && t.whileHeld -> "While held, the set's mappings are laid over the current set; inputs it doesn't map keep theirs."
                        t.override -> "Lays the set over the current set until pressed again or a mapping set changes everything; inputs it doesn't map keep theirs."
                        t.whileHeld -> "While held, everything does what the set maps (and nothing it doesn't); on release it goes back."
                        else -> "Everything does what the set maps from now on (nothing it doesn't). Map a \"Mapping set → Base\" in that set to get back."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Set", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = t.set == MappingSet.BASE, onClick = { t = t.copy(set = MappingSet.BASE) }, label = { Text("Base") })
                    for (s in known) {
                        FilterChip(selected = t.set == s.id, onClick = { t = t.copy(set = s.id) }, label = { Text(s.label, maxLines = 1) })
                    }
                    if (onAddSet != null && known.size < MappingSet.MAX) {
                        TextButton(onClick = {
                            val n = onAddSet()
                            known = known + n
                            t = t.copy(set = n.id)
                        }) { Text("+ New set") }
                    }
                }
                Text(
                    "Buttons held at the change keep pressing what they pressed until released, unless their \"Follows layers while held\" is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOk(t) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Sets up a picked [OutputTarget.Haptic] or [OutputTarget.HapticStep]: the workspace's feel or
 * one of its own, when a tick plays (the workspace's or its own), and the step size. [onOk] gets
 * the configured target; [onCancel] goes back.
 */
@Composable
internal fun HapticTargetDialog(
    start: OutputTarget,
    runtime: GamepadRuntime,
    onOk: (OutputTarget) -> Unit,
    onCancel: () -> Unit,
) {
    val held = start is OutputTarget.Haptic && start.held
    val startFeel = when (start) {
        is OutputTarget.Haptic -> start.feel
        is OutputTarget.HapticStep -> start.feel
        else -> null
    }
    var own by remember { mutableStateOf(startFeel != null) }
    var feel by remember { mutableStateOf(startFeel ?: HapticFeel()) }
    var percent by remember {
        mutableFloatStateOf(
            ((start as? OutputTarget.HapticStep)?.percent ?: OutputTarget.HapticStep.DEFAULT_STEP_PERCENT).toFloat()
        )
    }
    var edge by remember { mutableStateOf((start as? OutputTarget.Haptic)?.edge) }
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    fun result(): OutputTarget {
        val f = if (own) feel else null
        return when (start) {
            is OutputTarget.HapticStep -> start.copy(percent = percent.toInt(), feel = f)
            is OutputTarget.Haptic -> start.copy(feel = f, edge = if (start.held) null else edge)
            else -> start
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Haptic") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Describe.target(result()), style = MaterialTheme.typography.bodyMedium)
                if (start is OutputTarget.HapticStep) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Step " + percent.toInt() + " %", Modifier.padding(end = 8.dp))
                        Slider(
                            value = percent,
                            onValueChange = { percent = it.roundToInt().toFloat() },
                            valueRange = OutputTarget.HapticStep.MIN_STEP_PERCENT.toFloat()..OutputTarget.HapticStep.MAX_STEP_PERCENT.toFloat(),
                        )
                    }
                }
                if (start is OutputTarget.Haptic && !held) {
                    val wsEdge = profile.workspace(wsIndex).hapticEdge
                    Text("Plays", style = MaterialTheme.typography.labelLarge)
                    HapticEdgeChips(edge, onSelect = { edge = it }, workspaceEdge = wsEdge)
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(selected = !own, onClick = { own = false }, label = { Text("Workspace default") })
                    FilterChip(selected = own, onClick = { own = true }, label = { Text("Own feel") })
                    if (!own) {
                        TextButton(onClick = {
                            val ws = runtime.activeProfile.value.workspace(runtime.workspaceIndex.value)
                            runtime.previewHaptic(ws.haptics, held)
                        }) { Text("Try") }
                    }
                }
                if (own) {
                    HapticFeelEditor(
                        feel,
                        held,
                        onChange = { feel = it },
                        onTry = { runtime.previewHaptic(feel, held) })
                } else {
                    Text(
                        "Feels like the workspace's Haptics setting.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(result()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Picks when a haptic tick plays. With [workspaceEdge], a first chip stands for the workspace's
 * setting ([selected] null); without it, [selected] is never null.
 */
@Composable
internal fun HapticEdgeChips(
    selected: HapticEdge?,
    onSelect: (HapticEdge?) -> Unit,
    workspaceEdge: HapticEdge? = null
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (workspaceEdge != null) {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("Workspace default (" + workspaceEdge.label.lowercase() + ")") },
            )
        }
        for (e in HapticEdge.entries) {
            FilterChip(selected = selected == e, onClick = { onSelect(e) }, label = { Text(e.label) })
        }
    }
}

@Composable
private fun PickerBody(
    entries: List<PickerEntry>,
    query: String,
    onQuery: (String) -> Unit,
    showCapture: Boolean,
    onCapture: () -> Unit,
    current: Set<OutputTarget>,
    onPick: (OutputTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val headers = remember(entries) {
        entries.withIndex().filter { it.value is PickerEntry.Header }
            .map { (it.value as PickerEntry.Header).title to it.index }
    }
    val currentHeader by remember(headers) {
        derivedStateOf {
            val first = gridState.firstVisibleItemIndex
            headers.lastOrNull { it.second <= first }?.first ?: headers.firstOrNull()?.first
        }
    }
    LaunchedEffect(entries) { gridState.scrollToItem(0) }

    fun jump(index: Int) {
        scope.launch { gridState.animateScrollToItem(index) }
    }

    val search: @Composable (Modifier) -> Unit = { m ->
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Search, e.g. W, LT, wheel") },
            trailingIcon = {
                if (query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("✕") }
            },
            modifier = m,
        )
    }
    val grid: @Composable () -> Unit = {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing matches \"$query\"", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 112.dp),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().padding(end = 8.dp),
            ) {
                itemsIndexed(
                    entries,
                    span = { _, e -> GridItemSpan(if (e is PickerEntry.Header) maxLineSpan else 1) },
                ) { _, e ->
                    when (e) {
                        is PickerEntry.Header -> Text(
                            e.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )

                        is PickerEntry.Item -> TargetCell(
                            e.target,
                            mapped = e.target in current,
                            onClick = { onPick(e.target) })
                    }
                }
            }
        }
    }

    BoxWithConstraints(modifier) {
        if (maxWidth < 560.dp) {
            // Narrow (portrait): categories as a scrolling row of chips, search and key capture stacked.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (headers.size > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for ((title, index) in headers) {
                            FilterChip(
                                selected = title == currentHeader,
                                onClick = { jump(index) },
                                label = { Text(title) })
                        }
                    }
                }
                search(Modifier.fillMaxWidth().padding(end = 8.dp))
                if (showCapture) {
                    OutlinedButton(
                        onClick = onCapture,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp)
                    ) { Text("Press a key on a keyboard") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { grid() }
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (headers.size > 1) {
                    LazyColumn(Modifier.widthIn(min = 140.dp, max = 180.dp).fillMaxHeight()) {
                        lazyItemsIndexed(headers) { _, (title, index) ->
                            val selected = title == currentHeader
                            Text(
                                title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                    .clickable { jump(index) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        search(Modifier.weight(1f))
                        if (showCapture) {
                            OutlinedButton(onClick = onCapture) { Text("Press a key on a keyboard") }
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Spacer(Modifier.size(8.dp))
                    grid()
                }
            }
        }
    }
}

@Composable
private fun TargetCell(target: OutputTarget, mapped: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val short = Describe.shortTarget(target)
    val long = Describe.target(target)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (mapped) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (mapped) "✓ $short" else short,
            style = MaterialTheme.typography.labelLarge,
            color = if (mapped) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (long != short) {
            Text(
                long,
                style = MaterialTheme.typography.bodySmall,
                color = if (mapped) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Waits for one key of a physical keyboard. Keys reach either the activity (ControllerInput ->
 * runtime.keyLearnListener) or, while this dialog has window focus, the focused box below, which
 * forwards them to the same listener.
 */
@Composable
internal fun KeyCapturePanel(
    runtime: GamepadRuntime,
    onKey: (OutputTarget) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var message by remember { mutableStateOf<String?>(null) }
    var arm by remember { mutableIntStateOf(0) }
    val latestOnKey by rememberUpdatedState(onKey)
    val focus = remember { FocusRequester() }

    DisposableEffect(arm) {
        val listener: (Int) -> Unit = { code ->
            val usage = AndroidKeyToHid.usage(code)
            if (usage != null) {
                latestOnKey(OutputTarget.Key(usage))
            } else {
                message = "That key (code $code) has no keyboard equivalent. Press a key on a keyboard."
                arm++
            }
        }
        runtime.keyLearnListener = listener
        onDispose { if (runtime.keyLearnListener === listener) runtime.keyLearnListener = null }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                val native = e.nativeKeyEvent
                val learn = runtime.keyLearnListener
                if (learn == null || native.device?.isVirtual != false) {
                    false
                } else {
                    if (native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                        runtime.keyLearnListener = null
                        learn(native.keyCode)
                    }
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Press a key on an attached keyboard…", style = MaterialTheme.typography.titleLarge)
            Text(
                message ?: "USB or Bluetooth keyboard. The key is mapped as the PC key with the same name.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onCancel) { Text("Back to list") }
        }
    }
}
