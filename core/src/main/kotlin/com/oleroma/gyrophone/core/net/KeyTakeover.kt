package com.oleroma.gyrophone.core.net

/**
 * When a PC that is ready by key may replace the phone's current session flow without a tap.
 * It counts how long the session has been down, not how old it is: a flow that never came up
 * gives way [TAKEOVER_MS] after it started, one that was up gives way [TAKEOVER_MS] after it went
 * down. A PC that said it exited (HOST_BYE EXIT, e.g. a restart as administrator) gets
 * [EXITED_GRACE_MS] before another PC takes its place. Pure; the link's rx thread owns it.
 */
class KeyTakeover {
    private var downSinceMs = 0L
    private var up = false
    private var exited = false

    /** A new session flow started at [nowMs]. */
    fun started(nowMs: Long) {
        downSinceMs = nowMs
        up = false
        exited = false
    }

    /** The current flow reported [status] at [nowMs]. */
    fun onStatus(status: SessionStatus, nowMs: Long) {
        val nowUp = isUp(status)
        if (up && !nowUp) {
            downSinceMs = nowMs
            exited = status == SessionStatus.HOST_EXITED
        }
        if (nowUp) exited = false
        up = nowUp
    }

    /**
     * Whether a key session to a ready PC may replace the current flow, which is [running] with
     * [status]. [samePc]: the ready PC is the one the flow is for. Another phone taking over and a
     * version mismatch are respected.
     */
    fun allows(status: SessionStatus, running: Boolean, samePc: Boolean, nowMs: Long): Boolean {
        if (running && isUp(status)) return false
        if (status == SessionStatus.REPLACED || status == SessionStatus.VERSION_MISMATCH) return false
        val wait = if (exited && !samePc) EXITED_GRACE_MS else TAKEOVER_MS
        return nowMs - downSinceMs >= wait
    }

    companion object {
        /** How long a session may stay down before a PC that is ready by key replaces it. */
        const val TAKEOVER_MS = 3000L
        /** The same after the PC said it exited, when the ready PC is another one: time to restart. */
        const val EXITED_GRACE_MS = 15_000L

        /** LIVE, or live but not hearing from the PC (a new HELLO is running). */
        fun isUp(status: SessionStatus): Boolean = status == SessionStatus.LIVE || status == SessionStatus.HOST_SILENT
    }
}
