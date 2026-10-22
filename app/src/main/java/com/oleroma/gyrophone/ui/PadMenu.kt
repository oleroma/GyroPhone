package com.oleroma.gyrophone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.PadDevice
import com.oleroma.gyrophone.core.model.PadDevices
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** A page of the pad menu. Encoded as text so the open page survives the activity being recreated. */
sealed interface PadMenuPage {
    /** The menu of the current workspace: one list with collapsible categories. */
    data object Root : PadMenuPage
    /** Game profiles (above workspaces): pick, create, rename, delete; games and corners. */
    data object GameProfiles : PadMenuPage
    /**
     * Full settings of a profile of device [deviceKey]; [presetId] null = a new profile. With
     * [loadInto] (a workspace index) the saved profile is loaded into pad [loadPadId] there.
     */
    data class EditProfile(
        val deviceKey: String,
        val presetId: String?,
        val loadInto: Int? = null,
        val loadPadId: String? = null,
    ) : PadMenuPage

    companion object {
        private const val SEP = "\n"

        fun encode(stack: List<PadMenuPage>): String = stack.joinToString(SEP) { p ->
            when (p) {
                Root -> "root"
                GameProfiles -> "game"
                is EditProfile -> listOf("edit", p.deviceKey, p.presetId ?: "", p.loadInto?.toString() ?: "", p.loadPadId ?: "")
                    .joinToString("\t")
            }
        }

        fun decode(text: String): List<PadMenuPage> = if (text.isEmpty()) emptyList() else text.split(SEP).mapNotNull { line ->
            val f = line.split("\t")
            when (f[0]) {
                "root" -> Root
                "game" -> GameProfiles
                "edit" -> f.getOrNull(1)?.let { key ->
                    EditProfile(
                        key,
                        f.getOrNull(2)?.ifEmpty { null },
                        f.getOrNull(3)?.toIntOrNull(),
                        f.getOrNull(4)?.ifEmpty { null },
                    )
                }
                else -> null
            }
        }
    }
}

/**
 * The Pad tab: the pad itself, or the pad menu in its place. The menu is about the current
 * workspace only: its devices, each with its profile, then its settings, as collapsible
 * categories. Editing a device profile opens its own page. The pad's Game profile button opens
 * the game profiles page (they hold the workspaces, so they are not in the workspace menu).
 * Back steps out one page at a time.
 */
@Composable
fun PadTab(runtime: GamepadRuntime, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = remember(stackText) { PadMenuPage.decode(stackText) }
    fun go(next: List<PadMenuPage>) { stackText = PadMenuPage.encode(next) }

    if (stack.isEmpty()) {
        PadScreen(
            runtime,
            fullscreen = fullscreen,
            onFullscreen = onFullscreen,
            onOpenMenu = { page ->
                onFullscreen(false)
                go(listOf(page))
            },
        )
    } else {
        BackHandler { go(stack.dropLast(1)) }
        PadMenu(runtime = runtime, stack = stack, onNavigate = { go(it) })
    }
}

@Composable
private fun PadMenu(
    runtime: GamepadRuntime,
    stack: List<PadMenuPage>,
    onNavigate: (List<PadMenuPage>) -> Unit,
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
                    is PadMenuPage.EditProfile -> if (page.presetId == null) "New profile" else "Profile"
                    PadMenuPage.GameProfiles -> "Game profiles"
                    PadMenuPage.Root -> "Menu"
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
            PadMenuPage.Root -> MenuList(runtime, content, onOpen = { open(it) })
            PadMenuPage.GameProfiles -> Column(content.verticalScroll(rememberScrollState())) { GameProfileSection(runtime) }
            is PadMenuPage.EditProfile -> PadProfileEditor(
                runtime,
                page.deviceKey,
                page.presetId,
                page.loadInto,
                page.loadPadId,
                content,
                onDone = back,
                onLeaveGuard = { leaveGuard = it },
            )
        }
    }
}

/**
 * The menu of the current workspace only: its devices, each with its profile (load another, edit
 * one or make a new one), then the workspace's settings. Game profiles have their own page. Another workspace's
 * devices are changed by switching to it on the pad first (← / →).
 */
@Composable
private fun MenuList(runtime: GamepadRuntime, modifier: Modifier, onOpen: (PadMenuPage) -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val userPresets by runtime.padPresets.collectAsStateWithLifecycle()
    val current = wsIndex.coerceIn(0, profile.workspaces.lastIndex)
    val ws = profile.workspace(current)
    val devices = menuDevices(profile, controllers, userPresets)
    val open = MenuOpenState.open
    fun isOpen(key: String, default: Boolean = false) = open[key] ?: default
    fun toggle(key: String, default: Boolean = false) { open[key] = !isOpen(key, default) }
    var removing by remember { mutableStateOf<AttachedPad?>(null) }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Workspace ${current + 1}/${profile.workspaces.size} · ${ws.name}",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (profile.workspaces.size > 1) Hint("Other workspaces: switch with ← / → on the pad, then open the menu there.")

        // ── Devices of this workspace ───────────────────────────────────────
        CategoryHeader("Devices", "${ws.pads.size}", isOpen("devices", true)) { toggle("devices", true) }
        if (isOpen("devices", true)) {
            if (ws.pads.isEmpty()) Hint("No game pads in this workspace: only the touch screen.")
            for (pad in ws.pads) {
                val device = devices.firstOrNull { it.match == pad.match } ?: PadDevice(pad.match, pad.name, pad.family)
                val applied = PadPresets.applied(pad, userPresets)
                val key = "pad/" + ws.id + "/" + pad.id
                ItemHeader(
                    title = pad.name,
                    subtitle = (if (isConnected(device, controllers)) "Connected" else if (pad.match == null) "Any game pad" else "Not connected") +
                        " · Profile: " + (applied?.name ?: PadPresets.CUSTOM_NAME),
                    expanded = isOpen(key, true),
                ) { toggle(key, true) }
                if (isOpen(key, true)) {
                    Indented {
                        PadProfiles(runtime, current, pad, device, userPresets, onOpen, onRemove = { removing = pad })
                    }
                }
            }
            val addable = devices.filter { d -> ws.pads.none { it.match == d.match } }
            if (addable.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, top = 4.dp),
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

        // ── This workspace's settings ───────────────────────────────────────
        CategoryHeader("Gyro, touchpad, touch keys", null, isOpen("wsSettings")) { toggle("wsSettings") }
        if (isOpen("wsSettings")) {
            Indented { WorkspaceSettings(runtime, profile, current) }
        }
    }

    removing?.let { pad ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${pad.name}?") },
            text = { Text("It stops acting in \"${ws.name}\". Its profiles are kept.") },
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
 * One device of workspace [wsIndex]: its profiles (tap = load it into the pad; Edit opens its
 * settings), + New profile, the button layout, and Remove.
 */
@Composable
private fun PadProfiles(
    runtime: GamepadRuntime,
    wsIndex: Int,
    pad: AttachedPad,
    device: PadDevice,
    userPresets: List<PadPreset>,
    onOpen: (PadMenuPage) -> Unit,
    onRemove: () -> Unit,
) {
    val applied = PadPresets.applied(pad, userPresets)
    Text("Profile", style = MaterialTheme.typography.labelLarge)
    for (preset in PadPresets.forDevice(pad.match, pad.family, userPresets)) {
        val selected = preset.id == applied?.id
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    // Loading the shown profile again links a pad saved before pads named their profile.
                    if (!selected || pad.presetId != preset.id) runtime.applyPadPreset(pad.id, preset, wsIndex)
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (PadPresets.isBuiltin(preset.id)) "Built in · " else "") + "${preset.mappings.size} mappings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                // Only the profile the pad uses is loaded back into it on Save; others are just edited.
                onOpen(
                    if (selected) PadMenuPage.EditProfile(device.key, preset.id, wsIndex, pad.id)
                    else PadMenuPage.EditProfile(device.key, preset.id),
                )
            }) { Text("Edit") }
        }
    }
    if (applied == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = true, onClick = null, modifier = Modifier.padding(end = 8.dp))
            Text("${PadPresets.CUSTOM_NAME} (not saved)", modifier = Modifier.weight(1f))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onOpen(PadMenuPage.EditProfile(device.key, null, wsIndex, pad.id)) }) {
            Text(if (applied == null) "Save as profile…" else "+ New profile")
        }
        TextButton(onClick = onRemove) { Text("Remove device", color = MaterialTheme.colorScheme.error) }
    }
    Text("Button layout", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (family in FaceLayout.entries) {
            FilterChip(
                selected = family == pad.family,
                onClick = {
                    if (family == pad.family) return@FilterChip
                    // A pad on the standard profile takes the new layout's standard mapping.
                    val onStandard = applied?.id == PadPresets.STANDARD
                    val standard = PadPresets.builtins(family).first { it.id == PadPresets.STANDARD }
                    runtime.editActiveProfile { p ->
                        p.updateWorkspace(wsIndex) { w ->
                            w.copy(pads = w.pads.map {
                                if (it.id != pad.id) it
                                else it.copy(family = family).let { moved -> if (onStandard) PadPresets.apply(moved, standard) else moved }
                            })
                        }
                    }
                },
                label = { Text(familyTitle(family)) },
            )
        }
    }
}

/** Which menu categories and items are expanded; kept for as long as the app runs. */
private object MenuOpenState {
    val open = mutableStateMapOf<String, Boolean>()
}

@Composable
private fun CategoryHeader(title: String, detail: String?, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun ItemHeader(title: String, subtitle: String?, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (expanded) "▾" else "▸", modifier = Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Indented(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}
