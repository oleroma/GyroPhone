package com.oleroma.gyrophone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.oleroma.gyrophone.runtime.GamepadRuntime

/*
 * The one layout every settings page shares: a search box on top, then the page's categories,
 * each a card that folds open and shut and that a long press on its header drags up or down
 * ([ReorderableSections]).
 *
 * Search: while the box holds text, every category shows open and only the settings whose name
 * or explanation holds every typed word stay; categories with none hide. A category whose own
 * name matches shows whole. Rows take part through [Searchable] (the setting rows here and in
 * OverrideSettings do it themselves); notes and headings between settings hide while searching
 * ([NotSearching]).
 */

/** What the search box holds (lower case, trimmed); empty = not searching. */
internal val LocalSettingsQuery = compositionLocalOf { "" }

/** Counts the shown settings that match the search, for a category or a page ([CountHits]). */
internal class SearchHits {
    var count by mutableIntStateOf(0)
}

/** Every [SearchHits] the setting being drawn counts towards: its category's, its page's. */
internal val LocalSearchHits = compositionLocalOf<List<SearchHits>> { emptyList() }

/** Space a [RememberedSection] keeps under itself while shown (set by [ReorderableSections]), so hidden ones leave no gap. */
internal val LocalCardGap = compositionLocalOf { 0.dp }

/** [texts] together hold every word of [query] (already lower case). */
internal fun searchMatches(query: String, vararg texts: String?): Boolean {
    val words = query.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val hay = texts.filterNotNull().joinToString(" ").lowercase()
    return words.all { it in hay }
}

/** Matches of [content] also count towards [hits]. */
@Composable
internal fun CountHits(hits: SearchHits, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSearchHits provides LocalSearchHits.current + hits) { content() }
}

/** Counts one match towards every [LocalSearchHits] while it is shown. */
@Composable
internal fun RegisterHit() {
    val all = LocalSearchHits.current
    DisposableEffect(all) {
        all.forEach { it.count++ }
        onDispose { all.forEach { it.count-- } }
    }
}

/**
 * One setting: always shown, except while searching, when it shows (whole) only if [texts] (its
 * name, explanation, choices) hold the words searched for.
 */
@Composable
internal fun Searchable(vararg texts: String?, content: @Composable () -> Unit) {
    val q = LocalSettingsQuery.current
    if (q.isEmpty()) {
        content()
        return
    }
    if (!searchMatches(q, *texts)) return
    RegisterHit()
    CompositionLocalProvider(LocalSettingsQuery provides "") { content() }
}

/** [content] (a note or heading between settings) only while not searching. */
@Composable
internal fun NotSearching(content: @Composable () -> Unit) {
    if (LocalSettingsQuery.current.isEmpty()) content()
}

/** [content] never filtered (e.g. a full-screen editor opened from a searched page). */
@Composable
internal fun NoSearch(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsQuery provides "", LocalSearchHits provides emptyList()) { content() }
}

/** Takes no room and draws nothing, but stays composed (a category with no matches, which must keep counting). */
internal fun Modifier.hiddenLayout(): Modifier = layout { measurable, constraints ->
    measurable.measure(constraints)
    layout(0, 0) {}
}

/** The search box of a settings page. */
@Composable
internal fun SettingsSearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Search settings") },
        leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
        trailingIcon = {
            if (query.isNotEmpty()) TextButton(onClick = { onChange("") }) { Text("✕") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/**
 * A settings page's scrolling body: the search box, then [header] (hidden while searching, e.g.
 * tabs), then [content] (its categories), at most 720 dp wide and centred, filtered by the search.
 */
@Composable
internal fun SearchableSettings(
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.(searching: Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val hits = remember { SearchHits() }
    Column(modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSearchField(query, { query = it })
            if (q.isEmpty()) header()
            CompositionLocalProvider(LocalSettingsQuery provides q, LocalSearchHits provides listOf(hits)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content(q.isNotEmpty()) }
            }
            if (q.isNotEmpty() && hits.count == 0) {
                Text(
                    "No setting here matches \"${query.trim()}\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

/**
 * A full-screen settings page over the pad (a button's, the touchpad's, a gyro display's, the
 * mapping editor's Settings): [title] and [subtitle] with [actions] and Done on top, then
 * [SearchableSettings] with [content].
 */
@Composable
internal fun SettingsDialog(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.(searching: Boolean) -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    actions()
                    TextButton(onClick = onClose) { Text("Done") }
                }
                SearchableSettings(
                    Modifier.weight(1f).fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 4.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * One category of a settings page, for [ReorderableSections]: [id] names it in the kept order,
 * [prefKey] keeps whether it is open (by default "settings.[id]").
 */
internal fun category(
    runtime: GamepadRuntime,
    id: String,
    title: String,
    subtitle: String? = null,
    defaultOpen: Boolean = false,
    prefKey: String = "settings.$id",
    content: @Composable ColumnScope.() -> Unit,
) = SectionItem(id) { card, header ->
    RememberedSection(runtime, prefKey, title, card, defaultOpen = defaultOpen, subtitle = subtitle, headerModifier = header, content = content)
}

/** A small label over a group of categories while searching (e.g. "Defaults"), shown only when [hits] has matches. */
@Composable
internal fun SearchGroupLabel(text: String, hits: SearchHits) {
    if (LocalSettingsQuery.current.isNotEmpty() && hits.count > 0) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}
