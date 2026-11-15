using System.Collections.Concurrent;
using System.Runtime.InteropServices;

namespace GyroPhone.Windows;

/// <summary>
/// Tells the input driver's own virtual mouse and keyboard (<see cref="HidInputSink.DeviceIds"/>) apart from the PC's
/// real ones in raw input, by the device's name, looked up once per device handle. Raw input readers use it to skip
/// what GyroPhone itself sent, as they skip SendInput's input (no device behind it).
/// </summary>
public static class OwnInputDevice
{
    private const uint RidiDeviceName = 0x20000007;
    private static readonly ConcurrentDictionary<IntPtr, bool> Known = new();

    /// <summary>True for a raw input device handle of the input driver's mouse or keyboard.</summary>
    public static bool Is(IntPtr device)
    {
        if (device == IntPtr.Zero) return false;
        if (Known.TryGetValue(device, out bool own)) return own;
        own = Name(device)?.Contains(HidInputSink.DeviceIds, StringComparison.OrdinalIgnoreCase) == true;
        Known[device] = own;
        return own;
    }

    private static string? Name(IntPtr device)
    {
        uint size = 0;
        if (GetRawInputDeviceInfoW(device, RidiDeviceName, null, ref size) != 0 || size == 0 || size > 1024) return null;
        var buf = new char[size];
        if (GetRawInputDeviceInfoW(device, RidiDeviceName, buf, ref size) == unchecked((uint)-1)) return null;
        return new string(buf).TrimEnd('\0');
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint GetRawInputDeviceInfoW(IntPtr device, uint command, char[]? data, ref uint size);
}
