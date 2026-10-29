package com.oleroma.gyrophone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** A card with a tappable header (title, optional [subtitle], chevron) that shows or hides [content]. */
@Composable
internal fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    headerModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val turn by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().then(headerModifier).clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "▾",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(turn),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * A [CollapsibleSection] whose open state is kept in the runtime's UI prefs under [prefKey]
 * (e.g. "control.Look"), so it opens the same way next time.
 */
@Composable
internal fun RememberedSection(
    runtime: GamepadRuntime,
    prefKey: String,
    title: String,
    modifier: Modifier = Modifier,
    defaultOpen: Boolean = false,
    subtitle: String? = null,
    headerModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember(prefKey) {
        mutableStateOf(runtime.uiPref("section." + prefKey)?.let { it == "1" } ?: defaultOpen)
    }
    CollapsibleSection(
        title = title,
        expanded = open && !LocalSectionsCollapsed.current,
        onToggle = {
            open = !open
            runtime.setUiPref("section." + prefKey, if (open) "1" else "0")
        },
        modifier = modifier,
        subtitle = subtitle,
        headerModifier = headerModifier,
        content = content,
    )
}

/**
 * One category of [ReorderableSections]: [id] names it in the kept order; [content] draws it
 * (a [RememberedSection]) with the card's modifier and its header's (which drags it).
 */
internal class SectionItem(val id: String, val content: @Composable (card: Modifier, header: Modifier) -> Unit)

/** While a category is dragged ([ReorderableSections]), every section shows closed, so they are easy to move past. */
internal val LocalSectionsCollapsed = compositionLocalOf { false }

/**
 * [items] in the order kept in the UI prefs under [orderKey] (new ones where [items] has them).
 * Long press a category's header and drag it up or down to move it; while dragging every
 * category shows closed, and the order is kept on drop.
 */
@Composable
internal fun ReorderableSections(runtime: GamepadRuntime, orderKey: String, items: List<SectionItem>) {
    val gap = 8.dp
    fun kept(): List<String> = runtime.uiPref("order.$orderKey")?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    fun ordered(): List<String> {
        val ids = items.map { it.id }
        val known = kept().filter { it in ids }
        // A category the kept order lacks goes after the one before it in [items].
        val out = known.toMutableList()
        ids.forEachIndexed { i, id ->
            if (id !in out) out.add(if (i == 0) 0 else out.indexOf(ids[i - 1]) + 1, id)
        }
        return out
    }
    val row = remember { mutableStateListOf<String>() }
    var dragged by remember { mutableStateOf<String?>(null) }
    // The finger, in root coordinates, and where it holds the dragged card (from its top).
    var fingerY by remember { mutableFloatStateOf(0f) }
    var grab by remember { mutableFloatStateOf(0f) }
    // Each category's place as laid out (root top) and size, and its header's height.
    val tops = remember { mutableStateMapOf<String, Float>() }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val headers = remember { HashMap<String, Int>() }
    val shown = if (dragged == null) ordered() else row.toList()
    val byId = items.associateBy { it.id }

    fun drop() {
        // Keep ids of categories not shown here too, so another list's order stays.
        val rest = kept().filter { it !in row }
        runtime.setUiPref("order.$orderKey", (row + rest).joinToString(","))
        dragged = null
    }

    /** Moves the dragged [id] past a neighbour once its middle is past the neighbour's. */
    fun reorder(id: String) {
        val at = row.indexOf(id)
        if (at < 0) return
        val mid = fingerY - grab + (heights[id] ?: 0) / 2f
        fun middle(other: String) = (tops[other] ?: 0f) + (heights[other] ?: 0) / 2f
        if (at < row.lastIndex && mid > middle(row[at + 1])) {
            row.removeAt(at); row.add(at + 1, id)
        } else if (at > 0 && mid < middle(row[at - 1])) {
            row.removeAt(at); row.add(at - 1, id)
        }
    }

    // The gesture lives on the column, which never moves: a category's own node moves as the
    // row reorders, and a gesture on it would end at the first move.
    val ids by rememberUpdatedState(items.map { it.id })
    var column by remember { mutableStateOf<LayoutCoordinates?>(null) }
    CompositionLocalProvider(LocalSectionsCollapsed provides (dragged != null)) {
        Column(
            Modifier
                .onGloballyPositioned { column = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val col = column ?: return@awaitEachGesture
                        val y = col.localToRoot(down.position).y
                        val id = ids.firstOrNull { i ->
                            val top = tops[i] ?: return@firstOrNull false
                            y >= top && y <= top + (headers[i] ?: 0)
                        } ?: return@awaitEachGesture
                        val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        fingerY = col.localToRoot(long.position).y
                        grab = fingerY - (tops[id] ?: 0f)
                        row.clear(); row.addAll(ordered())
                        dragged = id
                        // Ahead of the header's own click and the scrolling list: the finger is ours now.
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = e.changes.firstOrNull { it.id == long.id } ?: break
                            e.changes.forEach { it.consume() }
                            if (!ch.pressed) break
                            column?.let { fingerY = it.localToRoot(ch.position).y }
                            reorder(id)
                        }
                        drop()
                    }
                },
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            for (id in shown) {
                val item = byId[id] ?: continue
                key(id) {
                    val isDragged = dragged == id
                    // The wrapper stays where the layout puts it; only the card follows the finger.
                    Box(
                        Modifier
                            .zIndex(if (isDragged) 1f else 0f)
                            .onGloballyPositioned {
                                tops[id] = it.positionInRoot().y
                                heights[id] = it.size.height
                            },
                    ) {
                        val card = Modifier.graphicsLayer {
                            if (isDragged) {
                                translationY = fingerY - grab - (tops[id] ?: 0f)
                                scaleX = 1.02f
                                scaleY = 1.02f
                                shadowElevation = 8f
                            }
                        }
                        val header = Modifier.onSizeChanged { headers[id] = it.height }
                        item.content(card, header)
                    }
                }
            }
        }
    }
}
