package com.oleroma.gyrophone.core.model

/**
 * Android KeyEvent / MotionEvent constants used by profiles. Duplicated here (stable platform
 * values) so the mapping core stays plain Kotlin and unit-testable on the JVM.
 */
object AndroidKey {
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val BUTTON_A = 96
    const val BUTTON_B = 97
    const val BUTTON_C = 98
    const val BUTTON_X = 99
    const val BUTTON_Y = 100
    const val BUTTON_Z = 101
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val BUTTON_L2 = 104
    const val BUTTON_R2 = 105
    const val BUTTON_THUMBL = 106
    const val BUTTON_THUMBR = 107
    const val BUTTON_START = 108
    const val BUTTON_SELECT = 109
    const val BUTTON_MODE = 110
    const val BUTTON_1 = 188 // BUTTON_1..BUTTON_16 are 188..203 (extra / paddle buttons)

    val names: Map<Int, String> = mapOf(
        DPAD_UP to "D-pad Up", DPAD_DOWN to "D-pad Down", DPAD_LEFT to "D-pad Left",
        DPAD_RIGHT to "D-pad Right", BUTTON_A to "A", BUTTON_B to "B", BUTTON_C to "C",
        BUTTON_X to "X", BUTTON_Y to "Y", BUTTON_Z to "Z", BUTTON_L1 to "L1", BUTTON_R1 to "R1",
        BUTTON_L2 to "L2", BUTTON_R2 to "R2", BUTTON_THUMBL to "L3", BUTTON_THUMBR to "R3",
        BUTTON_START to "Start", BUTTON_SELECT to "Select", BUTTON_MODE to "Home", BACK to "Back",
    )
}

object AndroidAxis {
    const val X = 0
    const val Y = 1
    const val Z = 11
    const val RZ = 14
    const val HAT_X = 15
    const val HAT_Y = 16
    const val LTRIGGER = 17
    const val RTRIGGER = 18
    const val GAS = 22
    const val BRAKE = 23

    /** Axes the controller reader polls on every joystick MotionEvent. */
    val polled: IntArray = intArrayOf(X, Y, Z, RZ, HAT_X, HAT_Y, LTRIGGER, RTRIGGER, GAS, BRAKE)
}
