/*
 * GyroPhone input driver: a user-mode (UMDF 2) driver that puts a virtual USB mouse and keyboard
 * (with media keys) on Windows' built-in Virtual HID Framework (VHF). The companion sends its
 * keyboard and mouse input here instead of calling SendInput, so Windows takes it like a real
 * mouse's and keyboard's: no "injected" mark, and the companion never waits on the input checks
 * Windows runs for SendInput. Nothing of ours runs in the kernel: VHF (vhf.sys) and the HID class
 * driver are Windows' own; this DLL runs in Windows' driver host process.
 *
 * The device is root-enumerated (Root\GyroPhoneInput, made by install.ps1). It exposes a device
 * interface (gpinput_ioctl.h) through which the companion plugs the mouse and keyboard, sends their
 * input reports and unplugs them. They belong to the handle that plugged them and go away when that
 * handle closes, so a crashed companion never leaves a key held down.
 *
 * Latency: each SUBMIT goes straight to VhfReadReportSubmit on the calling request, under one lock,
 * with no queue or thread of ours in between. One SUBMIT carries every report of one hand-off.
 *
 * A separate driver from the pad driver (driver/), installed and removed on its own. Its structure
 * follows that driver, which follows Microsoft's documentation and samples for VHF in UMDF.
 */
#include <windows.h>
#include <wdf.h>
#include <hidclass.h>
#include <vhf.h>
#include <initguid.h>

#include "gpinput_ioctl.h"
#include "gpinput_reports.h"

/* Status codes this driver returns, in case the user-mode headers leave any out. */
#ifndef STATUS_DEVICE_BUSY
#define STATUS_DEVICE_BUSY ((NTSTATUS)0x80000011L)
#endif
#ifndef STATUS_INVALID_HANDLE
#define STATUS_INVALID_HANDLE ((NTSTATUS)0xC0000008L)
#endif
#ifndef STATUS_INVALID_PARAMETER
#define STATUS_INVALID_PARAMETER ((NTSTATUS)0xC000000DL)
#endif
#ifndef STATUS_INVALID_DEVICE_REQUEST
#define STATUS_INVALID_DEVICE_REQUEST ((NTSTATUS)0xC0000010L)
#endif
#ifndef STATUS_REVISION_MISMATCH
#define STATUS_REVISION_MISMATCH ((NTSTATUS)0xC0000059L)
#endif
#ifndef STATUS_DEVICE_NOT_CONNECTED
#define STATUS_DEVICE_NOT_CONNECTED ((NTSTATUS)0xC000009DL)
#endif

#define GPI_NOTCH 120

/*
 * The mouse and keyboard's own container: Windows groups devices into physical "boxes" by container
 * id, and a device without one of its own counts as part of the PC. Windows then takes our mouse
 * for the laptop's built-in pointing device and drops its left clicks while any key is held (its
 * guard against palms brushing a touchpad while typing). With their own container they count as a
 * separate box, like a USB mouse plugged in, and every click gets through.
 * {B7D1E5A2-3C84-4F19-A6E0-5D2C8B91F473}
 */
static const GUID GpiContainerId =
    { 0xb7d1e5a2, 0x3c84, 0x4f19, { 0xa6, 0xe0, 0x5d, 0x2c, 0x8b, 0x91, 0xf4, 0x73 } };

typedef struct _GPI_CONTEXT {
    SRWLOCK Lock;
    VHFHANDLE Vhf;           /* the plugged mouse and keyboard, NULL when none */
    WDFIOTARGET VhfTarget;   /* the file handle VHF needs in user mode */
    WDFFILEOBJECT Owner;     /* the handle that plugged them */
    UCHAR Multiplier;        /* feature report 1's byte, as Windows set it */
    LONG WheelRest[2];       /* 1/120 steps not yet sent while a multiplier is 1 */
    UCHAR Mouse[GPI_MOUSE_SIZE];       /* the last report of each kind, for GetInputReport */
    UCHAR Keyboard[GPI_KEYBOARD_SIZE];
    UCHAR Media[GPI_MEDIA_SIZE];
} GPI_CONTEXT, *PGPI_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(GPI_CONTEXT, GpiGetContext)

DRIVER_INITIALIZE DriverEntry;
EVT_WDF_DRIVER_DEVICE_ADD GpiEvtDeviceAdd;
EVT_WDF_OBJECT_CONTEXT_CLEANUP GpiEvtDeviceCleanup;
EVT_WDF_FILE_CLEANUP GpiEvtFileCleanup;
EVT_WDF_IO_QUEUE_IO_DEVICE_CONTROL GpiEvtIoDeviceControl;
EVT_VHF_ASYNC_OPERATION GpiEvtGetFeature;
EVT_VHF_ASYNC_OPERATION GpiEvtSetFeature;
EVT_VHF_ASYNC_OPERATION GpiEvtWriteReport;
EVT_VHF_ASYNC_OPERATION GpiEvtGetInputReport;

static void GpiResetReports(PGPI_CONTEXT ctx)
{
    RtlZeroMemory(ctx->Mouse, sizeof(ctx->Mouse));
    RtlZeroMemory(ctx->Keyboard, sizeof(ctx->Keyboard));
    RtlZeroMemory(ctx->Media, sizeof(ctx->Media));
    ctx->Mouse[0] = GPI_REPORT_MOUSE;
    ctx->Keyboard[0] = GPI_REPORT_KEYBOARD;
    ctx->Media[0] = GPI_REPORT_MEDIA;
    ctx->Multiplier = 0;
    ctx->WheelRest[0] = ctx->WheelRest[1] = 0;
}

NTSTATUS DriverEntry(_In_ PDRIVER_OBJECT DriverObject, _In_ PUNICODE_STRING RegistryPath)
{
    WDF_DRIVER_CONFIG config;
    WDF_DRIVER_CONFIG_INIT(&config, GpiEvtDeviceAdd);
    return WdfDriverCreate(DriverObject, RegistryPath, WDF_NO_OBJECT_ATTRIBUTES, &config, WDF_NO_HANDLE);
}

NTSTATUS GpiEvtDeviceAdd(_In_ WDFDRIVER Driver, _Inout_ PWDFDEVICE_INIT DeviceInit)
{
    WDF_FILEOBJECT_CONFIG fileConfig;
    WDF_OBJECT_ATTRIBUTES attributes;
    WDF_IO_QUEUE_CONFIG queueConfig;
    WDFDEVICE device;
    PGPI_CONTEXT ctx;
    NTSTATUS status;

    UNREFERENCED_PARAMETER(Driver);

    WDF_FILEOBJECT_CONFIG_INIT(&fileConfig, WDF_NO_EVENT_CALLBACK, WDF_NO_EVENT_CALLBACK, GpiEvtFileCleanup);
    WdfDeviceInitSetFileObjectConfig(DeviceInit, &fileConfig, WDF_NO_OBJECT_ATTRIBUTES);

    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, GPI_CONTEXT);
    attributes.EvtCleanupCallback = GpiEvtDeviceCleanup;
    status = WdfDeviceCreate(&DeviceInit, &attributes, &device);
    if (!NT_SUCCESS(status)) return status;

    /* Removable, so Windows gives this device (and the mouse and keyboard under it, which inherit
       it) a container of its own instead of the PC's: Windows ignores a container id set on the
       mouse and keyboard while their parent counts as part of the PC (see GpiContainerId).
       SurpriseRemovalOK keeps it off "Safely Remove Hardware". */
    {
        WDF_DEVICE_PNP_CAPABILITIES caps;
        WDF_DEVICE_PNP_CAPABILITIES_INIT(&caps);
        caps.Removable = WdfTrue;
        caps.SurpriseRemovalOK = WdfTrue;
        WdfDeviceSetPnpCapabilities(device, &caps);
    }

    ctx = GpiGetContext(device);
    InitializeSRWLock(&ctx->Lock);
    GpiResetReports(ctx);

    status = WdfDeviceCreateDeviceInterface(device, &GUID_DEVINTERFACE_GYROPHONE_INPUT, NULL);
    if (!NT_SUCCESS(status)) return status;

    /* One request at a time: PLUG, SUBMIT and UNPLUG never overlap, and reports keep their order. */
    WDF_IO_QUEUE_CONFIG_INIT_DEFAULT_QUEUE(&queueConfig, WdfIoQueueDispatchSequential);
    queueConfig.EvtIoDeviceControl = GpiEvtIoDeviceControl;
    return WdfIoQueueCreate(device, &queueConfig, WDF_NO_OBJECT_ATTRIBUTES, WDF_NO_HANDLE);
}

/* Removes the mouse and keyboard, if plugged (by `owner`, or by anyone when owner is NULL).
   Windows lets go of every key and button of a device that goes away. */
static void GpiUnplug(PGPI_CONTEXT ctx, WDFFILEOBJECT owner)
{
    VHFHANDLE vhf;
    WDFIOTARGET target;

    AcquireSRWLockExclusive(&ctx->Lock);
    if (ctx->Vhf == NULL || (owner != NULL && ctx->Owner != owner)) {
        ReleaseSRWLockExclusive(&ctx->Lock);
        return;
    }
    vhf = ctx->Vhf;
    target = ctx->VhfTarget;
    ctx->Vhf = NULL;
    ctx->VhfTarget = NULL;
    ctx->Owner = NULL;
    GpiResetReports(ctx);
    ReleaseSRWLockExclusive(&ctx->Lock);

    /* Outside the lock: deleting waits for VHF's callbacks, which take the lock. */
    VhfDelete(vhf, TRUE);
    if (target != NULL) {
        WdfIoTargetClose(target);
        WdfObjectDelete(target);
    }
}

static NTSTATUS GpiPlug(WDFDEVICE device, PGPI_CONTEXT ctx, WDFFILEOBJECT owner, ULONG version)
{
    WDF_OBJECT_ATTRIBUTES attributes;
    WDF_IO_TARGET_OPEN_PARAMS openParams;
    WDFIOTARGET target = NULL;
    VHF_CONFIG config;
    VHFHANDLE vhf = NULL;
    HANDLE file;
    BOOLEAN plugged, mine;
    NTSTATUS status;

    if (version != GPI_PROTOCOL_VERSION) return STATUS_REVISION_MISMATCH;

    AcquireSRWLockShared(&ctx->Lock);
    plugged = ctx->Vhf != NULL;
    mine = plugged && ctx->Owner == owner;
    ReleaseSRWLockShared(&ctx->Lock);
    if (mine) return STATUS_SUCCESS;           /* already plugged by this handle */
    if (plugged) return STATUS_DEVICE_BUSY;    /* another program has them */

    /* In user mode VHF talks to its kernel half through a file handle on our own device stack. */
    WDF_OBJECT_ATTRIBUTES_INIT(&attributes);
    attributes.ParentObject = device;
    status = WdfIoTargetCreate(device, &attributes, &target);
    if (!NT_SUCCESS(status)) return status;
    WDF_IO_TARGET_OPEN_PARAMS_INIT_OPEN_BY_FILE(&openParams, NULL);
    status = WdfIoTargetOpen(target, &openParams);
    if (!NT_SUCCESS(status)) goto fail;
    file = WdfIoTargetWdmGetTargetFileHandle(target);
    if (file == NULL || file == INVALID_HANDLE_VALUE) {
        status = STATUS_INVALID_HANDLE;
        goto fail;
    }

    VHF_CONFIG_INIT(&config, file, (USHORT)sizeof(GpiReportDescriptor), (PUCHAR)GpiReportDescriptor);
    config.VendorID = GPI_VENDOR_ID;
    config.ProductID = GPI_PRODUCT_ID;
    config.VersionNumber = GPI_VERSION;
    config.ContainerID = GpiContainerId;
    config.VhfClientContext = ctx;
    config.EvtVhfAsyncOperationGetFeature = GpiEvtGetFeature;
    config.EvtVhfAsyncOperationSetFeature = GpiEvtSetFeature;
    config.EvtVhfAsyncOperationWriteReport = GpiEvtWriteReport;
    config.EvtVhfAsyncOperationGetInputReport = GpiEvtGetInputReport;
    status = VhfCreate(&config, &vhf);
    if (!NT_SUCCESS(status)) {
        vhf = NULL;
        goto fail;
    }

    AcquireSRWLockExclusive(&ctx->Lock);
    ctx->Vhf = vhf;
    ctx->VhfTarget = target;
    ctx->Owner = owner;
    GpiResetReports(ctx);
    ReleaseSRWLockExclusive(&ctx->Lock);

    status = VhfStart(vhf);
    if (!NT_SUCCESS(status)) {
        GpiUnplug(ctx, owner);
        return status;
    }
    return STATUS_SUCCESS;

fail:
    if (vhf != NULL) VhfDelete(vhf, TRUE);
    WdfIoTargetClose(target);
    WdfObjectDelete(target);
    return status;
}

static SHORT GpiGetShort(const UCHAR *p) { return (SHORT)(p[0] | (p[1] << 8)); }
static void GpiPutShort(UCHAR *p, LONG v) { p[0] = (UCHAR)(v & 0xFF); p[1] = (UCHAR)((v >> 8) & 0xFF); }

/* A wheel's 1/120 steps for Windows: as they are once Windows set the multiplier to 120, else
   added up and sent as whole notches. Called under the lock. */
static void GpiWheel(PGPI_CONTEXT ctx, UCHAR *field, int axis)
{
    LONG v = GpiGetShort(field);
    if ((ctx->Multiplier >> (2 * axis)) & 0x3) {
        ctx->WheelRest[axis] = 0;
        return;
    }
    v += ctx->WheelRest[axis];
    ctx->WheelRest[axis] = v % GPI_NOTCH;
    GpiPutShort(field, v / GPI_NOTCH);
}

/* Sends the SUBMIT's reports to Windows, in order. All are checked before any is sent. */
static NTSTATUS GpiSubmit(PGPI_CONTEXT ctx, WDFFILEOBJECT owner, const UCHAR *data, size_t length)
{
    HID_XFER_PACKET packet;
    NTSTATUS status = STATUS_SUCCESS;
    UCHAR *slot;
    size_t at;
    ULONG size = 0;

    if (length == 0 || length > GPI_MAX_SUBMIT) return STATUS_INVALID_PARAMETER;
    for (at = 0; at < length; at += size) {
        size = GpiReportSize(data[at]);
        if (size == 0 || at + size > length) return STATUS_INVALID_PARAMETER;
    }

    AcquireSRWLockExclusive(&ctx->Lock);
    if (ctx->Vhf == NULL || ctx->Owner != owner) {
        ReleaseSRWLockExclusive(&ctx->Lock);
        return STATUS_DEVICE_NOT_CONNECTED;
    }
    for (at = 0; at < length && NT_SUCCESS(status); at += size) {
        size = GpiReportSize(data[at]);
        switch (data[at]) {
        case GPI_REPORT_MOUSE: slot = ctx->Mouse; break;
        case GPI_REPORT_KEYBOARD: slot = ctx->Keyboard; break;
        default: slot = ctx->Media; break;
        }
        RtlCopyMemory(slot, data + at, size);
        if (data[at] == GPI_REPORT_MOUSE) {
            GpiWheel(ctx, slot + 6, 0);
            GpiWheel(ctx, slot + 8, 1);
        }
        packet.reportBuffer = slot;
        packet.reportBufferLen = size;
        packet.reportId = slot[0];
        /* VHF keeps its own copy until the HID class driver reads it. */
        status = VhfReadReportSubmit(ctx->Vhf, &packet);
    }
    /* The last mouse report's move and wheels are spent: GetInputReport shows only its buttons. */
    RtlZeroMemory(ctx->Mouse + 2, GPI_MOUSE_SIZE - 2);
    ReleaseSRWLockExclusive(&ctx->Lock);
    return status;
}

VOID GpiEvtIoDeviceControl(_In_ WDFQUEUE Queue, _In_ WDFREQUEST Request, _In_ size_t OutputBufferLength,
    _In_ size_t InputBufferLength, _In_ ULONG IoControlCode)
{
    WDFDEVICE device = WdfIoQueueGetDevice(Queue);
    PGPI_CONTEXT ctx = GpiGetContext(device);
    WDFFILEOBJECT file = WdfRequestGetFileObject(Request);
    ULONG_PTR information = 0;
    PVOID buffer;
    size_t length;
    NTSTATUS status;

    UNREFERENCED_PARAMETER(OutputBufferLength);
    UNREFERENCED_PARAMETER(InputBufferLength);

    switch (IoControlCode) {
    case IOCTL_GPI_SUBMIT:
        status = WdfRequestRetrieveInputBuffer(Request, 1, &buffer, &length);
        if (NT_SUCCESS(status)) status = GpiSubmit(ctx, file, (const UCHAR *)buffer, length);
        break;
    case IOCTL_GPI_VERSION:
        status = WdfRequestRetrieveOutputBuffer(Request, sizeof(ULONG), &buffer, &length);
        if (NT_SUCCESS(status)) {
            *(ULONG *)buffer = GPI_PROTOCOL_VERSION;
            information = sizeof(ULONG);
        }
        break;
    case IOCTL_GPI_PLUG:
        status = WdfRequestRetrieveInputBuffer(Request, sizeof(ULONG), &buffer, &length);
        if (NT_SUCCESS(status)) status = GpiPlug(device, ctx, file, *(const ULONG *)buffer);
        break;
    case IOCTL_GPI_UNPLUG:
        GpiUnplug(ctx, file);
        status = STATUS_SUCCESS;
        break;
    default:
        status = STATUS_INVALID_DEVICE_REQUEST;
        break;
    }
    WdfRequestCompleteWithInformation(Request, status, information);
}

VOID GpiEvtFileCleanup(_In_ WDFFILEOBJECT FileObject)
{
    WDFDEVICE device = WdfFileObjectGetDevice(FileObject);
    GpiUnplug(GpiGetContext(device), FileObject);
}

VOID GpiEvtDeviceCleanup(_In_ WDFOBJECT Object)
{
    GpiUnplug(GpiGetContext((WDFDEVICE)Object), NULL);
}

/* ---- What Windows asks the device (VHF calls these on its own threads) ---- */

/* Feature report 1: the wheels' resolution multipliers. */
VOID GpiEvtGetFeature(_In_ PVOID VhfClientContext, _In_ VHFOPERATIONHANDLE VhfOperationHandle,
    _In_opt_ PVOID VhfOperationContext, _In_ PHID_XFER_PACKET HidTransferPacket)
{
    PGPI_CONTEXT ctx = (PGPI_CONTEXT)VhfClientContext;
    PUCHAR b = HidTransferPacket->reportBuffer;
    ULONG n = HidTransferPacket->reportBufferLen;

    UNREFERENCED_PARAMETER(VhfOperationContext);

    if (HidTransferPacket->reportId != GPI_REPORT_MOUSE || b == NULL || n == 0) {
        VhfAsyncOperationComplete(VhfOperationHandle, STATUS_INVALID_PARAMETER);
        return;
    }
    b[0] = GPI_REPORT_MOUSE;
    if (n > 1) {
        AcquireSRWLockShared(&ctx->Lock);
        b[1] = ctx->Multiplier;
        ReleaseSRWLockShared(&ctx->Lock);
    }
    VhfAsyncOperationComplete(VhfOperationHandle, STATUS_SUCCESS);
}

VOID GpiEvtSetFeature(_In_ PVOID VhfClientContext, _In_ VHFOPERATIONHANDLE VhfOperationHandle,
    _In_opt_ PVOID VhfOperationContext, _In_ PHID_XFER_PACKET HidTransferPacket)
{
    PGPI_CONTEXT ctx = (PGPI_CONTEXT)VhfClientContext;
    PUCHAR b = HidTransferPacket->reportBuffer;
    ULONG n = HidTransferPacket->reportBufferLen;

    UNREFERENCED_PARAMETER(VhfOperationContext);

    if (HidTransferPacket->reportId != GPI_REPORT_MOUSE || b == NULL || n < GPI_MULTIPLIER_SIZE) {
        VhfAsyncOperationComplete(VhfOperationHandle, STATUS_INVALID_PARAMETER);
        return;
    }
    AcquireSRWLockExclusive(&ctx->Lock);
    ctx->Multiplier = (UCHAR)(b[1] & 0x0F);
    ReleaseSRWLockExclusive(&ctx->Lock);
    VhfAsyncOperationComplete(VhfOperationHandle, STATUS_SUCCESS);
}

/* Output report 2 (keyboard LEDs): accepted and ignored. */
VOID GpiEvtWriteReport(_In_ PVOID VhfClientContext, _In_ VHFOPERATIONHANDLE VhfOperationHandle,
    _In_opt_ PVOID VhfOperationContext, _In_ PHID_XFER_PACKET HidTransferPacket)
{
    UNREFERENCED_PARAMETER(VhfClientContext);
    UNREFERENCED_PARAMETER(VhfOperationContext);
    UNREFERENCED_PARAMETER(HidTransferPacket);
    VhfAsyncOperationComplete(VhfOperationHandle, STATUS_SUCCESS);
}

/* Someone asking for the current state outright (HidD_GetInputReport): the held keys and buttons. */
VOID GpiEvtGetInputReport(_In_ PVOID VhfClientContext, _In_ VHFOPERATIONHANDLE VhfOperationHandle,
    _In_opt_ PVOID VhfOperationContext, _In_ PHID_XFER_PACKET HidTransferPacket)
{
    PGPI_CONTEXT ctx = (PGPI_CONTEXT)VhfClientContext;
    ULONG n = HidTransferPacket->reportBufferLen;
    ULONG size = GpiReportSize(HidTransferPacket->reportId);
    const UCHAR *src;

    UNREFERENCED_PARAMETER(VhfOperationContext);

    if (size == 0 || HidTransferPacket->reportBuffer == NULL || n == 0) {
        VhfAsyncOperationComplete(VhfOperationHandle, STATUS_INVALID_PARAMETER);
        return;
    }
    switch (HidTransferPacket->reportId) {
    case GPI_REPORT_MOUSE: src = ctx->Mouse; break;
    case GPI_REPORT_KEYBOARD: src = ctx->Keyboard; break;
    default: src = ctx->Media; break;
    }
    if (n > size) n = size;
    AcquireSRWLockShared(&ctx->Lock);
    RtlCopyMemory(HidTransferPacket->reportBuffer, src, n);
    ReleaseSRWLockShared(&ctx->Lock);
    VhfAsyncOperationComplete(VhfOperationHandle, STATUS_SUCCESS);
}
