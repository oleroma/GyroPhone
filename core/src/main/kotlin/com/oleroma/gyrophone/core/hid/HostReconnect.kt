package com.oleroma.gyrophone.core.hid

/**
 * When to reconnect the Bluetooth HID host after the link dropped unexpectedly: the last host
 * again after 1, 2, 4 and 8 s, then every 10 s, for about a minute ([DELAYS_MS]). A new connection, a user
 * connect or disconnect and stopping cancel the retries; a link the user ended is never retried.
 * Hosts are named by address. Not thread-safe; the caller serialises the calls.
 */
class HostReconnect {
    private var lastHost: String? = null
    private var attempt = 0
    private var userEnded = false

    /** Address being retried, or null when no retry is pending. */
    var retrying: String? = null
        private set

    /** Bumped on every schedule and cancel: only an attempt scheduled with the current value may run. */
    var generation = 0
        private set

    fun connected(address: String) {
        lastHost = address
        userEnded = false
        cancel()
    }

    /** The user disconnected: the drop that follows is not retried. */
    fun userDisconnect() {
        userEnded = true
        cancel()
    }

    /** The user picked a host to connect to: that connect replaces any retry. */
    fun userConnect() {
        userEnded = false
        cancel()
    }

    fun cancel() {
        retrying = null
        attempt = 0
        generation++
    }

    /**
     * The link to [address] dropped (or a retry of it failed). [canRetry] says the app is still
     * wanted and registered. Returns the delay before the next attempt in ms, or -1 for none.
     */
    fun disconnected(address: String, canRetry: Boolean): Long {
        if (address != lastHost) return -1 // some other host; a retry in progress goes on
        if (!canRetry || userEnded) {
            cancel()
            return -1
        }
        return next(address)
    }

    /** A retry could not even start (connect() refused): wait for the next one. */
    fun attemptRefused(): Long {
        val address = retrying ?: return -1
        return next(address)
    }

    private fun next(address: String): Long {
        val delay = DELAYS_MS.getOrNull(attempt)
        if (delay == null) {
            cancel()
            return -1
        }
        retrying = address
        attempt++
        generation++
        return delay
    }

    companion object {
        /** Wait before each attempt, from the drop or the previous failed attempt: about a minute in all. */
        val DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000, 10_000, 10_000, 10_000)
    }
}
