package com.oleroma.gyrophone.runtime

import android.content.pm.ActivityInfo

/**
 * In-app screen rotation choice; the gyro follows the rotation it gives. AUTO, LANDSCAPE and
 * PORTRAIT follow the system rotation lock: unlocked they turn with the phone (LANDSCAPE only
 * between its two landscape sides, PORTRAIT its two portrait sides), locked they stay as the
 * screen is now. The FORCED ones always stay put.
 */
enum class ScreenOrientation(val label: String) {
    AUTO("Auto"),
    LANDSCAPE("Landscape"),
    PORTRAIT("Portrait"),
    FORCED_PORTRAIT("Forced portrait"),
    FORCED_LANDSCAPE_LEFT("Forced landscape, top on the left"),
    FORCED_LANDSCAPE_RIGHT("Forced landscape, top on the right"),
    ;

    /**
     * The activity's requested orientation, with the system's auto-rotate [rotationOn] and the
     * screen's current rotation [degrees] (0 / 90 / 180 / 270; 90 = top on the left).
     */
    fun activityValue(rotationOn: Boolean, degrees: Int): Int {
        val landscapeNow = degrees == 90 || degrees == 270
        return when (this) {
            AUTO -> if (rotationOn) ActivityInfo.SCREEN_ORIENTATION_FULL_USER else fixed(degrees)
            LANDSCAPE -> when {
                rotationOn -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                landscapeNow -> fixed(degrees)
                else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }

            PORTRAIT -> when {
                rotationOn -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                !landscapeNow -> fixed(degrees)
                else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }

            FORCED_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            FORCED_LANDSCAPE_LEFT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            FORCED_LANDSCAPE_RIGHT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    }

    private fun fixed(degrees: Int) = when (degrees) {
        0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}
