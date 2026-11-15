/*
 * What the virtual mouse and keyboard show Windows: one HID device with three top-level
 * collections, so Windows' own drivers take them as a mouse (mouhid), a keyboard (kbdhid) and
 * media keys (hidserv). The companion builds the input reports
 * (companion/src/GyroPhone.Protocol/Mapping/HidInputReports.cs keeps the same layout).
 *
 * Report 1, mouse (10 bytes): id, buttons (bit 0 left, 1 right, 2 middle, 3 back, 4 forward),
 *   X and Y (int16, relative counts), wheel and horizontal wheel (int16, in 1/120 notch).
 *   The wheels carry a resolution multiplier (feature report 1): Windows sets it to 120 at start,
 *   so one count is 1/120 notch, as SendInput's WHEEL_DELTA. Until it does, the driver adds the
 *   1/120 steps up and sends whole notches (gpinput driver.c, GpiWheel).
 * Report 2, keyboard (30 bytes): id, one bit per keyboard usage 0x00..0xE7 (modifiers included,
 *   0xE0..0xE7), the same bitmap as STATE's keys. Any number of keys at once. Output report 2
 *   (LEDs) is accepted and ignored.
 * Report 3, media keys (2 bytes): id, bit 0 play/pause, 1 previous, 2 next, 3 mute, 4 volume up,
 *   5 volume down.
 */
#pragma once

#define GPI_VENDOR_ID  0x1209  /* pid.codes, the open-source vendor id */
#define GPI_PRODUCT_ID 0x6770  /* our own; companion's raw input listeners skip VID_1209&PID_6770 */
#define GPI_VERSION    0x0100

#define GPI_REPORT_MOUSE    1
#define GPI_REPORT_KEYBOARD 2
#define GPI_REPORT_MEDIA    3

#define GPI_MOUSE_SIZE    10
#define GPI_KEYBOARD_SIZE 30
#define GPI_MEDIA_SIZE    2
/* Feature report 1: id, bits 0..1 wheel multiplier (0 = 1, 1 = 120), bits 2..3 horizontal. */
#define GPI_MULTIPLIER_SIZE 2

static const UCHAR GpiReportDescriptor[] = {
    /* ---- Mouse ---- */
    0x05, 0x01,             /* Usage Page (Generic Desktop) */
    0x09, 0x02,             /* Usage (Mouse) */
    0xA1, 0x01,             /* Collection (Application) */
    0x85, GPI_REPORT_MOUSE, /*   Report ID (1) */
    0x09, 0x01,             /*   Usage (Pointer) */
    0xA1, 0x00,             /*   Collection (Physical) */
    0x05, 0x09,             /*     Usage Page (Button) */
    0x19, 0x01,             /*     Usage Minimum (1) */
    0x29, 0x05,             /*     Usage Maximum (5) */
    0x15, 0x00,             /*     Logical Minimum (0) */
    0x25, 0x01,             /*     Logical Maximum (1) */
    0x95, 0x05,             /*     Report Count (5) */
    0x75, 0x01,             /*     Report Size (1) */
    0x81, 0x02,             /*     Input (Data, Var, Abs) */
    0x95, 0x01,             /*     Report Count (1) */
    0x75, 0x03,             /*     Report Size (3) */
    0x81, 0x03,             /*     Input (Const) */
    0x05, 0x01,             /*     Usage Page (Generic Desktop) */
    0x09, 0x30,             /*     Usage (X) */
    0x09, 0x31,             /*     Usage (Y) */
    0x16, 0x01, 0x80,       /*     Logical Minimum (-32767) */
    0x26, 0xFF, 0x7F,       /*     Logical Maximum (32767) */
    0x75, 0x10,             /*     Report Size (16) */
    0x95, 0x02,             /*     Report Count (2) */
    0x81, 0x06,             /*     Input (Data, Var, Rel) */
    0xA1, 0x02,             /*     Collection (Logical) */
    0x09, 0x48,             /*       Usage (Resolution Multiplier) */
    0x15, 0x00,             /*       Logical Minimum (0) */
    0x25, 0x01,             /*       Logical Maximum (1) */
    0x35, 0x01,             /*       Physical Minimum (1) */
    0x45, 0x78,             /*       Physical Maximum (120) */
    0x75, 0x02,             /*       Report Size (2) */
    0x95, 0x01,             /*       Report Count (1) */
    0xB1, 0x02,             /*       Feature (Data, Var, Abs) */
    0x35, 0x00,             /*       Physical Minimum (0) */
    0x45, 0x00,             /*       Physical Maximum (0) */
    0x09, 0x38,             /*       Usage (Wheel) */
    0x16, 0x01, 0x80,       /*       Logical Minimum (-32767) */
    0x26, 0xFF, 0x7F,       /*       Logical Maximum (32767) */
    0x75, 0x10,             /*       Report Size (16) */
    0x95, 0x01,             /*       Report Count (1) */
    0x81, 0x06,             /*       Input (Data, Var, Rel) */
    0xC0,                   /*     End Collection */
    0xA1, 0x02,             /*     Collection (Logical) */
    0x09, 0x48,             /*       Usage (Resolution Multiplier) */
    0x15, 0x00,             /*       Logical Minimum (0) */
    0x25, 0x01,             /*       Logical Maximum (1) */
    0x35, 0x01,             /*       Physical Minimum (1) */
    0x45, 0x78,             /*       Physical Maximum (120) */
    0x75, 0x02,             /*       Report Size (2) */
    0x95, 0x01,             /*       Report Count (1) */
    0xB1, 0x02,             /*       Feature (Data, Var, Abs) */
    0x35, 0x00,             /*       Physical Minimum (0) */
    0x45, 0x00,             /*       Physical Maximum (0) */
    0x05, 0x0C,             /*       Usage Page (Consumer) */
    0x0A, 0x38, 0x02,       /*       Usage (AC Pan) */
    0x16, 0x01, 0x80,       /*       Logical Minimum (-32767) */
    0x26, 0xFF, 0x7F,       /*       Logical Maximum (32767) */
    0x75, 0x10,             /*       Report Size (16) */
    0x95, 0x01,             /*       Report Count (1) */
    0x81, 0x06,             /*       Input (Data, Var, Rel) */
    0xC0,                   /*     End Collection */
    0x75, 0x04,             /*     Report Size (4) */
    0x95, 0x01,             /*     Report Count (1) */
    0xB1, 0x03,             /*     Feature (Const): pads the multipliers to a byte */
    0xC0,                   /*   End Collection */
    0xC0,                   /* End Collection */

    /* ---- Keyboard ---- */
    0x05, 0x01,             /* Usage Page (Generic Desktop) */
    0x09, 0x06,             /* Usage (Keyboard) */
    0xA1, 0x01,             /* Collection (Application) */
    0x85, GPI_REPORT_KEYBOARD, /* Report ID (2) */
    0x05, 0x07,             /*   Usage Page (Keyboard) */
    0x19, 0x00,             /*   Usage Minimum (0) */
    0x29, 0xE7,             /*   Usage Maximum (0xE7, Right GUI) */
    0x15, 0x00,             /*   Logical Minimum (0) */
    0x25, 0x01,             /*   Logical Maximum (1) */
    0x75, 0x01,             /*   Report Size (1) */
    0x96, 0xE8, 0x00,       /*   Report Count (232) */
    0x81, 0x02,             /*   Input (Data, Var, Abs): one bit per key */
    0x05, 0x08,             /*   Usage Page (LEDs) */
    0x19, 0x01,             /*   Usage Minimum (Num Lock) */
    0x29, 0x05,             /*   Usage Maximum (Kana) */
    0x95, 0x05,             /*   Report Count (5) */
    0x75, 0x01,             /*   Report Size (1) */
    0x91, 0x02,             /*   Output (Data, Var, Abs) */
    0x95, 0x01,             /*   Report Count (1) */
    0x75, 0x03,             /*   Report Size (3) */
    0x91, 0x03,             /*   Output (Const) */
    0xC0,                   /* End Collection */

    /* ---- Media keys ---- */
    0x05, 0x0C,             /* Usage Page (Consumer) */
    0x09, 0x01,             /* Usage (Consumer Control) */
    0xA1, 0x01,             /* Collection (Application) */
    0x85, GPI_REPORT_MEDIA, /*   Report ID (3) */
    0x09, 0xCD,             /*   Usage (Play/Pause) */
    0x09, 0xB6,             /*   Usage (Scan Previous Track) */
    0x09, 0xB5,             /*   Usage (Scan Next Track) */
    0x09, 0xE2,             /*   Usage (Mute) */
    0x09, 0xE9,             /*   Usage (Volume Increment) */
    0x09, 0xEA,             /*   Usage (Volume Decrement) */
    0x15, 0x00,             /*   Logical Minimum (0) */
    0x25, 0x01,             /*   Logical Maximum (1) */
    0x75, 0x01,             /*   Report Size (1) */
    0x95, 0x06,             /*   Report Count (6) */
    0x81, 0x02,             /*   Input (Data, Var, Abs) */
    0x95, 0x02,             /*   Report Count (2) */
    0x81, 0x03,             /*   Input (Const) */
    0xC0,                   /* End Collection */
};

/* The size of an input report by its id; 0 for an id there is none for. */
static __inline ULONG GpiReportSize(UCHAR id)
{
    switch (id) {
    case GPI_REPORT_MOUSE: return GPI_MOUSE_SIZE;
    case GPI_REPORT_KEYBOARD: return GPI_KEYBOARD_SIZE;
    case GPI_REPORT_MEDIA: return GPI_MEDIA_SIZE;
    default: return 0;
    }
}
