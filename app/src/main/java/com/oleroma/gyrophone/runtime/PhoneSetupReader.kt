package com.oleroma.gyrophone.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.PowerManager
import com.oleroma.gyrophone.core.net.PhoneSetup
import java.util.Locale

/**
 * Reads the phone's setup for Diagnostics (docs/spec/phone-settings.md, "Diagnostics"): battery and charger,
 * temperature and thermal state, battery saver and Doze, the USB network adapter plugged into the phone (by its USB
 * maker and product ids) and the Wi-Fi standard. Only Diagnostics' own thread calls it, once a second; nothing here
 * runs on an input path. Results that change link numbers without anyone noticing (a different adapter, unplugging
 * the charger) become events in the log and go to the PC in PHONE_STATS.
 */
class PhoneSetupReader(context: Context) {
    private val app = context.applicationContext
    private val power = app.getSystemService(PowerManager::class.java)
    private val usb = app.getSystemService(UsbManager::class.java)

    /** The setup now, plus a line describing it. [wifiGeneration] 0 when unknown or not on Wi-Fi. */
    fun read(wifiGeneration: Int): Pair<PhoneSetup, String> {
        val b: Intent? = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 255
        val plugged = (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val saver = runCatching { power?.isPowerSaveMode == true }.getOrDefault(false)
        val doze = runCatching { power?.isDeviceIdleMode == true }.getOrDefault(false)
        val thermal = runCatching { power?.currentThermalStatus ?: 255 }.getOrDefault(255)
        val net = usbNetAdapter()
        var flags = 0
        if (plugged) flags = flags or PhoneSetup.CHARGING
        if (saver) flags = flags or PhoneSetup.BATTERY_SAVER
        if (doze) flags = flags or PhoneSetup.DOZE
        if (net != null) flags = flags or PhoneSetup.USB_NET
        val setup = PhoneSetup(
            batteryPct = pct.coerceIn(0, 255),
            flags = flags,
            tempDeciC = if (temp == Int.MIN_VALUE) Short.MIN_VALUE.toInt() else temp.coerceIn(-32767, 32767),
            thermal = thermal.coerceIn(0, 255),
            usbVendor = net?.vendorId ?: 0,
            usbProduct = net?.productId ?: 0,
            wifiStandard = wifiGeneration.coerceIn(0, 255),
        )
        return setup to describe(setup, net?.name)
    }

    /** A USB device on the phone that is a network adapter (Ethernet), or null. */
    private fun usbNetAdapter(): UsbNet? {
        val list = runCatching { usb?.deviceList?.values?.toList() }.getOrNull() ?: return null
        for (d in list) {
            var net = false
            for (i in 0 until d.interfaceCount) {
                val c = d.getInterface(i).interfaceClass
                // CDC (communications, ECM/NCM) or vendor-specific with a known network adapter maker.
                if (c == UsbConstants.USB_CLASS_COMM || c == UsbConstants.USB_CLASS_CDC_DATA) net = true
                if (c == UsbConstants.USB_CLASS_VENDOR_SPEC && d.vendorId in NET_VENDORS) net = true
            }
            if (net) return UsbNet(d.vendorId, d.productId, runCatching { d.productName }.getOrNull() ?: d.deviceName)
        }
        return null
    }

    private data class UsbNet(val vendorId: Int, val productId: Int, val name: String)

    companion object {
        /** Makers of common USB Ethernet chips: Realtek, ASIX, Microchip/SMSC, Apple, Davicom, Huawei, Samsung. */
        private val NET_VENDORS = setOf(0x0bda, 0x0b95, 0x0424, 0x05ac, 0x0a46, 0x12d1, 0x04e8)

        private val THERMAL = arrayOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")

        fun describe(s: PhoneSetup, usbName: String?): String = buildString {
            append("battery ")
            append(if (s.batteryPct == 255) "?" else "${s.batteryPct}%")
            append(if (s.flags and PhoneSetup.CHARGING != 0) " charging" else " on battery")
            if (s.tempDeciC != Short.MIN_VALUE.toInt()) append(String.format(Locale.US, " %.1f °C", s.tempDeciC / 10.0))
            append(" · heat ").append(THERMAL.getOrNull(s.thermal) ?: "?")
            if (s.flags and PhoneSetup.BATTERY_SAVER != 0) append(" · battery saver ON")
            if (s.flags and PhoneSetup.DOZE != 0) append(" · Doze ON")
            append(" · USB network adapter ")
            append(if (s.flags and PhoneSetup.USB_NET != 0) String.format(Locale.US, "%04x:%04x", s.usbVendor, s.usbProduct) + (usbName?.let { " ($it)" } ?: "") else "none")
            if (s.wifiStandard != 0) append(" · Wi-Fi ").append(s.wifiStandard)
        }
    }
}
