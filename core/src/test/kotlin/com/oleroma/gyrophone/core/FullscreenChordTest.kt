package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.Bindings
import com.oleroma.gyrophone.core.model.BuiltinProfiles
import com.oleroma.gyrophone.core.model.FullscreenChord
import com.oleroma.gyrophone.core.model.Profile
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.contains
import com.oleroma.gyrophone.core.model.placeSize
import com.oleroma.gyrophone.core.model.placeX
import com.oleroma.gyrophone.core.model.placeY
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenChordTest {
    private val pad = BuiltinProfiles.all.first { it.touchControls.isNotEmpty() }

    @Test fun addPutsTwoCornersThatSendNothing() {
        assertFalse(FullscreenChord.has(pad))
        val p = FullscreenChord.add(pad)
        assertTrue(FullscreenChord.has(p))
        val corners = p.touchControls.filter { it.kind == TouchKind.FULLSCREEN }
        assertEquals(2, corners.size)
        assertEquals(2, corners.map { it.id }.toSet().size)
        assertTrue(corners.all { Bindings.touchInputs(it).isEmpty() })
        assertEquals(pad.mappings, p.mappings)
        assertEquals(pad.touchControls, p.touchControls.filter { it.kind != TouchKind.FULLSCREEN })
    }

    @Test fun addTwiceKeepsOnePairAndRemoveDropsIt() {
        val p = FullscreenChord.add(FullscreenChord.add(pad))
        assertEquals(2, FullscreenChord.ids(p).size)
        assertEquals(pad, FullscreenChord.remove(p))
    }

    @Test fun cornersStayOnScreenAndClearOfTheBuiltinControls() {
        val p = FullscreenChord.add(pad)
        for (portrait in listOf(false, true)) {
            val w = if (portrait) 1000f else 2100f
            val h = if (portrait) 2100f else 1000f
            for (c in p.touchControls.filter { it.kind == TouchKind.FULLSCREEN }) {
                val r = c.placeSize(portrait) * minOf(w, h) / 2f
                val cx = c.placeX(portrait) * w
                val cy = c.placeY(portrait) * h
                assertTrue(cx - r >= 0f && cx + r <= w && cy - r >= 0f && cy + r <= h)
                for (o in pad.touchControls) {
                    assertFalse("${c.id} covers ${o.id} (portrait=$portrait)", o.contains(cx, cy, w, h, inPortrait = portrait))
                }
            }
        }
    }

    @Test fun survivesJson() {
        val p = FullscreenChord.add(pad)
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(p, json.decodeFromString(Profile.serializer(), json.encodeToString(Profile.serializer(), p)))
    }
}
