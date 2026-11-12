package com.oleroma.gyrophone.core.net

import org.junit.Test
import org.junit.Assert.assertEquals

class PressStampTest {
    @Test fun offNotesNothing() {
        val p = PressStamp()
        p.note(100)
        p.enabled = true
        assertEquals(0L, p.take(200))
    }

    @Test fun takesTheNewestEdgeOnce() {
        val p = PressStamp().apply { enabled = true }
        p.note(1_000)
        p.note(2_000)
        assertEquals(2_000L, p.take(3_000))
        assertEquals(0L, p.take(3_000))
    }

    @Test fun anEdgeThatChangedNothingIsForgotten() {
        val p = PressStamp().apply { enabled = true }
        p.note(1_000)
        assertEquals(0L, p.take(1_000 + PressStamp.MAX_AGE_NS + 1))
    }

    @Test fun turningOffClearsIt() {
        val p = PressStamp().apply { enabled = true }
        p.note(1_000)
        p.enabled = false
        p.enabled = true
        assertEquals(0L, p.take(2_000))
    }
}
