package com.oleroma.gyrophone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.oleroma.gyrophone.runtime.GamepadRuntime

/** A page of the pad menu. Encoded as text so the open page survives the activity being recreated. */
sealed interface PadMenuPage {
    data object Root : PadMenuPage
    /** Game profiles: pick, create, rename, delete; workspaces list and corners. */
    data object Profiles : PadMenuPage
    /** Gyro, touch keys and touchpad of the current workspace. */
    data object Workspace : PadMenuPage
    /** The game pads attached to the current workspace. */
    data object Devices : PadMenuPage
    /** One attached pad of the current workspace and its profile. */
    data class Device(val padId: String) : PadMenuPage
    /** Full settings of a pad profile, for pad [padId]; [presetId] null = a new profile. */
    data class EditProfile(val padId: String, val presetId: String?) : PadMenuPage

    companion object {
        private const val SEP = "\n"

        fun encode(stack: List<PadMenuPage>): String = stack.joinToString(SEP) { p ->
            when (p) {
                Root -> "root"
                Profiles -> "profiles"
                Workspace -> "workspace"
                Devices -> "devices"
                is Device -> "device\t${p.padId}"
                is EditProfile -> "edit\t${p.padId}\t${p.presetId ?: ""}"
            }
        }

        fun decode(text: String): List<PadMenuPage> = if (text.isEmpty()) emptyList() else text.split(SEP).mapNotNull { line ->
            val f = line.split("\t")
            when (f[0]) {
                "root" -> Root
                "profiles" -> Profiles
                "workspace" -> Workspace
                "devices" -> Devices
                "device" -> f.getOrNull(1)?.let { Device(it) }
                "edit" -> f.getOrNull(1)?.let { EditProfile(it, f.getOrNull(2)?.ifEmpty { null }) }
                else -> null
            }
        }
    }
}

/**
 * The Pad tab: the pad itself, or the pad menu in its place. The menu holds everything that is not
 * the pad: game profiles, workspace settings, and the devices of the current workspace with their
 * profiles. Back steps out of the menu one page at a time.
 */
@Composable
fun PadTab(runtime: GamepadRuntime, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit) {
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = remember(stackText) { PadMenuPage.decode(stackText) }
    var editRequest by rememberSaveable { mutableStateOf(false) }
    fun go(next: List<PadMenuPage>) { stackText = PadMenuPage.encode(next) }

    if (stack.isEmpty()) {
        PadScreen(
            runtime,
            fullscreen = fullscreen,
            onFullscreen = onFullscreen,
            onOpenMenu = { page ->
                onFullscreen(false)
                go(if (page == PadMenuPage.Root) listOf(PadMenuPage.Root) else listOf(PadMenuPage.Root, page))
            },
            editRequest = editRequest,
            onEditRequestHandled = { editRequest = false },
        )
    } else {
        BackHandler { go(stack.dropLast(1)) }
        PadMenu(
            runtime = runtime,
            stack = stack,
            onNavigate = { go(it) },
            onEditLayout = {
                go(emptyList())
                editRequest = true
            },
        )
    }
}

@Composable
private fun PadMenu(
    runtime: GamepadRuntime,
    stack: List<PadMenuPage>,
    onNavigate: (List<PadMenuPage>) -> Unit,
    onEditLayout: () -> Unit,
) {
    val profile by runtime.activeProfile.collectAsStateWithLifecycle()
    val wsIndex by runtime.workspaceIndex.collectAsStateWithLifecycle()
    val workspace = profile.workspace(wsIndex)
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
                stack.joinToString("  ›  ") { pageTitle(it, profile.workspace(wsIndex)) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { leave { onNavigate(emptyList()) } }) { Text("Close") }
        }
        HorizontalDivider()
        val content = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)
        when (page) {
            PadMenuPage.Root -> Column(content.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuRow("Game profile", profile.name) { open(PadMenuPage.Profiles) }
                MenuRow(
                    "Workspace settings",
                    "${wsIndex.coerceIn(0, profile.workspaces.lastIndex) + 1}/${profile.workspaces.size} · ${workspace.name}" +
                        " · gyro, touchpad, touch keys",
                ) { open(PadMenuPage.Workspace) }
                MenuRow(
                    "Devices",
                    when (workspace.pads.size) {
                        0 -> "No game pads in this workspace"
                        else -> workspace.pads.joinToString(", ") { it.name }
                    } + " · their profiles and mappings",
                ) { open(PadMenuPage.Devices) }
                MenuRow("Edit on-screen layout", "Move, add and map touch controls; add and arrange workspaces", onEditLayout)
            }
            PadMenuPage.Profiles -> Column(content) { ProfilesPage(runtime) }
            PadMenuPage.Workspace -> Column(content, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.workspaces.size > 1) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        profile.workspaces.forEachIndexed { i, w ->
                            FilterChip(
                                selected = i == wsIndex,
                                onClick = { runtime.switchWorkspace(i) },
                                label = { Text("${i + 1} · ${w.name}", maxLines = 1) },
                            )
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WorkspaceSettings(runtime, profile, wsIndex)
                }
            }
            PadMenuPage.Devices -> DevicesPage(runtime, content, onOpenDevice = { open(PadMenuPage.Device(it)) })
            is PadMenuPage.Device -> DevicePage(
                runtime,
                page.padId,
                content,
                onEditProfile = { presetId -> open(PadMenuPage.EditProfile(page.padId, presetId)) },
                onGone = back,
            )
            is PadMenuPage.EditProfile -> PadProfileEditor(
                runtime,
                page.padId,
                page.presetId,
                content,
                onDone = back,
                onLeaveGuard = { leaveGuard = it },
            )
        }
    }
}

private fun pageTitle(page: PadMenuPage, workspace: com.oleroma.gyrophone.core.model.Workspace): String = when (page) {
    PadMenuPage.Root -> "Menu"
    PadMenuPage.Profiles -> "Game profiles"
    PadMenuPage.Workspace -> "Workspace"
    PadMenuPage.Devices -> "Devices"
    is PadMenuPage.Device -> workspace.pads.firstOrNull { it.id == page.padId }?.name ?: "Device"
    is PadMenuPage.EditProfile -> if (page.presetId == null) "New profile" else "Profile"
}

/** A tappable menu entry: title over a one-line summary, with a chevron. */
@Composable
internal fun MenuRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

