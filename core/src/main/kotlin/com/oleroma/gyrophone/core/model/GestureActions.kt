package com.oleroma.gyrophone.core.model

import com.oleroma.gyrophone.core.hid.HidUsage

/** Preset Windows actions for touchpad gestures, and display names for any [GestureAction]. */
object GestureActions {
    const val CTRL = 1
    const val SHIFT = 2
    const val ALT = 4
    const val WIN = 8

    private const val KEY_TAB = 0x2B
    private const val KEY_D = 0x07
    private const val KEY_N = 0x11
    private const val KEY_A = 0x04
    private const val KEY_S = 0x16
    private const val KEY_LEFT = 0x50
    private const val KEY_RIGHT = 0x4F

    val TASK_VIEW = GestureAction.Keys(WIN, KEY_TAB)
    val SHOW_DESKTOP = GestureAction.Keys(WIN, KEY_D)
    val DESKTOP_LEFT = GestureAction.Keys(CTRL or WIN, KEY_LEFT)
    val DESKTOP_RIGHT = GestureAction.Keys(CTRL or WIN, KEY_RIGHT)
    val NOTIFICATIONS = GestureAction.Keys(WIN, KEY_N)
    val QUICK_SETTINGS = GestureAction.Keys(WIN, KEY_A)
    val SEARCH = GestureAction.Keys(WIN, KEY_S)
    val START_MENU = GestureAction.Keys(WIN)

    /** Picker presets, in display order, with their names. */
    val presets: List<Pair<String, GestureAction>> = listOf(
        "Task View" to TASK_VIEW,
        "Show desktop" to SHOW_DESKTOP,
        "Switch apps" to GestureAction.SwitchApps,
        "Previous desktop" to DESKTOP_LEFT,
        "Next desktop" to DESKTOP_RIGHT,
        "Notification centre" to NOTIFICATIONS,
        "Quick settings" to QUICK_SETTINGS,
        "Search" to SEARCH,
        "Start menu" to START_MENU,
    )

    /** Key combo text, e.g. "Ctrl+Win+Left". */
    fun combo(modifiers: Int, key: Int): String {
        val parts = ArrayList<String>(5)
        if (modifiers and (CTRL or 0x10) != 0) parts.add("Ctrl")
        if (modifiers and (SHIFT or 0x20) != 0) parts.add("Shift")
        if (modifiers and (ALT or 0x40) != 0) parts.add("Alt")
        if (modifiers and (WIN or 0x80) != 0) parts.add("Win")
        if (key != 0) parts.add(HidUsage.names[key] ?: "0x%02X".format(key))
        return if (parts.isEmpty()) "Nothing" else parts.joinToString("+")
    }

    fun describe(a: GestureAction): String = when (a) {
        GestureAction.None -> "None"
        GestureAction.SwitchApps -> "Switch apps (Alt+Tab)"
        is GestureAction.Mouse -> "Mouse " + a.button.name.lowercase() + " click"
        is GestureAction.Keys -> {
            val text = combo(a.modifiers, a.key)
            presets.firstOrNull { it.second == a }?.let { "${it.first} ($text)" } ?: text
        }
    }
}
