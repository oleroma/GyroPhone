package com.oleroma.gyrophone.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed

/**
 * A two-finger tap calls [onTwo] (undo), a three-finger tap [onThree] (redo), anywhere in this
 * element. It is known when the fingers lift, so a one-finger tap never waits for a second finger;
 * the fingers count as a tap only while none has moved. Such a tap's lifts are consumed, so what
 * is under a finger isn't also tapped. Moves are never consumed: a two-finger scroll still scrolls.
 */
internal fun Modifier.fingerTaps(onTwo: () -> Unit, onThree: () -> Unit): Modifier = composed {
    val two by rememberUpdatedState(onTwo)
    val three by rememberUpdatedState(onThree)
    pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                // One gesture: from the first finger down until every finger is up.
                val start = HashMap<PointerId, Offset>()
                var most = 0
                var moved = false
                do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    for (c in e.changes) {
                        if (c.pressed) {
                            val at = start.getOrPut(c.id) { c.position }
                            if ((c.position - at).getDistance() > slop) moved = true
                        }
                    }
                    val down = e.changes.count { it.pressed }
                    if (down > most) most = down
                    // Two or more fingers, still: a finger tap, so nothing under them takes it.
                    if (most >= 2 && !moved) {
                        e.changes.forEach { c ->
                            if (!c.pressed || !c.previousPressed) c.consume()
                        }
                    }
                } while (e.changes.any { it.pressed })
                if (most >= 2 && !moved) {
                    if (most >= 3) three() else two()
                }
            }
        }
    }
}
