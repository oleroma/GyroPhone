package com.oleroma.gyrophone.ui

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.layout.Box
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.window.DialogProperties
import com.oleroma.gyrophone.runtime.GamepadRuntime

/*
 * The one layout every settings page shares: a search box on top, under it the page's categories
 * as tabs (a long press drags one sideways), only the picked one shown ([ReorderableSections]).
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

/**
 * The app's search box (settings pages, the mapping editor's library): a low rounded field in the cards' colours, ⌕ in front and
 * ✕ (clear) at the end while it holds text.
 */
@Composable
internal fun SettingsSearchField(
    query: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search settings",
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⌕", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable { onChange("") },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/**
 * A settings page: pinned on top the search box, then (not while searching) [header] (e.g. App
 * and Defaults), the page's own tabs ([PinnedScope]) and its category tabs ([ReorderableSections]);
 * under them [content] (its categories) scrolls. At most 720 dp wide and centred, filtered by the search.
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
    val scroll = rememberScrollState()
    val page = remember(scroll) { SettingsScroll(scroll) }
    val tabs = remember { SettingsTabs() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsSearchField(query, { query = it })
            if (q.isEmpty()) {
                header()
                tabs.scope.content?.invoke()
                tabs.bar.content?.invoke()
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { page.viewport = it.boundsInRoot() }.verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompositionLocalProvider(
                    LocalSettingsQuery provides q,
                    LocalSearchHits provides listOf(hits),
                    LocalSettingsScroll provides page,
                    LocalSettingsTabs provides tabs,
                ) {
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
}

/** A settings page's scrolling list and where it shows on screen (root coordinates): dragged categories scroll it ([ReorderableSections]). */
internal class SettingsScroll(val state: ScrollState) {
    var viewport: Rect = Rect.Zero
}

/** The [SearchableSettings] list this is in; null outside one. */
internal val LocalSettingsScroll = compositionLocalOf<SettingsScroll?> { null }

/**
 * A full-screen settings page over the pad (a button's, the touchpad's, a gyro display's, the
 * mapping editor's Settings): [title] and [subtitle] with [actions] and Done on top, [top] beside or under
 * them (all fixed), then [SearchableSettings] with [content].
 */
@Composable
internal fun SettingsDialog(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    /** Fixed, so it doesn't scroll with [content]: in the title row in landscape, under it in portrait; null = none. */
    top: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.(searching: Boolean) -> Unit,
) {
    FullScreenDialog(onDismiss = onClose) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val wide = top != null && LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(if (wide) Modifier.widthIn(max = 220.dp).padding(end = 12.dp) else Modifier.weight(1f)) {
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
                    if (wide) Box(Modifier.weight(1f)) { top?.invoke() }
                    actions()
                    TextButton(onClick = onClose) { Text("Done") }
                }
                if (top != null && !wide) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { top() }
                SearchableSettings(
                    Modifier.weight(1f).fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 4.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * A full-screen dialog whose window also covers the camera cutout, so the pad behind doesn't show
 * through there. [content] fills the whole screen: pad its controls by WindowInsets.safeDrawing.
 */
@Composable
internal fun FullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            val w = (view.parent as? DialogWindowProvider)?.window
            if (w != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (w.attributes.layoutInDisplayCutoutMode != mode) w.attributes = w.attributes.also { it.layoutInDisplayCutoutMode = mode }
            }
        }
        content()
    }
}

/**
 * A setting's context card (an action's ⚙): floating over the editor, but wide (the whole width in
 * portrait less a margin, wider still in landscape) so graphs and sliders have room. Scrolls when tall.
 */
@Composable
internal fun ContextCard(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val conf = LocalConfiguration.current
        val landscape = conf.screenWidthDp > conf.screenHeightDp
        Surface(
            Modifier
                .padding(horizontal = if (landscape) 20.dp else 8.dp, vertical = if (landscape) 8.dp else 24.dp)
                .widthIn(max = 1000.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f, fill = false).padding(top = 12.dp, end = 8.dp).verticalScroll(rememberScrollState()), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
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
) = SectionItem(id, title) { card, header ->
    RememberedSection(runtime, prefKey, title, card, defaultOpen = defaultOpen, subtitle = subtitle, headerModifier = header, content = content)
}

/** A small label over a group of categories while searching (e.g. "Defaults"), shown only when [hits] has matches. */
@Composable
internal fun SearchGroupLabel(text: String, hits: SearchHits) {
    if (LocalSettingsQuery.current.isNotEmpty() && hits.count > 0) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }
}

/** A heading over a group of settings inside a category (e.g. the Touchpad's "Held swipes"); hidden while searching. */
@Composable
internal fun SettingsGroupTitle(text: String, first: Boolean = false) = NotSearching {
    Text(
        text,
        Modifier.padding(top = if (first) 0.dp else 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
