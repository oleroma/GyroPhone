package com.oleroma.gyrophone.core

import com.oleroma.gyrophone.core.model.ButtonHow
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.KeyTiming
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TouchControl
import com.oleroma.gyrophone.core.model.TouchKind
import com.oleroma.gyrophone.core.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A button's How: its chips without their own follow it, where they can. */
class ButtonHowTest {
    private val press = InputSource.Touch("b")
    private val click = OutputTarget.Mouse(MouseButton.LEFT)

    private fun ws(how: How?) = Workspace(
        "w", "W", touchControls = listOf(TouchControl("b", TouchKind.BUTTON, "B", 0.5f, 0.5f, how = how, repeat = 3)),
    )

    @Test fun chipsWithoutTheirOwnFollowTheButton() {
        val out = ButtonHow.touch(ws(How.TOGGLE), listOf(Mapping(press, click)))
        assertEquals(How.TOGGLE, out[0].options!!.mode)
        val rep = ButtonHow.touch(ws(How.REPEAT), listOf(Mapping(press, click)))
        assertEquals(How.REPEAT, rep[0].options!!.mode)
        assertEquals(3, rep[0].options!!.repeat)
    }

    @Test fun aChipsOwnHowWins() {
        val own = Mapping(press, click, options = MapOptions(how = How.HELD))
        assertEquals(How.HELD, ButtonHow.touch(ws(How.TOGGLE), listOf(own))[0].options!!.mode)
    }

    @Test fun heldButtonsAndOtherButtonsChangeNothing() {
        val m = Mapping(press, click)
        assertNull(ButtonHow.touch(ws(null), listOf(m))[0].options)
        val other = Mapping(InputSource.Touch("x"), click)
        assertNull(ButtonHow.touch(ws(How.TOGGLE), listOf(other))[0].options)
    }

    @Test fun analogChipsStayHeld() {
        val move = Mapping(press, OutputTarget.MouseAxis(MouseMotion.MOVE_X))
        assertNull(ButtonHow.touch(ws(How.TOGGLE), listOf(move))[0].options)
    }

    @Test fun physicalKeysFollowTheirOwnHow() {
        val w = Workspace("w", "W", keyTimings = listOf(KeyTiming(96, how = How.ONCE)))
        val out = ButtonHow.keys(w, listOf(Mapping(InputSource.Key(96), click), Mapping(InputSource.Key(97), click)))
        assertEquals(How.ONCE, out[0].options!!.mode)
        assertNull(out[1].options)
    }
}
