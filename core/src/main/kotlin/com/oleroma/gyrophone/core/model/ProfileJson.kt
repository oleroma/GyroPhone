package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Reads and writes profile files. A file saved before workspaces existed (no "workspaces" key)
 * becomes a profile with one workspace holding its layout, touchpad, gyro and opacity; its
 * controller mappings become one attached pad (its single auto-select controller, else any game
 * pad), and its fullscreen corners become the ← / → corners. Nothing in an old file is lost.
 */
object ProfileJson {
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun encode(profile: Profile): String = json.encodeToString(Profile.serializer(), profile)

    fun decode(text: String): Profile {
        val obj = json.parseToJsonElement(text).jsonObject
        val profile = if ("workspaces" in obj) json.decodeFromJsonElement(Profile.serializer(), obj)
        else migrate(json.decodeFromJsonElement(LegacyProfile.serializer(), obj))
        return normalize(profile)
    }

    /**
     * At least one workspace, all corners present, no corner inside a workspace and unique
     * workspace ids.
     */
    fun normalize(p: Profile): Profile {
        var workspaces = p.workspaces.map { w ->
            val clean = if (w.touchControls.none { it.kind == TouchKind.FULLSCREEN }) w
            else w.copy(touchControls = w.touchControls.filterNot { it.kind == TouchKind.FULLSCREEN })
            if (clean.gyro.mode == GyroMode.HOLD_OR_TOGGLE) clean.copy(gyro = clean.gyro.copy(mode = GyroMode.OFF)) else clean
        }
        if (workspaces.isEmpty()) workspaces = listOf(Workspace(id = "ws1", name = "Main"))
        val seen = HashSet<String>()
        workspaces = workspaces.map { w ->
            if (seen.add(w.id)) w else w.copy(id = newWorkspaceId(workspaces.map { it.id } + seen)).also { seen.add(it.id) }
        }
        val corners = NavCorners.normalize(p.corners)
        return if (workspaces == p.workspaces && corners == p.corners) p else p.copy(workspaces = workspaces, corners = corners)
    }

    /** "ws<n>" not in [taken]. */
    fun newWorkspaceId(taken: Collection<String>): String {
        var n = 1
        while ("ws$n" in taken) n++
        return "ws$n"
    }

    internal fun migrate(old: LegacyProfile): Profile {
        val padMappings = old.mappings.filterNot { it.source.isTouch }
        val match = old.controllers.singleOrNull()
        val known = match?.let { m -> KnownLayouts.controllers.firstOrNull { it.match == m } }
        val pads = if (padMappings.isEmpty()) emptyList() else listOf(
            AttachedPad(
                id = "pad1",
                match = match,
                name = known?.name ?: match?.nameContains ?: AttachedPad.ANY_NAME,
                family = known?.layout ?: FaceLayout.XBOX,
                mappings = padMappings,
            ),
        )
        val workspace = Workspace(
            id = "ws1",
            name = "Main",
            touchControls = old.touchControls.filterNot { it.kind == TouchKind.FULLSCREEN },
            mappings = old.mappings.filter { it.source.isTouch },
            touchpad = old.touchpad,
            gyro = old.gyro,
            controlOpacity = old.controlOpacity,
            pads = pads,
        )
        return Profile(
            id = old.id,
            name = old.name,
            workspaces = listOf(workspace),
            games = old.games,
            controllers = old.controllers,
            corners = NavCorners.fromLegacy(old.touchControls),
            cornerOpacity = old.fullscreenOpacity,
            cornerSameSize = old.fullscreenSameSize,
        )
    }

    /** The profile file format before workspaces (app builds up to 0.1.45). */
    @Serializable
    internal data class LegacyProfile(
        val id: String,
        val name: String,
        val mappings: List<Mapping> = emptyList(),
        val touchControls: List<TouchControl> = emptyList(),
        val gyro: GyroSettings = GyroSettings(),
        val controllers: List<DeviceMatch> = emptyList(),
        val games: List<String> = emptyList(),
        val touchpad: TouchpadSettings = TouchpadSettings(),
        val controlOpacity: Float = 1f,
        val fullscreenOpacity: Float = 0.5f,
        val fullscreenSameSize: Boolean = true,
    )

}
