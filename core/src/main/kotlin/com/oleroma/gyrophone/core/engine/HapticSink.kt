package com.oleroma.gyrophone.core.engine

import com.oleroma.gyrophone.core.model.HapticFeel

/** Plays haptic targets on the phone; see [MappingEngine.hapticSink]. */
fun interface HapticSink {
    /**
     * [ticks]: feels of ticks to play now (reused list); [held]: the feel to keep playing, or
     * null to stop; [level]: how far its source is pushed (0..1; 1 for a button), scaling its strength.
     */
    fun haptics(ticks: List<HapticFeel>, held: HapticFeel?, level: Float)
}
