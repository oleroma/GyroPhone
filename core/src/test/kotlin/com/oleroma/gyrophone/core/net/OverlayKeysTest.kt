package com.oleroma.gyrophone.core.net

import com.oleroma.gyrophone.core.model.AndroidAxis
import com.oleroma.gyrophone.core.model.AndroidKey
import com.oleroma.gyrophone.core.model.AxisHalf
import com.oleroma.gyrophone.core.model.ButtonGesture
import com.oleroma.gyrophone.core.model.How
import com.oleroma.gyrophone.core.model.InputSource
import com.oleroma.gyrophone.core.model.MapOptions
import com.oleroma.gyrophone.core.model.Mapping
import com.oleroma.gyrophone.core.model.MouseButton
import com.oleroma.gyrophone.core.model.MouseMotion
import com.oleroma.gyrophone.core.model.OutputTarget
import com.oleroma.gyrophone.core.model.TriggerTuning
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Keys overlay's table and its bytes; the same hex is decoded by the companion's OverlayTests. */
class OverlayKeysTest {
    private fun key(code: Int) = InputSource.Key(code)
    private fun axis(a: Int, half: AxisHalf = AxisHalf.FULL) = InputSource.Axis(a, half)
    private fun usage(u: Int) = OutputTarget.Key(u)

    private val ms = listOf(
        Mapping(key(AndroidKey.BUTTON_A), usage(0x2C)), // Space
        Mapping(axis(AndroidAxis.X, AxisHalf.NEGATIVE), usage(0x04)), // A
        Mapping(axis(AndroidAxis.X, AxisHalf.POSITIVE), usage(0x07)), // D
        Mapping(axis(AndroidAxis.Y, AxisHalf.NEGATIVE), usage(0x1A)), // W
        Mapping(axis(AndroidAxis.Y, AxisHalf.POSITIVE), usage(0x16)), // S
        Mapping(axis(AndroidAxis.Z), OutputTarget.MouseAxis(MouseMotion.MOVE_X)),
        Mapping(axis(AndroidAxis.RZ), OutputTarget.MouseAxis(MouseMotion.MOVE_Y)),
        Mapping(key(AndroidKey.BUTTON_R2), OutputTarget.Mouse(MouseButton.LEFT)),
        Mapping(InputSource.KeyGesture(AndroidKey.BUTTON_B, ButtonGesture.LONG_PRESS), usage(0x08)), // E
        Mapping(key(AndroidKey.BUTTON_X), usage(0x06), options = MapOptions(how = How.TOGGLE)), // C
        Mapping(key(AndroidKey.BUTTON_A), OutputTarget.Mouse(MouseButton.RIGHT)),
        Mapping(key(AndroidKey.BUTTON_Y), OutputTarget.NoOutput),
    )

    @Test fun tableOfThePadsMappings() {
        val k = OverlayKeys.of(ms, rightStickRx = false, emptyMap(), mapOf("rt" to TriggerTuning(0.3f, 0.9f)), 250)
        val way = OverlayKey.WAY
        assertEquals(
            listOf(
                OverlayKey(0, OverlayKey.HELD, "A", "Space"),
                OverlayKey(way or 3, OverlayKey.HELD, "LS left", "A"),
                OverlayKey(way or 1, OverlayKey.HELD, "LS right", "D"),
                OverlayKey(way or 0, OverlayKey.HELD, "LS up", "W"),
                OverlayKey(way or 2, OverlayKey.HELD, "LS down", "S"),
                OverlayKey(OverlayKey.STICK or 1, OverlayKey.HELD, "RS x axis", "Mouse"),
                OverlayKey(OverlayKey.STICK or 1, OverlayKey.HELD, "RS y axis", "Mouse"),
                OverlayKey(7, OverlayKey.HELD, "RT", "Mouse L"),
                OverlayKey(1, OverlayKey.LONG_PRESS, "B long press", "E"),
                OverlayKey(2, OverlayKey.TOGGLE, "X", "C"),
                OverlayKey(0, OverlayKey.HELD, "A", "Mouse R"),
            ),
            k.keys,
        )
        assertEquals(TriggerTuning(0.3f, 0.9f), k.triggers[1])
        assertEquals(KEYS_BLOB, OverlayFeed.encodeFrames(OverlayFrames.NONE, 7, emptyMap(), k).hex())
    }

    @Test fun rightStickOnRxMakesZTriggers() {
        assertEquals(-1, OverlayKeys.stickOf(AndroidAxis.Z, rightStickRx = true))
        assertEquals(1, OverlayKeys.stickOf(AndroidAxis.RX, rightStickRx = true))
        assertEquals(1, OverlayKeys.stickOf(AndroidAxis.Z, rightStickRx = false))
        assertEquals(OverlayKey.TRIGGER or 0, OverlayKeys.inputOf(InputSource.Axis(AndroidAxis.Z), rx = true))
    }

    @Test fun keysGoWithTheFramesOnlyWhileWanted() {
        val f = OverlayFeed()
        assertEquals(-1, f.framesVersion())
        f.keysWanted = true
        f.setKeys(OverlayKeys.NONE)
        val v = f.framesVersion()
        assertEquals(true, v >= 0)
        f.setKeys(OverlayKeys.NONE)
        assertEquals(v, f.framesVersion())
        f.keysWanted = false
        assertEquals(-1, f.framesVersion())
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        /** The frames blob for layout 7 with no marks and the table above (also in the companion's OverlayTests). */
        const val KEYS_BLOB = "0107000000640000000000004bfa001af2d987871af2d9878780f24de60b000001410553706163654300074c53206c65667401414100084c5320726967687401444000054c5320757001574200074c5320646f776e0153610009525320782061786973054d6f757365610009525320792061786973054d6f7573650700025254074d6f757365204c01030c42206c6f6e67207072657373014502800158014300000141074d6f7573652052"
    }
}
