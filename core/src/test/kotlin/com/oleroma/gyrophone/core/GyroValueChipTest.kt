package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.AxisRange
import com.oleroma.gyrophone.core.model.FingerAxis
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.SliderOverrides
import com.oleroma.gyrophone.core.model.StickAxis
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gyro glide and Tilt range as value chips ([OutputTarget.GyroGlide], [OutputTarget.TiltRange]). */
class GyroValueChipTest {
    private var t = 0L

    private fun workspace(vararg ms: Mapping) = Workspace(
        "w", "w",
        touchControls = listOf(TouchControl("s", TouchKind.BUTTON, "S", 0.5f, 0.5f, shape = TouchShape.SQUARE)),
        mappings = ms.toList(),
    )

    private fun chip(target: OutputTarget, from: Float, to: Float) =
        Mapping(InputSource.Finger("s", FingerAxis.Y), target, range = AxisRange(from, to))

    @Test
    fun tiltRangeFollowsTheSliderWhileTheFingerIsDown() {
        val e = MappingEngine(single(workspace(chip(OutputTarget.TiltRange(), 10f, 90f)))) { t }
        e.setFinger("s", 0.5f, 0.5f); e.snapshot()
        assertEquals(50f, e.gyroSettings.tiltRangeOf(true), 0.5f)
        assertEquals(50f, e.gyroSettings.tiltRangeOf(false), 0.5f)
        e.fingerUp("s"); e.snapshot()
        assertEquals(GyroSettings.DEFAULT_TILT_RANGE_DEG, e.gyroSettings.tiltRangeOf(true), 0.01f)
    }

    @Test
    fun anAxisGlideChipChangesOnlyItsAxis() {
        val e = MappingEngine(single(workspace(chip(OutputTarget.GyroGlide(MotionAxis.YAW), 100f, 1000f)))) { t }
        e.setFinger("s", 0.5f, 1f); e.snapshot()
        assertEquals(1000f, e.gyroSettings.glideMsOf(MotionAxis.YAW), 0.5f)
        assertEquals(GyroSettings.DEFAULT_GLIDE_MS, e.gyroSettings.glideMsOf(MotionAxis.PITCH), 0.01f)
    }

    @Test
    fun aKeptTiltRangeLiesOverTheSetting() {
        val c = chip(OutputTarget.TiltRange(StickAxis.X), 10f, 90f)
        val p = SliderOverrides.put(single(workspace(c)), 0, c, 20f)
        assertEquals(GyroSettings.DEFAULT_TILT_RANGE_DEG, p.workspace(0).gyro.tiltRangeDeg, 0f) // the setting stays
        val e = MappingEngine(p) { t }
        assertEquals(20f, e.gyroSettings.tiltRangeOf(true), 0.01f)
        assertEquals(GyroSettings.DEFAULT_TILT_RANGE_DEG, e.gyroSettings.tiltRangeOf(false), 0.01f)
    }
}
