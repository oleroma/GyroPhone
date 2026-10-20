package com.oleroma.gyrophone.core.hid

/**
 * Composite HID report descriptor exposed over the Bluetooth HID Device profile.
 *
 * One Bluetooth HID device carries three top-level collections, each with its own report ID:
 *  - [REPORT_ID_GAMEPAD]: DirectInput-style game pad (16 buttons, hat, two sticks, two triggers).
 *  - [REPORT_ID_KEYBOARD]: standard 6KRO keyboard.
 *  - [REPORT_ID_MOUSE]: 5-button mouse with 16-bit relative X/Y, a wheel and horizontal pan (AC Pan).
 *    Wheel and pan each sit in a logical collection with a Resolution Multiplier (feature report
 *    [REPORT_ID_MOUSE_FEATURE]); a host that enables it (Windows does) reads them in 1/120 notch,
 *    otherwise in whole notches.
 *
 * Windows enumerates the game pad as a generic HID (DirectInput) controller. Native XInput needs a
 * Microsoft VID/PID in the Bluetooth Device ID record, which Android apps cannot set; XInput is
 * provided by the Windows companion (see README) or by Steam Input on top of this game pad.
 */
object HidDescriptor {
    const val REPORT_ID_GAMEPAD: Int = 1
    const val REPORT_ID_KEYBOARD: Int = 2
    const val REPORT_ID_MOUSE: Int = 3
    const val REPORT_ID_MOUSE_FEATURE: Int = 4

    /** Payload sizes in bytes, excluding the report ID byte (Android's sendReport adds it). */
    const val GAMEPAD_REPORT_SIZE: Int = 15
    const val KEYBOARD_REPORT_SIZE: Int = 8
    const val MOUSE_REPORT_SIZE: Int = 9
    const val MOUSE_FEATURE_SIZE: Int = 1

    /** Resolution multiplier at its logical maximum = 120x. */
    const val RESOLUTION_MULTIPLIER: Int = 120

    /** Feature byte bits: wheel multiplier (bits 0-1) and pan multiplier (bits 2-3), each 0 or 1. */
    fun wheelHiRes(feature: Int): Boolean = feature and 0x03 != 0
    fun panHiRes(feature: Int): Boolean = (feature shr 2) and 0x03 != 0

    private val gamepad = intArrayOf(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x05,             // Usage (Game Pad)
        0xA1, 0x01,             // Collection (Application)
        0x85, REPORT_ID_GAMEPAD, //   Report ID
        // 16 buttons
        0x05, 0x09,             //   Usage Page (Button)
        0x19, 0x01,             //   Usage Minimum (1)
        0x29, 0x10,             //   Usage Maximum (16)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95, 0x10,             //   Report Count (16)
        0x81, 0x02,             //   Input (Data, Var, Abs)
        // Hat switch, 4 bits + 4 bits padding
        0x05, 0x01,             //   Usage Page (Generic Desktop)
        0x09, 0x39,             //   Usage (Hat switch)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x07,             //   Logical Maximum (7)
        0x35, 0x00,             //   Physical Minimum (0)
        0x46, 0x3B, 0x01,       //   Physical Maximum (315)
        0x65, 0x14,             //   Unit (Degrees)
        0x75, 0x04,             //   Report Size (4)
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x42,             //   Input (Data, Var, Abs, Null State)
        0x65, 0x00,             //   Unit (None)
        0x45, 0x00,             //   Physical Maximum (0)
        0x75, 0x04,             //   Report Size (4)
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x03,             //   Input (Const)
        // Sticks: X, Y (left), Z, Rz (right), signed 16-bit
        0x09, 0x30,             //   Usage (X)
        0x09, 0x31,             //   Usage (Y)
        0x09, 0x32,             //   Usage (Z)
        0x09, 0x35,             //   Usage (Rz)
        0x16, 0x01, 0x80,       //   Logical Minimum (-32767)
        0x26, 0xFF, 0x7F,       //   Logical Maximum (32767)
        0x75, 0x10,             //   Report Size (16)
        0x95, 0x04,             //   Report Count (4)
        0x81, 0x02,             //   Input (Data, Var, Abs)
        // Triggers: Rx (left), Ry (right), 0..1023
        0x09, 0x33,             //   Usage (Rx)
        0x09, 0x34,             //   Usage (Ry)
        0x15, 0x00,             //   Logical Minimum (0)
        0x26, 0xFF, 0x03,       //   Logical Maximum (1023)
        0x75, 0x10,             //   Report Size (16)
        0x95, 0x02,             //   Report Count (2)
        0x81, 0x02,             //   Input (Data, Var, Abs)
        0xC0,                   // End Collection
    )

    private val keyboard = intArrayOf(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x06,             // Usage (Keyboard)
        0xA1, 0x01,             // Collection (Application)
        0x85, REPORT_ID_KEYBOARD, //   Report ID
        0x05, 0x07,             //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0,             //   Usage Minimum (Left Control)
        0x29, 0xE7,             //   Usage Maximum (Right GUI)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95, 0x08,             //   Report Count (8)
        0x81, 0x02,             //   Input (Data, Var, Abs) modifiers
        0x75, 0x08,             //   Report Size (8)
        0x95, 0x01,             //   Report Count (1)
        0x81, 0x01,             //   Input (Const) reserved
        0x15, 0x00,             //   Logical Minimum (0)
        0x26, 0xFF, 0x00,       //   Logical Maximum (255)
        0x19, 0x00,             //   Usage Minimum (0)
        0x2A, 0xFF, 0x00,       //   Usage Maximum (255)
        0x75, 0x08,             //   Report Size (8)
        0x95, 0x06,             //   Report Count (6)
        0x81, 0x00,             //   Input (Data, Array)
        0xC0,                   // End Collection
    )

    private val mouse = intArrayOf(
        0x05, 0x01,             // Usage Page (Generic Desktop)
        0x09, 0x02,             // Usage (Mouse)
        0xA1, 0x01,             // Collection (Application)
        0x85, REPORT_ID_MOUSE,  //   Report ID
        0x09, 0x01,             //   Usage (Pointer)
        0xA1, 0x00,             //   Collection (Physical)
        0x05, 0x09,             //     Usage Page (Button)
        0x19, 0x01,             //     Usage Minimum (1)
        0x29, 0x05,             //     Usage Maximum (5)
        0x15, 0x00,             //     Logical Minimum (0)
        0x25, 0x01,             //     Logical Maximum (1)
        0x75, 0x01,             //     Report Size (1)
        0x95, 0x05,             //     Report Count (5)
        0x81, 0x02,             //     Input (Data, Var, Abs)
        0x75, 0x03,             //     Report Size (3)
        0x95, 0x01,             //     Report Count (1)
        0x81, 0x03,             //     Input (Const)
        0x05, 0x01,             //     Usage Page (Generic Desktop)
        0x09, 0x30,             //     Usage (X)
        0x09, 0x31,             //     Usage (Y)
        0x16, 0x01, 0x80,       //     Logical Minimum (-32767)
        0x26, 0xFF, 0x7F,       //     Logical Maximum (32767)
        0x75, 0x10,             //     Report Size (16)
        0x95, 0x02,             //     Report Count (2)
        0x81, 0x06,             //     Input (Data, Var, Rel)
        // Wheel with its resolution multiplier (1 -> 120 counts per notch).
        0xA1, 0x02,             //     Collection (Logical)
        0x85, REPORT_ID_MOUSE_FEATURE, //   Report ID (feature)
        0x09, 0x48,             //       Usage (Resolution Multiplier)
        0x15, 0x00,             //       Logical Minimum (0)
        0x25, 0x01,             //       Logical Maximum (1)
        0x35, 0x01,             //       Physical Minimum (1)
        0x45, RESOLUTION_MULTIPLIER, //  Physical Maximum (120)
        0x75, 0x02,             //       Report Size (2)
        0x95, 0x01,             //       Report Count (1)
        0xB1, 0x02,             //       Feature (Data, Var, Abs)
        0x85, REPORT_ID_MOUSE,  //       Report ID (input)
        0x35, 0x00,             //       Physical Minimum (0)
        0x45, 0x00,             //       Physical Maximum (0)
        0x09, 0x38,             //       Usage (Wheel)
        0x16, 0x01, 0x80,       //       Logical Minimum (-32767)
        0x26, 0xFF, 0x7F,       //       Logical Maximum (32767)
        0x75, 0x10,             //       Report Size (16)
        0x95, 0x01,             //       Report Count (1)
        0x81, 0x06,             //       Input (Data, Var, Rel)
        0xC0,                   //     End Collection
        // Horizontal pan with its own multiplier, then 4 bits of feature padding.
        0xA1, 0x02,             //     Collection (Logical)
        0x85, REPORT_ID_MOUSE_FEATURE, //   Report ID (feature)
        0x09, 0x48,             //       Usage (Resolution Multiplier)
        0x15, 0x00,             //       Logical Minimum (0)
        0x25, 0x01,             //       Logical Maximum (1)
        0x35, 0x01,             //       Physical Minimum (1)
        0x45, RESOLUTION_MULTIPLIER, //  Physical Maximum (120)
        0x75, 0x02,             //       Report Size (2)
        0x95, 0x01,             //       Report Count (1)
        0xB1, 0x02,             //       Feature (Data, Var, Abs)
        0x35, 0x00,             //       Physical Minimum (0)
        0x45, 0x00,             //       Physical Maximum (0)
        0x75, 0x04,             //       Report Size (4)
        0xB1, 0x03,             //       Feature (Const) padding
        0x85, REPORT_ID_MOUSE,  //       Report ID (input)
        0x05, 0x0C,             //       Usage Page (Consumer)
        0x0A, 0x38, 0x02,       //       Usage (AC Pan)
        0x16, 0x01, 0x80,       //       Logical Minimum (-32767)
        0x26, 0xFF, 0x7F,       //       Logical Maximum (32767)
        0x75, 0x10,             //       Report Size (16)
        0x95, 0x01,             //       Report Count (1)
        0x81, 0x06,             //       Input (Data, Var, Rel)
        0xC0,                   //     End Collection
        0xC0,                   //   End Collection
        0xC0,                   // End Collection
    )

    /** The full descriptor passed to BluetoothHidDeviceAppSdpSettings. */
    val bytes: ByteArray = (gamepad + keyboard + mouse).map { it.toByte() }.toByteArray()
}
