package com.oleroma.gyrophone.runtime

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.oleroma.gyrophone.core.engine.HapticSink
import com.oleroma.gyrophone.core.model.Describe
import com.oleroma.gyrophone.core.model.HapticFeel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Plays haptic mappings on the phone's vibrator, only while [connected]. [haptics] is called on
 * the report pump's thread and only hands the request to a thread of its own: the vibrator calls
 * are binder calls, and the report they come with must not wait for them. A hold is kept going
 * with short one-shots renewed by each call, so it stops by itself within [HOLD_MS] if the calls stop.
 */
class PhoneHaptics(context: Context, private val connected: () -> Boolean) : HapticSink {
    private val vibrator: Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val amplitudeControl = vibrator.hasAmplitudeControl()
    private val touchAttrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
    private val hardwareAttrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
    private val tickEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    private val clickEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    private val heavyEffect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)

    /** The debug log while the debug tools are on: each tick and hold, played or skipped. */
    @Volatile var log: ((String) -> Unit)? = null

    /** For the debug tools: whether the phone has a vibrator and the vibrator can set its strength. */
    val describe: String
        get() = "vibrator " + (if (vibrator.hasVibrator()) "yes" else "NONE") +
                ", strength control " + (if (amplitudeControl) "yes" else "no")

    private val thread = HandlerThread("gp-haptics", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val worker = Handler(thread.looper)

    // Handed from the pump thread to the haptics thread under [this]: the strongest tick since the
    // last run and the latest hold.
    private var pendingTick: HapticFeel? = null
    private var pendingHeld: HapticFeel? = null
    private var pendingLevel = 0f
    private val posted = AtomicBoolean(false)
    private val run = Runnable {
        posted.set(false)
        val tick: HapticFeel?
        val held: HapticFeel?
        val level: Float
        synchronized(this) {
            tick = pendingTick; held = pendingHeld; level = pendingLevel
            pendingTick = null
        }
        play(tick, held, level)
    }

    override fun haptics(ticks: List<HapticFeel>, held: HapticFeel?, level: Float) {
        synchronized(this) {
            for (i in ticks.indices) {
                val t = ticks[i]
                val best = pendingTick
                if (best == null || t.strength > best.strength) pendingTick = t
            }
            pendingHeld = held; pendingLevel = level
        }
        if (posted.compareAndSet(false, true)) worker.post(run)
    }

    // Touched by the haptics thread only.
    private var holding: HapticFeel? = null
    private var holdAmplitude = 0
    private var lastIssue = 0L
    /** A tick replaced the running hold; the hold resumes at [resumeAt], once the tick is over. */
    private var tickPending = false
    private var resumeAt = 0L

    private fun play(tick: HapticFeel?, held: HapticFeel?, level: Float) {
        val on = connected()
        val now = SystemClock.uptimeMillis()
        log?.let { l ->
            val why = if (on) "" else " skipped: no PC connected"
            if (tick != null) l("haptic tick" + Describe.feel(tick) + why)
            if (held != holding && (held == null || on || holding != null)) {
                l(if (held == null) "haptic hold ends" else "haptic hold" + Describe.feel(held) + why)
            }
        }
        if (on && tick != null) {
            playTick(tick)
            tickPending = true
            resumeAt = now + tickLength(tick)
        }
        if (!on || held == null) {
            if (holding != null) {
                // A tick still playing has already replaced the hold; let it finish.
                if (!(tickPending && now < resumeAt)) vibrator.cancel()
                holding = null
            }
            tickPending = false
            return
        }
        if (tickPending && now < resumeAt) return
        val amplitude = amplitudeOf(held, level)
        if (tickPending || held != holding || abs(amplitude - holdAmplitude) > AMPLITUDE_STEP || now >= lastIssue + RENEW_MS) {
            vibrator.vibrate(VibrationEffect.createOneShot(HOLD_MS, amplitude), hardwareAttrs)
            holding = held
            holdAmplitude = amplitude
            lastIssue = now
            tickPending = false
        }
    }

    // Game rumble and the notice buzz, from the PC (RUMBLE). Touched by the haptics thread only.
    private var rumbleLevel = 0
    private var buzzUntil = 0L
    private var lastBuzz = -BUZZ_GAP_MS
    private val renewRumble = Runnable { issueRumble() }

    /**
     * The game's rumble on the PC's virtual controller: the stronger motor sets the strength, held
     * until the next call (0 stops it). A haptic mapping's hold or tick plays over it.
     */
    fun rumble(large: Int, small: Int) {
        val level = maxOf(large, small).coerceIn(0, 255)
        worker.post {
            log?.let { l -> if (level != rumbleLevel) l("game rumble $large/$small") }
            rumbleLevel = level
            worker.removeCallbacks(renewRumble)
            if (level == 0) {
                if (holding == null && SystemClock.uptimeMillis() >= buzzUntil) vibrator.cancel()
            } else {
                issueRumble()
            }
        }
    }

    /** Android's double beep at the notification volume, then the tone player is let go. */
    private fun beep() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_MS.toInt())
            worker.postDelayed({ tone.release() }, BEEP_MS + 200)
        } catch (e: RuntimeException) {
            log?.invoke("notice beep failed: ${e.message}") // no audio device for tones
        }
    }

    /**
     * Two short strong buzzes and two beeps (a Performance record started or ended on the PC). Repeats
     * within a second are one. The beeps play at the notification volume, so silent mode silences them.
     */
    fun buzz() {
        worker.post {
            val now = SystemClock.uptimeMillis()
            if (now - lastBuzz < BUZZ_GAP_MS) return@post
            lastBuzz = now
            log?.invoke("notice buzz")
            val amplitude = if (amplitudeControl) 255 else VibrationEffect.DEFAULT_AMPLITUDE
            val timings = longArrayOf(0, BUZZ_ON_MS, BUZZ_OFF_MS, BUZZ_ON_MS)
            val amplitudes = intArrayOf(0, amplitude, 0, amplitude)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), hardwareAttrs)
            buzzUntil = now + timings.sum()
            beep()
            if (rumbleLevel > 0) {
                worker.removeCallbacks(renewRumble)
                worker.postAtTime(renewRumble, buzzUntil)
            }
        }
    }

    /** Keeps the game's rumble going with short one-shots, so it stops by itself if the link goes. */
    private fun issueRumble() {
        if (rumbleLevel == 0) return
        if (!connected()) { rumbleLevel = 0; return }
        val now = SystemClock.uptimeMillis()
        val busy = holding != null || (tickPending && now < resumeAt) || now < buzzUntil
        if (!busy) {
            val amplitude = if (amplitudeControl) rumbleLevel.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(VibrationEffect.createOneShot(HOLD_MS, amplitude), hardwareAttrs)
        }
        worker.postDelayed(renewRumble, RENEW_MS)
    }

    /** Plays [feel] once whether or not a PC is connected: a tick, or a short hold for [held]. */
    fun preview(feel: HapticFeel, held: Boolean) {
        worker.post {
            if (held) {
                vibrator.vibrate(VibrationEffect.createOneShot(PREVIEW_HOLD_MS, amplitudeOf(feel, 1f)), hardwareAttrs)
            } else {
                playTick(feel)
            }
        }
    }

    private fun playTick(feel: HapticFeel) {
        if (feel.system) {
            val effect = when {
                feel.strength < LIGHT_BELOW -> tickEffect
                feel.strength < MEDIUM_BELOW -> clickEffect
                else -> heavyEffect
            }
            vibrator.vibrate(effect, touchAttrs)
        } else {
            val amplitude = if (amplitudeControl) feel.strength.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
            vibrator.vibrate(VibrationEffect.createOneShot(customLength(feel).toLong(), amplitude), hardwareAttrs)
        }
    }

    private fun amplitudeOf(feel: HapticFeel, level: Float): Int {
        if (!amplitudeControl) return VibrationEffect.DEFAULT_AMPLITUDE
        val base = if (feel.system) SYSTEM_HOLD_STRENGTH else feel.strength
        return (base * level).roundToInt().coerceIn(1, 255)
    }

    private fun customLength(feel: HapticFeel): Int =
        feel.lengthMs.coerceIn(HapticFeel.MIN_LENGTH_MS, HapticFeel.MAX_LENGTH_MS)

    private fun tickLength(feel: HapticFeel): Int = if (feel.system) SYSTEM_TICK_MS else customLength(feel)

    private companion object {
        const val HOLD_MS = 300L
        const val RENEW_MS = 150L
        const val PREVIEW_HOLD_MS = 400L
        const val BUZZ_ON_MS = 90L
        const val BUZZ_OFF_MS = 110L
        const val BUZZ_GAP_MS = 1000L
        const val BEEP_MS = 400L
        const val AMPLITUDE_STEP = 12
        const val SYSTEM_HOLD_STRENGTH = 160
        /** About how long a predefined click lasts. */
        const val SYSTEM_TICK_MS = 30
        // Same bands as Describe.systemLevel.
        const val LIGHT_BELOW = 86
        const val MEDIUM_BELOW = 171
    }
}
