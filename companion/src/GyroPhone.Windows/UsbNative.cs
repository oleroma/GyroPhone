using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace GyroPhone.Windows;

/// <summary>SetupAPI and WinUSB declarations for the direct USB link. Layouts follow the x64 headers.</summary>
internal static unsafe class UsbNative
{
    public const uint DigcfPresent = 0x02;
    public const uint DigcfAllClasses = 0x04;
    public const uint DigcfDeviceInterface = 0x10;
    public const uint SpdrpHardwareId = 0x01;
    public const uint SpdrpCompatibleIds = 0x02;
    public const uint SpdrpService = 0x04;
    public const uint DicsFlagGlobal = 0x01;
    public const uint DiregDev = 0x01;
    public const int KeyRead = 0x20019;

    public const uint GenericRead = 0x80000000;
    public const uint GenericWrite = 0x40000000;
    public const uint FileShareRead = 1;
    public const uint FileShareWrite = 2;
    public const uint OpenExisting = 3;
    public const uint FileAttributeNormal = 0x80;
    public const uint FileFlagOverlapped = 0x40000000;

    public const int ErrorAccessDenied = 5;
    public const int ErrorSharingViolation = 32;
    public const int ErrorSemTimeout = 121;
    public const int ErrorNoMoreItems = 259;
    public const int ErrorInsufficientBuffer = 122;

    public const uint PipeTransferTimeout = 0x03;
    public const int UsbdPipeTypeBulk = 2;

    public static readonly nint InvalidHandle = -1;

    [StructLayout(LayoutKind.Sequential)]
    public struct SP_DEVINFO_DATA
    {
        public uint cbSize;
        public Guid ClassGuid;
        public uint DevInst;
        public nint Reserved;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct SP_DEVICE_INTERFACE_DATA
    {
        public uint cbSize;
        public Guid InterfaceClassGuid;
        public uint Flags;
        public nint Reserved;
    }

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct WINUSB_SETUP_PACKET
    {
        public byte RequestType;
        public byte Request;
        public ushort Value;
        public ushort Index;
        public ushort Length;
    }

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    public struct USB_INTERFACE_DESCRIPTOR
    {
        public byte bLength;
        public byte bDescriptorType;
        public byte bInterfaceNumber;
        public byte bAlternateSetting;
        public byte bNumEndpoints;
        public byte bInterfaceClass;
        public byte bInterfaceSubClass;
        public byte bInterfaceProtocol;
        public byte iInterface;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct WINUSB_PIPE_INFORMATION
    {
        public int PipeType;
        public byte PipeId;
        public ushort MaximumPacketSize;
        public byte Interval;
    }

    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern nint SetupDiGetClassDevsW(Guid* classGuid, string? enumerator, nint hwndParent, uint flags);

    [DllImport("setupapi.dll", SetLastError = true)]
    public static extern bool SetupDiEnumDeviceInfo(nint set, uint index, ref SP_DEVINFO_DATA data);

    [DllImport("setupapi.dll", SetLastError = true)]
    public static extern bool SetupDiEnumDeviceInterfaces(nint set, nint devInfo, ref Guid guid, uint index, ref SP_DEVICE_INTERFACE_DATA data);

    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool SetupDiGetDeviceInterfaceDetailW(nint set, ref SP_DEVICE_INTERFACE_DATA data, byte* detail,
        uint detailSize, out uint requiredSize, ref SP_DEVINFO_DATA devInfo);

    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool SetupDiGetDeviceRegistryPropertyW(nint set, ref SP_DEVINFO_DATA data, uint property,
        out uint regType, byte* buffer, uint size, out uint requiredSize);

    [DllImport("setupapi.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool SetupDiGetDeviceInstanceIdW(nint set, ref SP_DEVINFO_DATA data, char* id, uint size, out uint requiredSize);

    [DllImport("setupapi.dll", SetLastError = true)]
    public static extern nint SetupDiOpenDevRegKey(nint set, ref SP_DEVINFO_DATA data, uint scope, uint hwProfile, uint keyType, int samDesired);

    [DllImport("setupapi.dll", SetLastError = true)]
    public static extern bool SetupDiDestroyDeviceInfoList(nint set);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern SafeFileHandle CreateFileW(string path, uint access, uint share, nint security, uint disposition, uint flags, nint template);

    [DllImport("winusb.dll", SetLastError = true)]
    public static extern bool WinUsb_Initialize(SafeFileHandle device, out nint iface);

    [DllImport("winusb.dll", SetLastError = true)]
    public static extern bool WinUsb_Free(nint iface);

    [DllImport("winusb.dll", SetLastError = true)]
    public static extern bool WinUsb_QueryInterfaceSettings(nint iface, byte altSetting, out USB_INTERFACE_DESCRIPTOR descriptor);

    [DllImport("winusb.dll", SetLastError = true)]
    public static extern bool WinUsb_QueryPipe(nint iface, byte altSetting, byte pipeIndex, out WINUSB_PIPE_INFORMATION info);

    [DllImport("winusb.dll", SetLastError = true)]
    public static extern bool WinUsb_SetPipePolicy(nint iface, byte pipeId, uint policyType, uint valueLength, ref uint value);

    [DllImport("winusb.dll", SetLastError = true)]
    public static extern bool WinUsb_ReadPipe(nint iface, byte pipeId, byte* buffer, uint length, out uint transferred, nint overlapped);

    [DllImport("winusb.dll", SetLastError = true)]
    public static extern bool WinUsb_WritePipe(nint iface, byte pipeId, byte* buffer, uint length, out uint transferred, nint overlapped);

    [DllImport("winusb.dll", SetLastError = true)]
    public static extern bool WinUsb_ControlTransfer(nint iface, WINUSB_SETUP_PACKET setup, byte* buffer, uint length, out uint transferred, nint overlapped);

    [DllImport("winusb.dll", SetLastError = true)]
    public static extern bool WinUsb_AbortPipe(nint iface, byte pipeId);
}
