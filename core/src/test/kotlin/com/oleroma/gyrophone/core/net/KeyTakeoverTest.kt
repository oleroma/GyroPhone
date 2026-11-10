package com.oleroma.gyrophone.core.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTakeoverTest {
    @Test fun aFlowThatNeverCameUpGivesWayAfterThreeSeconds() {
        val t = KeyTakeover()
        t.started(1000)
        t.onStatus(SessionStatus.CONNECTING, 1000)
        t.onStatus(SessionStatus.DISCOVERING, 1500)
        assertFalse(t.allows(SessionStatus.DISCOVERING, true, false, 3999))
        assertTrue(t.allows(SessionStatus.DISCOVERING, true, false, 4000))
        // A key session the PC refused is retried at the same pace.
        t.onStatus(SessionStatus.NOT_PAIRED, 2000)
        assertTrue(t.allows(SessionStatus.NOT_PAIRED, true, true, 4000))
    }

    @Test fun theFirstTryAtStartGivesWayQuicklyUntilItComesUp() {
        val t = KeyTakeover()
        t.started(1000, firstTry = true)
        t.onStatus(SessionStatus.CONNECTING, 1000)
        assertFalse(t.allows(SessionStatus.CONNECTING, true, false, 1000 + KeyTakeover.FIRST_TRY_MS - 1))
        assertTrue(t.allows(SessionStatus.CONNECTING, true, false, 1000 + KeyTakeover.FIRST_TRY_MS))
        // An old PC that refuses this phone gives way just as fast.
        t.onStatus(SessionStatus.NOT_PAIRED, 1100)
        assertTrue(t.allows(SessionStatus.NOT_PAIRED, true, false, 1000 + KeyTakeover.FIRST_TRY_MS))
        // Once it came up, a drop waits the usual time.
        t.onStatus(SessionStatus.LIVE, 2000)
        t.onStatus(SessionStatus.HOST_EXITED, 3000)
        assertFalse(t.allows(SessionStatus.HOST_EXITED, true, true, 3000 + KeyTakeover.FIRST_TRY_MS))
        assertTrue(t.allows(SessionStatus.HOST_EXITED, true, true, 3000 + KeyTakeover.TAKEOVER_MS))
        // A later flow (a tap on Connect) is not a first try.
        t.started(10_000)
        assertFalse(t.allows(SessionStatus.CONNECTING, true, false, 10_000 + KeyTakeover.FIRST_TRY_MS))
    }

    @Test fun downTimeCountsNotAge() {
        val t = KeyTakeover()
        t.started(0)
        t.onStatus(SessionStatus.LIVE, 100)
        assertFalse(t.allows(SessionStatus.LIVE, true, true, 60_000))
        t.onStatus(SessionStatus.HOST_SILENT, 50_000)
        assertFalse(t.allows(SessionStatus.HOST_SILENT, true, false, 60_000))
        t.onStatus(SessionStatus.LIVE, 55_000)
        // Up for a minute, then the companion restarts: its own key session comes back at the usual pace.
        t.onStatus(SessionStatus.HOST_EXITED, 60_000)
        assertFalse(t.allows(SessionStatus.HOST_EXITED, true, true, 61_000))
        assertTrue(t.allows(SessionStatus.HOST_EXITED, true, true, 63_000))
    }

    @Test fun anExitedPcGetsTimeToRestartBeforeAnotherPcTakesOver() {
        val t = KeyTakeover()
        t.started(0)
        t.onStatus(SessionStatus.LIVE, 100)
        t.onStatus(SessionStatus.HOST_EXITED, 60_000)
        // The same PC, back by key: reconnect at the usual pace.
        assertFalse(t.allows(SessionStatus.HOST_EXITED, true, true, 61_000))
        assertTrue(t.allows(SessionStatus.HOST_EXITED, true, true, 63_000))
        // Another PC waits longer, even when the restarted PC first refuses the HELLO.
        t.onStatus(SessionStatus.NOT_PAIRED, 62_000)
        assertFalse(t.allows(SessionStatus.NOT_PAIRED, true, false, 63_000))
        assertFalse(t.allows(SessionStatus.NOT_PAIRED, true, false, 60_000 + KeyTakeover.EXITED_GRACE_MS - 1))
        assertTrue(t.allows(SessionStatus.NOT_PAIRED, true, false, 60_000 + KeyTakeover.EXITED_GRACE_MS))
        // Back up, then down for another reason (a stopped flow): the usual 3 s again.
        t.onStatus(SessionStatus.LIVE, 80_000)
        t.onStatus(SessionStatus.FORGOTTEN, 90_000)
        assertFalse(t.allows(SessionStatus.FORGOTTEN, false, false, 92_999))
        assertTrue(t.allows(SessionStatus.FORGOTTEN, false, false, 93_000))
        // A new flow starts over.
        t.started(100_000)
        assertFalse(t.allows(SessionStatus.IDLE, true, false, 102_000))
        assertTrue(t.allows(SessionStatus.IDLE, true, false, 103_000))
    }

    @Test fun replacedAndVersionMismatchAreRespected() {
        val t = KeyTakeover()
        t.started(0)
        assertFalse(t.allows(SessionStatus.REPLACED, false, false, 100_000))
        assertFalse(t.allows(SessionStatus.VERSION_MISMATCH, true, true, 100_000))
        // A flow that stopped (FORGOTTEN) gives way although its last status was not up.
        assertTrue(t.allows(SessionStatus.FORGOTTEN, false, true, 3000))
    }
}
