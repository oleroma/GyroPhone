package com.oleroma.gyrophone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
        expanded = open,
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

/**
 * [items] in the order kept in the UI prefs under [orderKey] (new ones where [items] has them).
 * Long press a category's header and drag it up or down to move it; the order is kept on drop.
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
    var dragY by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val shown = if (dragged == null) ordered() else row.toList()
    val byId = items.associateBy { it.id }

    fun drop() {
        // Keep ids of categories not shown here too, so another list's order stays.
        val rest = kept().filter { it !in row }
        runtime.setUiPref("order.$orderKey", (row + rest).joinToString(","))
        dragged = null
        dragY = 0f
    }

    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        for (id in shown) {
            val item = byId[id] ?: continue
            key(id) {
                val isDragged = dragged == id
                val card = Modifier
                    .onSizeChanged { heights[id] = it.height }
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer {
                        if (isDragged) {
                            translationY = dragY
                            scaleX = 1.02f
                            scaleY = 1.02f
                            shadowElevation = 8f
                        }
                    }
                val header = Modifier.pointerInput(id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            row.clear(); row.addAll(ordered())
                            dragged = id
                            dragY = 0f
                        },
                        onDragEnd = { drop() },
                        onDragCancel = { drop() },
                    ) { change, drag ->
                        change.consume()
                        dragY += drag.y
                        // Swap with a neighbour once the category is past half of it.
                        val at = row.indexOf(id)
                        val step = gap.toPx()
                        if (dragY > 0 && at < row.lastIndex) {
                            val next = heights[row[at + 1]] ?: 0
                            if (dragY > next / 2f + step) {
                                row.removeAt(at); row.add(at + 1, id)
                                dragY -= next + step
                            }
                        } else if (dragY < 0 && at > 0) {
                            val prev = heights[row[at - 1]] ?: 0
                            if (-dragY > prev / 2f + step) {
                                row.removeAt(at); row.add(at - 1, id)
                                dragY += prev + step
                            }
                        }
                    }
                }
                item.content(card, header)
            }
        }
    }
}
