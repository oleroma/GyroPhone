package com.oleroma.gyrophone.core.engine

/** Gets the mouse motion sent; see [MappingEngine.mouseSink]. */
fun interface MouseSink {
    /** [dx], [dy]: the mouse counts of one snapshot (right +, down +). */
    fun moved(dx: Int, dy: Int)
}
