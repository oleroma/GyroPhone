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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** A page of the pad menu. Encoded as text so the open page survives the activity being recreated. */
sealed interface PadMenuPage {
    /** The menu: one list with collapsible categories. */
    data object Root : PadMenuPage
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
                is EditProfile -> listOf("edit", p.deviceKey, p.presetId ?: "", p.loadInto?.toString() ?: "", p.loadPadId ?: "")
                    .joinToString("\t")
            }
        }

        fun decode(text: String): List<PadMenuPage> = if (text.isEmpty()) emptyList() else text.split(SEP).mapNotNull { line ->
            val f = line.split("\t")
            when (f[0]) {
                "root" -> Root
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
 * The Pad tab: the pad itself, or the pad menu in its place. The menu is one list with collapsible
 * categories: Devices (each with its profiles), Workspaces (the devices each one uses together, and
 * its gyro / touchpad settings) and Game profile. Editing a device profile opens its own page.
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
            onOpenMenu = {
                onFullscreen(false)
                go(listOf(PadMenuPage.Root))
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
                if (page is PadMenuPage.EditProfile) (if (page.presetId == null) "New profile" else "Profile") else "Menu",
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

/** The single menu list. Which categories and items are open is kept while the app runs. */
@Composable
private fun MenuList(runtime: GamepadRuntime, modifier: Modifier, onOpen: (PadMenuPage) -> Unit) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val userPresets by runtime.padPresets.collectAsStateWithLifecycle()
    val current = wsIndex.coerceIn(0, profile.workspaces.lastIndex)
    val devices = menuDevices(profile, controllers, userPresets)
    val open = MenuOpenState.open
    fun isOpen(key: String, default: Boolean = false) = open[key] ?: default
    fun toggle(key: String, default: Boolean = false) { open[key] = !isOpen(key, default) }
    var detaching by remember { mutableStateOf<Pair<Int, AttachedPad>?>(null) }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // ── Devices ─────────────────────────────────────────────────────────
        CategoryHeader("Devices", "${devices.size}", isOpen("devices", true)) { toggle("devices", true) }
        if (isOpen("devices", true)) {
            if (devices.isEmpty()) {
                Hint("No game pads yet. Connect one to the phone and it shows here.")
            }
            for (d in devices) {
                val key = "device/" + d.key
                val usedIn = profile.workspaces.withIndex().filter { (_, w) -> w.pads.any { it.match == d.match } }.map { it.index }
                ItemHeader(
                    title = d.name,
                    subtitle = (if (isConnected(d, controllers)) "Connected" else if (d.match == null) "Any game pad" else "Not connected") +
                        " · " + (if (usedIn.isEmpty()) "in no workspace" else "in " + usedIn.joinToString(", ") { profile.workspaces[it].name }),
                    expanded = isOpen(key),
                ) { toggle(key) }
                if (isOpen(key)) {
                    Indented {
                        DeviceDetails(runtime, d, userPresets, onOpen)
                    }
                }
            }
        }

        // ── Workspaces ──────────────────────────────────────────────────────
        CategoryHeader("Workspaces", "${profile.workspaces.size}", isOpen("workspaces", true)) { toggle("workspaces", true) }
        if (isOpen("workspaces", true)) {
            Hint("Each workspace uses its devices together with the touch screen. Tap ← / → on the pad to switch; Edit layout adds and arranges workspaces.")
            profile.workspaces.forEachIndexed { i, w ->
                val key = "ws/" + w.id
                val expanded = isOpen(key, i == current)
                ItemHeader(
                    title = (if (i == current) "● " else "") + "${i + 1} · ${w.name}",
                    subtitle = if (w.pads.isEmpty()) "Touch only" else w.pads.joinToString(" + ") { it.name },
                    expanded = expanded,
                ) { toggle(key, i == current) }
                if (expanded) {
                    Indented {
                        if (i != current) {
                            OutlinedButton(onClick = { runtime.switchWorkspace(i) }) { Text("Switch to this workspace") }
                        }
                        DevicesPanel(
                            runtime = runtime,
                            wsIndex = i,
                            devices = devices,
                            userPresets = userPresets,
                            onOpen = onOpen,
                            onDetach = { pad -> detaching = i to pad },
                        )
                        val settingsKey = "wsSettings/" + w.id
                        ItemHeader("Gyro, touchpad, touch keys", null, isOpen(settingsKey)) { toggle(settingsKey) }
                        if (isOpen(settingsKey)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                WorkspaceSettings(runtime, profile, i)
                            }
                        }
                    }
                }
            }
        }

        // ── Game profile ────────────────────────────────────────────────────
        CategoryHeader("Game profile", profile.name, isOpen("game")) { toggle("game") }
        if (isOpen("game")) {
            Indented { GameProfileSection(runtime) }
        }
    }

    detaching?.let { (i, pad) ->
        AlertDialog(
            onDismissRequest = { detaching = null },
            title = { Text("Remove ${pad.name}?") },
            text = { Text("It stops acting in \"${profile.workspace(i).name}\". Its profiles are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    detaching = null
                    runtime.editActiveProfile { p ->
                        p.updateWorkspace(i) { w -> w.copy(pads = w.pads.filterNot { it.id == pad.id }) }
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { detaching = null }) { Text("Cancel") } },
        )
    }
}

/** One device: its button layout and its profiles (edit any, or make a new one). */
@Composable
private fun DeviceDetails(
    runtime: GamepadRuntime,
    device: PadDevice,
    userPresets: List<com.oleroma.gyrophone.core.model.PadPreset>,
    onOpen: (PadMenuPage) -> Unit,
) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val pads = profile.workspaces.flatMap { it.pads }.filter { it.match == device.match }
    Text("Profiles", style = MaterialTheme.typography.labelLarge)
    for (preset in PadPresets.forDevice(device.match, device.family, userPresets)) {
        val users = pads.count { PadPresets.applied(it, userPresets)?.id == preset.id }
        Row(
            Modifier.fillMaxWidth().clickable { onOpen(PadMenuPage.EditProfile(device.key, preset.id)) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (PadPresets.isBuiltin(preset.id)) "Built in" else null,
                        if (users > 0) "used in $users workspace(s)" else null,
                        "${preset.mappings.size} mappings",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Edit ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
    OutlinedButton(onClick = { onOpen(PadMenuPage.EditProfile(device.key, null)) }) { Text("+ New profile") }
    if (pads.isNotEmpty()) {
        Text("Button layout", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (family in FaceLayout.entries) {
                FilterChip(
                    selected = family == device.family,
                    onClick = {
                        if (family == device.family) return@FilterChip
                        val standard = PadPresets.builtins(family).first { it.id == PadPresets.STANDARD }
                        runtime.editActiveProfile { p ->
                            p.copy(workspaces = p.workspaces.map { w ->
                                w.copy(pads = w.pads.map { pad ->
                                    if (pad.match != device.match) pad
                                    else {
                                        // A pad on the standard profile takes the new layout's standard mapping.
                                        val onStandard = PadPresets.applied(pad, userPresets)?.id == PadPresets.STANDARD
                                        val moved = pad.copy(family = family)
                                        if (onStandard) PadPresets.apply(moved, standard) else moved
                                    }
                                })
                            })
                        }
                    },
                    label = { Text(familyTitle(family)) },
                )
            }
        }
    }
}

/**
 * The devices workspace [wsIndex] uses at the same time: each with its profile (tap another to
 * load it, or + New), and the other devices to add.
 */
@Composable
private fun DevicesPanel(
    runtime: GamepadRuntime,
    wsIndex: Int,
    devices: List<PadDevice>,
    userPresets: List<com.oleroma.gyrophone.core.model.PadPreset>,
    onOpen: (PadMenuPage) -> Unit,
    onDetach: (AttachedPad) -> Unit,
) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val controllers by runtime.controllers.collectAsStateWithLifecycle()
    val w = profile.workspace(wsIndex)
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Devices used together", style = MaterialTheme.typography.labelLarge)
            if (w.pads.isEmpty()) Hint("None: only the touch screen.")
            for (pad in w.pads) {
                val device = devices.firstOrNull { it.match == pad.match } ?: PadDevice(pad.match, pad.name, pad.family)
                val applied = PadPresets.applied(pad, userPresets)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pad.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (if (isConnected(device, controllers)) "Connected" else "Not connected") +
                                " · Profile: " + (applied?.name ?: PadPresets.CUSTOM_NAME),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { onDetach(pad) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (preset in PadPresets.forDevice(pad.match, pad.family, userPresets)) {
                        FilterChip(
                            selected = preset.id == applied?.id,
                            onClick = {
                                // Loading the shown profile again links a pad saved before pads named their profile.
                                if (preset.id != applied?.id || pad.presetId != preset.id) runtime.applyPadPreset(pad.id, preset, wsIndex)
                            },
                            label = { Text(preset.name, maxLines = 1) },
                        )
                    }
                    if (applied == null) {
                        FilterChip(selected = true, onClick = {}, enabled = false, label = { Text(PadPresets.CUSTOM_NAME) })
                    }
                    OutlinedButton(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        onClick = { onOpen(PadMenuPage.EditProfile(device.key, null, wsIndex, pad.id)) },
                    ) { Text(if (applied == null) "Save as profile…" else "+ New") }
                }
            }
            val addable = devices.filter { d -> w.pads.none { it.match == d.match } }
            if (addable.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Add:", style = MaterialTheme.typography.labelLarge)
                    for (d in addable) {
                        OutlinedButton(
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            onClick = { runtime.editActiveProfile { p -> p.updateWorkspace(wsIndex) { PadDevices.attach(it, d) } } },
                        ) { Text("+ ${d.name}", maxLines = 1) }
                    }
                }
            }
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
