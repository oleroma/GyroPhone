package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.engine.MappingEngine
import com.oleroma.gyrophone.core.model.ButtonGyro
import com.oleroma.gyrophone.core.model.CurveFrame
import com.oleroma.gyrophone.core.model.CurvePoint
import com.oleroma.gyrophone.core.model.GyroCurve
import com.oleroma.gyrophone.core.model.GyroSettings
import com.oleroma.gyrophone.core.model.MotionAxis
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.TouchShape
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurveFrameTest {
    private val settings = GyroCurve(listOf(CurvePoint(10f, 1f), CurvePoint(100f, 2f)))
    private val yawOwn = GyroCurve(listOf(CurvePoint(20f, 1f), CurvePoint(200f, 4f)))
    private val framed = GyroCurve(listOf(CurvePoint(30f, 1f), CurvePoint(270f, 2.5f)))

    private fun frame(id: String, curve: GyroCurve, axis: MotionAxis = MotionAxis.NONE, edited: Long = 1L) =
        TouchControl(id, TouchKind.CURVE, "", 0.5f, 0.5f, shape = TouchShape.SQUARE, curveFrame = CurveFrame(curve, axis, edited = edited))

    private fun ws(vararg controls: TouchControl) = Workspace(
        "w", "w", touchControls = controls.toList(),
        gyro = GyroSettings(sensitivity = 16f, accel = settings, yawAccel = yawOwn),
    )

    @Test
    fun anAllAxesFrameLiesOverTheSettingsAndTheAxesOwnWithoutChangingThem() {
        val w = ws(frame("f", framed))
        val e = MappingEngine(single(w)) { 0L }
        assertEquals(framed, e.gyroSettings.accel)
        assertNull(e.gyroSettings.yawAccel)
        assertEquals(settings, w.gyro.accel) // the parent stays
        // No frame: the settings' curves again.
        val back = MappingEngine(single(ws())) { 0L }
        assertEquals(settings, back.gyroSettings.accel)
        assertEquals(yawOwn, back.gyroSettings.yawAccel)
    }

    @Test
    fun anAxisFrameSetsOnlyThatAxis() {
        val e = MappingEngine(single(ws(frame("f", framed, MotionAxis.PITCH)))) { 0L }
        assertEquals(settings, e.gyroSettings.accel)
        assertEquals(framed, e.gyroSettings.pitchAccel)
        assertEquals(yawOwn, e.gyroSettings.yawAccel)
    }

    @Test
    fun theFrameChangedLastWins() {
        val newer = GyroCurve(listOf(CurvePoint(50f, 1f), CurvePoint(150f, 1.5f)))
        val e = MappingEngine(single(ws(frame("b", newer, edited = 9L), frame("a", framed, edited = 2L)))) { 0L }
        assertEquals(newer, e.gyroSettings.accel)
    }

    @Test
    fun aHeldButtonsOwnCurveWinsAndOneThatFollowsGetsTheFrames() {
        val ads = TouchControl("ads", TouchKind.BUTTON, "ADS", 0.2f, 0.2f, gyro = listOf(ButtonGyro(accel = GyroCurve.OFF)))
        val hip = TouchControl("hip", TouchKind.BUTTON, "Hip", 0.8f, 0.8f, gyro = listOf(ButtonGyro(sensitivity = 8f)))
        val e = MappingEngine(single(ws(frame("f", framed), ads, hip))) { 0L }
        e.setTouch("ads", true)
        assertEquals(GyroCurve.OFF, e.gyroSettings.accel)
        assertEquals(false, e.frameInEffect("f"))
        e.setTouch("ads", false)
        e.setTouch("hip", true)
        assertEquals(framed, e.gyroSettings.accel)
        assertEquals(8f, e.gyroSettings.sensitivity, 0f)
    }

    @Test
    fun aCurveBeingDraggedTakesEffectAtOnceAndGoesBackWhenDropped() {
        val e = MappingEngine(single(ws(frame("f", framed)))) { 0L }
        val dragging = GyroCurve(listOf(CurvePoint(30f, 1f), CurvePoint(270f, 2.9f)))
        e.setFrameCurve("f", dragging)
        assertEquals(dragging, e.gyroSettings.accel)
        assertEquals(true, e.frameInEffect("f")) // dragging doesn't gray it
        e.setFrameCurve("f", null)
        assertEquals(framed, e.gyroSettings.accel)
        e.setFrameCurve("nope", dragging) // no such frame: nothing
        assertEquals(framed, e.gyroSettings.accel)
    }

    @Test
    fun aFramesCurveStaysInsideItsRange() {
        val f = CurveFrame(GyroCurve(listOf(CurvePoint(10f, 1f), CurvePoint(500f, 6f))), maxSpeed = 300f, maxGain = 3f)
        val c = f.inRange()
        assertEquals(300f, c.points.last().speed, 0f)
        assertEquals(3f, c.points.last().gain, 0f)
        val ramp = CurveFrame.ramp(300f, 3f)
        assertEquals(listOf(CurvePoint(30f, 1f), CurvePoint(270f, 2f)), ramp.points)
    }
}
