package com.oleroma.gyrophone.link

import android.content.Context
import android.os.PowerManager
import com.oleroma.gyrophone.core.net.HostStatusFlags2

/** The Android settings page a tip's button opens. */
enum class TipFix(val label: String) {
    WIFI("Wi-Fi"),
    BATTERY_SAVER("Battery saver"),
    APP_BATTERY("App battery"),
}

/** One thing about the phone's or the PC's setup that could be better for GP, and how to make it so. */
data class SetupTip(val text: String, val fix: TipFix? = null)

/** What the phone's own power state says (read by [PhonePower.read]). */
data class PhonePower(
    val batterySaver: Boolean,
    /** GP's battery use is Unrestricted (Android never pauses it in the background). */
    val unrestricted: Boolean,
    /** The phone is warm enough to slow itself down (thermal status moderate or worse). */
    val hot: Boolean,
) {
    companion object {
        fun read(context: Context): PhonePower {
            val pm = context.getSystemService(PowerManager::class.java) ?: return PhonePower(false, true, false)
            return PhonePower(
                batterySaver = runCatching { pm.isPowerSaveMode }.getOrDefault(false),
                unrestricted = runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true),
                hot = runCatching { pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE }.getOrDefault(false),
            )
        }
    }
}

/**
 * The Setup tips (docs/spec/phone-connection.md, "Setup tips"): only what could be better, never what is fine, and
 * never a cable for the phone (moving freely is the point). Link tips need a live Wi-Fi session ([LinkStats.hostRxPerSecond]);
 * the PC's own come from HOST_STATUS ([LinkStats.pcSetup]).
 */
object SetupTips {
    /** A phone Wi-Fi signal below this (dBm) is weak, as on the PC. */
    const val WEAK_DBM = -70
    /** A phone Wi-Fi link speed below this (Mbps) is slow. */
    const val SLOW_MBPS = 100
    /** Lost packets above this (%) get a tip. */
    const val LOSS_PERCENT = 2f

    fun of(stats: LinkStats, power: PhonePower): List<SetupTip> = buildList {
        val live = stats.hostRxPerSecond != null
        val pc = stats.pcSetup ?: 0
        fun pc(bit: Int) = pc and bit != 0
        val routerWifi = live && !stats.usb // not cable, Ethernet or the phone's hotspot
        if (routerWifi && pc(HostStatusFlags2.PC_WIFI)) {
            add(SetupTip("The PC is on Wi-Fi too, so each packet crosses the air twice. A cable from the PC to the router, " +
                "or the PC's own Mobile hotspot with the phone joined to it, cuts that out."))
        }
        if (live && stats.hotspot) {
            add(SetupTip("You're on the phone's hotspot. The PC's own Mobile hotspot ran smoother in tests: turn it on in " +
                "Windows (Settings, Network, Mobile hotspot) and join the phone to it."))
        }
        if (routerWifi) {
            val weak = stats.rssi?.let { it in -126 until WEAK_DBM } == true
            if (stats.band == 2) {
                add(SetupTip("The phone's Wi-Fi is on 2.4 GHz. The router's 5 GHz network, if it has one, has less lag.", TipFix.WIFI))
            }
            if (weak) {
                add(SetupTip("The phone's Wi-Fi signal is weak (${stats.rssi} dBm). Closer to the router, fewer packets arrive late.", TipFix.WIFI))
            } else if (stats.band != 2 && stats.linkMbps?.let { it in 1 until SLOW_MBPS } == true) {
                add(SetupTip("The phone's Wi-Fi link is slow (${stats.linkMbps} Mbps). Closer to the router, or a newer router, would help.", TipFix.WIFI))
            }
        }
        if (live && pc(HostStatusFlags2.PC_WIFI_24)) {
            add(SetupTip("The PC's Wi-Fi is on 2.4 GHz. A 5 GHz network has less lag."))
        }
        if (live && pc(HostStatusFlags2.PC_WIFI_WEAK)) {
            add(SetupTip("The PC's Wi-Fi signal is weak. Closer to the router would help."))
        }
        if (live && (stats.lossPercent ?: 0f) > LOSS_PERCENT) {
            add(SetupTip("The PC misses " + "%.1f".format(stats.lossPercent) + " % of packets. Closer to the router, or a lower " +
                "report rate, would help."))
        }
        if (power.batterySaver) {
            add(SetupTip("Battery saver is on. It can slow the app and its Wi-Fi: turn it off while you play.", TipFix.BATTERY_SAVER))
        }
        if (live && pc(HostStatusFlags2.PC_ON_BATTERY)) {
            add(SetupTip("The PC runs on battery. Plugged in, Windows gives its Wi-Fi and processor full power."))
        }
        if (live && pc(HostStatusFlags2.PC_POWER_SAVING)) {
            add(SetupTip("The PC is saving power (energy saver or \"Best power efficiency\"). Set Windows' power mode to " +
                "Best performance while you play."))
        }
        if (!power.unrestricted) {
            add(SetupTip("Android may pause GP while it's in the background. Set its battery use to Unrestricted " +
                "(App battery, then Battery usage).", TipFix.APP_BATTERY))
        }
        if (power.hot) add(SetupTip("The phone is hot and may slow itself down. A cooler spot, or no case, helps."))
        if (stats.vigemOk == false) {
            add(SetupTip("The PC has no gamepad driver (ViGEmBus): pad input is dropped there, keyboard and mouse still work."))
        }
    }
}
