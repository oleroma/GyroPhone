/*
 * GyroPhone pad driver: a user-mode (UMDF 2) driver that puts a virtual DualSense on Windows'
 * built-in Virtual HID Framework (VHF). Nothing of ours runs in the kernel: VHF (vhf.sys) and the
 * HID class driver are Windows' own; this DLL runs in Windows' driver host process.
 *
 * The device is root-enumerated (Root\GyroPhonePad, made by install.ps1). It exposes a device
 * interface (gyropad_ioctl.h) through which the companion plugs the pad, sends its input reports
 * and unplugs it. A pad belongs to the handle that plugged it and goes away when that handle
 * closes, so a crashed companion never leaves a stuck pad behind.
 *
 * Written from Microsoft's documentation and samples for VHF in UMDF (the DMF VirtualHidDeviceVhf
 * module and its UMDF sample).
 */
#include <windows.h>
#include <wdf.h>
#include <hidclass.h>
#include <vhf.h>
#include <initguid.h>

#include "gyropad_ioctl.h"
#include "dualsense.h"

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
#ifndef STATUS_NOT_SUPPORTED
#define STATUS_NOT_SUPPORTED ((NTSTATUS)0xC00000BBL)
#endif

typedef struct _GP_CONTEXT {
    SRWLOCK Lock;
    VHFHANDLE Vhf;           /* the plugged pad, NULL when none */
    WDFIOTARGET VhfTarget;   /* the file handle VHF needs in user mode */
    WDFFILEOBJECT Owner;     /* the handle that plugged it */
    UCHAR Counter;
    UCHAR Input[DS_INPUT_REPORT_SIZE];
    UCHAR Output[DS_OUTPUT_REPORT_SIZE];
} GP_CONTEXT, *PGP_CONTEXT;

WDF_DECLARE_CONTEXT_TYPE_WITH_NAME(GP_CONTEXT, GpGetContext)

DRIVER_INITIALIZE DriverEntry;
EVT_WDF_DRIVER_DEVICE_ADD GpEvtDeviceAdd;
EVT_WDF_OBJECT_CONTEXT_CLEANUP GpEvtDeviceCleanup;
EVT_WDF_FILE_CLEANUP GpEvtFileCleanup;
EVT_WDF_IO_QUEUE_IO_DEVICE_CONTROL GpEvtIoDeviceControl;
EVT_VHF_ASYNC_OPERATION GpEvtGetFeature;
EVT_VHF_ASYNC_OPERATION GpEvtSetFeature;
EVT_VHF_ASYNC_OPERATION GpEvtWriteReport;
EVT_VHF_ASYNC_OPERATION GpEvtGetInputReport;

NTSTATUS DriverEntry(_In_ PDRIVER_OBJECT DriverObject, _In_ PUNICODE_STRING RegistryPath)
{
    WDF_DRIVER_CONFIG config;
    WDF_DRIVER_CONFIG_INIT(&config, GpEvtDeviceAdd);
    return WdfDriverCreate(DriverObject, RegistryPath, WDF_NO_OBJECT_ATTRIBUTES, &config, WDF_NO_HANDLE);
}

NTSTATUS GpEvtDeviceAdd(_In_ WDFDRIVER Driver, _Inout_ PWDFDEVICE_INIT DeviceInit)
{
    WDF_FILEOBJECT_CONFIG fileConfig;
    WDF_OBJECT_ATTRIBUTES attributes;
    WDF_IO_QUEUE_CONFIG queueConfig;
    WDFDEVICE device;
    PGP_CONTEXT ctx;
    NTSTATUS status;

    UNREFERENCED_PARAMETER(Driver);

    WDF_FILEOBJECT_CONFIG_INIT(&fileConfig, WDF_NO_EVENT_CALLBACK, WDF_NO_EVENT_CALLBACK, GpEvtFileCleanup);
    WdfDeviceInitSetFileObjectConfig(DeviceInit, &fileConfig, WDF_NO_OBJECT_ATTRIBUTES);

    WDF_OBJECT_ATTRIBUTES_INIT_CONTEXT_TYPE(&attributes, GP_CONTEXT);
    attributes.EvtCleanupCallback = GpEvtDeviceCleanup;
    status = WdfDeviceCreate(&DeviceInit, &attributes, &device);
    if (!NT_SUCCESS(status)) return status;

    ctx = GpGetContext(device);
    InitializeSRWLock(&ctx->Lock);
    DsNeutralReport(ctx->Input);

    status = WdfDeviceCreateDeviceInterface(device, &GUID_DEVINTERFACE_GYROPHONE_PAD, NULL);
    if (!NT_SUCCESS(status)) return status;

    /* One request at a time: PLUG, SUBMIT and UNPLUG never overlap. */
    WDF_IO_QUEUE_CONFIG_INIT_DEFAULT_QUEUE(&queueConfig, WdfIoQueueDispatchSequential);
    queueConfig.EvtIoDeviceControl = GpEvtIoDeviceControl;
    return WdfIoQueueCreate(device, &queueConfig, WDF_NO_OBJECT_ATTRIBUTES, WDF_NO_HANDLE);
}

/* Removes the pad, if one is plugged (by `owner`, or by anyone when owner is NULL). */
static void GpUnplug(PGP_CONTEXT ctx, WDFFILEOBJECT owner)
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
    DsNeutralReport(ctx->Input);
    ReleaseSRWLockExclusive(&ctx->Lock);

    /* Outside the lock: deleting waits for VHF's callbacks, which take the lock. */
    VhfDelete(vhf, TRUE);
    if (target != NULL) {
        WdfIoTargetClose(target);
        WdfObjectDelete(target);
    }
}

static NTSTATUS GpPlug(WDFDEVICE device, PGP_CONTEXT ctx, WDFFILEOBJECT owner, const GP_PLUG_IN *in)
{
    WDF_OBJECT_ATTRIBUTES attributes;
    WDF_IO_TARGET_OPEN_PARAMS openParams;
    WDFIOTARGET target = NULL;
    VHF_CONFIG config;
    VHFHANDLE vhf = NULL;
    HANDLE file;
    BOOLEAN plugged, mine;
    NTSTATUS status;

    if (in->Version != GP_PROTOCOL_VERSION) return STATUS_REVISION_MISMATCH;
    if (in->Pad != GP_PAD_DUALSENSE) return STATUS_NOT_SUPPORTED;

    AcquireSRWLockShared(&ctx->Lock);
    plugged = ctx->Vhf != NULL;
    mine = plugged && ctx->Owner == owner;
    ReleaseSRWLockShared(&ctx->Lock);
    if (mine) return STATUS_SUCCESS;           /* already plugged by this handle */
    if (plugged) return STATUS_DEVICE_BUSY;    /* another program has the pad */

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

    VHF_CONFIG_INIT(&config, file, (USHORT)sizeof(DsReportDescriptor), (PUCHAR)DsReportDescriptor);
    config.VendorID = DS_VENDOR_ID;
    config.ProductID = DS_PRODUCT_ID;
    config.VersionNumber = DS_VERSION;
    config.VhfClientContext = ctx;
    config.EvtVhfAsyncOperationGetFeature = GpEvtGetFeature;
    config.EvtVhfAsyncOperationSetFeature = GpEvtSetFeature;
    config.EvtVhfAsyncOperationWriteReport = GpEvtWriteReport;
    config.EvtVhfAsyncOperationGetInputReport = GpEvtGetInputReport;
    status = VhfCreate(&config, &vhf);
    if (!NT_SUCCESS(status)) {
        vhf = NULL;
        goto fail;
    }

    AcquireSRWLockExclusive(&ctx->Lock);
    ctx->Vhf = vhf;
    ctx->VhfTarget = target;
    ctx->Owner = owner;
    ctx->Counter = 0;
    DsNeutralReport(ctx->Input);
    ReleaseSRWLockExclusive(&ctx->Lock);

    status = VhfStart(vhf);
    if (!NT_SUCCESS(status)) {
        GpUnplug(ctx, owner);
        return status;
    }
    return STATUS_SUCCESS;

fail:
    if (vhf != NULL) VhfDelete(vhf, TRUE);
    WdfIoTargetClose(target);
    WdfObjectDelete(target);
    return status;
}

static NTSTATUS GpSubmit(PGP_CONTEXT ctx, WDFFILEOBJECT owner, const UCHAR *report, size_t length)
{
    HID_XFER_PACKET packet;
    NTSTATUS status;

    if (length != DS_INPUT_REPORT_SIZE || report[0] != DS_INPUT_REPORT_ID) return STATUS_INVALID_PARAMETER;

    AcquireSRWLockExclusive(&ctx->Lock);
    if (ctx->Vhf == NULL || ctx->Owner != owner) {
        ReleaseSRWLockExclusive(&ctx->Lock);
        return STATUS_DEVICE_NOT_CONNECTED;
    }
    RtlCopyMemory(ctx->Input, report, DS_INPUT_REPORT_SIZE);
    ctx->Input[7] = ctx->Counter++;
    packet.reportBuffer = ctx->Input;
    packet.reportBufferLen = DS_INPUT_REPORT_SIZE;
    packet.reportId = DS_INPUT_REPORT_ID;
    /* VHF keeps its own copy until the HID class driver reads it. */
    status = VhfReadReportSubmit(ctx->Vhf, &packet);
    ReleaseSRWLockExclusive(&ctx->Lock);
    return status;
}

VOID GpEvtIoDeviceControl(_In_ WDFQUEUE Queue, _In_ WDFREQUEST Request, _In_ size_t OutputBufferLength,
    _In_ size_t InputBufferLength, _In_ ULONG IoControlCode)
{
    WDFDEVICE device = WdfIoQueueGetDevice(Queue);
    PGP_CONTEXT ctx = GpGetContext(device);
    WDFFILEOBJECT file = WdfRequestGetFileObject(Request);
    ULONG_PTR information = 0;
    PVOID buffer;
    size_t length;
    NTSTATUS status;

    UNREFERENCED_PARAMETER(OutputBufferLength);
    UNREFERENCED_PARAMETER(InputBufferLength);

    switch (IoControlCode) {
    case IOCTL_GP_VERSION:
        status = WdfRequestRetrieveOutputBuffer(Request, sizeof(ULONG), &buffer, &length);
        if (NT_SUCCESS(status)) {
            *(ULONG *)buffer = GP_PROTOCOL_VERSION;
            information = sizeof(ULONG);
        }
        break;
    case IOCTL_GP_PLUG:
        status = WdfRequestRetrieveInputBuffer(Request, sizeof(GP_PLUG_IN), &buffer, &length);
        if (NT_SUCCESS(status)) status = GpPlug(device, ctx, file, (const GP_PLUG_IN *)buffer);
        break;
    case IOCTL_GP_UNPLUG:
        GpUnplug(ctx, file);
        status = STATUS_SUCCESS;
        break;
    case IOCTL_GP_SUBMIT:
        status = WdfRequestRetrieveInputBuffer(Request, DS_INPUT_REPORT_SIZE, &buffer, &length);
        if (NT_SUCCESS(status)) status = GpSubmit(ctx, file, (const UCHAR *)buffer, length);
        break;
    default:
        status = STATUS_INVALID_DEVICE_REQUEST;
        break;
    }
    WdfRequestCompleteWithInformation(Request, status, information);
}

VOID GpEvtFileCleanup(_In_ WDFFILEOBJECT FileObject)
{
    WDFDEVICE device = WdfFileObjectGetDevice(FileObject);
    GpUnplug(GpGetContext(device), FileObject);
}

VOID GpEvtDeviceCleanup(_In_ WDFOBJECT Object)
{
    GpUnplug(GpGetContext((WDFDEVICE)Object), NULL);
}

/* ---- What the game asks the pad (VHF calls these on its own threads) ---- */

VOID GpEvtGetFeature(_In_ PVOID VhfClientContext, _In_ VHFOPERATIONHANDLE VhfOperationHandle,
    _In_opt_ PVOID VhfOperationContext, _In_ PHID_XFER_PACKET HidTransferPacket)
{
    UCHAR report[64];
    ULONG size = DsFeatureSize(HidTransferPacket->reportId);
    ULONG n;

    UNREFERENCED_PARAMETER(VhfClientContext);
    UNREFERENCED_PARAMETER(VhfOperationContext);

    if (size == 0 || HidTransferPacket->reportBuffer == NULL || HidTransferPacket->reportBufferLen == 0) {
        VhfAsyncOperationComplete(VhfOperationHandle, STATUS_INVALID_PARAMETER);
        return;
    }
    DsFillFeature(HidTransferPacket->reportId, report, size);
    n = HidTransferPacket->reportBufferLen < size ? HidTransferPacket->reportBufferLen : size;
    RtlCopyMemory(HidTransferPacket->reportBuffer, report, n);
    VhfAsyncOperationComplete(VhfOperationHandle, STATUS_SUCCESS);
}

/* Settings a game sends the pad (feature reports): accepted and ignored for now. */
VOID GpEvtSetFeature(_In_ PVOID VhfClientContext, _In_ VHFOPERATIONHANDLE VhfOperationHandle,
    _In_opt_ PVOID VhfOperationContext, _In_ PHID_XFER_PACKET HidTransferPacket)
{
    UNREFERENCED_PARAMETER(VhfClientContext);
    UNREFERENCED_PARAMETER(VhfOperationContext);
    UNREFERENCED_PARAMETER(HidTransferPacket);
    VhfAsyncOperationComplete(VhfOperationHandle, STATUS_SUCCESS);
}

/* Output report 0x02 (rumble, lightbar, LEDs, triggers): kept for later, nothing uses it yet. */
VOID GpEvtWriteReport(_In_ PVOID VhfClientContext, _In_ VHFOPERATIONHANDLE VhfOperationHandle,
    _In_opt_ PVOID VhfOperationContext, _In_ PHID_XFER_PACKET HidTransferPacket)
{
    PGP_CONTEXT ctx = (PGP_CONTEXT)VhfClientContext;
    ULONG n = HidTransferPacket->reportBufferLen;

    UNREFERENCED_PARAMETER(VhfOperationContext);

    if (HidTransferPacket->reportBuffer != NULL && n > 0) {
        if (n > DS_OUTPUT_REPORT_SIZE) n = DS_OUTPUT_REPORT_SIZE;
        AcquireSRWLockExclusive(&ctx->Lock);
        RtlCopyMemory(ctx->Output, HidTransferPacket->reportBuffer, n);
        ReleaseSRWLockExclusive(&ctx->Lock);
    }
    VhfAsyncOperationComplete(VhfOperationHandle, STATUS_SUCCESS);
}

/* A game asking for the current state outright (HidD_GetInputReport): the last report sent. */
VOID GpEvtGetInputReport(_In_ PVOID VhfClientContext, _In_ VHFOPERATIONHANDLE VhfOperationHandle,
    _In_opt_ PVOID VhfOperationContext, _In_ PHID_XFER_PACKET HidTransferPacket)
{
    PGP_CONTEXT ctx = (PGP_CONTEXT)VhfClientContext;
    ULONG n = HidTransferPacket->reportBufferLen;

    UNREFERENCED_PARAMETER(VhfOperationContext);

    if (HidTransferPacket->reportId != DS_INPUT_REPORT_ID || HidTransferPacket->reportBuffer == NULL || n == 0) {
        VhfAsyncOperationComplete(VhfOperationHandle, STATUS_INVALID_PARAMETER);
        return;
    }
    if (n > DS_INPUT_REPORT_SIZE) n = DS_INPUT_REPORT_SIZE;
    AcquireSRWLockShared(&ctx->Lock);
    RtlCopyMemory(HidTransferPacket->reportBuffer, ctx->Input, n);
    ReleaseSRWLockShared(&ctx->Lock);
    VhfAsyncOperationComplete(VhfOperationHandle, STATUS_SUCCESS);
}
