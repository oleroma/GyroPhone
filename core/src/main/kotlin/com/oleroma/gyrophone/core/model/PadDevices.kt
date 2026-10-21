package com.oleroma.gyrophone.core.model

/**
 * A physical game pad model as the menu lists it: the pads of all workspaces that share a
 * [DeviceMatch] are one device (null match = any game pad).
 */
data class PadDevice(val match: DeviceMatch?, val name: String, val family: FaceLayout) {
    /** Stable text id of [match], used to reopen a device's page. */
    val key: String get() = PadDevices.key(match)
}

object PadDevices {
    fun key(match: DeviceMatch?): String =
        if (match == null) "any" else "${match.vendorId ?: ""}|${match.productId ?: ""}|${match.nameContains ?: ""}"

    /** The devices attached to any workspace of [profile], in first-seen order. */
    fun inProfile(profile: Profile): List<PadDevice> =
        profile.workspaces.flatMap { it.pads }.distinctBy { it.match }.map { PadDevice(it.match, it.name, it.family) }

    /** [workspace] with [device] attached as a new pad on the standard mapping for its family. */
    fun attach(workspace: Workspace, device: PadDevice): Workspace {
        val used = workspace.pads.mapTo(HashSet()) { it.id }
        var n = 1
        while ("pad$n" in used) n++
        val standard = PadPresets.builtins(device.family).first { it.id == PadPresets.STANDARD }
        val pad = PadPresets.apply(
            AttachedPad(id = "pad$n", match = device.match, name = device.name, family = device.family),
            standard,
        )
        return workspace.copy(pads = workspace.pads + pad)
    }
}
