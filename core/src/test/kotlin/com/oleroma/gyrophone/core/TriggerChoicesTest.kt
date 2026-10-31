package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.Duration
import com.oleroma.gyrophone.core.model.FiresOn
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.ProfileJson
import com.oleroma.gyrophone.core.model.Taps
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Trigger
import com.oleroma.gyrophone.core.model.TriggerJson
import com.oleroma.gyrophone.core.model.TriggerPreset
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The three trigger choices (Fires on × Taps × Duration), the second tap release window and old saved triggers. */
class TriggerChoicesTest {
    private val MS = 1_000_000L
    private val A = 0x04
    private val B = 0x05
    private var t = 0L

    private fun on(key: Int, tr: Trigger) = Mapping(InputSource.Touch("b"), OutputTarget.Key(key), trigger = tr)

    private fun engine(vararg ms: Mapping, ws: (Workspace) -> Workspace = { it }): MappingEngine {
        val c = TouchControl("b", TouchKind.BUTTON, "", 0.5f, 0.5f)
        val p = single(ws(Workspace(id = "w", name = "w", touchControls = listOf(c), mappings = ms.toList())))
        return MappingEngine(p) { t }.apply { touchpadPxPerDp = 1f; touchpadMinPulseNanos = 10 * MS }
    }

    private fun MappingEngine.keys(): Set<Int> = snapshot().keyboard.keys.toSet()

    private fun MappingEngine.press(ms: Long) {
        buttonDown(1, "b", 10f, 10f); keys(); t += ms * MS; buttonUp(1)
    }

    @Test
    fun liftAfterALongPressFiresOnlyAfterALongPress() {
        val e = engine(on(A, Trigger(fires = FiresOn.LIFT, duration = Duration.LONG)))
        e.press(50)
        assertEquals(emptySet<Int>(), e.keys())
        t += 1000 * MS; e.keys()
        e.press(500)
        assertEquals(setOf(A), e.keys())
    }

    @Test
    fun doubleQuickWaitsForTheSecondLiftWithinTheReleaseWindow() {
        val e = engine(on(A, Trigger.DOUBLE_TAP), ws = { it.copy(doubleReleaseMs = 200) })
        e.press(50); t += 50 * MS; e.keys()
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(emptySet<Int>(), e.keys()) // waits for the lift
        t += 100 * MS; e.buttonUp(1)
        assertEquals(setOf(A), e.keys())
        t += 30 * MS; e.keys()
        // A second press past the release window is not a Double Quick.
        t += 1000 * MS; e.keys()
        e.press(50); t += 50 * MS; e.keys()
        e.press(300)
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun aReleaseWindowOfZeroFiresDoubleQuickOnTheSecondPress() {
        val e = engine(on(A, Trigger.DOUBLE_TAP), ws = { it.copy(doubleReleaseMs = 0) })
        e.press(50); t += 50 * MS; e.keys()
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A), e.keys())
    }

    @Test
    fun doubleLongHoldsOncePastTheReleaseWindow() {
        val e = engine(on(A, Trigger(taps = Taps.DOUBLE, duration = Duration.LONG)), ws = { it.copy(doubleReleaseMs = 200) })
        e.press(50); t += 50 * MS; e.keys()
        e.buttonDown(1, "b", 10f, 10f)
        t += 150 * MS
        assertEquals(emptySet<Int>(), e.keys())
        t += 100 * MS
        assertEquals(setOf(A), e.keys())
        e.buttonUp(1); t += 20 * MS
        assertEquals(emptySet<Int>(), e.keys())
    }

    @Test
    fun holdFiresOnBothPressesOfADouble() {
        val e = engine(on(A, Trigger.DEFAULT), on(B, Trigger.DOUBLE_TAP))
        e.press(50); t += 50 * MS; e.keys()
        e.buttonDown(1, "b", 10f, 10f)
        assertEquals(setOf(A), e.keys())
    }

    @Test
    fun presetsNameTheirChoices() {
        assertEquals(TriggerPreset.TAP, Trigger.TAP.preset)
        assertEquals(TriggerPreset.DOUBLE_HOLD, Trigger.DOUBLE_PRESS.preset)
        assertNull(Trigger(fires = FiresOn.LIFT, duration = Duration.LONG).preset)
        // Don't wait only stays on a Tap.
        assertEquals(false, Trigger.TAP_NOW.with(TriggerPreset.RELEASE).dontWait)
    }

    @Test
    fun triggersSavedByTheEarlierBuildConvert() {
        fun read(s: String) = ProfileJson.json.decodeFromString(TriggerJson, s)
        assertEquals(Trigger.TAP_NOW, read("""{"when":"TAP"}"""))
        assertEquals(Trigger.TAP, read("""{"when":"TAP","taps":"SINGLE"}"""))
        assertEquals(Trigger.DOUBLE_TAP, read("""{"when":"TAP","taps":"DOUBLE"}"""))
        assertEquals(Trigger.LONG_PRESS, read("""{"when":"LONG_PRESS"}"""))
        assertEquals(Trigger.RELEASE.copy(output = com.oleroma.gyrophone.core.model.Output.STICKY), read("""{"when":"RELEASE","output":"STICKY"}"""))
        assertEquals(Trigger.DOUBLE_PRESS, read("""{"taps":"DOUBLE"}"""))
        assertEquals(Trigger.DEFAULT, read("""{"taps":"ANY"}"""))
        // New ones read as they are.
        assertEquals(Trigger.TAP_NOW, read(ProfileJson.json.encodeToString(TriggerJson, Trigger.TAP_NOW)))
    }
}
