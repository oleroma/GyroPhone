package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AttachedPad
import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.DeviceMatch
import com.oleroma.gyrophone.core.model.FaceLayout
import com.oleroma.gyrophone.core.model.GamepadButton
import com.oleroma.gyrophone.core.model.GyroMode
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KnownLayouts
import com.oleroma.gyrophone.core.model.NavCorners
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import com.oleroma.gyrophone.core.model.isTouch
import com.oleroma.gyrophone.core.model.placeSize
import com.oleroma.gyrophone.core.model.placeX
import com.oleroma.gyrophone.core.model.placeY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceModelTest {
    /** A Virtual gamepad copy as saved by builds before workspaces, with fullscreen corners. */
    private val legacyPad = """
        {"id":"user.1","name":"My pad","games":["game.exe"],
         "mappings":[
           {"source":{"type":"touch","id":"a"},"target":{"type":"pad","button":"A"}},
           {"source":{"type":"touch_stick","id":"ls","axis":"X"},"target":{"type":"pad_axis","axis":"LX"}},
           {"source":{"type":"key","keyCode":96},"target":{"type":"pad","button":"B"}},
           {"source":{"type":"touch","id":"g"},"target":{"type":"gyro_hold"}}],
         "touchControls":[
           {"id":"a","kind":"BUTTON","label":"A","x":0.8,"y":0.7},
           {"id":"ls","kind":"STICK","label":"LS","x":0.2,"y":0.6,"size":0.3},
           {"id":"fs2","kind":"FULLSCREEN","label":"","x":0.9,"y":0.1,"size":0.1},
           {"id":"fs1","kind":"FULLSCREEN","label":"","x":0.1,"y":0.1,"size":0.1}],
         "gyro":{"mode":"ALWAYS","sensitivity":20.0},
         "touchpad":{"sensitivity":2.5},
         "controlOpacity":0.4,"fullscreenOpacity":0.3,"fullscreenSameSize":false}
    """.trimIndent()

    @Test fun legacyProfileBecomesOneWorkspaceWithNothingLost() {
        val p = ProfileJson.decode(legacyPad)
        assertEquals("user.1", p.id)
        assertEquals("My pad", p.name)
        assertEquals(listOf("game.exe"), p.games)
        val w = p.workspaces.single()
        assertEquals(listOf("a", "ls"), w.touchControls.map { it.id })
        assertTrue(w.mappings.all { it.source.isTouch })
        assertEquals(3, w.mappings.size)
        assertEquals(GyroMode.ALWAYS, w.gyro.mode)
        assertEquals(20f, w.gyro.sensitivity)
        assertEquals(2.5f, w.touchpad.sensitivity)
        assertEquals(0.4f, w.controlOpacity)
        val pad = w.pads.single()
        assertNull(pad.match)
        assertEquals(AttachedPad.ANY_NAME, pad.name)
        assertEquals(listOf(InputSource.Key(96)), pad.mappings.map { it.source })
        // Corners: the left one (fs1) becomes ←, keeping its placement.
        assertEquals(listOf(NavCorners.PREV, NavCorners.NEXT, NavCorners.GYRO), p.corners.map { it.id })
        assertEquals(0.1f, p.corners[0].x)
        assertEquals(0.9f, p.corners[1].x)
        assertEquals(0.3f, p.cornerOpacity)
        assertEquals(false, p.cornerSameSize)
        // Saved again and read back: identical, and now in the new shape.
        val again = ProfileJson.decode(ProfileJson.encode(p))
        assertEquals(p, again)
        assertTrue(ProfileJson.encode(p).contains("\"workspaces\""))
    }

    @Test fun legacyEngineBehaviourIsKept() {
        val e = MappingEngine(ProfileJson.decode(legacyPad))
        e.setKey(AndroidKey.BUTTON_A, true) // key 96, mapped to B
        e.setTouch("a", true)
        assertEquals((1 shl GamepadButton.A.bit) or (1 shl GamepadButton.B.bit), e.snapshot().gamepad.buttons)
        assertTrue(e.gyroActive)
    }

    @Test fun legacyWithoutCornersGetsDefaultsAndAutoControllerBecomesThatPad() {
        val p = ProfileJson.decode(
            """{"id":"user.2","name":"DS","controllers":[{"nameContains":"DualSense"}],
               "mappings":[{"source":{"type":"key","keyCode":96},"target":{"type":"key","usage":44}}]}""",
        )
        assertEquals(NavCorners.defaults(), p.corners)
        val pad = p.workspaces.single().pads.single()
        assertEquals(DeviceMatch(nameContains = "DualSense"), pad.match)
        assertEquals("DualSense", pad.name)
        assertEquals(FaceLayout.PLAYSTATION, pad.family)
        assertEquals(1f, p.workspaces.single().controlOpacity)
        assertEquals(0.5f, p.cornerOpacity)
    }

    @Test fun legacyTouchOnlyProfileHasNoPads() {
        val p = ProfileJson.decode("""{"id":"p","name":"P","mappings":[]}""")
        assertEquals(emptyList<AttachedPad>(), p.workspaces.single().pads)
    }

    @Test fun normalizeFixesBrokenFiles() {
        val broken = """{"id":"p","name":"P","workspaces":[],"corners":[]}"""
        val p = ProfileJson.decode(broken)
        assertEquals(1, p.workspaces.size)
        assertEquals(NavCorners.defaults(), p.corners)
        val dup = Profile("p", "P", listOf(Workspace("ws1", "A"), Workspace("ws1", "B")))
        val fixed = ProfileJson.normalize(dup)
        assertEquals(2, fixed.workspaces.map { it.id }.toSet().size)
    }

    @Test fun previousAndNextStopAtTheEndsUnlessWrapping() {
        val p = Profile("p", "P", listOf(Workspace("a", "A"), Workspace("b", "B"), Workspace("c", "C")))
        assertNull(p.previousWorkspace(0))
        assertEquals(1, p.nextWorkspace(0))
        assertNull(p.nextWorkspace(2))
        val wrap = p.copy(wrapWorkspaces = true)
        assertEquals(2, wrap.previousWorkspace(0))
        assertEquals(0, wrap.nextWorkspace(2))
        val one = Profile("p", "P", listOf(Workspace("a", "A")), wrapWorkspaces = true)
        assertNull(one.nextWorkspace(0))
        assertNull(one.previousWorkspace(0))
    }

    @Test fun cornersSendNothingAndStayOnScreen() {
        for (c in NavCorners.defaults()) {
            assertEquals(TouchKind.FULLSCREEN, c.kind)
            assertTrue(Bindings.touchInputs(c).isEmpty())
            for (portrait in listOf(false, true)) {
                val w = if (portrait) 1000f else 2100f
                val h = if (portrait) 2100f else 1000f
                val r = c.placeSize(portrait) * minOf(w, h) / 2f
                val cx = c.placeX(portrait) * w
                val cy = c.placeY(portrait) * h
                assertTrue(cx - r >= 0f && cx + r <= w && cy - r >= 0f && cy + r <= h)
            }
        }
    }

    @Test fun nintendoFamilySwapsFaceButtons() {
        val m = KnownLayouts.familyMappings(FaceLayout.NINTENDO)
        fun target(key: Int) = m.first { it.source == InputSource.Key(key) }.target
        assertEquals(OutputTarget.Pad(GamepadButton.B), target(AndroidKey.BUTTON_A))
        assertEquals(OutputTarget.Pad(GamepadButton.X), target(AndroidKey.BUTTON_Y))
        assertEquals(KnownLayouts.standardControllerMappings(), KnownLayouts.familyMappings(FaceLayout.XBOX))
    }

    @Test fun defaultProfileHasThreeWorkspaces() {
        val p = BuiltinProfiles.default
        assertEquals(listOf("Controller", "WASD", "Keyboard"), p.workspaces.map { it.name })
        assertEquals(p, ProfileJson.decode(ProfileJson.encode(p)))
    }
}
