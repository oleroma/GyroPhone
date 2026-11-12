package com.oleroma.gyrophone.core.net

/**
 * When a PC that is ready by key may replace the phone's current session flow without a tap.
 * It counts how long the session has been down, not how old it is: a flow that never came up
 * gives way [TAKEOVER_MS] after it started, one that was up gives way [TAKEOVER_MS] after it went
 * down. A PC that said it exited (HOST_BYE EXIT, e.g. a restart as administrator) gets
 * [EXITED_GRACE_MS] before another PC takes its place. The link's first try at start (the last
 * code-paired PC, which may be an old one that no longer answers) gives way after only
 * [FIRST_TRY_MS] while it has never come up, so a PC ready by key is not kept waiting at every
 * app start. Pure; the link's rx thread owns it.
 */
class KeyTakeover {
    private var downSinceMs = 0L
    private var up = false
    private var exited = false
    private var firstTry = false

    /** A new session flow started at [nowMs]; [firstTry] when it is the link's automatic one at start. */
    fun started(nowMs: Long, firstTry: Boolean = false) {
        downSinceMs = nowMs
        up = false
        exited = false
        this.firstTry = firstTry
    }

    /** The current flow reported [status] at [nowMs]. */
    fun onStatus(status: SessionStatus, nowMs: Long) {
        val nowUp = isUp(status)
        if (up && !nowUp) {
            downSinceMs = nowMs
            exited = status == SessionStatus.HOST_EXITED
        }
        if (nowUp) {
            exited = false
            firstTry = false // it worked: from now on it is an ordinary session
        }
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
        val wait = when {
            exited && !samePc -> EXITED_GRACE_MS
            firstTry -> FIRST_TRY_MS
            else -> TAKEOVER_MS
        }
        return nowMs - downSinceMs >= wait
    }

    companion object {
        /** How long a session may stay down before a PC that is ready by key replaces it. */
        const val TAKEOVER_MS = 3000L
        /** The same after the PC said it exited, when the ready PC is another one: time to restart. */
        const val EXITED_GRACE_MS = 15_000L
        /** The same for the link's first try at start, while it has never come up: a HELLO answers well within it. */
        const val FIRST_TRY_MS = 400L

        /** LIVE, or live but not hearing from the PC (a new HELLO is running). */
        fun isUp(status: SessionStatus): Boolean = status == SessionStatus.LIVE || status == SessionStatus.HOST_SILENT
    }
}
