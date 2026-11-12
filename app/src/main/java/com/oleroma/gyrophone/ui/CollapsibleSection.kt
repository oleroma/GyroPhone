package com.oleroma.gyrophone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalDensity
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
    val gap = LocalCardGap.current
    val query = LocalSettingsQuery.current
    if (query.isNotEmpty()) {
        // Searching: open, showing only its matching settings (all of them when its own name
        // matches), and hidden, though still drawn so it keeps counting, when none match.
        val titleHit = searchMatches(query, title, subtitle)
        val hits = remember { SearchHits() }
        val shown = titleHit || hits.count > 0
        if (titleHit) RegisterHit()
        CollapsibleSection(
            title = title,
            expanded = true,
            onToggle = {},
            modifier = if (shown) Modifier.padding(bottom = gap).then(modifier) else Modifier.hiddenLayout(),
            subtitle = subtitle,
            headerModifier = headerModifier,
        ) {
            CountHits(hits) {
                CompositionLocalProvider(LocalSettingsQuery provides if (titleHit) "" else query) { content() }
            }
        }
        return
    }
    if (LocalSectionAsTab.current) {
        // Its tab is picked ([ReorderableSections]): the tab names it, so only its settings, always open.
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
        return
    }
    CollapsibleSection(
        title = title,
        expanded = open && !LocalSectionsCollapsed.current,
        onToggle = {
            open = !open
            runtime.setUiPref("section." + prefKey, if (open) "1" else "0")
        },
        modifier = Modifier.padding(bottom = gap).then(modifier),
        subtitle = subtitle,
        headerModifier = headerModifier,
        content = content,
    )
}

/**
 * One category of [ReorderableSections]: [id] names it in the kept order, [title] on its tab;
 * [content] draws it (a [RememberedSection]) with the card's modifier and its header's (which
 * drags it while searching shows the categories as cards).
 */
internal class SectionItem(val id: String, val title: String, val content: @Composable (card: Modifier, header: Modifier) -> Unit)

/** True while a [RememberedSection] is drawn as the picked tab ([ReorderableSections]): no header, always open. */
internal val LocalSectionAsTab = compositionLocalOf { false }

/** While a category is dragged ([ReorderableSections]), every section shows closed, so they are easy to move past. */
internal val LocalSectionsCollapsed = compositionLocalOf { false }

/** The category order kept in the UI prefs under [orderKey]. */
private fun keptOrder(runtime: GamepadRuntime, orderKey: String): List<String> =
    runtime.uiPref("order.$orderKey")?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

/** [ids] in the order kept under [orderKey]; one the kept order lacks goes after the one before it in [ids]. */
private fun orderedIds(runtime: GamepadRuntime, orderKey: String, ids: List<String>): List<String> {
    val out = keptOrder(runtime, orderKey).filter { it in ids }.toMutableList()
    ids.forEachIndexed { i, id ->
        if (id !in out) out.add(if (i == 0) 0 else out.indexOf(ids[i - 1]) + 1, id)
    }
    return out
}

/** Keeps [row] as the order under [orderKey], with ids of categories not shown here each after the one it followed, so another list's order stays. */
private fun keepOrder(runtime: GamepadRuntime, orderKey: String, row: List<String>) {
    val old = keptOrder(runtime, orderKey)
    val out = row.toMutableList()
    old.forEachIndexed { i, id ->
        if (id in out) return@forEachIndexed
        val before = old.subList(0, i).lastOrNull { it in out }
        out.add(if (before == null) 0 else out.indexOf(before) + 1, id)
    }
    runtime.setUiPref("order.$orderKey", out.joinToString(","))
}

/**
 * A settings page's categories: [items] in the order kept in the UI prefs under [orderKey]
 * (new ones where [items] has them).
 *
 * Not searching, they are tabs ([CategoryTabs], pinned under the search box by
 * [SearchableSettings]) and only the picked one shows, open; the picked tab is kept under
 * "tab.[orderKey]". Long press a tab and drag it sideways to move it.
 *
 * Searching, every category shows as a card with its matching settings. Long press a card's
 * header and drag it up or down to move it; while dragging every category shows closed, the page
 * scrolls when the finger nears its top or bottom edge (faster closer to it), and the order is
 * kept on drop.
 */
@Composable
internal fun ReorderableSections(runtime: GamepadRuntime, orderKey: String, items: List<SectionItem>) {
    if (LocalSettingsQuery.current.isEmpty()) {
        TabbedSections(runtime, orderKey, items)
        return
    }
    val gap = 8.dp
    fun ordered(ids: List<String>) = orderedIds(runtime, orderKey, ids)
    val row = remember { mutableStateListOf<String>() }
    var dragged by remember { mutableStateOf<String?>(null) }
    // The finger, in root coordinates, and where it holds the dragged card (from its top).
    var fingerY by remember { mutableFloatStateOf(0f) }
    var grab by remember { mutableFloatStateOf(0f) }
    // Each category's place as laid out (root top) and size, and its header's height.
    val tops = remember { mutableStateMapOf<String, Float>() }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val headers = remember { HashMap<String, Int>() }
    val shown = if (dragged == null) ordered(items.map { it.id }) else row.toList()
    val byId = items.associateBy { it.id }

    fun drop() {
        keepOrder(runtime, orderKey, row.toList())
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

    // Near the page's top or bottom edge the dragged category scrolls the page, faster closer to it.
    val page = LocalSettingsScroll.current
    val density = LocalDensity.current
    LaunchedEffect(dragged, page) {
        val id = dragged ?: return@LaunchedEffect
        if (page == null) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val v = page.viewport
            if (v.height <= 0f) continue
            val y = fingerY
            val step = when {
                y < v.top + edge -> -maxStep * ((v.top + edge - y) / edge).coerceIn(0f, 1f)
                y > v.bottom - edge -> maxStep * ((y - (v.bottom - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (step != 0f && page.state.scrollBy(step) != 0f) reorder(id)
        }
    }

    // The gesture lives on the column, which never moves: a category's own node moves as the
    // row reorders, and a gesture on it would end at the first move.
    val ids by rememberUpdatedState(items.map { it.id })
    val searching by rememberUpdatedState(LocalSettingsQuery.current.isNotEmpty())
    var column by remember { mutableStateOf<LayoutCoordinates?>(null) }
    CompositionLocalProvider(LocalSectionsCollapsed provides (dragged != null), LocalCardGap provides gap) {
        Column(
            Modifier
                .onGloballyPositioned { column = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (searching) return@awaitEachGesture
                        val col = column ?: return@awaitEachGesture
                        val y = col.localToRoot(down.position).y
                        val id = ids.firstOrNull { i ->
                            val top = tops[i] ?: return@firstOrNull false
                            y >= top && y <= top + (headers[i] ?: 0)
                        } ?: return@awaitEachGesture
                        val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        fingerY = col.localToRoot(long.position).y
                        grab = fingerY - (tops[id] ?: 0f)
                        // The latest items: the gesture outlives the composition that started it.
                        row.clear(); row.addAll(ordered(ids))
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

/**
 * [items] as tabs, only the picked one shown (see [ReorderableSections]). The tab row goes to the
 * page's [SettingsTabs] (pinned under its search box) when it has one, else above the category.
 */
@Composable
private fun TabbedSections(runtime: GamepadRuntime, orderKey: String, items: List<SectionItem>) {
    if (items.isEmpty()) return
    // Bumped when a drag keeps a new order, so the tabs read it again.
    var orderVersion by remember { mutableIntStateOf(0) }
    val ids = items.map { it.id }
    val shown = remember(orderKey, ids, orderVersion) { orderedIds(runtime, orderKey, ids) }
    var picked by remember(orderKey) { mutableStateOf(runtime.uiPref("tab.$orderKey")) }
    val current = picked?.takeIf { it in ids } ?: shown.first()
    val titles = items.associate { it.id to it.title }
    val bar: @Composable () -> Unit = {
        CategoryTabs(
            tabs = shown.map { it to (titles[it] ?: it) },
            picked = current,
            onPick = { id ->
                picked = id
                runtime.setUiPref("tab.$orderKey", id)
            },
            onMove = { row ->
                keepOrder(runtime, orderKey, row)
                orderVersion++
            },
        )
    }
    val tabs = LocalSettingsTabs.current
    if (tabs != null) PinIn(tabs.bar, bar) else {
        bar()
        Spacer(Modifier.height(8.dp))
    }
    // A new tab starts at the top of the page.
    val page = LocalSettingsScroll.current
    LaunchedEffect(current) { page?.state?.scrollTo(0) }
    val item = items.first { it.id == current }
    CompositionLocalProvider(LocalSectionAsTab provides true, LocalCardGap provides 0.dp) {
        key(current) { item.content(Modifier, Modifier) }
    }
}

/**
 * What a settings page pins under its search box ([SearchableSettings]), each drawn by the one
 * that claimed it last: [scope] the page's own tabs (a workspace's Base and layers, [PinnedScope]),
 * [bar] the category tabs of its [ReorderableSections].
 */
internal class SettingsTabs {
    val scope = PinnedSlot()
    val bar = PinnedSlot()
}

/** One pinned place of [SettingsTabs]. */
internal class PinnedSlot {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set
    private var owner: Any? = null

    fun claim(token: Any, content: @Composable () -> Unit) {
        owner = token
        this.content = content
    }

    fun release(token: Any) {
        if (owner !== token) return
        owner = null
        content = null
    }
}

/** Draws [content] in [slot] (pinned under the search box) while this is shown. */
@Composable
internal fun PinIn(slot: PinnedSlot, content: @Composable () -> Unit) {
    val token = remember { Any() }
    SideEffect { slot.claim(token, content) }
    DisposableEffect(slot, token) { onDispose { slot.release(token) } }
}

/** [content] (a page's own tabs, e.g. a workspace's Base and layers) pinned under the search box, above the category tabs; in place outside a settings page. */
@Composable
internal fun PinnedScope(content: @Composable () -> Unit) {
    val tabs = LocalSettingsTabs.current
    if (tabs == null) content() else PinIn(tabs.scope, content)
}

/** The [SettingsTabs] of the settings page this is on; null outside one. */
internal val LocalSettingsTabs = compositionLocalOf<SettingsTabs?> { null }

/**
 * A row of category tabs ([tabs]: id to name), [picked] highlighted, scrolling sideways when long.
 * Tap one to pick it; long press one and drag it sideways to move it ([onMove] gets the new order on drop).
 */
@Composable
private fun CategoryTabs(
    tabs: List<Pair<String, String>>,
    picked: String,
    onPick: (String) -> Unit,
    onMove: (List<String>) -> Unit,
) {
    val ids by rememberUpdatedState(tabs.map { it.first })
    val titles = tabs.toMap()
    val row = remember { mutableStateListOf<String>() }
    var dragged by remember { mutableStateOf<String?>(null) }
    // The finger, in root coordinates, and where it holds the dragged tab (from its left).
    var fingerX by remember { mutableFloatStateOf(0f) }
    var grab by remember { mutableFloatStateOf(0f) }
    val lefts = remember { mutableStateMapOf<String, Float>() }
    val widths = remember { mutableStateMapOf<String, Int>() }
    var strip by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val move by rememberUpdatedState(onMove)
    val shown = if (dragged == null) ids else row.toList()

    /** Moves the dragged [id] past a neighbour once its middle is past the neighbour's. */
    fun reorder(id: String) {
        val at = row.indexOf(id)
        if (at < 0) return
        val mid = fingerX - grab + (widths[id] ?: 0) / 2f
        fun middle(other: String) = (lefts[other] ?: 0f) + (widths[other] ?: 0) / 2f
        if (at < row.lastIndex && mid > middle(row[at + 1])) {
            row.removeAt(at); row.add(at + 1, id)
        } else if (at > 0 && mid < middle(row[at - 1])) {
            row.removeAt(at); row.add(at - 1, id)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .onGloballyPositioned { strip = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val s = strip ?: return@awaitEachGesture
                    val x = s.localToRoot(down.position).x
                    val id = ids.firstOrNull { i ->
                        val left = lefts[i] ?: return@firstOrNull false
                        x >= left && x <= left + (widths[i] ?: 0)
                    } ?: return@awaitEachGesture
                    val long = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    fingerX = s.localToRoot(long.position).x
                    grab = fingerX - (lefts[id] ?: 0f)
                    row.clear(); row.addAll(ids)
                    dragged = id
                    // Ahead of the tab's own click and the scrolling row: the finger is ours now.
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = e.changes.firstOrNull { it.id == long.id } ?: break
                        e.changes.forEach { it.consume() }
                        if (!ch.pressed) break
                        strip?.let { fingerX = it.localToRoot(ch.position).x }
                        reorder(id)
                    }
                    move(row.toList())
                    dragged = null
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (id in shown) {
            key(id) {
                val isDragged = dragged == id
                Box(
                    Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .onGloballyPositioned {
                            lefts[id] = it.positionInRoot().x
                            widths[id] = it.size.width
                        },
                ) {
                    FilterChip(
                        selected = id == picked,
                        onClick = { onPick(id) },
                        label = { Text(titles[id] ?: id, maxLines = 1) },
                        modifier = Modifier.graphicsLayer {
                            if (isDragged) {
                                translationX = fingerX - grab - (lefts[id] ?: 0f)
                                scaleX = 1.05f
                                scaleY = 1.05f
                                shadowElevation = 8f
                            }
                        },
                    )
                }
            }
        }
    }
}
