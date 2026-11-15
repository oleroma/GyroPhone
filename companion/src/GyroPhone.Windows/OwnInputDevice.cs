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
/// drivers'). So a device is ours when the device tree above its HID collection leads to the driver's root device,
/// hardware id <see cref="RootHardwareId"/>. A name with <see cref="HidInputSink.DeviceIds"/> counts too.
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
        own = name != null && (name.Contains(HidInputSink.DeviceIds, StringComparison.OrdinalIgnoreCase) || UnderOurRoot(name));
        Known[device] = own;
        return own;
    }

    /// <summary>The input driver's root device (install.ps1 makes it).</summary>
    public const string RootHardwareId = @"Root\GyroPhoneInput";

    private const int MaxDepth = 6;

    /// <summary>
    /// <paramref name="rawName"/> is a raw input device's name (<c>\\?\HID#…#…#{class}</c>); true when one of the
    /// devices above its instance (the VHF child, then the root device) has hardware id <see cref="RootHardwareId"/>.
    /// </summary>
    internal static bool UnderOurRoot(string rawName)
    {
        string? instance = InstanceIdOf(rawName);
        if (instance == null) return false;
        try
        {
            if (CM_Locate_DevNodeW(out uint node, instance, 0) != 0) return false;
            for (int i = 0; i < MaxDepth; i++)
            {
                if (CM_Get_Parent(out uint parent, node, 0) != 0) return false;
                node = parent;
                foreach (var id in HardwareIds(node))
                    if (string.Equals(id, RootHardwareId, StringComparison.OrdinalIgnoreCase)) return true;
            }
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException) { }
        return false;
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

    private static IEnumerable<string> HardwareIds(uint node)
    {
        const uint CmDrpHardwareId = 0x2;
        uint size = 0;
        if (CM_Get_DevNode_Registry_PropertyW(node, CmDrpHardwareId, out _, null, ref size, 0) is not (0 or CrBufferSmall) || size == 0 || size > 8192)
            return [];
        var buf = new byte[size];
        if (CM_Get_DevNode_Registry_PropertyW(node, CmDrpHardwareId, out _, buf, ref size, 0) != 0) return [];
        return System.Text.Encoding.Unicode.GetString(buf, 0, (int)size).Split('\0', StringSplitOptions.RemoveEmptyEntries);
    }

    private const uint CrBufferSmall = 0x1A;

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    private static extern uint CM_Locate_DevNodeW(out uint devInst, string deviceId, uint flags);

    [DllImport("cfgmgr32.dll")]
    private static extern uint CM_Get_Parent(out uint parent, uint devInst, uint flags);

    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode)]
    private static extern uint CM_Get_DevNode_Registry_PropertyW(uint devInst, uint property, out uint regType, byte[]? buffer, ref uint length, uint flags);

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
