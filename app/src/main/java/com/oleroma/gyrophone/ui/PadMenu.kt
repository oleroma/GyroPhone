package com.oleroma.gyrophone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.PadDevice
import com.oleroma.gyrophone.core.model.PadDevices
import com.oleroma.gyrophone.core.model.isTouchpad
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** A page of the pad menu. Encoded as text so the open page survives the activity being recreated. */
sealed interface PadMenuPage {
    /** The menu of the current workspace: one list with collapsible categories. */
    data object Root : PadMenuPage
    /** Every mapping of the current workspace. */
    data object Mappings : PadMenuPage
    /** The PC link (the pad's Connection capsule). */
    data object Connection : PadMenuPage
    /** Options of the whole app (the pad's Settings capsule). */
    data object AppSettings : PadMenuPage

    companion object {
        private const val SEP = "\n"

        fun encode(stack: List<PadMenuPage>): String = stack.joinToString(SEP) { p ->
            when (p) {
                Root -> "root"
                Mappings -> "mapping"
                Connection -> "connection"
                AppSettings -> "settings"
            }
        }

        fun decode(text: String): List<PadMenuPage> = if (text.isEmpty()) emptyList() else text.split(SEP).mapNotNull { line ->
            val f = line.split("\t")
            when (f[0]) {
                "root" -> Root
                "mapping" -> Mappings
                "connection" -> Connection
                "settings" -> AppSettings
                else -> null
            }
        }
    }
}

/**
 * The whole app: the pad itself, or one of its pages in its place. The pad's capsules open
 * Connection and Settings; a workspace's full settings (one list of collapsible sections) open
 * from that workspace in edit mode. Its mappings open on their own page. Back steps out one page
 * at a time.
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
    fun open(p: PadMenuPage) = onNavigate(stack + p)
    val back = { onNavigate(stack.dropLast(1)) }
    /** Set by a page with unsaved changes: it asks before running the given way out. */
    var leaveGuard by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    fun leave(action: () -> Unit) = leaveGuard?.invoke(action) ?: action()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { leave(back) }) { Text(if (stack.size > 1) "← Back" else "← Pad") }
            Text(
                when (page) {
                    PadMenuPage.Mappings -> "Mappings"
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
            PadMenuPage.Root -> MenuList(runtime, content, onOpen = { open(it) }, onDeleted = onWorkspaceDeleted)
            PadMenuPage.Connection -> ConnectScreen(runtime, onRetry)
            PadMenuPage.AppSettings -> AppSettingsScreen(runtime, content)
            PadMenuPage.Mappings -> WorkspaceMappingEditor(
                runtime,
                content,
                onDone = back,
                onLeaveGuard = { leaveGuard = it },
            )
        }
    }
}

/**
 * All settings of the current workspace (⚙ in edit mode), as one list of collapsible sections:
 * the workspace itself (rename / duplicate / delete), its mappings, its devices, then its gyro,
 * controls and haptics. Settings of the whole app are on the Settings page instead.
 */
@Composable
private fun MenuList(runtime: GamepadRuntime, modifier: Modifier, onOpen: (PadMenuPage) -> Unit, onDeleted: () -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val current = wsIndex.coerceIn(0, profile.workspaces.lastIndex)
    val ws = profile.workspace(current)
    val devices = menuDevices(profile, controllers)
    var removing by remember { mutableStateOf<AttachedPad?>(null) }
    var copying by remember { mutableStateOf(false) }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RememberedSection(
            runtime,
            "workspace.Workspace",
            "Workspace",
            defaultOpen = true,
            subtitle = "${ws.name} · ${current + 1} of ${profile.workspaces.size}",
        ) {
            WorkspaceActions(profile, current) { p, _ ->
                val deleted = p.workspaces.size < profile.workspaces.size
                runtime.editActiveProfile { p }
                if (deleted) onDeleted()
            }
            Hint("Duplicate copies everything, mappings included; the copy can then be changed on its own.")
        }

        val touchCount = ws.mappings.count { !it.source.isTouchpad }
        RememberedSection(
            runtime,
            "workspace.Mappings",
            "Mappings",
            defaultOpen = true,
            subtitle = "$touchCount touch · ${ws.keyboard.size} keyboard · ${ws.controller.size} controller" +
                if (ws.sets.isEmpty()) "" else " · ${ws.sets.size} layers",
        ) {
            Hint("Touch buttons, touchpad, keyboard and controller mappings of this workspace only.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpen(PadMenuPage.Mappings) }) { Text("Edit mappings") }
                if (profile.workspaces.size > 1) {
                    OutlinedButton(onClick = { copying = true }) { Text("Copy from…") }
                }
            }
        }

        RememberedSection(
            runtime,
            "workspace.Devices",
            "Devices",
            subtitle = if (ws.pads.isEmpty()) "Touch screen only" else ws.pads.joinToString { it.name },
        ) {
            if (ws.pads.isEmpty()) Hint("No game pads in this workspace: only the touch screen (and a keyboard, if one is connected).")
            for (pad in ws.pads) {
                val device = devices.firstOrNull { it.match == pad.match } ?: PadDevice(pad.match, pad.name, pad.family)
                Text(pad.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (isConnected(device, controllers)) "Connected" else if (pad.match == null) "Any game pad" else "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DeviceItem(runtime, current, pad, onRemove = { removing = pad })
                HorizontalDivider()
            }
            val addable = devices.filter { d -> ws.pads.none { it.match == d.match } }
            if (addable.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Add device:", style = MaterialTheme.typography.labelLarge)
                    for (d in addable) {
                        OutlinedButton(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            onClick = { runtime.editActiveProfile { p -> p.updateWorkspace(current) { PadDevices.attach(it, d) } } },
                        ) { Text("+ ${d.name}", maxLines = 1) }
                    }
                }
            }
        }

        WorkspaceSettings(runtime, profile, current)
    }

    if (copying) {
        CopyMappingsDialog(runtime, current, discards = false, onDismiss = { copying = false }, onCopied = { copying = false })
    }

    removing?.let { pad ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${pad.name}?") },
            text = { Text("It stops acting in \"${ws.name}\". The workspace's controller mappings are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    runtime.editActiveProfile { p ->
                        p.updateWorkspace(current) { w -> w.copy(pads = w.pads.filterNot { it.id == pad.id }) }
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/**
 * One device of workspace [wsIndex]: its button layout (how its buttons are named and drawn) and
 * Remove. Its mappings are the workspace's controller mappings, unless it kept its own from
 * before mapping profiles.
 */
@Composable
private fun DeviceItem(runtime: GamepadRuntime, wsIndex: Int, pad: AttachedPad, onRemove: () -> Unit) {
    Text("Button layout", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (family in FaceLayout.entries) {
            FilterChip(
                selected = family == pad.family,
                onClick = {
                    if (family == pad.family) return@FilterChip
                    runtime.editActiveProfile { p ->
                        p.updateWorkspace(wsIndex) { w -> w.copy(pads = w.pads.map { if (it.id == pad.id) it.copy(family = family) else it }) }
                    }
                },
                label = { Text(familyTitle(family)) },
            )
        }
    }
    pad.own?.let { own ->
        Hint("This pad keeps the mappings it had before mapping profiles (${own.size}), instead of the workspace's controller mappings.")
        OutlinedButton(onClick = {
            runtime.editActiveProfile { p ->
                p.updateWorkspace(wsIndex) { w -> w.copy(pads = w.pads.map { if (it.id == pad.id) it.copy(own = null) else it }) }
            }
        }) { Text("Use the workspace's controller mappings") }
    }
    TextButton(onClick = onRemove) { Text("Remove device", color = MaterialTheme.colorScheme.error) }
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
