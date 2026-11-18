/*
 * The GyroPhone input driver's control interface, shared with the companion
 * (companion/src/GyroPhone.Windows/HidInputSink.cs keeps the same numbers).
 *
 * The companion opens the device interface below and, through DeviceIoControl:
 *   VERSION reads GPI_PROTOCOL_VERSION (a ULONG),
 *   PLUG    plugs the virtual mouse and keyboard, owned by that handle,
 *   SUBMIT  sends one or more input reports, back to back, each with its report id first
 *           (sizes in gpinput_reports.h); they go to Windows in that order,
 *   UNPLUG  removes the devices again; closing the handle removes them too.
 * Only one owner at a time: PLUG from a second handle fails with "busy".
 *
 * Its own GUID, hardware id and control codes: nothing is shared with the pad driver (driver/).
 */
#pragma once

/* {6E0B53F1-7C2D-4C6A-9E45-0A8D3B6F2C71} */
DEFINE_GUID(GUID_DEVINTERFACE_GYROPHONE_INPUT,
    0x6e0b53f1, 0x7c2d, 0x4c6a, 0x9e, 0x45, 0x0a, 0x8d, 0x3b, 0x6f, 0x2c, 0x71);

#define GPI_PROTOCOL_VERSION 1

/* CTL_CODE(FILE_DEVICE_UNKNOWN, 0x900 + n, METHOD_BUFFERED, FILE_ANY_ACCESS) = 0x222400 + 4n */
#define IOCTL_GPI_VERSION 0x222400
#define IOCTL_GPI_PLUG    0x222404
#define IOCTL_GPI_UNPLUG  0x222408
#define IOCTL_GPI_SUBMIT  0x22240C

/* The most bytes one SUBMIT takes. */
#define GPI_MAX_SUBMIT 4096
