package com.oleroma.gyrophone.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChipDefaults
import androidx.activity.compose.BackHandler
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** A page of the pad menu. Encoded as text so the open page survives the activity being recreated. */
sealed interface PadMenuPage {
    /** The menu of the current workspace: one list with collapsible categories. */
    data object Root : PadMenuPage
    /** What physical devices do: the device presets (the pad's Controller capsule). */
    data object Devices : PadMenuPage
    /** The PC link (the pad's Connection capsule). */
    data object Connection : PadMenuPage
    /** Options of the whole app (the pad's App capsule). */
    data object AppSettings : PadMenuPage
    /** What every workspace uses unless it sets its own (the pad's Defaults capsule). */
    data object DefaultsSettings : PadMenuPage

    companion object {
        private const val SEP = "\n"

        fun encode(stack: List<PadMenuPage>): String = stack.joinToString(SEP) { p ->
            when (p) {
                Root -> "root"
                Devices -> "devices"
                Connection -> "connection"
                AppSettings -> "settings"
                DefaultsSettings -> "defaults"
            }
        }

        fun decode(text: String): List<PadMenuPage> = if (text.isEmpty()) emptyList() else text.split(SEP).mapNotNull { line ->
            val f = line.split("\t")
            when (f[0]) {
                "root" -> Root
                "devices" -> Devices
                "connection" -> Connection
                "settings" -> AppSettings
                "defaults" -> DefaultsSettings
                else -> null
            }
        }
    }
}

/**
 * The whole app: the pad itself, or one of its pages in its place. The pad's capsules open
 * the device screens (Controller), Connection and Settings; a workspace's full settings (one list
 * of collapsible sections) open from that workspace in edit mode. Back steps out one page at a time.
 */
@Composable
fun PadTab(runtime: GamepadRuntime, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, onRetry: () -> Unit) {
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = remember(stackText) { PadMenuPage.decode(stackText) }
    fun go(next: List<PadMenuPage>) { stackText = PadMenuPage.encode(next) }
    /** The workspace page was opened from edit mode: back on the pad, edit mode again. */
    var editOnReturn by rememberSaveable { mutableStateOf(false) }

    // The pad stays composed under a menu page, only hidden: coming back shows it at once, with its
    // caches, instead of building the whole pad again ([PadScreen] pauses itself while hidden).
    val menuOpen = stack.isNotEmpty()
    Box(Modifier.fillMaxSize()) {
        KeptUnder(hidden = menuOpen) {
            PadScreen(
                runtime,
                fullscreen = fullscreen,
                onFullscreen = onFullscreen,
                onOpenMenu = { page ->
                    onFullscreen(false)
                    go(listOf(page))
                },
                onWorkspaceSettings = {
                    editOnReturn = true
                    go(listOf(PadMenuPage.Root))
                },
                startInEdit = editOnReturn,
                onEditResumed = { editOnReturn = false },
                hidden = menuOpen,
            )
        }
        if (menuOpen) {
            BackHandler { go(stack.dropLast(1)) }
            PadMenu(
                runtime = runtime,
                stack = stack,
                onNavigate = { go(it) },
                onRetry = onRetry,
                onWorkspaceDeleted = {
                    editOnReturn = false
                    go(emptyList())
                },
            )
        }
    }
}

/**
 * [content] stays composed, but while [hidden] it is only measured: not placed, so it is neither
 * drawn nor touched, and what lies over it gets the whole screen.
 */
@Composable
private fun KeptUnder(hidden: Boolean, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (!hidden) placeables.forEach { it.place(0, 0) }
        }
    }
}

@Composable
private fun PadMenu(
    runtime: GamepadRuntime,
    stack: List<PadMenuPage>,
    onNavigate: (List<PadMenuPage>) -> Unit,
    onRetry: () -> Unit,
    onWorkspaceDeleted: () -> Unit,
) {
    val page = stack.last()
    val back = { onNavigate(stack.dropLast(1)) }
    /** Set by a page with unsaved changes: it asks before running the given way out. */
    var leaveGuard by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    fun leave(action: () -> Unit) = leaveGuard?.invoke(action) ?: action()

    if (page == PadMenuPage.Devices) {
        // Its own top row: the presets.
        DevicePresetsScreen(runtime, onBack = back)
        return
    }
    // The workspace's own actions stay fixed: in the title row in landscape, under it in portrait.
    val wide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val actions = page == PadMenuPage.Root
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { leave(back) }) { Text(if (stack.size > 1) "← Back" else "← Pad") }
            Text(
                when (page) {
                    PadMenuPage.Devices -> "Controller"
                    PadMenuPage.Root -> "Workspace settings"
                    PadMenuPage.Connection -> "Connection"
                    PadMenuPage.AppSettings -> "App"
                    PadMenuPage.DefaultsSettings -> "Defaults"
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (actions && wide) Modifier.padding(end = 12.dp) else Modifier.weight(1f),
            )
            if (actions && wide) Box(Modifier.weight(1f)) { RootActions(runtime, onWorkspaceDeleted) }
        }
        if (actions && !wide) Box(Modifier.padding(horizontal = 12.dp)) { RootActions(runtime, onWorkspaceDeleted) }
        HorizontalDivider()
        val content = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)
        when (page) {
            PadMenuPage.Root -> MenuList(runtime, content)
            PadMenuPage.Connection -> ConnectScreen(runtime, onRetry)
            PadMenuPage.AppSettings -> AppSettingsScreen(runtime, content)
            PadMenuPage.DefaultsSettings -> DefaultsSettingsScreen(runtime, content)
            PadMenuPage.Devices -> Unit
        }
    }
}

/**
 * The current workspace's settings page from the Settings menu (the live profile): the same
 * [WorkspaceSettingsPage] as edit mode's ⚙ and the touchpad's Settings in its mapping editor.
 */
@Composable
private fun MenuList(runtime: GamepadRuntime, modifier: Modifier) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(MappingSet.BASE) }
    SearchableSettings(modifier) {
        WorkspaceSettingsPage(runtime, profile, wsIndex, tab, { tab = it }, onWorkspaceChange = { change -> editLive(runtime, wsIndex, change) })
    }
}

private fun editLive(runtime: GamepadRuntime, index: Int, change: (Workspace) -> Workspace) {
    runtime.editActiveProfile { it.updateWorkspace(index.coerceIn(0, it.workspaces.lastIndex), change) }
}

/** The current workspace's [WorkspaceTopBar] on the live profile; [onDeleted] after a delete. */
@Composable
private fun RootActions(runtime: GamepadRuntime, onDeleted: () -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    WorkspaceTopBar(
        runtime, profile, wsIndex,
        onProfileChange = { p, _ ->
            val deleted = p.workspaces.size < profile.workspaces.size
            runtime.editActiveProfile { p }
            if (deleted) onDeleted()
        },
        onWorkspaceChange = { change -> editLive(runtime, wsIndex, change) },
    )
}

/**
 * Workspace [index]'s own actions, in the fixed panel above its settings (they don't scroll):
 * rename, duplicate, delete ([onProfileChange], with the index to show next) and "Copy mappings
 * from…" ([onWorkspaceChange] on a [draft]).
 */
@Composable
internal fun WorkspaceTopBar(
    runtime: GamepadRuntime,
    profile: Profile,
    index: Int,
    onProfileChange: (Profile, Int) -> Unit,
    onWorkspaceChange: ((Workspace) -> Workspace) -> Unit,
    /** Copies go into this profile ([CopyMappingsDialog]'s onCopy) instead of the saved one: edit mode's draft. */
    draft: Boolean = false,
) {
    val current = index.coerceIn(0, profile.workspaces.lastIndex)
    var copying by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        WorkspaceActions(profile, current, onProfileChange) {
            if (profile.workspaces.size > 1) {
                OutlinedButton(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), onClick = { copying = true }) {
                    Text("Copy mappings from…")
                }
            }
        }
    }
    if (copying) {
        CopyMappingsDialog(
            runtime, current, discards = false, onDismiss = { copying = false }, onCopied = { copying = false },
            profile = if (draft) profile else null,
            onCopy = if (draft) { w -> onWorkspaceChange { w } } else null,
        )
    }
}

/**
 * All settings of workspace [index] of [profile] in one list of categories the person can
 * reorder: gyro, controls, haptics, gestures and the touchpad, over the Defaults (rename,
 * duplicate, delete and copy are above it, in [WorkspaceTopBar]). With layers, chips on top pick
 * Base or a layer ([set], [onSet]), whose settings go over Base's. One page wherever it opens:
 * edit mode's ⚙, the touchpad's Settings in its mapping editor (both on edit mode's draft), the
 * Settings menu. [onWorkspaceChange] gets every edit.
 */
@Composable
internal fun WorkspaceSettingsPage(
    runtime: GamepadRuntime,
    profile: Profile,
    index: Int,
    set: Int,
    onSet: (Int) -> Unit,
    onWorkspaceChange: ((Workspace) -> Workspace) -> Unit,
) {
    val current = index.coerceIn(0, profile.workspaces.lastIndex)
    val ws = profile.workspace(current)
    val shown = if (ws.sets.any { it.id == set }) set else MappingSet.BASE

    // Base and the layers: pinned under the search box, above the category tabs, as an accented
    // "Layers" band so they don't read as more category tabs.
    if (ws.sets.isNotEmpty()) NotSearching {
        PinnedScope { LayerPicker(ws, shown, onSet) }
    }
    Hint(
        if (shown == MappingSet.BASE) "Each setting follows the Defaults (the Defaults pill) unless it is set here: those are " +
                "highlighted, with ↺ to follow the Defaults again. Long press a tab to move it."
        else "While this layer is in use. Each setting follows Base unless it is set here: those are " +
                "highlighted, with ↺ to follow Base again.",
    )
    // Kept while the workspace and Defaults are: its own keys and children are worked out once, not per recomposition.
    val update by rememberUpdatedState(onWorkspaceChange)
    val level = remember(ws, profile.defaults, shown) { setLevel(ws, profile.defaults, shown) { change -> update(change) } }
    WorkspaceSettings(runtime, level, profile.id + "/" + ws.id + "/" + shown)
}

@Composable
internal fun Hint(text: String) = NotSearching {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/** Which of [ws]'s layers (or Base) a workspace page shows: a band in the accent colour, "Layers" then a chip each. */
@Composable
private fun LayerPicker(ws: Workspace, shown: Int, onSet: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = c.tertiaryContainer.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Layers", style = MaterialTheme.typography.labelLarge, color = c.tertiary)
            val colors = FilterChipDefaults.filterChipColors(
                labelColor = c.onTertiaryContainer,
                selectedContainerColor = c.tertiary,
                selectedLabelColor = c.onTertiary,
            )
            @Composable
            fun chip(id: Int, label: String) {
                FilterChip(
                    selected = shown == id,
                    onClick = { onSet(id) },
                    label = { Text(label) },
                    colors = colors,
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = shown == id,
                        borderColor = c.tertiary.copy(alpha = 0.6f),
                    ),
                )
            }
            chip(MappingSet.BASE, "Base")
            ws.sets.forEach { st -> chip(st.id, st.label) }
        }
    }
}
