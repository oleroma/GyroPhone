package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A mapping's hash is worked out once and still follows equality, also after saving and loading. */
class MappingHashTest {
    private val a = Mapping(InputSource.Shake(), OutputTarget.Key(0x2C), options = MapOptions(how = How.TOGGLE))

    @Test fun equalMappingsHashTheSame() {
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = a.copy(set = 1)
        assertNotEquals(a, c)
        assertNotEquals(a.hashCode(), c.hashCode())
        assertEquals(setOf(a), hashSetOf(b))
    }

    @Test fun aLoadedMappingHashesAsTheSaved() {
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(a)
        assertFalse(text, "hash" in text)
        val back = json.decodeFromString<Mapping>(text)
        assertEquals(a, back)
        assertEquals(a.hashCode(), back.hashCode())
    }
}
