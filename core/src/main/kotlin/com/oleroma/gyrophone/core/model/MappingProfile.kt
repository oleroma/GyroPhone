package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Every remap in one place, in three categories: the on-screen [touch] buttons (keyed by control
 * id), physical [keyboard] keys, and [controller] buttons and sticks (one set for every attached
 * game pad). Builds before workspaces owned their mappings ([WorkspaceMappings]) kept them here,
 * shared by the workspaces naming the profile ([Workspace.mappingProfileId]); only read now to move
 * them into the workspaces ([WorkspaceMappings.fromProfiles]).
 */
@Serializable
data class MappingProfile(
    val id: String,
    val name: String,
    /** [InputSource.Touch] / [InputSource.TouchStick] sources. */
    val touch: List<Mapping> = emptyList(),
    /** [InputSource.Key] sources from a physical keyboard. */
    val keyboard: List<Mapping> = emptyList(),
    /** [InputSource.Key] / [InputSource.Axis] sources from a game pad. */
    val controller: List<Mapping> = emptyList(),
    /**
     * The workspace touchpad's sources ([InputSource.isTouchpad]); null (saved before touchpad
     * mappings) = what each workspace's touchpad switches stand for ([Bindings.defaultTouchpadMappings]).
     */
    val touchpad: List<Mapping>? = null,
    /** The named mapping sets above Base ([Mapping.set], [OutputTarget.Layer]), by id. */
    val sets: List<MappingSet> = emptyList(),
)

object MappingProfiles {
    const val DEFAULT_NAME = "My mappings"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(MappingProfile.serializer())

    fun encode(profiles: List<MappingProfile>): String = json.encodeToString(listSerializer, profiles)

    /** Reads a saved list; unreadable text gives null (the caller keeps the file). */
    fun decode(text: String): List<MappingProfile>? =
        runCatching { json.decodeFromString(listSerializer, text) }.getOrNull()

    fun newId(taken: Collection<String>): String {
        var n = 1
        while ("map$n" in taken) n++
        return "map$n"
    }

    /** The touch control id a mapping's source belongs to, or null for a key / axis source. */
    fun touchId(source: InputSource): String? = when (source) {
        is InputSource.Touch -> source.id
        is InputSource.TouchStick -> source.id
        is InputSource.TouchStickDiagonal -> source.id
        is InputSource.TouchSwipe -> source.id
        is InputSource.TouchRelease -> source.id
        is InputSource.TouchDoubleTap -> source.id
        is InputSource.TouchTap -> source.id
        is InputSource.TouchLongPress -> source.id
        is InputSource.TouchArea -> source.id
        is InputSource.Surface -> source.id.ifEmpty { null }
        is InputSource.Gyro -> source.id.ifEmpty { null }
        else -> null
    }

    /** [mapping]'s touchpad mappings, or the ones [w]'s touchpad switches stand for. */
    fun touchpadOf(mapping: MappingProfile, w: Workspace): List<Mapping> =
        mapping.touchpad ?: Bindings.defaultTouchpadMappings(w.touchpad)

    /**
     * A new profile: the builtin touch mappings for every control of [set] that has one, and the
     * standard controller mapping.
     */
    fun fresh(id: String, name: String, set: Profile): MappingProfile {
        val ids = set.workspaces.flatMapTo(HashSet()) { w -> w.touchControls.map { it.id } }
        val touch = BuiltinProfiles.all.flatMap { p -> p.workspaces.flatMap { it.mappings } }
            .filter { touchId(it.source) in ids }
            .distinct()
        return MappingProfile(id, name, touch = touch, controller = KnownLayouts.familyMappings(FaceLayout.XBOX))
    }

    /** The profile [w] uses: the one it names, else the first of [profiles]. */
    fun of(w: Workspace, profiles: List<MappingProfile>): MappingProfile? =
        profiles.firstOrNull { it.id == w.mappingProfileId } ?: profiles.firstOrNull()

    /**
     * [set] as the engine and editors see it: each workspace's touch mappings are its profile's for
     * its controls, every attached pad has the profile's controller set (or its [AttachedPad.own]),
     * and [Workspace.keyboard] holds its keyboard set. A workspace with no profile (empty [profiles]) is left as it is.
     */
    fun resolve(set: Profile, profiles: List<MappingProfile>): Profile = set.copy(
        workspaces = set.workspaces.map { w -> of(w, profiles)?.let { resolve(w, it) } ?: w },
    )

    fun resolve(w: Workspace, mapping: MappingProfile): Workspace {
        val ids = w.touchControls.mapTo(HashSet()) { it.id }
        return w.copy(
            mappingProfileId = mapping.id,
            mappings = Bindings.upgradeSwipes(mapping.touch).filter { touchId(it.source) in ids } + touchpadOf(
                mapping,
                w
            ),
            touchpad = w.touchpad.copy(mapped = true),
            keyboard = mapping.keyboard,
            pads = w.pads.map { it.copy(mappings = it.own ?: mapping.controller) },
            sets = mapping.sets,
        )
    }

    /**
     * [set] without mappings (they live in the mapping profiles), as it is stored. A pad with its
     * [AttachedPad.own] mappings keeps its current ones there.
     */
    fun strip(set: Profile): Profile = set.copy(
        workspaces = set.workspaces.map { w ->
            w.copy(
                mappings = emptyList(),
                touchpad = w.touchpad.copy(mapped = false),
                keyboard = emptyList(),
                sets = emptyList(),
                pads = w.pads.map {
                    it.copy(
                        mappings = emptyList(),
                        presetId = null,
                        own = if (it.own != null) it.mappings else null
                    )
                },
            )
        },
    )

    /**
     * [profiles] with the mapping edits made between [before] and [after] (resolved sets, see
     * [resolve]) taken into each workspace's profile. Touch: mappings of the workspace's controls
     * removed or added since [before] (a workspace new in [after] is compared with its profile); a
     * deleted control leaves its mappings, since another workspace may have it. Controller: the
     * mappings of a pad that changed since [before] (not one with its own mappings); a newly
     * attached pad only gives its mappings when the set is empty.
     */
    fun absorb(profiles: List<MappingProfile>, before: Profile, after: Profile): List<MappingProfile> {
        var result = profiles
        for (w in after.workspaces) {
            val mapping = of(w, result) ?: continue
            val old = before.workspaces.firstOrNull { it.id == w.id && it.mappingProfileId == w.mappingProfileId }
            val ids = w.touchControls.mapTo(HashSet()) { it.id }
            val base = Bindings.upgradeSwipes(mapping.touch)
            val was = (old?.mappings ?: base).filter { touchId(it.source) in ids }.toSet()
            val now = w.mappings.filter { touchId(it.source) in ids }.toSet()
            val touch = (base.filterNot { it in was - now } + (now - was)).distinct()
            // The touchpad set is taken whole once it changed (it may only stand for the switches so far).
            val padNow = w.mappings.filter { it.source.isTouchpad }
            val padWas = old?.mappings?.filter { it.source.isTouchpad } ?: touchpadOf(mapping, w)
            val touchpad = if (w.touchpad.mapped && padNow.toSet() != padWas.toSet()) padNow else mapping.touchpad
            val changed = w.pads.firstOrNull { p ->
                if (p.own != null) return@firstOrNull false
                val oldPad = old?.pads?.firstOrNull { it.id == p.id }
                if (oldPad != null) p.mappings.toSet() != oldPad.mappings.toSet()
                else mapping.controller.isEmpty() && p.mappings.isNotEmpty()
            }
            val controller = changed?.mappings ?: mapping.controller
            // Sets added, renamed or removed in the workspace (the pad's mapping screen).
            val sets = if (old != null && w.sets != old.sets) w.sets else mapping.sets
            if (touch == mapping.touch && controller == mapping.controller && touchpad == mapping.touchpad &&
                sets == mapping.sets
            ) continue
            val updated = mapping.copy(touch = touch, controller = controller, touchpad = touchpad, sets = sets)
            result = result.map { if (it.id == mapping.id) updated else it }
        }
        return result
    }

    /**
     * Gives each workspace of [after] that names no profile one: a workspace that comes with its own
     * mappings (a builtin preset added) gets a new profile made of them, named after it; any other
     * (a blank one) uses [fallbackId]. Returns the set and the profiles with any new ones added.
     */
    fun adopt(
        profiles: List<MappingProfile>,
        after: Profile,
        fallbackId: String?
    ): Pair<Profile, List<MappingProfile>> {
        if (after.workspaces.none { it.mappingProfileId == null }) return after to profiles
        val all = ArrayList(profiles)
        val workspaces = after.workspaces.map { w ->
            if (w.mappingProfileId != null) return@map w
            val ids = w.touchControls.mapTo(HashSet()) { it.id }
            val touch = w.mappings.filter { touchId(it.source) in ids }
            val controller = w.pads.firstOrNull()?.mappings.orEmpty()
            if (touch.isEmpty() && controller.isEmpty() && fallbackId != null) return@map w.copy(mappingProfileId = fallbackId)
            val created = MappingProfile(
                newId(all.map { it.id }), w.name, touch = touch, keyboard = w.keyboard,
                controller = controller.ifEmpty { KnownLayouts.familyMappings(FaceLayout.XBOX) },
                touchpad = if (w.touchpad.mapped) w.mappings.filter { it.source.isTouchpad } else null,
            )
            all += created
            w.copy(mappingProfileId = created.id)
        }
        return after.copy(workspaces = workspaces) to all
    }

    /** What [migrate] makes from a set that still holds its own mappings. */
    data class Migration(val set: Profile, val profiles: List<MappingProfile>)

    /**
     * Moves the mappings out of a [set] saved before mapping profiles, so every input does what it
     * did. Each workspace gets a profile with its touch mappings, its first pad's controller set (the
     * standard one without pads) and, as the keyboard set, the key mappings of its first "any game
     * pad" pad (which keyboards used to act through); workspaces whose mappings are equal share one,
     * named after the first of them. A later pad of a workspace that mapped differently from its
     * first keeps them as [AttachedPad.own]. The returned set is [strip]ped and each workspace names
     * its profile.
     */
    fun migrate(set: Profile): Migration {
        val profiles = ArrayList<MappingProfile>()
        val workspaces = set.workspaces.map { w ->
            val ids = w.touchControls.mapTo(HashSet()) { it.id }
            val touch = w.mappings.filter { touchId(it.source) in ids }
            val first = w.pads.firstOrNull()
            val controller = first?.mappings ?: KnownLayouts.familyMappings(FaceLayout.XBOX)
            val keyboard =
                w.pads.firstOrNull { it.match == null }?.mappings.orEmpty().filter { it.source is InputSource.Key }
            val same = profiles.firstOrNull { p ->
                p.controller.toSet() == controller.toSet() && p.keyboard.toSet() == keyboard.toSet() &&
                        p.touch.filter { touchId(it.source) in ids }.toSet() == touch.toSet() &&
                        // Sharing must not change what the earlier workspaces' controls do.
                        touch.none { m -> p.touch.any { touchId(it.source) == touchId(m.source) && it !in touch } }
            }
            val mapping = same ?: MappingProfile(
                newId(profiles.map { it.id }),
                w.name,
                touch,
                keyboard,
                controller
            ).also { profiles += it }
            val pads =
                w.pads.map { p -> if (p !== first && p.mappings.toSet() != controller.toSet()) p.copy(own = p.mappings) else p }
            w.copy(mappingProfileId = mapping.id, pads = pads)
        }
        return Migration(strip(set.copy(workspaces = workspaces)), profiles)
    }

    /** Ids used by any control of [set] or any touch mapping of [profiles]: a new control must avoid them. */
    fun takenTouchIds(set: Profile, profiles: List<MappingProfile>): Set<String> =
        set.workspaces.flatMapTo(HashSet()) { w -> w.touchControls.map { it.id } } +
                profiles.flatMap { p -> p.touch.mapNotNull { touchId(it.source) } }
}
