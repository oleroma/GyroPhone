package com.oleroma.gyrophone.runtime

import android.content.pm.ActivityInfo

/** In-app screen rotation choice; AUTO follows the sensor and respects the system rotation lock. */
enum class ScreenOrientation(val label: String, val activityValue: Int) {
    AUTO("Auto", ActivityInfo.SCREEN_ORIENTATION_FULL_USER),
    LANDSCAPE("Landscape", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    PORTRAIT("Portrait", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
}
