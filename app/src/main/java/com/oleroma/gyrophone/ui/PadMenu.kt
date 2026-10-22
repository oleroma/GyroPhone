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
import com.oleroma.gyrophone.core.model.MappingProfiles
import com.oleroma.gyrophone.core.model.PadDevice
import com.oleroma.gyrophone.core.model.PadDevices
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** A page of the pad menu. Encoded as text so the open page survives the activity being recreated. */
sealed interface PadMenuPage {
    /** The menu of the current workspace: one list with collapsible categories. */
    data object Root : PadMenuPage
    /** Full settings of mapping profile [profileId]; null = a new profile for the current workspace. */
    data class EditMapping(val profileId: String?) : PadMenuPage

    companion object {
        private const val SEP = "\n"

        fun encode(stack: List<PadMenuPage>): String = stack.joinToString(SEP) { p ->
            when (p) {
                Root -> "root"
                is EditMapping -> "mapping\t" + (p.profileId ?: "")
            }
        }

        fun decode(text: String): List<PadMenuPage> = if (text.isEmpty()) emptyList() else text.split(SEP).mapNotNull { line ->
            val f = line.split("\t")
            when (f[0]) {
                "root" -> Root
                "mapping" -> EditMapping(f.getOrNull(1)?.ifEmpty { null })
                else -> null
            }
        }
    }
}

/**
 * The Pad tab: the pad itself, or the pad menu in its place. The menu is about the current
 * workspace only: its mapping profile, its devices, then its settings, as collapsible categories.
 * Editing a mapping profile opens its own page.
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
                    is PadMenuPage.EditMapping -> if (page.profileId == null) "New mapping profile" else "Mapping profile"
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
            is PadMenuPage.EditMapping -> MappingProfileEditor(
                runtime,
                page.profileId,
                content,
                onDone = back,
                onLeaveGuard = { leaveGuard = it },
            )
        }
    }
}

/**
 * The menu of the current workspace only: which mapping profile it uses (pick, edit or make a new
 * one), its devices, then its settings. Another workspace is changed by switching to it on the pad
 * first (← / →).
 */
@Composable
private fun MenuList(runtime: GamepadRuntime, modifier: Modifier, onOpen: (PadMenuPage) -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val mappings by runtime.mappingProfiles.collectAsStateWithLifecycle()
    val current = wsIndex.coerceIn(0, profile.workspaces.lastIndex)
    val ws = profile.workspace(current)
    val using = MappingProfiles.of(ws, mappings)
    val devices = menuDevices(profile, controllers)
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

        // ── Mapping profile of this workspace ───────────────────────────────
        CategoryHeader("Mapping profile", using?.name, isOpen("mapping", true)) { toggle("mapping", true) }
        if (isOpen("mapping", true)) {
            Indented {
                Hint("Touch buttons, keyboard and controller mappings. Tap one to use it in this workspace.")
                for (m in mappings) {
                    val selected = m.id == using?.id
                    val users = profile.workspaces.filter { it.mappingProfileId == m.id }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { if (!selected) runtime.selectMappingProfile(m.id, current) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (users.isEmpty()) "Not used" else "Used by " + users.joinToString { it.name }) +
                                    " · ${m.touch.size + m.keyboard.size + m.controller.size} mappings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { onOpen(PadMenuPage.EditMapping(m.id)) }) { Text("Edit") }
                    }
                }
                OutlinedButton(onClick = { onOpen(PadMenuPage.EditMapping(null)) }) { Text("+ New profile") }
            }
        }

        // ── Devices of this workspace ───────────────────────────────────────
        CategoryHeader("Devices", "${ws.pads.size}", isOpen("devices", true)) { toggle("devices", true) }
        if (isOpen("devices", true)) {
            if (ws.pads.isEmpty()) Hint("No game pads in this workspace: only the touch screen (and a keyboard, if one is connected).")
            for (pad in ws.pads) {
                val device = devices.firstOrNull { it.match == pad.match } ?: PadDevice(pad.match, pad.name, pad.family)
                val key = "pad/" + ws.id + "/" + pad.id
                ItemHeader(
                    title = pad.name,
                    subtitle = if (isConnected(device, controllers)) "Connected" else if (pad.match == null) "Any game pad" else "Not connected",
                    expanded = isOpen(key),
                ) { toggle(key) }
                if (isOpen(key)) {
                    Indented { DeviceItem(runtime, current, pad, onRemove = { removing = pad }) }
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
            text = { Text("It stops acting in \"${ws.name}\". The mapping profile is kept.") },
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
 * Remove. Its mappings are the controller category of the workspace's mapping profile, unless it
 * kept its own from before mapping profiles.
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
        Hint("This pad keeps the mappings it had before mapping profiles (${own.size}), instead of the profile's Controller set.")
        OutlinedButton(onClick = {
            runtime.editActiveProfile { p ->
                p.updateWorkspace(wsIndex) { w -> w.copy(pads = w.pads.map { if (it.id == pad.id) it.copy(own = null) else it }) }
            }
        }) { Text("Use the profile's Controller mappings") }
    }
    TextButton(onClick = onRemove) { Text("Remove device", color = MaterialTheme.colorScheme.error) }
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
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}
