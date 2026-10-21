package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.PadDevice
import com.oleroma.gyrophone.core.model.PadDevices
import com.oleroma.gyrophone.core.model.PadPreset
import com.oleroma.gyrophone.core.model.PadPresets
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

class PadDevicesTest {
    private val ds = DeviceMatch(vendorId = 0x054C, productId = 0x0CE6)
    private val x5 = DeviceMatch(nameContains = "X5")

    @Test fun devicesAreDistinctMatchesAcrossWorkspaces() {
        val profile = Profile(
            id = "user.p", name = "P",
            workspaces = listOf(
                Workspace("ws1", "A", pads = listOf(AttachedPad("pad1", ds, "DualSense", FaceLayout.PLAYSTATION))),
                Workspace("ws2", "B", pads = listOf(AttachedPad("pad1", ds, "DualSense"), AttachedPad("pad2", x5, "X5"))),
            ),
        )
        val devices = PadDevices.inProfile(profile)
        assertEquals(listOf(ds, x5), devices.map { it.match })
        assertEquals(FaceLayout.PLAYSTATION, devices[0].family)
        assertEquals(listOf("1356|3302|", "||X5", "any"), listOf(devices[0].key, devices[1].key, PadDevices.key(null)))
    }

    @Test fun attachUsesTheStandardProfileWithAFreeId() {
        val w = Workspace("ws1", "A", pads = listOf(AttachedPad("pad1", ds, "DualSense")))
        val pad = PadDevices.attach(w, PadDevice(x5, "X5", FaceLayout.NINTENDO)).pads.last()
        assertEquals("pad2", pad.id)
        assertEquals(PadPresets.STANDARD, pad.presetId)
        assertEquals(KnownLayouts.familyMappings(FaceLayout.NINTENDO), pad.mappings)
    }

    @Test fun deviceProfilesAreItsOwnPlusUnattachedOnes() {
        val mine = PadPreset("user.pad1", "Mine", device = ds)
        val other = PadPreset("user.pad2", "Other", device = x5)
        val shared = PadPreset("user.pad3", "Shared")
        val ids = PadPresets.forDevice(ds, FaceLayout.XBOX, listOf(mine, other, shared)).map { it.id }
        assertEquals(listOf(PadPresets.STANDARD, PadPresets.KBM, "user.pad1", "user.pad3"), ids)
        assertEquals(listOf(mine), PadPresets.decode(PadPresets.encode(listOf(mine))))
    }
}
