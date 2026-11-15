using System.Collections.Concurrent;
using System.Runtime.InteropServices;

namespace GyroPhone.Windows;

/// <summary>
/// Tells the input driver's own virtual mouse and keyboard apart from the PC's real ones in raw input, looked up once
/// per device handle. Raw input readers use it to skip what GyroPhone itself sent, as they skip SendInput's input (no
/// device behind it).
/// <para>
/// The driver's devices are virtual HID (VHF) collections, whose names carry no vendor or product id
/// (<c>\\?\HID#HID_DEVICE_SYSTEM_VHF&amp;Col01#2&amp;…#{…}</c>), and other VHF devices exist on a PC (a digitizer, other
/// drivers'). So a device is ours when it carries the driver's own container id (<see cref="ContainerId"/>, set by the
/// driver since it gives its devices their own container). A name with <see cref="HidInputSink.DeviceIds"/> counts too.
/// </para>
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
        string? name = Name(device);
        own = name != null && (name.Contains(HidInputSink.DeviceIds, StringComparison.OrdinalIgnoreCase) || InOurContainer(name));
        Known[device] = own;
        return own;
    }

    /// <summary>The input driver's own container id (GpiContainerId in input-driver/src/driver.c): its mouse and keyboard carry it.</summary>
    public static readonly Guid ContainerId = new("b7d1e5a2-3c84-4f19-a6e0-5d2c8b91f473");

    private const int MaxDepth = 3;

    /// <summary>
    /// <paramref name="rawName"/> is a raw input device's name (<c>\\?\HID#…#…#{class}</c>); true when its device (or
    /// the virtual HID device above it) carries the input driver's <see cref="ContainerId"/>.
    /// </summary>
    internal static bool InOurContainer(string rawName)
    {
        string? instance = InstanceIdOf(rawName);
        if (instance == null) return false;
        try
        {
            if (CM_Locate_DevNodeW(out uint node, instance, 0) != 0) return false;
            for (int i = 0; i < MaxDepth; i++)
            {
                if (ContainerOf(node) == ContainerId) return true;
                if (CM_Get_Parent(out uint parent, node, 0) != 0) return false;
                node = parent;
            }
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException) { }
        return false;
    }

    private static Guid? ContainerOf(uint node)
    {
        const uint DevpropTypeGuid = 0x0D;
        var key = new DevPropKey { Fmtid = new Guid("8c7ed206-3f8a-4827-b3ab-ae9e1faefc6c"), Pid = 2 }; // DEVPKEY_Device_ContainerId
        var buf = new byte[16];
        uint size = 16;
        if (CM_Get_DevNode_PropertyW(node, ref key, out uint type, buf, ref size, 0) != 0 || type != DevpropTypeGuid || size != 16) return null;
        return new Guid(buf);
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DevPropKey
    {
        public Guid Fmtid;
        public uint Pid;
    }

    /// <summary><c>\\?\HID#VID_1&amp;Col01#2&amp;x&amp;0#{guid}</c> to <c>HID\VID_1&amp;Col01\2&amp;x&amp;0</c>; null when it isn't one.</summary>
    internal static string? InstanceIdOf(string rawName)
    {
        string s = rawName.TrimEnd('\0');
        if (s.StartsWith(@"\\?\", StringComparison.Ordinal) || s.StartsWith(@"\\.\", StringComparison.Ordinal)) s = s[4..];
        int cls = s.LastIndexOf("#{", StringComparison.Ordinal);
        if (cls > 0) s = s[..cls];
        return s.Length == 0 || !s.Contains('#') ? null : s.Replace('#', '\\');
    }

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    private static extern uint CM_Locate_DevNodeW(out uint devInst, string deviceId, uint flags);

    [DllImport("cfgmgr32.dll")]
    private static extern uint CM_Get_Parent(out uint parent, uint devInst, uint flags);

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    private static extern uint CM_Get_DevNode_PropertyW(uint devInst, ref DevPropKey key, out uint type, byte[] buffer, ref uint size, uint flags);

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
