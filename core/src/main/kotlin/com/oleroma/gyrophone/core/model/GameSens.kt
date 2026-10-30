package com.oleroma.gyrophone.core.model

import kotlinx.serialization.Serializable

/**
 * A game's mouse scale: [yaw] is the camera's turn in degrees per mouse count at in-game
 * sensitivity 1 (Source games 0.022), so the turn per count is yaw × the game's sensitivity.
 */
@Serializable
data class GamePreset(val name: String, val yaw: Double)

/**
 * Real world sensitivity (RWS): degrees the game's camera turns per degree the phone turns. Gyro
 * sensitivity is mouse counts per degree, so RWS = sensitivity × 360 / the game's counts per full
 * turn. Exact only with raw input and no in-game mouse acceleration.
 */
object GameSens {
    /** Games with a known, linear scale (in-game degrees per count at sensitivity 1). */
    val builtin: List<GamePreset> = listOf(
        GamePreset("Counter-Strike 2", 0.022),
        GamePreset("Apex Legends", 0.022),
        GamePreset("Team Fortress 2", 0.022),
        GamePreset("Titanfall 2", 0.022),
        GamePreset("Quake Champions", 0.022),
        GamePreset("Source games (Half-Life 2, Portal)", 0.022),
        GamePreset("Valorant", 0.07),
        GamePreset("Overwatch 2", 0.0066),
        GamePreset("Destiny 2", 0.0066),
    )

    /** Counts per full turn of a game with degrees per count [yaw] at sensitivity 1, set to [gameSens]. */
    fun countsPer360(yaw: Double, gameSens: Double): Float = (360.0 / (yaw * gameSens)).toFloat()

    /** The degrees per count at sensitivity 1 of a game measured at [countsPer360] counts per turn with sensitivity [gameSens]. */
    fun yawOf(countsPer360: Float, gameSens: Double): Double = 360.0 / (countsPer360 * gameSens)

    /** Gyro sensitivity [sens] (counts per degree) as RWS in a game of [countsPer360]. */
    fun rws(sens: Float, countsPer360: Float): Float = sens * 360f / countsPer360

    /** The gyro sensitivity (counts per degree) giving real world sensitivity [rws] in a game of [countsPer360]. */
    fun sensOf(rws: Float, countsPer360: Float): Float = rws * countsPer360 / 360f
}
