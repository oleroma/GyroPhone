/*
 * The GyroPhone pad driver's control interface, shared with the companion
 * (companion/src/GyroPhone.Windows/DualSensePadSink.cs keeps the same numbers).
 *
 * The companion opens the device interface below and, through DeviceIoControl:
 *   PLUG    plugs one virtual DualSense (USB) owned by that handle,
 *   SUBMIT  sends its 64-byte input report (report id 0x01 first),
 *   UNPLUG  removes it again; closing the handle removes it too,
 *   VERSION reads GP_PROTOCOL_VERSION (a ULONG).
 * Only one pad at a time: PLUG from a second handle fails with "busy".
 */
#pragma once

/* {9B7491BC-0FE9-49AA-BA4A-D5C8040BE226} */
DEFINE_GUID(GUID_DEVINTERFACE_GYROPHONE_PAD,
    0x9b7491bc, 0x0fe9, 0x49aa, 0xba, 0x4a, 0xd5, 0xc8, 0x04, 0x0b, 0xe2, 0x26);

#define GP_PROTOCOL_VERSION 1

/* CTL_CODE(FILE_DEVICE_UNKNOWN, 0x800 + n, METHOD_BUFFERED, FILE_ANY_ACCESS) = 0x222000 + 4n */
#define IOCTL_GP_VERSION 0x222000
#define IOCTL_GP_PLUG    0x222004
#define IOCTL_GP_UNPLUG  0x222008
#define IOCTL_GP_SUBMIT  0x22200C

/* PLUG's input: which pad. Only the DualSense for now. */
#define GP_PAD_DUALSENSE 1

typedef struct _GP_PLUG_IN {
    ULONG Version; /* GP_PROTOCOL_VERSION */
    ULONG Pad;     /* GP_PAD_DUALSENSE */
} GP_PLUG_IN;
