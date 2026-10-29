package com.oleroma.gyrophone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.MappingSet
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** A page of the pad menu. Encoded as text so the open page survives the activity being recreated. */
sealed interface PadMenuPage {
    /** The menu of the current workspace: one list with collapsible categories. */
    data object Root : PadMenuPage
    /** What physical devices do: the device presets (the pad's Controller capsule). */
    data object Devices : PadMenuPage
    /** The PC link (the pad's Connection capsule). */
    data object Connection : PadMenuPage
    /** Options of the whole app (the pad's Settings capsule). */
    data object AppSettings : PadMenuPage

    companion object {
        private const val SEP = "\n"

        fun encode(stack: List<PadMenuPage>): String = stack.joinToString(SEP) { p ->
            when (p) {
                Root -> "root"
                Devices -> "devices"
                Connection -> "connection"
                AppSettings -> "settings"
            }
        }

        fun decode(text: String): List<PadMenuPage> = if (text.isEmpty()) emptyList() else text.split(SEP).mapNotNull { line ->
            val f = line.split("\t")
            when (f[0]) {
                "root" -> Root
                "devices" -> Devices
                "connection" -> Connection
                "settings" -> AppSettings
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

    if (stack.isEmpty()) {
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
        )
    } else {
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
                    PadMenuPage.AppSettings -> "Settings"
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
        val content = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)
        when (page) {
            PadMenuPage.Root -> MenuList(runtime, content, onDeleted = onWorkspaceDeleted)
            PadMenuPage.Connection -> ConnectScreen(runtime, onRetry)
            PadMenuPage.AppSettings -> AppSettingsScreen(runtime, content)
            PadMenuPage.Devices -> Unit
        }
    }
}

/**
 * All settings of the current workspace (⚙ in edit mode) in one list of categories the person
 * can reorder: Workspace (rename / duplicate / delete, copy mappings), gyro, controls, haptics,
 * touchpad and gestures, over the Defaults. With layers, tabs on top pick Base or a layer, whose
 * settings go over Base's. Settings of the whole app are on the Settings page instead.
 */
@Composable
private fun MenuList(runtime: GamepadRuntime, modifier: Modifier, onDeleted: () -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val current = wsIndex.coerceIn(0, profile.workspaces.lastIndex)
    val ws = profile.workspace(current)
    var copying by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(MappingSet.BASE) }
    val set = if (ws.sets.any { it.id == tab }) tab else MappingSet.BASE

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ws.sets.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = set == MappingSet.BASE, onClick = { tab = MappingSet.BASE }, label = { Text("Base") })
                ws.sets.forEach { st ->
                    FilterChip(selected = set == st.id, onClick = { tab = st.id }, label = { Text(st.label) })
                }
            }
        }
        Hint(
            if (set == MappingSet.BASE) "Each setting follows the Defaults (Settings) unless it is set here: those are " +
                    "highlighted, with ↺ to follow the Defaults again. Long press a category to move it."
            else "While this layer is in use. Each setting follows Base unless it is set here: those are " +
                    "highlighted, with ↺ to follow Base again.",
        )
        val workspace = SectionItem("workspace") { card, header ->
            RememberedSection(
                runtime,
                "workspace.Workspace",
                "Workspace",
                card,
                defaultOpen = true,
                subtitle = "${ws.name} · ${current + 1} of ${profile.workspaces.size}",
                headerModifier = header,
            ) {
                WorkspaceActions(profile, current) { p, _ ->
                    val deleted = p.workspaces.size < profile.workspaces.size
                    runtime.editActiveProfile { p }
                    if (deleted) onDeleted()
                }
                Hint("Duplicate copies everything, mappings included; the copy can then be changed on its own.")
                if (profile.workspaces.size > 1) {
                    OutlinedButton(onClick = { copying = true }) { Text("Copy mappings from…") }
                }
            }
        }
        val level = setLevel(ws, profile.defaults, set) { change -> runtime.editActiveProfile { it.updateWorkspace(current, change) } }
        WorkspaceSettings(
            runtime, level, profile.id + "/" + ws.id + "/" + set,
            extra = if (set == MappingSet.BASE) listOf(workspace) else emptyList(),
        )
    }

    if (copying) {
        CopyMappingsDialog(runtime, current, discards = false, onDismiss = { copying = false }, onCopied = { copying = false })
    }
}

@Composable
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}
