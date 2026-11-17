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
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Keys overlay's rows and their bytes; the same hex is decoded by the companion's OverlayTests. */
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

    @Test fun rowsAndSticks() {
        val t = OverlayKeysTable(ms, rightStickRx = false)
        val on = BooleanArray(ms.size)
        val toggled = BooleanArray(ms.size)
        // Nothing pressed: only the stick rows.
        val idle = t.keys(on, toggled)
        assertEquals(
            listOf(
                OverlayKeyStick(OverlayKeyStick.LEFT, "", listOf("W", "D", "S", "A"), 0),
                OverlayKeyStick(OverlayKeyStick.RIGHT, "Mouse", listOf("", "", "", ""), 0),
            ),
            idle.sticks,
        )
        assertEquals(emptyList<OverlayKeyRow>(), idle.rows)
        // A held (both its mappings), W and D (a diagonal), RT pulled, X toggled on, B long press.
        for (i in listOf(0, 10, 2, 3, 7, 8)) on[i] = true
        toggled[9] = true
        val k = t.keys(on, toggled)
        assertEquals(OverlayKeyStick.UP or OverlayKeyStick.RIGHT_WAY, k.sticks[0].lit)
        assertEquals(
            listOf(
                OverlayKeyRow("A", "Space + Mouse R", 0),
                OverlayKeyRow("RT", "Mouse L", OverlayKeyRow.RIGHT_TRIGGER),
                OverlayKeyRow("B long press", "E", 0),
                OverlayKeyRow("X", "C on", OverlayKeyRow.TOGGLE),
            ),
            k.rows,
        )
        assertEquals(KEYS_BLOB, OverlayFeed.encodeFrames(OverlayFrames.NONE, 7, emptyMap(), k).hex())
    }

    @Test fun rightStickOnRxMakesZTriggers() {
        assertEquals(-1, OverlayKeysTable.stickOf(AndroidAxis.Z, rightStickRx = true))
        assertEquals(OverlayKeyStick.RIGHT, OverlayKeysTable.stickOf(AndroidAxis.RX, rightStickRx = true))
        assertEquals(OverlayKeyStick.RIGHT, OverlayKeysTable.stickOf(AndroidAxis.Z, rightStickRx = false))
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
        /** The frames blob for layout 7 with no marks and the keys above (also in the companion's OverlayTests). */
        const val KEYS_BLOB = "0107000000640000000000004b0200030001570144015301410100054d6f75736500000000040001410f5370616365202b204d6f757365205204025254074d6f757365204c000c42206c6f6e6720707265737301450101580443206f6e"
    }
}
