package com.oleroma.gyrophone.link

import kotlinx.coroutines.flow.StateFlow

/**
 * One way of delivering the mapping engine's output to the PC. Only one link runs at a time,
 * so only one thread ever calls `MappingEngine.snapshot()`.
 */
interface OutputLink {
    val kind: TransportKind
    val state: StateFlow<LinkState>
    fun start()
    fun stop()
    fun stats(): LinkStats
}

/** Link status shown in the Connect tab and the service notification. */
sealed interface LinkState {
    data object Off : LinkState
    /** Running and waiting for (or looking for) a PC. */
    data object Searching : LinkState
    /** Pairing with [host]; [sas] is the 6-digit code once known. */
    data class Pairing(val sas: String?, val host: String) : LinkState
    data class Connecting(val name: String) : LinkState
    /** [detail] is transport specific, e.g. "5 GHz · 1201 Mbps · −51 dBm". */
    data class Connected(val name: String, val detail: String) : LinkState
    /** The session is up but the PC stopped answering; reconnecting. */
    data class HostSilent(val name: String) : LinkState
    data class Failed(val reason: String) : LinkState
}

/**
 * Once-a-second link counters. The nullable fields only exist for Wi-Fi: what the PC reports
 * back (HOST_STATUS), the round trip, and the phone's Wi-Fi radio.
 */
data class LinkStats(
    val sentPerSecond: Int,
    val failedPerSecond: Int,
    val hostRxPerSecond: Int? = null,
    val lossPercent: Float? = null,
    val latP50Ms: Float? = null,
    val latP99Ms: Float? = null,
    val rttMs: Float? = null,
    /** 2, 5 or 6 (GHz); 0 = unknown. */
    val band: Int? = null,
    val rssi: Int? = null,
    val linkMbps: Int? = null,
    val lowLatencyLock: Boolean? = null,
    /** Sending over USB (the Wi-Fi fields are then not the link's). */
    val usb: Boolean = false,
    /** With [usb]: over the direct cable (accessory mode), not tethering. */
    val usbDirect: Boolean = false,
    /** False when the PC has no virtual gamepad driver (pad input is dropped there). */
    val vigemOk: Boolean? = null,
)
