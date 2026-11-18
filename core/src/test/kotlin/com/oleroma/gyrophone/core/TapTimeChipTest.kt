package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.AxisRange
import com.oleroma.gyrophone.core.model.FingerAxis
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SliderOverrides
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tap time as a value chip ([OutputTarget.TapTime]): a slider changes the taps to come. */
class TapTimeChipTest {
    private val MS = 1_000_000L
    private var t = 0L
    private val D = 0x07

    private val long = Mapping(InputSource.TouchLongPress("b"), OutputTarget.Key(D))

    private fun chip(options: MapOptions? = null) =
        Mapping(InputSource.Finger("tap", FingerAxis.Y), OutputTarget.TapTime, range = AxisRange(100f, 1000f), options = options)

    private fun workspace(vararg ms: Mapping, own: Int? = null) = Workspace(
        "w", "w",
        touchControls = listOf(
            TouchControl("tap", TouchKind.BUTTON, "Tap", 0.2f, 0.5f, shape = TouchShape.SQUARE),
            TouchControl("b", TouchKind.BUTTON, "B", 0.8f, 0.5f, longPressMs = own),
        ),
        mappings = ms.toList(),
    )

    private fun MappingEngine.keys(): Set<Int> = snapshot().keyboard.keys.toSet()

    /** Whether a press of "b" held [ms] is a long press by then. */
    private fun MappingEngine.longAfter(ms: Long): Boolean {
        buttonDown(1, "b", 10f, 10f); keys()
        t += ms * MS
        val long = D in keys()
        buttonUp(1); repeat(5) { t += 300 * MS; keys() }
        return long
    }

    @Test
    fun aHeldChipSetsTheTapTimeWhileTheFingerIsDown() {
        val e = MappingEngine(single(workspace(chip(), long))) { t }
        assertEquals(false, e.longAfter(150)) // the workspace's 300 ms
        e.setFinger("tap", 0.5f, 0f); e.keys() // 100 ms
        assertEquals(true, e.longAfter(150))
        e.fingerUp("tap"); e.keys()
        assertEquals(false, e.longAfter(150)) // back to 300 ms
    }

    @Test
    fun aButtonWithItsOwnTapTimeKeepsIt() {
        val e = MappingEngine(single(workspace(chip(), long, own = 120))) { t }
        e.setFinger("tap", 0.5f, 1f); e.keys() // 1000 ms
        assertEquals(true, e.longAfter(150))
    }

    @Test
    fun aToggleChipIsKeptAsTheWorkspaceSliderOverride() {
        val c = chip(MapOptions(how = How.TOGGLE))
        val e = MappingEngine(single(workspace(c, long))) { t }
        val commits = ArrayList<MappingEngine.SetCommit>()
        e.onSetValue = { commits.add(it) }
        e.setFinger("tap", 0.5f, 0f); e.keys()
        e.fingerUp("tap"); e.keys()
        assertEquals(1, commits.size)
        assertEquals(100f, commits[0].value, 0.5f)
        assertEquals(true, e.longAfter(150)) // kept

        // Saved: it lies over the workspace's Tap time, which stays.
        val p = SliderOverrides.put(single(workspace(c, long)), 0, c, commits[0].value)
        assertEquals(300, p.workspace(0).longPressMs)
        val again = MappingEngine(p) { t }
        assertEquals(true, again.longAfter(150))
    }
}
