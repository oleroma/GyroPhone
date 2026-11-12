/*
 * What a wired (USB) Sony DualSense shows Windows: its IDs, its HID report descriptor and the
 * answers to the feature reports games and Steam read when they find one.
 *
 * Layout (report id first):
 *   input   0x01, 64 bytes: LX LY RX RY L2 R2, counter, 4 button bytes (D-pad hat in the low
 *           nibble, then the same bits as the DualShock 4), gyro, accelerometer, timestamp,
 *           touchpad, battery
 *   output  0x02, 48 bytes: rumble, lightbar, player LEDs, adaptive triggers (ignored for now)
 *   feature 0x05 calibration (41), 0x09 pairing info (20), 0x20 firmware info (64) and others,
 *           answered with zeros
 */
#pragma once

#define DS_VENDOR_ID  0x054C
#define DS_PRODUCT_ID 0x0CE6
#define DS_VERSION    0x0100

#define DS_INPUT_REPORT_ID   0x01
#define DS_INPUT_REPORT_SIZE 64
#define DS_OUTPUT_REPORT_SIZE 48

static const UCHAR DsReportDescriptor[] = {
    0x05, 0x01,             /* Usage Page (Generic Desktop) */
    0x09, 0x05,             /* Usage (Gamepad) */
    0xA1, 0x01,             /* Collection (Application) */
    0x85, 0x01,             /*   Report ID 1 */
    0x09, 0x30,             /*   Usage (X)   left stick x */
    0x09, 0x31,             /*   Usage (Y)   left stick y */
    0x09, 0x32,             /*   Usage (Z)   right stick x */
    0x09, 0x35,             /*   Usage (Rz)  right stick y */
    0x09, 0x33,             /*   Usage (Rx)  L2 */
    0x09, 0x34,             /*   Usage (Ry)  R2 */
    0x15, 0x00,             /*   Logical Minimum (0) */
    0x26, 0xFF, 0x00,       /*   Logical Maximum (255) */
    0x75, 0x08,             /*   Report Size (8) */
    0x95, 0x06,             /*   Report Count (6) */
    0x81, 0x02,             /*   Input (Data, Var, Abs) */
    0x06, 0x00, 0xFF,       /*   Usage Page (Vendor 0xFF00) */
    0x09, 0x20,             /*   Usage (0x20)  report counter */
    0x95, 0x01,             /*   Report Count (1) */
    0x81, 0x02,             /*   Input (Data, Var, Abs) */
    0x05, 0x01,             /*   Usage Page (Generic Desktop) */
    0x09, 0x39,             /*   Usage (Hat switch) */
    0x15, 0x00,             /*   Logical Minimum (0) */
    0x25, 0x07,             /*   Logical Maximum (7) */
    0x35, 0x00,             /*   Physical Minimum (0) */
    0x46, 0x3B, 0x01,       /*   Physical Maximum (315) */
    0x65, 0x14,             /*   Unit (degrees) */
    0x75, 0x04,             /*   Report Size (4) */
    0x95, 0x01,             /*   Report Count (1) */
    0x81, 0x42,             /*   Input (Data, Var, Abs, Null) */
    0x65, 0x00,             /*   Unit (none) */
    0x05, 0x09,             /*   Usage Page (Button) */
    0x19, 0x01,             /*   Usage Minimum (1) */
    0x29, 0x0F,             /*   Usage Maximum (15) */
    0x15, 0x00,             /*   Logical Minimum (0) */
    0x25, 0x01,             /*   Logical Maximum (1) */
    0x75, 0x01,             /*   Report Size (1) */
    0x95, 0x0F,             /*   Report Count (15) */
    0x81, 0x02,             /*   Input (Data, Var, Abs) */
    0x06, 0x00, 0xFF,       /*   Usage Page (Vendor 0xFF00) */
    0x09, 0x21,             /*   Usage (0x21) */
    0x95, 0x0D,             /*   Report Count (13) */
    0x81, 0x02,             /*   Input (Data, Var, Abs) */
    0x06, 0x00, 0xFF,       /*   Usage Page (Vendor 0xFF00) */
    0x09, 0x22,             /*   Usage (0x22)  motion, touchpad, battery */
    0x15, 0x00,             /*   Logical Minimum (0) */
    0x26, 0xFF, 0x00,       /*   Logical Maximum (255) */
    0x75, 0x08,             /*   Report Size (8) */
    0x95, 0x34,             /*   Report Count (52) */
    0x81, 0x02,             /*   Input (Data, Var, Abs) */
    0x85, 0x02, 0x09, 0x23, 0x95, 0x2F, 0x91, 0x02, /* Output 0x02, 47 bytes */
    0x85, 0x05, 0x09, 0x33, 0x95, 0x28, 0xB1, 0x02, /* Feature 0x05, 40 bytes: calibration */
    0x85, 0x08, 0x09, 0x34, 0x95, 0x2F, 0xB1, 0x02, /* Feature 0x08, 47 bytes */
    0x85, 0x09, 0x09, 0x24, 0x95, 0x13, 0xB1, 0x02, /* Feature 0x09, 19 bytes: pairing info */
    0x85, 0x0A, 0x09, 0x25, 0x95, 0x1A, 0xB1, 0x02, /* Feature 0x0A, 26 bytes */
    0x85, 0x20, 0x09, 0x26, 0x95, 0x3F, 0xB1, 0x02, /* Feature 0x20, 63 bytes: firmware info */
    0x85, 0x21, 0x09, 0x27, 0x95, 0x04, 0xB1, 0x02, /* Feature 0x21, 4 bytes */
    0x85, 0x22, 0x09, 0x40, 0x95, 0x3F, 0xB1, 0x02, /* Feature 0x22, 63 bytes */
    0x85, 0x80, 0x09, 0x28, 0x95, 0x3F, 0xB1, 0x02, /* Feature 0x80, 63 bytes */
    0x85, 0x81, 0x09, 0x29, 0x95, 0x3F, 0xB1, 0x02, /* Feature 0x81, 63 bytes */
    0x85, 0x82, 0x09, 0x2A, 0x95, 0x09, 0xB1, 0x02, /* Feature 0x82, 9 bytes */
    0x85, 0x83, 0x09, 0x2B, 0x95, 0x3F, 0xB1, 0x02, /* Feature 0x83, 63 bytes */
    0x85, 0x84, 0x09, 0x2C, 0x95, 0x3F, 0xB1, 0x02, /* Feature 0x84, 63 bytes */
    0x85, 0x85, 0x09, 0x2D, 0x95, 0x02, 0xB1, 0x02, /* Feature 0x85, 2 bytes */
    0x85, 0xA0, 0x09, 0x2E, 0x95, 0x01, 0xB1, 0x02, /* Feature 0xA0, 1 byte */
    0x85, 0xE0, 0x09, 0x2F, 0x95, 0x3F, 0xB1, 0x02, /* Feature 0xE0, 63 bytes */
    0x85, 0xF0, 0x09, 0x30, 0x95, 0x3F, 0xB1, 0x02, /* Feature 0xF0, 63 bytes */
    0x85, 0xF1, 0x09, 0x31, 0x95, 0x3F, 0xB1, 0x02, /* Feature 0xF1, 63 bytes */
    0x85, 0xF2, 0x09, 0x32, 0x95, 0x0F, 0xB1, 0x02, /* Feature 0xF2, 15 bytes */
    0x85, 0xF4, 0x09, 0x35, 0x95, 0x3F, 0xB1, 0x02, /* Feature 0xF4, 63 bytes */
    0x85, 0xF5, 0x09, 0x36, 0x95, 0x03, 0xB1, 0x02, /* Feature 0xF5, 3 bytes */
    0xC0                    /* End Collection */
};

/* Each feature report's size with its id byte, as the descriptor declares it; 0 = not declared. */
static ULONG DsFeatureSize(UCHAR id)
{
    switch (id) {
    case 0x05: return 41;
    case 0x08: return 48;
    case 0x09: return 20;
    case 0x0A: return 27;
    case 0x20: return 64;
    case 0x21: return 5;
    case 0x22: return 64;
    case 0x80: return 64;
    case 0x81: return 64;
    case 0x82: return 10;
    case 0x83: return 64;
    case 0x84: return 64;
    case 0x85: return 3;
    case 0xA0: return 2;
    case 0xE0: return 64;
    case 0xF0: return 64;
    case 0xF1: return 64;
    case 0xF2: return 16;
    case 0xF4: return 64;
    case 0xF5: return 4;
    default:   return 0;
    }
}

static void PutLe16(UCHAR *p, int v) { p[0] = (UCHAR)(v & 0xFF); p[1] = (UCHAR)((v >> 8) & 0xFF); }
static void PutLe32(UCHAR *p, ULONG v) { PutLe16(p, (int)(v & 0xFFFF)); PutLe16(p + 2, (int)(v >> 16)); }

/*
 * Fills feature report `id` (id byte first) into `buf`, which holds DsFeatureSize(id) bytes.
 *
 * 0x05 calibration: no gyro bias, and ranges that make one count exactly 1/16 deg/s
 *      ((plus - minus) / (speed_plus + speed_minus) = 17280 / 1080 = 16) and 1/8192 g, the units
 *      the companion's motion already uses.
 * 0x09 pairing info: a made-up, locally administered address (games use it as the serial).
 * 0x20 firmware info: a build date and versions new enough that SDL uses its newer rumble.
 */
static void DsFillFeature(UCHAR id, UCHAR *buf, ULONG size)
{
    RtlZeroMemory(buf, size);
    buf[0] = id;
    switch (id) {
    case 0x05: {
        int i;
        /* pitch, yaw, roll bias */
        for (i = 0; i < 3; i++) PutLe16(buf + 1 + 2 * i, 0);
        /* pitch +/-, yaw +/-, roll +/- */
        for (i = 0; i < 3; i++) {
            PutLe16(buf + 7 + 4 * i, 8640);
            PutLe16(buf + 9 + 4 * i, -8640);
        }
        PutLe16(buf + 19, 540);   /* speed plus */
        PutLe16(buf + 21, 540);   /* speed minus */
        /* accelerometer x, y, z: +1 g / -1 g */
        for (i = 0; i < 3; i++) {
            PutLe16(buf + 23 + 4 * i, 8192);
            PutLe16(buf + 25 + 4 * i, -8192);
        }
        break;
    }
    case 0x09: {
        static const UCHAR mac[6] = { 0x50, 0x48, 0x50, 0x59, 0x47, 0x02 }; /* 02:47:59:50:48:50, stored low byte first */
        RtlCopyMemory(buf + 1, mac, sizeof(mac));
        break;
    }
    case 0x20: {
        static const char date[] = "Jun 26 2024";
        static const char time[] = "10:44:24";
        RtlCopyMemory(buf + 1, date, 11);
        RtlCopyMemory(buf + 12, time, 8);
        PutLe16(buf + 20, 0x0004);           /* firmware type */
        PutLe16(buf + 22, 0x0004);           /* software series */
        PutLe32(buf + 24, 0x00000614);       /* hardware info */
        PutLe32(buf + 28, 0x0110002A);       /* firmware version */
        PutLe16(buf + 44, 0x0630);           /* update version */
        break;
    }
    default:
        break;
    }
}

/* The input report with everything at rest: sticks centred, D-pad and buttons released, no finger on the touchpad. */
static void DsNeutralReport(UCHAR *r)
{
    RtlZeroMemory(r, DS_INPUT_REPORT_SIZE);
    r[0] = DS_INPUT_REPORT_ID;
    r[1] = r[2] = r[3] = r[4] = 0x80;
    r[8] = 0x08;          /* hat: none */
    r[33] = 0x80;         /* touch point 1: not touching */
    r[37] = 0x80;         /* touch point 2: not touching */
    r[53] = 0x1A;         /* battery: on cable, charging, level 10 of 10 */
}
