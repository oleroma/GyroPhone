package com.oleroma.gyrophone.core.engine

/**
 * The pointer's guns ([com.oleroma.gyrophone.core.model.GyroDisplay.laserGun]): the Gun look's models, and for the
 * Crosshair look the recoil it kicks like. Each has its own model (boxes in its own frame: x right, y up, z toward
 * the stock, the muzzle out ahead at [Gun.muzzleZ]), recoil ([CrosshairKick.Recoil]), default fire rate, muzzle
 * size ([Gun.bore], how wide its flash is) and calibre ([Gun.flash], how long), and its scope ([Gun.scope], none
 * on the pistol). As the PC's Guns.
 */
object Guns {
    /** A round scope along the barrel at height [y], from [front] to [back] (z), its tube [radius], its objective bell [bell]. */
    class Scope(val y: Float, val front: Float, val back: Float, val radius: Float, val bell: Float)

    class Gun(
        val key: String,
        val name: String,
        /** Boxes: centre x, y, z and half sizes. */
        val boxes: FloatArray,
        val muzzleZ: Float,
        val bore: Float,
        val flash: Float,
        val recoil: CrosshairKick.Recoil,
        val rpm: Int,
        val scope: Scope?,
        /** What it pitches about kicking and lowered: the shoulder, or the wrist for a pistol. */
        val pivotZ: Float = 0.6f,
    )

    val PISTOL = Gun(
        "pistol", "Pistol",
        floatArrayOf(
            // slide, frame, grip, trigger guard, rear sight, front sight
            0f, 0.035f, -0.2f, 0.035f, 0.04f, 0.25f,
            0f, -0.02f, -0.17f, 0.03f, 0.025f, 0.2f,
            0f, -0.16f, 0.0f, 0.032f, 0.13f, 0.055f,
            0f, -0.07f, -0.1f, 0.008f, 0.03f, 0.045f,
            0f, 0.085f, 0.02f, 0.025f, 0.012f, 0.01f,
            0f, 0.085f, -0.42f, 0.006f, 0.012f, 0.01f,
        ),
        muzzleZ = -0.46f, bore = 0.012f, flash = 0.12f,
        recoil = CrosshairKick.Recoil(riseMs = 18f, decayMs = 35f, swingMs = 120f, lengthMs = 220f, back = 0.1f, climbDeg = 16f),
        rpm = 400, scope = null, pivotZ = 0.15f,
    )

    val SMG = Gun(
        "smg", "SMG",
        floatArrayOf(
            // receiver, shroud, barrel, stock, magazine, grip, front sight
            0f, 0f, 0f, 0.05f, 0.07f, 0.22f,
            0f, 0f, -0.32f, 0.032f, 0.032f, 0.12f,
            0f, 0f, -0.5f, 0.014f, 0.014f, 0.06f,
            0f, -0.02f, 0.42f, 0.03f, 0.06f, 0.2f,
            0f, -0.2f, -0.06f, 0.025f, 0.12f, 0.03f,
            0f, -0.16f, 0.15f, 0.028f, 0.09f, 0.04f,
            0f, 0.08f, -0.36f, 0.01f, 0.04f, 0.012f,
        ),
        muzzleZ = -0.56f, bore = 0.016f, flash = 0.16f,
        recoil = CrosshairKick.Recoil(riseMs = 14f, decayMs = 25f, swingMs = 90f, lengthMs = 150f, back = 0.06f, climbDeg = 5f),
        rpm = 800, scope = Scope(0.13f, -0.12f, 0.16f, 0.026f, 0.036f), pivotZ = 0.45f,
    )

    val AR = Gun(
        "ar", "Assault rifle",
        floatArrayOf(
            // stock, buffer tube, receiver, carry rail, handguard, barrel, muzzle, front sight, magazine, grip
            0.0f, -0.05f, 0.68f, 0.05f, 0.09f, 0.2f,
            0.0f, 0.0f, 0.42f, 0.03f, 0.03f, 0.08f,
            0.0f, 0.0f, 0.12f, 0.055f, 0.075f, 0.22f,
            0.0f, 0.1f, 0.1f, 0.028f, 0.025f, 0.18f,
            0.0f, 0.0f, -0.32f, 0.055f, 0.055f, 0.2f,
            0.0f, 0.0f, -0.7f, 0.018f, 0.018f, 0.18f,
            0.0f, 0.0f, -0.9f, 0.026f, 0.026f, 0.03f,
            0.0f, 0.07f, -0.5f, 0.012f, 0.06f, 0.015f,
            0.0f, -0.2f, 0.0f, 0.035f, 0.12f, 0.06f,
            0.0f, -0.17f, 0.28f, 0.03f, 0.09f, 0.045f,
        ),
        muzzleZ = -0.93f, bore = 0.02f, flash = 0.22f,
        recoil = CrosshairKick.Recoil(riseMs = 24f, decayMs = 42f, swingMs = 150f, lengthMs = 280f, back = 0.12f, climbDeg = 10f),
        rpm = 600, scope = Scope(0.17f, -0.12f, 0.3f, 0.032f, 0.045f),
    )

    val BATTLE_RIFLE = Gun(
        "battle", "Battle rifle",
        floatArrayOf(
            // stock, receiver, handguard, barrel, muzzle brake, front sight, magazine, grip, rail
            0f, -0.04f, 0.7f, 0.055f, 0.1f, 0.2f,
            0f, 0f, 0.12f, 0.06f, 0.08f, 0.26f,
            0f, 0f, -0.36f, 0.06f, 0.06f, 0.24f,
            0f, 0f, -0.76f, 0.02f, 0.02f, 0.18f,
            0f, 0f, -0.94f, 0.032f, 0.032f, 0.035f,
            0f, 0.08f, -0.56f, 0.012f, 0.06f, 0.015f,
            0f, -0.2f, -0.02f, 0.04f, 0.12f, 0.07f,
            0f, -0.17f, 0.32f, 0.03f, 0.09f, 0.045f,
            0f, 0.1f, 0.06f, 0.03f, 0.022f, 0.24f,
        ),
        muzzleZ = -0.975f, bore = 0.024f, flash = 0.28f,
        recoil = CrosshairKick.Recoil(riseMs = 28f, decayMs = 55f, swingMs = 180f, lengthMs = 340f, back = 0.16f, climbDeg = 13f),
        rpm = 450, scope = Scope(0.17f, -0.16f, 0.3f, 0.034f, 0.05f),
    )

    val LMG = Gun(
        "lmg", "LMG",
        floatArrayOf(
            // stock, receiver, shroud, barrel, box magazine, grip, carry handle, folded bipod
            0f, -0.03f, 0.64f, 0.05f, 0.09f, 0.2f,
            0f, 0f, 0.1f, 0.07f, 0.09f, 0.25f,
            0f, 0.01f, -0.36f, 0.045f, 0.045f, 0.22f,
            0f, 0f, -0.76f, 0.022f, 0.022f, 0.18f,
            0f, -0.18f, 0.02f, 0.07f, 0.09f, 0.08f,
            0f, -0.17f, 0.32f, 0.03f, 0.09f, 0.045f,
            0f, 0.14f, -0.02f, 0.015f, 0.04f, 0.09f,
            0f, -0.06f, -0.66f, 0.012f, 0.012f, 0.16f,
        ),
        muzzleZ = -0.94f, bore = 0.022f, flash = 0.28f,
        recoil = CrosshairKick.Recoil(riseMs = 22f, decayMs = 40f, swingMs = 140f, lengthMs = 260f, back = 0.1f, climbDeg = 8f),
        rpm = 700, scope = Scope(0.17f, -0.2f, 0.18f, 0.032f, 0.045f),
    )

    val SHOTGUN = Gun(
        "shotgun", "Shotgun",
        floatArrayOf(
            // stock, receiver, barrel, magazine tube, pump, bead sight
            0f, -0.06f, 0.56f, 0.045f, 0.1f, 0.25f,
            0f, 0f, 0.1f, 0.05f, 0.08f, 0.18f,
            0f, 0.02f, -0.45f, 0.03f, 0.03f, 0.4f,
            0f, -0.05f, -0.35f, 0.025f, 0.025f, 0.3f,
            0f, -0.05f, -0.38f, 0.042f, 0.042f, 0.12f,
            0f, 0.06f, -0.82f, 0.008f, 0.01f, 0.008f,
        ),
        muzzleZ = -0.85f, bore = 0.034f, flash = 0.36f,
        recoil = CrosshairKick.Recoil(riseMs = 30f, decayMs = 80f, swingMs = 240f, lengthMs = 450f, back = 0.22f, climbDeg = 18f),
        rpm = 80, scope = Scope(0.12f, -0.1f, 0.22f, 0.03f, 0.042f),
    )

    val SNIPER = Gun(
        "sniper", "Sniper",
        floatArrayOf(
            // stock, cheek rest, receiver, barrel, muzzle brake, bolt handle, magazine, grip
            0f, -0.04f, 0.6f, 0.05f, 0.11f, 0.24f,
            0f, 0.06f, 0.56f, 0.03f, 0.03f, 0.12f,
            0f, 0f, 0.1f, 0.05f, 0.06f, 0.22f,
            0f, 0f, -0.58f, 0.02f, 0.02f, 0.34f,
            0f, 0f, -0.94f, 0.032f, 0.032f, 0.035f,
            0.07f, 0.02f, 0.2f, 0.03f, 0.012f, 0.012f,
            0f, -0.12f, 0.05f, 0.03f, 0.06f, 0.05f,
            0f, -0.16f, 0.32f, 0.03f, 0.09f, 0.045f,
        ),
        muzzleZ = -0.975f, bore = 0.024f, flash = 0.38f,
        recoil = CrosshairKick.Recoil(riseMs = 34f, decayMs = 100f, swingMs = 300f, lengthMs = 600f, back = 0.26f, climbDeg = 14f),
        rpm = 45, scope = Scope(0.16f, -0.26f, 0.32f, 0.04f, 0.065f),
    )

    /** Every gun, by its number. */
    val ALL = listOf(PISTOL, SMG, AR, BATTLE_RIFLE, LMG, SHOTGUN, SNIPER)

    /** The default gun's number: the assault rifle. */
    const val DEFAULT = 2

    /** Gun [i], or the assault rifle. */
    fun of(i: Int): Gun = ALL.getOrElse(i) { AR }
}
